package au.net.kal.teslasync.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import au.net.kal.teslasync.MainActivity
import au.net.kal.teslasync.TeslaSyncApp
import au.net.kal.teslasync.data.Destination
import au.net.kal.teslasync.data.SettingsRepository
import au.net.kal.teslasync.data.TessieClient
import au.net.kal.teslasync.data.TessieParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The whole engine: while armed, polls Tessie every N seconds and opens Waze when the
 * destination changes. Runs as a foreground service so it keeps polling reliably during a
 * drive (foreground services are exempt from Doze).
 */
class DestinationWatcherService : LifecycleService() {

    private val settings by lazy { SettingsRepository(this) }
    private val tessie = TessieClient()
    private val stateMachine = DestinationStateMachine()
    private var pollJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!startForegroundNotification("Watching for a destination…")) {
            // The OS refused the foreground start (the throw is deferred into this
            // onStartCommand dispatch, so the companion start()'s try/catch can't catch it).
            // We've already posted a tap-to-start notification and stopped — don't ask to be
            // recreated, it would just fail the same way.
            return START_NOT_STICKY
        }
        isRunning = true
        if (pollJob?.isActive != true) {
            pollJob = lifecycleScope.launch { runPollLoop(this) }
        }
        // Restart if the OS kills us mid-drive; the loop re-reads settings on restart.
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    /**
     * Android 15 (API 35) gives the *timed* FGS types (dataSync, mediaProcessing) a ~6h/24h
     * budget, then calls this and expects a prompt stop or it throws
     * ForegroundServiceDidNotStopInTimeException. We run as connectedDevice, which has NO time
     * cap — so this should not fire for that reason. We keep the handler anyway so that if a
     * timed type is ever added, the service stops cleanly instead of crashing.
     *
     * (We previously ran as dataSync and shipped a crash: once a day's drives totalled ~6h, the
     * budget was exhausted and the next arm threw "Time limit already exhausted for foreground
     * service type dataSync" from startForeground. The 6h is a rolling-24h budget, NOT refunded
     * when the service stops — which is why "we always stop on disconnect" didn't save us.)
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground service timed out (type=$fgsType) — stopping")
        stopSelf()
    }

    private suspend fun runPollLoop(scope: CoroutineScope) {
        val token = settings.tessieToken
        val vin = settings.vin
        if (token.isNullOrBlank() || vin.isNullOrBlank()) {
            updateNotification("Not configured — open the app")
            stopSelf()
            return
        }

        // If we should NOT fire an already-set destination, seed it silently first.
        if (!settings.fireOnArm) {
            stateMachine.prime(pollOnce(vin, token))
        }

        val intervalMs = settings.pollIntervalSeconds.toLong() * 1000L
        while (scope.isActive) {
            val destination = pollOnce(vin, token)
            val toFire = stateMachine.onPoll(destination)
            when {
                toFire != null -> {
                    Log.i(TAG, "New destination: ${toFire.name} (${toFire.latitude},${toFire.longitude})")
                    WazeLauncher.launch(this, toFire)
                    updateNotification("Sent to Waze: ${toFire.name}")
                }
                destination != null -> updateNotification("Active: ${destination.name}")
                else -> updateNotification("Watching for a destination…")
            }
            delay(intervalMs)
        }
    }

    /** One Tessie poll. Never throws — logs and returns null on any failure. */
    private suspend fun pollOnce(vin: String, token: String): Destination? = withContext(Dispatchers.IO) {
        when (val r = tessie.getState(vin, token)) {
            is TessieClient.Result.Ok -> runCatching { TessieParser.parseDestination(r.body) }
                .onFailure { Log.w(TAG, "Parse failed", it) }
                .getOrNull()
            is TessieClient.Result.HttpError -> {
                Log.w(TAG, "Tessie HTTP ${r.code}: ${r.message}")
                null
            }
            is TessieClient.Result.Failure -> {
                Log.w(TAG, "Tessie call failed", r.error)
                null
            }
        }
    }

    // --- Foreground notification -------------------------------------------

    /**
     * Promote to a foreground service as CONNECTED_DEVICE — the car we auto-arm with over
     * Bluetooth. connectedDevice has no Android-15 time cap (unlike dataSync, which used to
     * crash the app once a day's drives totalled ~6h); the OS only requires that we hold a
     * qualifying permission (BLUETOOTH_CONNECT), which we do.
     *
     * Returns false if the OS refused the start. That refusal is thrown into this
     * onStartCommand dispatch — NOT into the companion start()'s startForegroundService() call —
     * so we must catch it here. On refusal we post a tap-to-start notification (a notification
     * tap is itself a background-start exemption) and stop, rather than crash.
     */
    private fun startForegroundNotification(text: String): Boolean = try {
        ServiceCompat.startForeground(
            this,
            FGS_ID,
            buildNotification(text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        true
    } catch (e: Exception) {
        Log.w(TAG, "Foreground start refused: ${e.message}")
        postTapToStartNotification()
        stopSelf()
        false
    }

    /**
     * Fallback when the OS refuses the foreground start: a high-priority notification whose tap
     * restarts the service via getForegroundService (a notification tap is a background-start
     * exemption, so the tap always succeeds).
     */
    private fun postTapToStartNotification() {
        val restart = PendingIntent.getForegroundService(
            this,
            2,
            Intent(this, DestinationWatcherService::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, TeslaSyncApp.CHANNEL_NAV)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("TeslaSync needs a tap")
            .setContentText("Tap to start watching for a Tesla destination")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(restart)
            .build()
        getSystemService<NotificationManager>()?.notify(FALLBACK_ID, notification)
    }

    private fun updateNotification(text: String) {
        getSystemService<NotificationManager>()?.notify(FGS_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, TeslaSyncApp.CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("TeslaSync")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val TAG = "DestWatcher"
        private const val FGS_ID = 1001

        // Distinct from FGS_ID and CarBluetoothReceiver.TAP_TO_ARM_ID (2001).
        private const val FALLBACK_ID = 2002

        /**
         * Whether the watcher is currently running. Set when the foreground promote succeeds,
         * cleared in onDestroy. Read by the UI to show "Watching" vs "Idle" — it's a best-effort
         * snapshot (the UI re-reads it on resume), not a live-observable source of truth.
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * Start the watcher as a foreground service. Safe to call when already running.
         * Returns false if the OS blocked the start (Android 12+ background restriction —
         * e.g. auto-arm fired while the app had no exemption); the caller can fall back to
         * a tap-to-arm notification.
         */
        fun start(context: Context): Boolean {
            val intent = Intent(context, DestinationWatcherService::class.java)
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Could not start foreground service: ${e.message}")
                false
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DestinationWatcherService::class.java))
        }
    }
}
