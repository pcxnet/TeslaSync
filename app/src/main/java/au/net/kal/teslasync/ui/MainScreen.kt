package au.net.kal.teslasync.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import au.net.kal.teslasync.BuildConfig
import au.net.kal.teslasync.bluetooth.CarCompanionManager
import au.net.kal.teslasync.service.DestinationWatcherService

@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val companion = remember { CarCompanionManager(context) }
    var armed by remember { mutableStateOf(false) }
    // Auto-arm relies on CompanionDeviceService, which needs Android 12 (API 31+).
    val autoArmSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    // CompanionDeviceManager device picker result -> save the chosen car's MAC.
    val chooseCar = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        vm.onCarBtAddress(companion.onCarSelected(result.data))
    }

    val requestNotif = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op: notifications just won't show if denied */ }

    val requestBt = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCarPicker(companion, chooseCar) { vm.message = it.toString() }
        else vm.message = "Bluetooth permission needed to pick the car"
    }

    // Ask for notification permission once (Android 13+); the foreground service needs it.
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Check GitHub Releases for a newer build when the screen opens.
    LaunchedEffect(Unit) { vm.checkForUpdate() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("TeslaSync", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Sends the destination you set in your Tesla to Waze.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        vm.updateInfo?.let { info ->
            UpdateBanner(
                version = info.version,
                installing = vm.installingUpdate,
                onInstall = { vm.installUpdate() },
                onDismiss = { vm.dismissUpdate() },
            )
        }

        vm.message?.let { msg ->
            Spacer(Modifier.height(12.dp))
            Text(msg, color = MaterialTheme.colorScheme.primary)
        }

        // 1. Token -----------------------------------------------------------
        Section("1. Tessie API token")
        OutlinedTextField(
            value = vm.token,
            onValueChange = vm::onTokenChange,
            label = { Text("Tessie token") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::saveToken) { Text("Save token") }
            Button(onClick = vm::loadVehicles, enabled = !vm.busy) {
                Text(if (vm.busy) "Loading…" else "Load vehicles")
            }
        }

        // 2. Vehicle ---------------------------------------------------------
        Section("2. Vehicle (VIN)")
        OutlinedTextField(
            value = vm.vin,
            onValueChange = vm::onVinChange,
            label = { Text("VIN") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        vm.vehicles.forEach { v ->
            Text(
                text = "${v.displayName} — ${v.vin}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { vm.onVinChange(v.vin) }
                    .padding(vertical = 10.dp),
            )
        }

        // 3. Arming ----------------------------------------------------------
        Section("3. Arming")
        SwitchRow(
            label = "Auto-arm when phone connects to car Bluetooth",
            checked = vm.autoArm,
            enabled = autoArmSupported,
        ) { checked ->
            vm.onAutoArmChanged(checked)
            // Keep presence observation in sync with the toggle.
            vm.carBtAddress?.takeIf { autoArmSupported && it.isNotBlank() }?.let { addr ->
                if (checked) companion.startObservingPresence(addr) else companion.stopObservingPresence(addr)
            }
        }
        SwitchRow("Fire if a destination is already set at arm time", vm.fireOnArm) {
            vm.onFireOnArmChanged(it)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            vm.carBtAddress?.let { "Car Bluetooth: $it" } ?: "No car Bluetooth chosen yet",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (autoArmSupported) {
            OutlinedButton(
                enabled = companion.isSupported,
                onClick = { requestBt.launch(Manifest.permission.BLUETOOTH_CONNECT) },
            ) { Text("Choose car Bluetooth") }
        } else {
            Text(
                "Auto-arm needs Android 12+. Use “Start watching now” below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 4. Permissions -----------------------------------------------------
        Section("4. Permissions")
        OutlinedButton(onClick = { openOverlaySettings(context) }) {
            Text("Allow display over other apps (one-tap Waze)")
        }
        OutlinedButton(onClick = { openBatterySettings(context) }) {
            Text("Disable battery optimisation")
        }

        // 5. Manual control --------------------------------------------------
        Section("5. Watch now")
        Button(
            enabled = vm.isConfigured,
            onClick = {
                if (armed) {
                    DestinationWatcherService.stop(context)
                    armed = false
                } else {
                    DestinationWatcherService.start(context)
                    armed = true
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (armed) "Stop watching" else "Start watching now") }

        if (!vm.isConfigured) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Add a token and VIN to enable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(20.dp))
    Text(title, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun UpdateBanner(
    version: String,
    installing: Boolean,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("Update available — v$version", style = MaterialTheme.typography.titleSmall)
            Text(
                "You're on v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onInstall, enabled = !installing) {
                    Text(if (installing) "Updating…" else "Update")
                }
                OutlinedButton(onClick = onDismiss, enabled = !installing) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

private fun launchCarPicker(
    companion: CarCompanionManager,
    chooser: androidx.activity.result.ActivityResultLauncher<IntentSenderRequest>,
    onError: (CharSequence) -> Unit,
) {
    companion.requestCarSelection(
        onChooserReady = { sender -> chooser.launch(IntentSenderRequest.Builder(sender).build()) },
        onError = onError,
    )
}

private fun openOverlaySettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
        )
    }
}

private fun openBatterySettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}"),
            )
        )
    }.onFailure {
        // Some OEMs don't support the direct action — fall back to the app settings page.
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            )
        }
    }
}
