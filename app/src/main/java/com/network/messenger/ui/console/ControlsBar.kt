package com.network.messenger.ui.console

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.network.messenger.R
import com.network.messenger.domain.model.LinkQuality
import com.network.messenger.domain.model.TransportStatus
import com.network.messenger.domain.model.TransportType
import com.network.messenger.ui.contract.MeshUiIntent
import com.network.messenger.ui.contract.MeshUiState
import com.network.messenger.ui.theme.ConsoleBorder
import com.network.messenger.ui.theme.ConsoleCanvas
import com.network.messenger.ui.theme.ConsoleSurface
import com.network.messenger.ui.theme.ConsoleSurfaceElevated
import com.network.messenger.ui.theme.ConsoleTextMuted
import com.network.messenger.ui.theme.ConsoleTextPrimary
import com.network.messenger.ui.theme.PttActiveRed
import com.network.messenger.ui.theme.SignalAmber
import com.network.messenger.ui.theme.SignalCyan
import com.network.messenger.ui.theme.SignalEmerald
import com.network.messenger.ui.theme.SignalRose
import com.network.messenger.ui.theme.SignalSky

@Composable
fun ControlsBar(
    state: MeshUiState,
    onIntent: (MeshUiIntent) -> Unit,
    onToggleTransportWithPermission: (TransportType) -> Unit,
    onPttStartWithPermission: () -> Unit,
    onOpenCreateNetwork: () -> Unit,
    onOpenJoinNetwork: () -> Unit,
    onOpenNetworkMembers: () -> Unit
) {
    var messageInput by remember { mutableStateOf("") }
    var showNetMenu by remember { mutableStateOf(false) }

    Surface(
        color = ConsoleSurface,
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
        ) {
            HorizontalDivider(color = ConsoleBorder, thickness = 1.dp)

            // Live low-bandwidth typing indicator banner
            val typers = state.activeTypingPeers
            if (typers.isNotEmpty()) {
                val names = typers.joinToString(", ") {
                    com.network.messenger.crypto.CryptoManager.shortId(it.userId)
                }
                Text(
                    text = "$names ${if (typers.size == 1) "is" else "are"} typing…",
                    style = MaterialTheme.typography.labelSmall,
                    color = SignalCyan,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ConsoleSurfaceElevated)
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .testTag("typing_indicator_banner")
                )
            }

            // Row 1: Transport Toggles + Network Selector + Create/Join + Auto-Play
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TransportType.entries.forEach { type ->
                    val tState = state.transportStates[type]
                    val status = tState?.status ?: TransportStatus.STOPPED
                    val peers = tState?.peerCount ?: 0
                    TransportToggleChip(
                        type = type,
                        status = status,
                        peerCount = peers,
                        onClick = { onToggleTransportWithPermission(type) }
                    )
                }

                Box {
                    val activeNet = state.activeNetwork
                    Row(
                        modifier = Modifier
                            .heightIn(min = 34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(ConsoleSurfaceElevated)
                            .border(
                                1.dp,
                                when {
                                    activeNet == null -> ConsoleBorder
                                    activeNet.isMuted -> SignalAmber
                                    else -> SignalSky
                                },
                                RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                if (state.networks.isEmpty()) onOpenCreateNetwork()
                                else showNetMenu = true
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag("network_selector_button"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (activeNet?.isMuted == true) Icons.Default.VolumeOff else Icons.Default.Lan,
                            contentDescription = null,
                            tint = when {
                                activeNet == null -> ConsoleTextMuted
                                activeNet.isMuted -> SignalAmber
                                else -> SignalSky
                            },
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = activeNet?.let {
                                val muteTag = if (it.isMuted) " [M]" else ""
                                "${it.name} (k${it.keyVersion})$muteTag"
                            } ?: stringResource(R.string.label_no_network),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (activeNet != null) ConsoleTextPrimary else ConsoleTextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    DropdownMenu(
                        expanded = showNetMenu,
                        onDismissRequest = { showNetMenu = false }
                    ) {
                        state.networks.forEach { net ->
                            DropdownMenuItem(
                                text = {
                                    val muteSuffix = if (net.isMuted) " • MUTED" else ""
                                    Text("${net.name} • ${net.role.name} • k${net.keyVersion}$muteSuffix")
                                },
                                onClick = {
                                    showNetMenu = false
                                    onIntent(MeshUiIntent.SelectNetwork(net.networkId))
                                }
                            )
                        }
                        if (state.activeNetwork != null) {
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Manage members / Verify / Mute / QR") },
                                leadingIcon = { Icon(Icons.Default.Group, contentDescription = null) },
                                onClick = {
                                    showNetMenu = false
                                    onOpenNetworkMembers()
                                }
                            )
                        }
                    }
                }

                CompactActionChip(
                    icon = Icons.Default.Add,
                    label = "New",
                    tint = SignalEmerald,
                    testTag = "create_network_button",
                    onClick = onOpenCreateNetwork
                )
                CompactActionChip(
                    icon = Icons.Default.QrCodeScanner,
                    label = "Join",
                    tint = SignalCyan,
                    testTag = "join_network_button",
                    onClick = onOpenJoinNetwork
                )
                CompactActionChip(
                    icon = if (state.autoPlayVoice) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                    label = if (state.autoPlayVoice) "Auto" else "Mute",
                    tint = if (state.autoPlayVoice) SignalSky else ConsoleTextMuted,
                    testTag = "autoplay_toggle_button",
                    onClick = { onIntent(MeshUiIntent.SetAutoPlayVoice(!state.autoPlayVoice)) }
                )
            }

            // Row 2: Encrypted Text Input + Send + Hold-to-Talk PTT Button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = messageInput,
                    onValueChange = { updated ->
                        messageInput = updated
                        onIntent(MeshUiIntent.OnTypingChanged(updated.isNotBlank()))
                    },
                    placeholder = {
                        Text(
                            text = stringResource(R.string.hint_message_input),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ConsoleTextMuted
                        )
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = ConsoleTextPrimary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (messageInput.isNotBlank()) {
                                onIntent(MeshUiIntent.SendText(messageInput))
                                messageInput = ""
                            }
                        }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SignalCyan,
                        unfocusedBorderColor = ConsoleBorder,
                        focusedContainerColor = ConsoleCanvas,
                        unfocusedContainerColor = ConsoleCanvas,
                        cursorColor = SignalCyan
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("message_text_input")
                )

                IconButton(
                    onClick = {
                        if (messageInput.isNotBlank()) {
                            onIntent(MeshUiIntent.SendText(messageInput))
                            messageInput = ""
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (messageInput.isNotBlank()) SignalCyan else ConsoleSurfaceElevated)
                        .testTag("send_message_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.action_send),
                        tint = if (messageInput.isNotBlank()) ConsoleCanvas else ConsoleTextMuted
                    )
                }

                // Hold-to-Talk PTT tactile button
                val pttBg = if (state.isRecordingPtt) PttActiveRed else SignalEmerald.copy(alpha = 0.16f)
                val pttBorder = if (state.isRecordingPtt) PttActiveRed else SignalEmerald
                val pttModeTag = when (state.linkQuality) {
                    LinkQuality.GOOD -> "LIVE"
                    else -> "CLIP"
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .height(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(pttBg)
                        .border(1.5.dp, pttBorder, RoundedCornerShape(10.dp))
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                onPttStartWithPermission()
                                waitForUpOrCancellation()
                                onIntent(MeshUiIntent.StopPtt)
                            }
                        }
                        .padding(horizontal = 14.dp)
                        .testTag("ptt_hold_button")
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = stringResource(R.string.cd_ptt_mic),
                            tint = if (state.isRecordingPtt) Color.White else SignalEmerald,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Column(horizontalAlignment = Alignment.Start) {
                            Text(
                                text = if (state.isRecordingPtt) "TX" else "PTT",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (state.isRecordingPtt) Color.White else SignalEmerald,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = pttModeTag,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (state.isRecordingPtt) Color.White.copy(alpha = 0.85f) else ConsoleTextMuted
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransportToggleChip(
    type: TransportType,
    status: TransportStatus,
    peerCount: Int,
    onClick: () -> Unit
) {
    val icon: ImageVector = when (type) {
        TransportType.NEARBY -> Icons.Default.Bluetooth
        TransportType.WIFI_LAN -> Icons.Default.Wifi
        TransportType.INTERNET_WS -> Icons.Default.Public
    }
    val accent = when (status) {
        TransportStatus.ACTIVE -> SignalEmerald
        TransportStatus.STARTING -> SignalCyan
        TransportStatus.DEGRADED -> SignalAmber
        TransportStatus.ERROR -> SignalRose
        TransportStatus.STOPPED -> ConsoleTextMuted
    }
    val active = status != TransportStatus.STOPPED

    Row(
        modifier = Modifier
            .heightIn(min = 34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) accent.copy(alpha = 0.16f) else ConsoleSurfaceElevated)
            .border(1.dp, if (active) accent else ConsoleBorder, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp)
            .testTag("transport_toggle_${type.name.lowercase()}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(accent)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Icon(
            imageVector = icon,
            contentDescription = type.label,
            tint = accent,
            modifier = Modifier.size(15.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = if (peerCount > 0) "${type.code}($peerCount)" else type.code,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) ConsoleTextPrimary else ConsoleTextMuted,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun CompactActionChip(
    icon: ImageVector,
    label: String,
    tint: Color,
    testTag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .heightIn(min = 34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(ConsoleSurfaceElevated)
            .border(1.dp, ConsoleBorder, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(15.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = ConsoleTextPrimary
        )
    }
}
