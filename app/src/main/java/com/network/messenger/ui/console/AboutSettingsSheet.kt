package com.network.messenger.ui.console

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.network.messenger.R
import com.network.messenger.crypto.CryptoManager
import com.network.messenger.ui.contract.MeshUiIntent
import com.network.messenger.ui.contract.MeshUiState
import com.network.messenger.ui.theme.ConsoleBorder
import com.network.messenger.ui.theme.ConsoleCanvas
import com.network.messenger.ui.theme.ConsoleSurface
import com.network.messenger.ui.theme.ConsoleSurfaceElevated
import com.network.messenger.ui.theme.ConsoleTextMuted
import com.network.messenger.ui.theme.ConsoleTextPrimary
import com.network.messenger.ui.theme.SignalAmber
import com.network.messenger.ui.theme.SignalCyan
import com.network.messenger.ui.theme.SignalEmerald
import com.network.messenger.ui.theme.SignalOrange
import com.network.messenger.ui.theme.SignalRose
import com.network.messenger.ui.theme.SignalViolet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSettingsSheet(
    state: MeshUiState,
    onDismiss: () -> Unit,
    onIntent: (MeshUiIntent) -> Unit,
    onCopyUserId: () -> Unit,
    onShowMyVerifyQr: () -> Unit,
    onRequestPermissions: () -> Unit,
    onExportEncryptedBackup: (passphrase: String) -> Unit,
    onRestoreEncryptedBackup: (passphrase: String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var relayUrlInput by remember(state.relayServerUrl) { mutableStateOf(state.relayServerUrl) }
    var blockUserIdInput by remember { mutableStateOf("") }
    var muteUserIdInput by remember { mutableStateOf("") }
    var backupPassphrase by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ConsoleSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // App Identity Block (consistent across manifest, gradle, log screen, and settings)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(ConsoleSurfaceElevated)
                    .border(1.dp, SignalCyan.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = state.appName,
                    style = MaterialTheme.typography.titleLarge,
                    color = SignalCyan
                )
                Text(
                    text = "Package: ${state.packageName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = ConsoleTextPrimary
                )
                Text(
                    text = "Version: ${state.versionName} (versionCode ${state.versionCode})",
                    style = MaterialTheme.typography.bodySmall,
                    color = ConsoleTextMuted
                )
                Text(
                    text = "Crypto: Google Tink (Ed25519 + X25519 + HKDF-SHA256 + AES-256-GCM) • Android Keystore",
                    style = MaterialTheme.typography.labelSmall,
                    color = SignalEmerald
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Local User ID (SHA-256 pubkey)",
                            style = MaterialTheme.typography.labelSmall,
                            color = ConsoleTextMuted
                        )
                        Text(
                            text = state.localUserId,
                            style = MaterialTheme.typography.labelSmall,
                            color = ConsoleTextPrimary
                        )
                    }
                    IconButton(
                        onClick = onCopyUserId,
                        modifier = Modifier.testTag("copy_user_id_button")
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy user ID", tint = SignalCyan)
                    }
                }
                OutlinedButton(
                    onClick = onShowMyVerifyQr,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("my_verify_qr_button")
                ) {
                    Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("My Verification QR & Scan Contact")
                }
            }

            // Encrypted Backup & Restore (PBKDF2-HMAC-SHA256 + Tink AES-256-GCM)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(ConsoleSurfaceElevated)
                    .border(1.dp, SignalViolet.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.label_encrypted_backup),
                    style = MaterialTheme.typography.titleSmall,
                    color = SignalViolet
                )
                Text(
                    text = "Protects identity keys, contacts, networks, mute list & block list with PBKDF2-SHA256 (210k) + AES-256-GCM.",
                    style = MaterialTheme.typography.labelSmall,
                    color = ConsoleTextMuted
                )
                OutlinedTextField(
                    value = backupPassphrase,
                    onValueChange = { backupPassphrase = it },
                    label = { Text("Backup passphrase (min 6 chars)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("backup_passphrase_input")
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onExportEncryptedBackup(backupPassphrase) },
                        enabled = backupPassphrase.length >= 6,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("export_backup_button")
                    ) {
                        Icon(Icons.Default.Backup, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Export")
                    }
                    OutlinedButton(
                        onClick = { onRestoreEncryptedBackup(backupPassphrase) },
                        enabled = backupPassphrase.length >= 6,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("restore_backup_button")
                    ) {
                        Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Restore")
                    }
                }
            }

            // Offline Queue Expiry Config (default 24h)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "${stringResource(R.string.label_queue_expiry)} (currently ${state.queueExpiryHours}h • ${state.pendingQueueCount} queued)",
                    style = MaterialTheme.typography.titleSmall,
                    color = ConsoleTextPrimary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(6, 12, 24, 48, 72).forEach { hrs ->
                        val selected = state.queueExpiryHours == hrs
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (selected) SignalCyan.copy(alpha = 0.2f) else ConsoleSurfaceElevated)
                                .border(1.dp, if (selected) SignalCyan else ConsoleBorder, RoundedCornerShape(6.dp))
                                .clickable { onIntent(MeshUiIntent.SetQueueExpiryHours(hrs)) }
                                .padding(horizontal = 12.dp, vertical = 7.dp)
                                .testTag("queue_expiry_${hrs}h")
                        ) {
                            Text(
                                text = "${hrs}h",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) SignalCyan else ConsoleTextMuted,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            // Internet Relay Server Config
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.label_relay_url),
                    style = MaterialTheme.typography.titleSmall,
                    color = ConsoleTextPrimary
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = relayUrlInput,
                        onValueChange = { relayUrlInput = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("relay_url_input")
                    )
                    Button(
                        onClick = { onIntent(MeshUiIntent.UpdateRelayUrl(relayUrlInput)) },
                        modifier = Modifier.testTag("save_relay_url_button")
                    ) {
                        Text("Save")
                    }
                }
            }

            // Voice Auto-Play Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(ConsoleSurfaceElevated)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.label_autoplay_voice),
                        style = MaterialTheme.typography.titleSmall,
                        color = ConsoleTextPrimary
                    )
                    Text(
                        text = "Automatically decode & play incoming non-blocked, non-muted voice messages",
                        style = MaterialTheme.typography.labelSmall,
                        color = ConsoleTextMuted
                    )
                }
                Switch(
                    checked = state.autoPlayVoice,
                    onCheckedChange = { onIntent(MeshUiIntent.SetAutoPlayVoice(it)) },
                    modifier = Modifier.testTag("settings_autoplay_switch")
                )
            }

            HorizontalDivider(color = ConsoleBorder)

            // Muted Users Manager (stays connected & relaying, silenced)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "${stringResource(R.string.label_muted_users)} (${state.mutedUsers.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = SignalAmber
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = muteUserIdInput,
                        onValueChange = { muteUserIdInput = it },
                        placeholder = { Text("User ID to mute") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("mute_user_id_input")
                    )
                    OutlinedButton(
                        onClick = {
                            if (muteUserIdInput.isNotBlank()) {
                                onIntent(MeshUiIntent.MuteUser(muteUserIdInput.trim()))
                                muteUserIdInput = ""
                            }
                        },
                        modifier = Modifier.testTag("mute_user_button")
                    ) {
                        Icon(Icons.Default.VolumeOff, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Mute")
                    }
                }
                state.mutedUsers.forEach { muted ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(ConsoleCanvas)
                            .border(1.dp, ConsoleBorder, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = CryptoManager.shortId(muted.userId),
                            style = MaterialTheme.typography.bodyMedium,
                            color = SignalAmber,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { onIntent(MeshUiIntent.UnmuteUser(muted.userId)) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Unmute user", tint = SignalEmerald)
                        }
                    }
                }
            }

            HorizontalDivider(color = ConsoleBorder)

            // Block List Manager (Room-backed)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "${stringResource(R.string.label_blocked_users)} (${state.blockedUsers.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = SignalOrange
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = blockUserIdInput,
                        onValueChange = { blockUserIdInput = it },
                        placeholder = { Text("User ID to block") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("block_user_id_input")
                    )
                    Button(
                        onClick = {
                            if (blockUserIdInput.isNotBlank()) {
                                onIntent(MeshUiIntent.BlockUser(blockUserIdInput.trim(), "Blocked in settings"))
                                blockUserIdInput = ""
                            }
                        },
                        modifier = Modifier.testTag("block_user_button")
                    ) {
                        Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Block")
                    }
                }

                if (state.blockedUsers.isEmpty()) {
                    Text(
                        text = "No blocked users.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ConsoleTextMuted
                    )
                } else {
                    state.blockedUsers.forEach { blocked ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(ConsoleCanvas)
                                .border(1.dp, ConsoleBorder, RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = CryptoManager.shortId(blocked.userId),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = SignalRose,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = blocked.reason,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ConsoleTextMuted
                                )
                            }
                            IconButton(
                                onClick = { onIntent(MeshUiIntent.UnblockUser(blocked.userId)) }
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Unblock user",
                                    tint = SignalEmerald
                                )
                            }
                        }
                    }
                }
            }

            if (state.userReports.isNotEmpty()) {
                HorizontalDivider(color = ConsoleBorder)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "${stringResource(R.string.label_user_reports)} (${state.userReports.size})",
                        style = MaterialTheme.typography.titleSmall,
                        color = SignalRose
                    )
                    state.userReports.take(10).forEach { rep ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(ConsoleCanvas)
                                .border(1.dp, ConsoleBorder, RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "uid:${CryptoManager.shortId(rep.reportedUserId)} ${if (rep.sentToOwner) "• sent to owner" else "• local"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = SignalAmber
                            )
                            Text(
                                text = rep.reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = ConsoleTextPrimary
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = ConsoleBorder)

            // Runtime Permissions Status
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Permissions",
                        style = MaterialTheme.typography.titleSmall,
                        color = ConsoleTextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = onRequestPermissions,
                        modifier = Modifier.testTag("request_permissions_sheet_button")
                    ) {
                        Text("Request Missing")
                    }
                }
                state.permissions.forEach { perm ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (perm.granted) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (perm.granted) SignalEmerald else SignalAmber,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = perm.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = ConsoleTextPrimary
                            )
                            Text(
                                text = perm.requiredFor,
                                style = MaterialTheme.typography.labelSmall,
                                color = ConsoleTextMuted
                            )
                        }
                        Text(
                            text = if (perm.granted) "GRANTED" else "DENIED",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (perm.granted) SignalEmerald else SignalAmber
                        )
                    }
                }
            }
        }
    }
}
