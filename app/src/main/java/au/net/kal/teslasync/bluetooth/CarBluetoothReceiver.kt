package au.net.kal.teslasync.bluetooth

import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import au.net.kal.teslasync.TeslaSyncApp
import au.net.kal.teslasync.data.SettingsRepository
import au.net.kal.teslasync.service.DestinationWatcherService

/**
 * Auto-arm: reacts to the system's Bluetooth connect/disconnect broadcasts for the saved
 * car. ACTION_ACL_CONNECTED/_DISCONNECTED are on Android's implicit-broadcast exception
 * list, so a manifest-declared receiver gets them even when the app isn't running.
 *
 * Starting a foreground service from here is restricted on Android 12+ UNLESS the user
 * has exempted the app from battery optimisation (the app prompts for this) or granted
 * "display over other apps". If the start is still blocked, we post a high-priority
 * notification — tapping a notification is itself an exemption, so the tap always works.
 */
class CarBluetoothReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val device = IntentCompat.getParcelableExtra(
            intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java,
        ) ?: return
        val settings = SettingsRepository(context)
        val carMac = settings.carBluetoothAddress
        // Only the chosen car, and only when auto-arm is on — manual arming stays manual.
        if (carMac.isNullOrBlank() || !device.address.equals(carMac, ignoreCase = true)) return
        if (!settings.autoArmOnBluetooth) return

        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                if (!settings.isConfigured) {
                    Log.i(TAG, "Car connected but app not configured — ignoring")
                    return
                }
                Log.i(TAG, "Car connected — arming")
                if (!DestinationWatcherService.start(context)) postTapToArmNotification(context)
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                Log.i(TAG, "Car disconnected — disarming")
                DestinationWatcherService.stop(context)
            }
        }
    }

    private fun postTapToArmNotification(context: Context) {
        val pi = PendingIntent.getForegroundService(
            context,
            1,
            Intent(context, DestinationWatcherService::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, TeslaSyncApp.CHANNEL_NAV)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Car connected")
            .setContentText("Tap to start watching for a Tesla destination")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(TAP_TO_ARM_ID, notification)
    }

    private companion object {
        const val TAG = "CarBtReceiver"
        const val TAP_TO_ARM_ID = 2001
    }
}
