package au.net.kal.teslasync.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import au.net.kal.teslasync.BuildConfig

@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current

    // Permission flags, re-checked on every resume so a button vanishes the moment its permission
    // is granted in system settings and the user returns to the app.
    var overlayOk by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var batteryOk by remember { mutableStateOf(isIgnoringBatteryOptimisation(context)) }

    val requestNotif = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op: notifications just won't show if denied */ }

    // BLUETOOTH_CONNECT (runtime on Android 12+) is needed to read the paired-device list
    // and to receive the car's connect/disconnect broadcasts for auto-arm.
    val requestBt = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.loadBondedDevices()
        else vm.message = "Bluetooth permission needed to list paired devices"
    }

    // Ask for notification permission once (Android 13+); the foreground service needs it.
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Check GitHub Releases for a newer build when the screen opens.
    LaunchedEffect(Unit) { vm.checkForUpdate() }

    // On resume: reflect watcher/connection state (self-arming if the car is already connected,
    // e.g. after a reboot mid-drive) and refresh the permission flags.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.onScreenResumed()
        overlayOk = Settings.canDrawOverlays(context)
        batteryOk = isIgnoringBatteryOptimisation(context)
    }

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

        vm.crashReport?.let { report ->
            CrashReportCard(report = report, onDismiss = { vm.clearCrashReport() })
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
        SwitchRow("Auto-arm when phone connects to car Bluetooth", vm.autoArm) {
            vm.onAutoArmChanged(it)
        }
        SwitchRow("Fire if a destination is already set at arm time", vm.fireOnArm) {
            vm.onFireOnArmChanged(it)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            vm.carBtAddress?.let { addr -> "Car: ${vm.carBtName ?: addr} ($addr)" }
                ?: "No car Bluetooth chosen yet",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    requestBt.launch(Manifest.permission.BLUETOOTH_CONNECT)
                } else {
                    vm.loadBondedDevices()
                }
            },
        ) { Text("Choose car Bluetooth (paired devices)") }
        vm.bondedDevices.forEach { device ->
            Text(
                text = "${device.name} — ${device.address}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { vm.onCarPicked(device) }
                    .padding(vertical = 10.dp),
            )
        }

        // Permissions — only the ones still missing; the whole section hides once both are
        // granted, and a button disappears as soon as you grant it (re-checked on resume).
        if (!batteryOk || !overlayOk) {
            Section("Permissions")
            if (!batteryOk) {
                OutlinedButton(onClick = { openBatterySettings(context) }) {
                    Text("Disable battery optimisation")
                }
            }
            if (!overlayOk) {
                OutlinedButton(onClick = { openOverlaySettings(context) }) {
                    Text("Allow display over other apps (one-tap Waze)")
                }
            }
        }

        // Status — read-only. Watching starts automatically when the car connects (or when you
        // open the app already in the car); turn off auto-arm above to stop it.
        Section("Status")
        when {
            !vm.isConfigured -> StatusLine(
                text = "Add a token and VIN above to enable.",
                color = MaterialTheme.colorScheme.error,
            )
            !vm.autoArm -> StatusLine(
                text = "○ Auto-arm is off — turn it on above to watch automatically.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            vm.watching -> StatusLine(
                text = "● Watching for a destination…",
                color = MaterialTheme.colorScheme.primary,
                subtitle = if (vm.carConnected) "Car connected via Bluetooth." else null,
            )
            else -> StatusLine(
                text = "○ Idle — starts automatically when your car connects.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun StatusLine(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    subtitle: String? = null,
) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = color)
    if (subtitle != null) {
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
private fun CrashReportCard(report: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "Last crash — long-press to select & copy, then send it over",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(6.dp))
            SelectionContainer {
                Text(
                    report.take(3000),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onDismiss) { Text("Dismiss") }
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

private fun isIgnoringBatteryOptimisation(context: android.content.Context): Boolean {
    val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
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
