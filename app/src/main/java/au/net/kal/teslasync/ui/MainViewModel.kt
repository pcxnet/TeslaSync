package au.net.kal.teslasync.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import au.net.kal.teslasync.data.SettingsRepository
import au.net.kal.teslasync.data.TessieClient
import au.net.kal.teslasync.data.TessieParser
import au.net.kal.teslasync.data.VehicleSummary
import au.net.kal.teslasync.update.ApkInstaller
import au.net.kal.teslasync.update.UpdateChecker
import au.net.kal.teslasync.update.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds the configuration screen's state and the Tessie calls it needs. Persists every
 * change immediately through [SettingsRepository] so the foreground service always reads
 * fresh values.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsRepository(app)
    private val tessie = TessieClient()

    var token by mutableStateOf(settings.tessieToken.orEmpty())
        private set
    var vin by mutableStateOf(settings.vin.orEmpty())
        private set
    var vehicles by mutableStateOf<List<VehicleSummary>>(emptyList())
        private set
    var carBtAddress by mutableStateOf(settings.carBluetoothAddress)
        private set
    var autoArm by mutableStateOf(settings.autoArmOnBluetooth)
        private set
    var fireOnArm by mutableStateOf(settings.fireOnArm)
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    var updateInfo by mutableStateOf<UpdateInfo?>(null)
        private set
    var installingUpdate by mutableStateOf(false)
        private set

    val isConfigured: Boolean get() = token.isNotBlank() && vin.isNotBlank()

    fun onTokenChange(value: String) { token = value }

    fun saveToken() {
        settings.tessieToken = token.trim()
        message = "Token saved"
    }

    fun onVinChange(value: String) {
        vin = value
        settings.vin = value.trim()
    }

    // NB: not named setAutoArm/setFireOnArm — those JVM signatures collide with the
    // generated setters of the `autoArm`/`fireOnArm` state properties (platform clash).
    fun onAutoArmChanged(value: Boolean) { autoArm = value; settings.autoArmOnBluetooth = value }

    fun onFireOnArmChanged(value: Boolean) { fireOnArm = value; settings.fireOnArm = value }

    fun onCarBtAddress(address: String?) {
        carBtAddress = address
        settings.carBluetoothAddress = address
        message = if (address != null) "Car Bluetooth saved" else "Couldn't read the selected device"
    }

    fun clearMessage() { message = null }

    fun loadVehicles() {
        val t = token.trim()
        if (t.isBlank()) { message = "Enter your Tessie token first"; return }
        settings.tessieToken = t
        busy = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { tessie.getVehicles(t) }
            busy = false
            when (result) {
                is TessieClient.Result.Ok -> {
                    vehicles = runCatching { TessieParser.parseVehicles(result.body) }.getOrDefault(emptyList())
                    message = if (vehicles.isEmpty()) {
                        "No vehicles parsed — type the VIN manually"
                    } else {
                        "Found ${vehicles.size} vehicle(s)"
                    }
                    if (vin.isBlank() && vehicles.size == 1) onVinChange(vehicles.first().vin)
                }
                is TessieClient.Result.HttpError ->
                    message = "Tessie error ${result.code} — check the token"
                is TessieClient.Result.Failure ->
                    message = "Network error: ${result.error.message}"
            }
        }
    }

    // ── Self-update (GitHub Releases) ───────────────────────────────────────

    fun checkForUpdate() {
        viewModelScope.launch {
            val info = UpdateChecker.check() ?: return@launch
            if (settings.dismissedUpdateVersion != info.version) updateInfo = info
        }
    }

    fun dismissUpdate() {
        updateInfo?.let { settings.dismissedUpdateVersion = it.version }
        updateInfo = null
    }

    fun installUpdate() {
        val info = updateInfo ?: return
        installingUpdate = true
        viewModelScope.launch {
            val result = ApkInstaller.downloadAndInstall(getApplication<Application>(), info.apkUrl, info.sha256)
            installingUpdate = false
            message = when (result) {
                is ApkInstaller.Result.Launched -> "Opening installer…"
                is ApkInstaller.Result.PermissionRequired ->
                    "Allow “install unknown apps” for TeslaSync, then tap Update again."
                is ApkInstaller.Result.Failed -> "Update failed: ${result.error}"
            }
        }
    }
}
