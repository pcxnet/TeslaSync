package au.net.kal.teslasync

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.content.getSystemService
import au.net.kal.teslasync.bluetooth.CarCompanionManager
import au.net.kal.teslasync.data.SettingsRepository
import au.net.kal.teslasync.util.CrashReporter

/**
 * Application entry point. Creates the two notification channels up front, and re-arms
 * Bluetooth presence observation so auto-arm keeps working after an OS kill or reboot.
 */
class TeslaSyncApp : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)   // first, so it catches any early crash
        createNotificationChannels()
        rearmPresenceObservation()
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

    /**
     * CompanionDeviceManager presence observation may not survive a reboot. Re-register it on
     * every process start when auto-arm is enabled and a car has been chosen, so the system
     * keeps waking CarPresenceService when the car's Bluetooth appears.
     */
    private fun rearmPresenceObservation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val settings = SettingsRepository(this)
        val address = settings.carBluetoothAddress
        if (settings.autoArmOnBluetooth && !address.isNullOrBlank()) {
            CarCompanionManager(this).startObservingPresence(address)
        }
    }

    companion object {
        const val CHANNEL_STATUS = "status"
        const val CHANNEL_NAV = "navigate"
    }
}
