package au.net.kal.teslasync.bluetooth

import android.companion.CompanionDeviceService
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import au.net.kal.teslasync.data.SettingsRepository
import au.net.kal.teslasync.service.DestinationWatcherService

/**
 * Bound by the system (via CompanionDeviceManager.startObservingDevicePresence) to receive
 * "the car's Bluetooth is here / gone" callbacks even when the app is in the background.
 * This is the sanctioned way to get background-launch privilege on Android 12+ — a plain
 * BroadcastReceiver on ACL_CONNECTED does not get it.
 *
 * We override only the String-based callbacks: on Android 13+ the framework's default
 * onDeviceAppeared(AssociationInfo) delegates to onDeviceAppeared(String) for normal
 * (non-self-managed) associations, so this works across API 31–35 without referencing the
 * API-33 AssociationInfo type (which would risk class-load issues on older devices).
 */
@RequiresApi(Build.VERSION_CODES.S)
class CarPresenceService : CompanionDeviceService() {

    @Deprecated("Deprecated in API 33; still delegated to by the framework default.")
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onDeviceAppeared(address: String) {
        val settings = SettingsRepository(this)
        if (settings.autoArmOnBluetooth && settings.isConfigured) {
            Log.i(TAG, "Car present — arming")
            DestinationWatcherService.start(this)
        } else {
            Log.i(TAG, "Car present but auto-arm off or not configured — ignoring")
        }
    }

    @Deprecated("Deprecated in API 33; still delegated to by the framework default.")
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onDeviceDisappeared(address: String) {
        Log.i(TAG, "Car gone — disarming")
        DestinationWatcherService.stop(this)
    }

    private companion object {
        const val TAG = "CarPresence"
    }
}
