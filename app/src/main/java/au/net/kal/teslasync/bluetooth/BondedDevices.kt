package au.net.kal.teslasync.bluetooth

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
}
