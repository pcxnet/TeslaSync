package au.net.kal.teslasync.bluetooth

import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log

/**
 * Lists the phone's already-paired (bonded) Bluetooth devices for the car picker.
 *
 * This deliberately replaces CompanionDeviceManager: CDM's picker SCANS for nearby
 * discoverable devices, and a paired car isn't discoverable outside pairing mode — so the
 * car never appeared in the list. The user pairs the car once in Android Settings; we just
 * let them choose it from the bonded list.
 */
object BondedDevices {
    data class Entry(val name: String, val address: String)

    /** Requires BLUETOOTH_CONNECT (runtime) on API 31+, plain BLUETOOTH manifest perm on <=30. */
    fun list(context: Context): List<Entry> = try {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        adapter?.bondedDevices.orEmpty()
            .map { Entry(name = it.name ?: it.address, address = it.address) }
            .sortedBy { it.name.lowercase() }
    } catch (e: SecurityException) {
        Log.w("BondedDevices", "Missing Bluetooth permission: ${e.message}")
        emptyList()
    }

    /**
     * Best-effort async check: is [mac] currently connected on the A2DP profile? A car presents
     * as an A2DP (media audio) device, so this is a reliable "am I in the car right now?" signal
     * without resorting to hidden `BluetoothDevice.isConnected()` reflection. Used to self-arm
     * when the app is opened with the car already connected (e.g. the phone rebooted mid-drive,
     * so the ACL_CONNECTED broadcast that normally arms us was missed).
     *
     * The profile proxy connects asynchronously; [onResult] is invoked on the main thread with
     * false if Bluetooth is off, the permission is missing, or the device isn't connected.
     */
    fun isCarConnected(context: Context, mac: String, onResult: (Boolean) -> Unit) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) { onResult(false); return }
        try {
            val requested = adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    val connected = try {
                        proxy.connectedDevices.any { it.address.equals(mac, ignoreCase = true) }
                    } catch (e: SecurityException) {
                        Log.w("BondedDevices", "Missing Bluetooth permission for proxy: ${e.message}")
                        false
                    }
                    adapter.closeProfileProxy(profile, proxy)
                    onResult(connected)
                }

                override fun onServiceDisconnected(profile: Int) { /* no-op */ }
            }, BluetoothProfile.A2DP)
            if (!requested) onResult(false)   // couldn't bind the profile service
        } catch (e: SecurityException) {
            Log.w("BondedDevices", "Missing Bluetooth permission: ${e.message}")
            onResult(false)
        }
    }
}
