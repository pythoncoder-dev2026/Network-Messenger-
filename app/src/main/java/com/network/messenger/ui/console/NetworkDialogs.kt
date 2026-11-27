package com.network.messenger.ui.console

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.network.messenger.R
import com.network.messenger.crypto.CryptoManager
import com.network.messenger.domain.model.DiscoveredNetwork
import com.network.messenger.domain.model.NetworkMember
import com.network.messenger.domain.model.NetworkRole
import com.network.messenger.domain.model.NetworkSummary
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
import com.network.messenger.util.QrCodeGenerator

@Composable
fun CreateNetworkDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, password: String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Encrypted Mesh Network") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Generates a random 128-bit network ID, 256-bit AES-GCM group key, QR code, and share code.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Network name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create_net_name_input")
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Optional password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create_net_password_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name, password.takeIf { it.isNotBlank() }) },
                modifier = Modifier.testTag("confirm_create_network_button")
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun ShareNetworkQrDialog(
    networkName: String,
    shareCode: String,
    onCopyCode: () -> Unit,
    onDismiss: () -> Unit
) {
    val qrBitmap = remember(shareCode) { QrCodeGenerator.generateQrBitmap(shareCode, 512) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share \"$networkName\"") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.cd_qr_code),
                        modifier = Modifier
                            .size(220.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .padding(8.dp)
                    )
                }
                Text(
                    text = shareCode,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                )
                OutlinedButton(
                    onClick = onCopyCode,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("copy_share_code_button")
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy Share Code")
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Done") }
        }
    )
}

@Composable
fun JoinNetworkDialog(
    discoveredNetworks: List<DiscoveredNetwork>,
    onDismiss: () -> Unit,
    onJoinCode: (code: String, password: String?) -> Unit,
    onJoinDiscovered: (network: DiscoveredNetwork, password: String?) -> Unit,
    onLaunchQrScanner: (onScanned: (String) -> Unit) -> Unit
) {
    var shareCode by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Join Mesh Network") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        onLaunchQrScanner { scanned ->
                            shareCode = scanned
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("scan_qr_button")
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Scan Network QR Code")
                }

                OutlinedTextField(
                    value = shareCode,
                    onValueChange = { shareCode = it },
                    label = { Text("Share code (NMSG1:...)") },
                    maxLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("join_share_code_input")
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password (if required)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("join_password_input")
                )

                if (discoveredNetworks.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        text = "Nearby Discovered Networks (${discoveredNetworks.size})",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 150.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(discoveredNetworks, key = { it.networkId }) { net ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                    .clickable {
                                        onJoinDiscovered(net, password.takeIf { it.isNotBlank() })
                                    }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = net.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "id:${CryptoManager.shortId(net.networkId)} via ${net.viaTransport.code}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = "JOIN",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (shareCode.isNotBlank()) {
                        onJoinCode(shareCode, password.takeIf { it.isNotBlank() })
                    }
                },
                enabled = shareCode.isNotBlank(),
                modifier = Modifier.testTag("confirm_join_network_button")
            ) {
                Text("Join")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun NetworkMembersDialog(
    network: NetworkSummary,
    localUserId: String,
    members: List<NetworkMember>,
    mutedUserIds: Set<String>,
    onDismiss: () -> Unit,
    onShowQr: () -> Unit,
    onToggleNetworkMute: () -> Unit,
    onRotateKey: () -> Unit,
    onVerifyMember: (NetworkMember) -> Unit,
    onToggleMuteMember: (userId: String, currentlyMuted: Boolean) -> Unit,
    onReportMember: (userId: String) -> Unit,
    onRemoveMember: (String) -> Unit,
    onBlockMember: (String) -> Unit,
    onLeaveNetwork: () -> Unit
) {
    val isOwner = network.role == NetworkRole.OWNER

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(network.name)
                Text(
                    text = "id:${CryptoManager.shortId(network.networkId)} • ${network.role.name} • keyVer=${network.keyVersion}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = onShowQr,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("show_network_qr_button")
                    ) {
                        Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("QR")
                    }
                    OutlinedButton(
                        onClick = onToggleNetworkMute,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("mute_network_button")
                    ) {
                        Icon(
                            imageVector = if (network.isMuted) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                            contentDescription = null,
                            tint = if (network.isMuted) SignalAmber else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (network.isMuted) "Unmute" else "Mute")
                    }
                }
                if (isOwner) {
                    OutlinedButton(
                        onClick = onRotateKey,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("rotate_key_button")
                    ) {
                        Icon(
                            Icons.Default.Key,
                            contentDescription = null,
                            tint = SignalViolet,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Rotate Network Group Key")
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = "Members (${members.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(members, key = { it.userId }) { member ->
                        val isSelf = member.userId == localUserId
                        val isMuted = mutedUserIds.contains(member.userId)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .border(
                                    1.dp,
                                    if (member.keyChangedWarning) SignalRose else MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${CryptoManager.shortId(member.userId)}${if (isSelf) " (you)" else ""}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    val statusLabel = when {
                                        member.keyChangedWarning -> "KEY CHANGED!"
                                        member.verified -> "VERIFIED"
                                        else -> "UNVERIFIED"
                                    }
                                    val statusColor = when {
                                        member.keyChangedWarning -> SignalRose
                                        member.verified -> SignalEmerald
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                    Text(
                                        text = "${member.role.name} • $statusLabel${if (isMuted) " • MUTED" else ""}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = statusColor
                                    )
                                }
                            }
                            if (!isSelf) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    IconButton(onClick = { onVerifyMember(member) }) {
                                        Icon(
                                            imageVector = if (member.keyChangedWarning) Icons.Default.GppMaybe else Icons.Default.VerifiedUser,
                                            contentDescription = stringResource(R.string.action_verify_contact),
                                            tint = if (member.keyChangedWarning) SignalRose else if (member.verified) SignalEmerald else MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    IconButton(onClick = { onToggleMuteMember(member.userId, isMuted) }) {
                                        Icon(
                                            imageVector = if (isMuted) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                                            contentDescription = if (isMuted) "Unmute user" else "Mute user",
                                            tint = if (isMuted) SignalAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    IconButton(onClick = { onReportMember(member.userId) }) {
                                        Icon(
                                            imageVector = Icons.Default.Flag,
                                            contentDescription = stringResource(R.string.action_report_user),
                                            tint = SignalAmber,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    IconButton(onClick = { onBlockMember(member.userId) }) {
                                        Icon(
                                            Icons.Default.Block,
                                            contentDescription = "Block user",
                                            tint = SignalOrange,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    if (isOwner) {
                                        IconButton(onClick = { onRemoveMember(member.userId) }) {
                                            Icon(
                                                Icons.Default.PersonRemove,
                                                contentDescription = "Remove member and rotate key",
                                                tint = SignalRose,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onLeaveNetwork,
                    colors = ButtonDefaults.buttonColors(containerColor = SignalRose.copy(alpha = 0.2f), contentColor = SignalRose),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("leave_network_button")
                ) {
                    Text("Leave Network")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun VerifyContactDialog(
    contactUserId: String,
    safetyNumber: String?,
    alreadyVerified: Boolean,
    keyChangedWarning: Boolean,
    myVerificationQrPayload: String,
    onScanContactQr: () -> Unit,
    onMarkVerifiedManually: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    val qrBitmap = remember(myVerificationQrPayload) {
        QrCodeGenerator.generateQrBitmap(myVerificationQrPayload, 440)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = if (safetyNumber == null) "My Identity Verification QR" else "Verify Contact Key"
                )
                Text(
                    text = "uid:${CryptoManager.shortId(contactUserId)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (keyChangedWarning) SignalRose else if (alreadyVerified) SignalEmerald else MaterialTheme.colorScheme.primary
                )
            }
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (keyChangedWarning) {
                    Text(
                        text = "WARNING: This contact's identity key changed! Compare the safety number or scan their QR in person.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SignalRose
                    )
                }

                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.cd_verify_qr),
                        modifier = Modifier
                            .size(190.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White)
                            .padding(8.dp)
                    )
                }

                if (!safetyNumber.isNullOrBlank()) {
                    Text(
                        text = "Safety Number (compare on both phones)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = safetyNumber,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                            .padding(10.dp)
                            .testTag("safety_number_text")
                    )
                }

                OutlinedButton(
                    onClick = onScanContactQr,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("scan_contact_verify_qr_button")
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Scan Contact's Verification QR")
                }

                if (onMarkVerifiedManually != null && !alreadyVerified) {
                    Button(
                        onClick = onMarkVerifiedManually,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("mark_verified_manual_button")
                    ) {
                        Icon(Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Safety Number Matches — Mark Verified")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun ReportUserDialog(
    targetUserId: String,
    canSendToOwner: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (reason: String, sendToOwner: Boolean) -> Unit
) {
    val presets = listOf("Spam / flooding", "Harassment", "Impersonation", "Malicious traffic")
    var selectedReason by remember { mutableStateOf(presets.first()) }
    var customReason by remember { mutableStateOf("") }
    var sendToOwner by remember { mutableStateOf(canSendToOwner) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report User ${CryptoManager.shortId(targetUserId)}", color = SignalAmber) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Saved locally in Room. Never logs plaintext message content.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                presets.forEach { preset ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (selectedReason == preset) SignalAmber.copy(alpha = 0.16f)
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .border(
                                1.dp,
                                if (selectedReason == preset) SignalAmber else MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(6.dp)
                            )
                            .clickable { selectedReason = preset }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Text(preset, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                OutlinedTextField(
                    value = customReason,
                    onValueChange = { customReason = it },
                    label = { Text("Optional details") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (canSendToOwner) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = sendToOwner, onCheckedChange = { sendToOwner = it })
                        Text(
                            text = "Also send encrypted report to network owner",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalReason = if (customReason.isNotBlank()) "$selectedReason: ${customReason.trim()}" else selectedReason
                    onSubmit(finalReason, sendToOwner)
                },
                modifier = Modifier.testTag("confirm_report_user_button")
            ) {
                Text("Submit Report")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
