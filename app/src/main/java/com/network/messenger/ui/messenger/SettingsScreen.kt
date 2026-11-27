package com.network.messenger.ui.messenger

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import com.network.messenger.data.preferences.ThemeMode
import com.network.messenger.ui.contract.MeshUiIntent
import com.network.messenger.ui.contract.MeshUiState
import com.network.messenger.ui.theme.SignalAmber
import com.network.messenger.ui.theme.SignalEmerald
import com.network.messenger.ui.theme.SignalRose

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: MeshUiState,
    onIntent: (MeshUiIntent) -> Unit,
    onOpenFullLogs: () -> Unit,
    onCopyUserId: () -> Unit,
    onShowMyVerifyQr: () -> Unit,
    onRequestPermissions: () -> Unit,
    onExportEncryptedBackup: (passphrase: String) -> Unit,
    onRestoreEncryptedBackup: (passphrase: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var relayUrlInput by remember(state.relayServerUrl) { mutableStateOf(state.relayServerUrl) }
    var blockUserIdInput by remember { mutableStateOf("") }
    var muteUserIdInput by remember { mutableStateOf("") }
    var backupPassphrase by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.tab_settings),
                    style = MaterialTheme.typography.titleLarge
                )
            },
            actions = {
                TopBarLogToggleButton(
                    showLogsPanel = state.showLogsPanel,
                    onToggle = { onIntent(MeshUiIntent.SetShowLogsPanel(!state.showLogsPanel)) }
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Logs & Appearance Section (includes mandatory "Show logs" DataStore switch)
            SettingsSectionCard(title = "Logs & Appearance") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.label_show_logs),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.label_show_logs_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Switch(
                        checked = state.showLogsPanel,
                        onCheckedChange = { onIntent(MeshUiIntent.SetShowLogsPanel(it)) },
                        modifier = Modifier.testTag("settings_show_logs_switch")
                    )
                }

                OutlinedButton(
                    onClick = onOpenFullLogs,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_open_full_logs_button")
                ) {
                    Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("${stringResource(R.string.label_open_full_logs)} (${state.logs.size})")
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))

                Text(
                    text = stringResource(R.string.label_theme_mode),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ThemeMode.entries.forEach { mode ->
                        val selected = state.themeMode == mode
                        val label = when (mode) {
                            ThemeMode.SYSTEM -> "System"
                            ThemeMode.LIGHT -> "Light"
                            ThemeMode.DARK -> "Dark"
                        }
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable { onIntent(MeshUiIntent.SetThemeMode(mode)) }
                                .padding(vertical = 10.dp)
                                .testTag("theme_mode_${mode.name.lowercase()}")
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // 2. Identity & App Info Card
            SettingsSectionCard(title = "Identity & About") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MonogramAvatar(
                        seed = state.localUserId,
                        label = state.shortUserId.ifBlank { "NM" },
                        size = 48.dp,
                        isVerified = true
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.appName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "${state.packageName} • v${state.versionName} (${state.versionCode})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Google Tink (Ed25519 + X25519 + AES-256-GCM) • Android Keystore",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Local User ID (SHA-256 pubkey)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = state.localUserId,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(
                        onClick = onCopyUserId,
                        modifier = Modifier.testTag("copy_user_id_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy user ID",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                OutlinedButton(
                    onClick = onShowMyVerifyQr,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("my_verify_qr_button")
                ) {
                    Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("My Verification QR & Scan Contact")
                }
            }

            // 3. Voice, Queue & Internet Relay Settings
            SettingsSectionCard(title = "Voice & Mesh Routing") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.label_autoplay_voice),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Automatically play incoming non-blocked, non-muted voice",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.autoPlayVoice,
                        onCheckedChange = { onIntent(MeshUiIntent.SetAutoPlayVoice(it)) },
                        modifier = Modifier.testTag("settings_autoplay_switch")
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))

                Text(
                    text = "${stringResource(R.string.label_queue_expiry)} (${state.queueExpiryHours}h • ${state.pendingQueueCount} queued)",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(6, 12, 24, 48, 72).forEach { hrs ->
                        val selected = state.queueExpiryHours == hrs
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                                .border(
                                    1.dp,
                                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { onIntent(MeshUiIntent.SetQueueExpiryHours(hrs)) }
                                .padding(vertical = 8.dp)
                                .testTag("queue_expiry_${hrs}h")
                        ) {
                            Text(
                                text = "${hrs}h",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))

                Text(
                    text = stringResource(R.string.label_relay_url),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
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

            // 4. Encrypted Backup & Restore
            SettingsSectionCard(title = stringResource(R.string.label_encrypted_backup)) {
                Text(
                    text = "Exports identity keys, contacts, networks, mute list & block list protected by PBKDF2-SHA256 (210k) + AES-256-GCM.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        Spacer(Modifier.width(6.dp))
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
                        Spacer(Modifier.width(6.dp))
                        Text("Restore")
                    }
                }
            }

            // 5. Privacy & Moderation (Muted, Blocked, Reports)
            SettingsSectionCard(title = "Privacy & Moderation") {
                Text(
                    text = "${stringResource(R.string.label_muted_users)} (${state.mutedUsers.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
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
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = CryptoManager.shortId(muted.userId),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { onIntent(MeshUiIntent.UnmuteUser(muted.userId)) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Unmute user", tint = SignalEmerald)
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))

                Text(
                    text = "${stringResource(R.string.label_blocked_users)} (${state.blockedUsers.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error
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
                state.blockedUsers.forEach { blocked ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = CryptoManager.shortId(blocked.userId),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = blocked.reason,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { onIntent(MeshUiIntent.UnblockUser(blocked.userId)) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Unblock user", tint = SignalEmerald)
                        }
                    }
                }

                if (state.userReports.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
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
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
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
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            // 6. Permissions Section
            SettingsSectionCard(title = "Permissions") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${state.permissions.count { it.granted }}/${state.permissions.size} granted",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = perm.requiredFor,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
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

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            content()
        }
    }
}
