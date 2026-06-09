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
        startForegroundNotification("Watching for a destination…")
        if (pollJob?.isActive != true) {
            pollJob = lifecycleScope.launch { runPollLoop(this) }
        }
        // Restart if the OS kills us mid-drive; the loop re-reads settings on restart.
        return START_STICKY
    }

    /**
     * Android 15 (API 35) caps a dataSync foreground service at ~6h/24h, then calls this and
     * expects a prompt stop or it throws ForegroundServiceDidNotStopInTimeException. In normal
     * use we stop well before this (on car disconnect / manual stop), but handle it cleanly.
     * Launching dataSync is otherwise fine on 15 — only BOOT_COMPLETED launches are blocked,
     * and we never start from boot.
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

    private fun startForegroundNotification(text: String) {
        ServiceCompat.startForeground(
            this,
            FGS_ID,
            buildNotification(text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
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

        /** Start the watcher as a foreground service. Safe to call when already running. */
        fun start(context: Context) {
            val intent = Intent(context, DestinationWatcherService::class.java)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                // e.g. ForegroundServiceStartNotAllowedException if started from a
                // disallowed background state. Auto-arm runs inside CompanionDeviceService,
                // which is an allowed state; manual arm runs from the foreground.
                Log.w(TAG, "Could not start foreground service", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DestinationWatcherService::class.java))
        }
    }
}
