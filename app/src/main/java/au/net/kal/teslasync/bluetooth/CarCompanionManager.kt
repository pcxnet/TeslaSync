package au.net.kal.teslasync.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.os.Parcelable
import android.util.Log
import androidx.core.content.IntentCompat

/**
 * Wraps CompanionDeviceManager for the "auto-arm on car Bluetooth" feature:
 *  1. [requestCarSelection] shows the system device picker (also creates the association).
 *  2. The Activity launches the returned IntentSender; the user taps the car.
 *  3. [onCarSelected] reads the chosen MAC from the result, persists nothing itself
 *     (caller saves it) and starts presence observation so [CarPresenceService] fires.
 *
 * Uses the legacy associate(request, callback, handler) entry point on purpose: it routes
 * to onDeviceFound() consistently across API 26–35, avoiding the API-33 Executor/
 * AssociationInfo callbacks. Presence observation requires API 31+.
 */
class CarCompanionManager(context: Context) {

    private val appContext = context.applicationContext
    private val cdm: CompanionDeviceManager? =
        appContext.getSystemService(Context.COMPANION_DEVICE_SERVICE) as? CompanionDeviceManager

    val isSupported: Boolean get() = cdm != null

    fun requestCarSelection(
        onChooserReady: (IntentSender) -> Unit,
        onError: (CharSequence) -> Unit,
    ) {
        val manager = cdm ?: run { onError("Companion device manager unavailable"); return }
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().build())
            .setSingleDevice(false)
            .build()

        @Suppress("DEPRECATION")
        manager.associate(
            request,
            object : CompanionDeviceManager.Callback() {
                @Deprecated("Deprecated in API 33; still invoked by the legacy associate() path.")
                @Suppress("OVERRIDE_DEPRECATION")
                override fun onDeviceFound(chooserLauncher: IntentSender) {
                    onChooserReady(chooserLauncher)
                }

                override fun onFailure(error: CharSequence?) {
                    onError(error ?: "Bluetooth association failed")
                }
            },
            null,
        )
    }

    /**
     * Reads the picked device's MAC from the picker result and starts observing it.
     * Returns the MAC (caller persists it), or null if the result had no device.
     */
    fun onCarSelected(resultData: Intent?): String? {
        // The legacy picker creates the association when the user confirms; the chosen
        // device comes back as EXTRA_DEVICE (BluetoothDevice or, for BLE, ScanResult).
        val parcelable: Parcelable? = resultData?.let {
            IntentCompat.getParcelableExtra(it, CompanionDeviceManager.EXTRA_DEVICE, Parcelable::class.java)
        }
        val address = when (parcelable) {
            is BluetoothDevice -> parcelable.address
            is ScanResult -> parcelable.device?.address
            else -> null
        } ?: run {
            Log.w(TAG, "Picker returned no device address")
            return null
        }
        startObservingPresence(address)
        return address
    }

    /**
     * Re-arm presence observation for an already-associated MAC (e.g. on app start, after a
     * reboot, or when auto-arm is toggled on). Returns true if observation started.
     */
    fun startObservingPresence(address: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Log.w(TAG, "Presence observation needs Android 12+ (have API ${Build.VERSION.SDK_INT})")
            return false
        }
        val manager = cdm ?: return false
        return try {
            @Suppress("DEPRECATION")
            manager.startObservingDevicePresence(address)
            Log.i(TAG, "Observing presence of $address")
            true
        } catch (e: Exception) {
            // Most common cause: no association exists for this MAC (picker cancelled/failed).
            Log.w(TAG, "startObservingDevicePresence failed for $address: ${e.message}", e)
            false
        }
    }

    /** Stop observing a MAC (e.g. when the user turns auto-arm off). */
    fun stopObservingPresence(address: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val manager = cdm ?: return
        try {
            @Suppress("DEPRECATION")
            manager.stopObservingDevicePresence(address)
            Log.i(TAG, "Stopped observing $address")
        } catch (e: Exception) {
            Log.w(TAG, "stopObservingDevicePresence failed for $address: ${e.message}", e)
        }
    }

    private companion object {
        const val TAG = "CarCompanion"
    }
}
