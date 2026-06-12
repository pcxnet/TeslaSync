package au.net.kal.teslasync

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.content.getSystemService
import au.net.kal.teslasync.util.CrashReporter

/**
 * Application entry point. Creates the two notification channels up front so the
 * foreground service and the "navigate"/"tap to arm" notifications always have a channel.
 * (Auto-arm needs no re-arming here: CarBluetoothReceiver is manifest-declared, so the
 * system delivers the car's connect/disconnect broadcasts without the app running.)
 */
class TeslaSyncApp : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)   // first, so it catches any early crash
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService<NotificationManager>() ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATUS,
                "Watching status",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Ongoing notification while TeslaSync is armed." }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_NAV,
                "Navigate in Waze",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Tap to open Waze when a new destination is detected." }
        )
    }

    companion object {
        const val CHANNEL_STATUS = "status"
        const val CHANNEL_NAV = "navigate"
    }
}
