package com.network.messenger

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.network.messenger.data.preferences.ThemeMode
import com.network.messenger.domain.model.TransportType
import com.network.messenger.ui.contract.MeshUiIntent
import com.network.messenger.ui.messenger.MessengerRootScreen
import com.network.messenger.ui.theme.NetworkMessengerTheme
import com.network.messenger.ui.viewmodel.MeshViewModel
import com.network.messenger.util.PermissionHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File

private data class PendingPermissionPrompt(
    val title: String,
    val rationale: String,
    val permissions: List<String>,
    val onGrantedAction: () -> Unit
)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val vm: MeshViewModel = viewModel()
            val uiState by vm.uiState.collectAsStateWithLifecycle()
            val useDarkBars = when (uiState.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            SideEffect {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !useDarkBars
                controller.isAppearanceLightNavigationBars = !useDarkBars
            }

            NetworkMessengerTheme(themeMode = uiState.themeMode) {
                val scope = rememberCoroutineScope()

                var pendingPrompt by remember { mutableStateOf<PendingPermissionPrompt?>(null) }
                var pendingAfterGrant by remember { mutableStateOf<(() -> Unit)?>(null) }
                var pendingExportPassphrase by remember { mutableStateOf<String?>(null) }
                var pendingRestorePassphrase by remember { mutableStateOf<String?>(null) }

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { results ->
                    vm.dispatch(MeshUiIntent.RefreshPermissions(results))
                    val action = pendingAfterGrant
                    pendingAfterGrant = null
                    if (results.values.all { it }) {
                        action?.invoke()
                    }
                }

                // Zero-permission Storage Access Framework exporter for encrypted .nmbak backups
                val backupExportLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("application/octet-stream")
                ) { uri ->
                    val pass = pendingExportPassphrase
                    pendingExportPassphrase = null
                    if (uri == null || pass.isNullOrEmpty()) return@rememberLauncherForActivityResult
                    scope.launch {
                        val result = vm.buildEncryptedBackup(pass)
                        result.onSuccess { (bytes, summary) ->
                            runCatching {
                                contentResolver.openOutputStream(uri)?.use { out -> out.write(bytes) }
                            }.onSuccess {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Encrypted backup saved (${summary.networkCount} nets, ${summary.sizeBytes}B)",
                                    Toast.LENGTH_LONG
                                ).show()
                            }.onFailure { err ->
                                Toast.makeText(
                                    this@MainActivity,
                                    "Could not write backup file (check storage): ${err.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }.onFailure { err ->
                            Toast.makeText(this@MainActivity, "Backup failed: ${err.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }

                // Zero-permission Storage Access Framework importer for encrypted .nmbak backups
                val backupRestoreLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri ->
                    val pass = pendingRestorePassphrase
                    pendingRestorePassphrase = null
                    if (uri == null || pass.isNullOrEmpty()) return@rememberLauncherForActivityResult
                    scope.launch {
                        val rawBytes = runCatching {
                            contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        }.getOrNull()
                        if (rawBytes == null || rawBytes.isEmpty()) {
                            Toast.makeText(this@MainActivity, "Unable to read backup file", Toast.LENGTH_LONG).show()
                            return@launch
                        }
                        val result = vm.restoreEncryptedBackup(pass, rawBytes)
                        result.onSuccess { summary ->
                            Toast.makeText(
                                this@MainActivity,
                                "Restored identity + ${summary.networkCount} network(s) & ${summary.contactCount} contact(s)",
                                Toast.LENGTH_LONG
                            ).show()
                        }.onFailure { err ->
                            Toast.makeText(
                                this@MainActivity,
                                "Restore failed (wrong passphrase or corrupt file): ${err.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }

                fun ensurePermissionsWithRationale(
                    title: String,
                    rationale: String,
                    permissions: List<String>,
                    onReady: () -> Unit
                ) {
                    val missing = permissions.filterNot { PermissionHelper.isGranted(this@MainActivity, it) }
                    if (missing.isEmpty()) {
                        onReady()
                    } else {
                        pendingPrompt = PendingPermissionPrompt(
                            title = title,
                            rationale = rationale,
                            permissions = missing,
                            onGrantedAction = onReady
                        )
                    }
                }

                MessengerRootScreen(
                    state = uiState,
                    onIntent = vm::dispatch,
                    onToggleTransportWithPermission = { type ->
                        val isCurrentlyOff = uiState.transportStates[type]?.status?.name in listOf("STOPPED", "ERROR")
                        if (!isCurrentlyOff) {
                            vm.dispatch(MeshUiIntent.ToggleTransport(type))
                        } else {
                            when (type) {
                                TransportType.NEARBY -> {
                                    val needed = PermissionHelper.nearbyPermissions().toMutableList().apply {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            add(Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                    }
                                    ensurePermissionsWithRationale(
                                        title = "Enable Nearby Mesh (Bluetooth / BLE)",
                                        rationale = "Nearby Connections requires Bluetooth and Nearby Devices permissions to discover and relay encrypted packets offline, plus Notifications to keep relaying with the screen off.",
                                        permissions = needed,
                                        onReady = { vm.dispatch(MeshUiIntent.ToggleTransport(type)) }
                                    )
                                }
                                TransportType.WIFI_LAN -> {
                                    val needed = buildList {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            add(Manifest.permission.NEARBY_WIFI_DEVICES)
                                            add(Manifest.permission.POST_NOTIFICATIONS)
                                        } else {
                                            add(Manifest.permission.ACCESS_FINE_LOCATION)
                                        }
                                    }
                                    ensurePermissionsWithRationale(
                                        title = "Enable Wi-Fi LAN / Direct Mesh",
                                        rationale = "Local Wi-Fi Direct discovery uses Nearby Wi-Fi Devices (or Location on Android 12 and below) so peers on the same Wi-Fi can discover each other automatically.",
                                        permissions = needed,
                                        onReady = { vm.dispatch(MeshUiIntent.ToggleTransport(type)) }
                                    )
                                }
                                TransportType.INTERNET_WS -> {
                                    val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        listOf(Manifest.permission.POST_NOTIFICATIONS)
                                    } else emptyList()
                                    ensurePermissionsWithRationale(
                                        title = "Enable Internet Relay",
                                        rationale = "Notifications permission allows Network messenger to show its persistent background status while connected to the encrypted WebSocket relay.",
                                        permissions = needed,
                                        onReady = { vm.dispatch(MeshUiIntent.ToggleTransport(type)) }
                                    )
                                }
                            }
                        }
                    },
                    onPttStartWithPermission = {
                        if (PermissionHelper.hasMicrophonePermission(this@MainActivity)) {
                            vm.dispatch(MeshUiIntent.StartPtt)
                        } else {
                            ensurePermissionsWithRationale(
                                title = "Microphone Permission for Push-to-Talk",
                                rationale = "Network messenger needs Microphone access to record encrypted Opus voice messages while you hold the PTT button.",
                                permissions = listOf(Manifest.permission.RECORD_AUDIO),
                                onReady = { /* user can now press and hold PTT */ }
                            )
                        }
                    },
                    onCopyLogs = {
                        copyToClipboard("Network messenger logs", vm.exportLogsText())
                    },
                    onExportLogs = {
                        exportLogsViaShareSheet(vm.exportLogsText())
                    },
                    onCopyText = { text ->
                        copyToClipboard("Network messenger", text)
                    },
                    onRequestPermissions = {
                        val missing = PermissionHelper.requiredRuntimePermissions()
                            .filterNot { PermissionHelper.isGranted(this@MainActivity, it) }
                        if (missing.isEmpty()) {
                            Toast.makeText(this@MainActivity, "All permissions already granted", Toast.LENGTH_SHORT).show()
                        } else {
                            pendingPrompt = PendingPermissionPrompt(
                                title = "Grant Mesh Permissions",
                                rationale = "Bluetooth, Nearby Wi-Fi, Microphone, and Notifications enable offline multi-hop mesh networking, encrypted push-to-talk voice, and screen-off background relaying.",
                                permissions = missing,
                                onGrantedAction = {}
                            )
                        }
                    },
                    onLaunchQrScanner = { onScanned ->
                        launchQrScanner(onScanned)
                    },
                    onExportEncryptedBackup = { passphrase ->
                        pendingExportPassphrase = passphrase
                        backupExportLauncher.launch("network_messenger_backup_v1.nmbak")
                    },
                    onRestoreEncryptedBackup = { passphrase ->
                        pendingRestorePassphrase = passphrase
                        backupRestoreLauncher.launch(arrayOf("*/*"))
                    }
                )

                pendingPrompt?.let { prompt ->
                    AlertDialog(
                        onDismissRequest = { pendingPrompt = null },
                        title = { Text(prompt.title) },
                        text = { Text(prompt.rationale) },
                        confirmButton = {
                            Button(
                                onClick = {
                                    val perms = prompt.permissions.toTypedArray()
                                    pendingAfterGrant = prompt.onGrantedAction
                                    pendingPrompt = null
                                    permissionLauncher.launch(perms)
                                }
                            ) {
                                Text("Continue")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { pendingPrompt = null }) {
                                Text("Not now")
                            }
                        }
                    )
                }
            }
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun exportLogsViaShareSheet(logText: String) {
        try {
            val dir = File(cacheDir, "logs").apply { mkdirs() }
            val file = File(dir, "network_messenger_logs_${System.currentTimeMillis()}.txt")
            file.writeText(logText)
            val uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Network messenger v${BuildConfig.VERSION_NAME} logs")
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, logText.take(4000))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Export Network messenger logs"))
        } catch (_: Exception) {
            val fallback = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, logText)
            }
            startActivity(Intent.createChooser(fallback, "Export Network messenger logs"))
        }
    }

    private fun launchQrScanner(onScanned: (String) -> Unit) {
        runCatching {
            val scanner = GmsBarcodeScanning.getClient(this)
            scanner.startScan()
                .addOnSuccessListener { barcode ->
                    barcode.rawValue?.let(onScanned)
                }
                .addOnFailureListener {
                    Toast.makeText(
                        this,
                        "QR scanner unavailable on this device — paste code or compare safety number",
                        Toast.LENGTH_SHORT
                    ).show()
                }
        }.onFailure {
            Toast.makeText(
                this,
                "QR scanner unavailable — paste code or compare safety number",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
