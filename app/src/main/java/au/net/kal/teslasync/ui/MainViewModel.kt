package au.net.kal.teslasync.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import au.net.kal.teslasync.bluetooth.BondedDevices
import au.net.kal.teslasync.data.SettingsRepository
import au.net.kal.teslasync.data.TessieClient
import au.net.kal.teslasync.data.TessieParser
import au.net.kal.teslasync.data.VehicleSummary
import au.net.kal.teslasync.service.DestinationWatcherService
import au.net.kal.teslasync.update.ApkInstaller
import au.net.kal.teslasync.update.UpdateChecker
import au.net.kal.teslasync.update.UpdateInfo
import au.net.kal.teslasync.util.CrashReporter
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

    // If the app crashed last run, surface the captured stack trace once.
    var crashReport by mutableStateOf<String?>(null)
        private set

    init {
        crashReport = CrashReporter.consume(app)
    }

    var token by mutableStateOf(settings.tessieToken.orEmpty())
        private set
    var vin by mutableStateOf(settings.vin.orEmpty())
        private set
    var vehicles by mutableStateOf<List<VehicleSummary>>(emptyList())
        private set
    var carBtAddress by mutableStateOf(settings.carBluetoothAddress)
        private set
    var carBtName by mutableStateOf(settings.carBluetoothName)
        private set
    var bondedDevices by mutableStateOf<List<BondedDevices.Entry>>(emptyList())
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

    // Whether the watcher is currently running, and whether the chosen car is connected right
    // now. Both are refreshed on screen resume — they drive the read-only status line that
    // replaced the old manual Start/Stop button.
    var watching by mutableStateOf(DestinationWatcherService.isRunning)
        private set
    var carConnected by mutableStateOf(false)
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
    // With the manual Start/Stop button gone, this toggle is also the de-facto on/off switch:
    // turning it off stops an active watch; turning it on arms immediately if already in the car.
    fun onAutoArmChanged(value: Boolean) {
        autoArm = value
        settings.autoArmOnBluetooth = value
        if (value) {
            refreshConnectionAndMaybeArm()
        } else {
            DestinationWatcherService.stop(getApplication())
            watching = false
        }
    }

    fun onFireOnArmChanged(value: Boolean) { fireOnArm = value; settings.fireOnArm = value }

    /**
     * Call when the config screen resumes. Reflects whether the watcher is running and, if the
     * car is already connected (e.g. the phone rebooted mid-drive so the ACL_CONNECTED broadcast
     * that normally arms us was missed), self-arms. The activity is in the foreground here, so
     * the foreground-service start is exempt from the Android 12+ background-start restriction.
     */
    fun onScreenResumed() {
        watching = DestinationWatcherService.isRunning
        refreshConnectionAndMaybeArm()
    }

    private fun refreshConnectionAndMaybeArm() {
        val mac = carBtAddress
        if (mac.isNullOrBlank()) { carConnected = false; return }
        BondedDevices.isCarConnected(getApplication(), mac) { connected ->
            carConnected = connected
            if (connected && isConfigured && autoArm && !DestinationWatcherService.isRunning) {
                if (DestinationWatcherService.start(getApplication())) watching = true
            }
        }
    }

    /** Loads the phone's paired devices for the car picker (call after BLUETOOTH_CONNECT). */
    fun loadBondedDevices() {
        val list = BondedDevices.list(getApplication())
        bondedDevices = list
        if (list.isEmpty()) {
            message = "No paired Bluetooth devices found — pair the car in Android Settings first"
        }
    }

    fun onCarPicked(device: BondedDevices.Entry) {
        carBtAddress = device.address
        carBtName = device.name
        settings.carBluetoothAddress = device.address
        settings.carBluetoothName = device.name
        bondedDevices = emptyList()   // collapse the picker list
        message = "Car Bluetooth saved: ${device.name}"
    }

    fun clearMessage() { message = null }

    fun clearCrashReport() { crashReport = null }

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
