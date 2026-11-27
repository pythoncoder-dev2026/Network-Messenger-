package com.network.messenger.ui.messenger

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.network.messenger.R
import com.network.messenger.crypto.CryptoManager
import com.network.messenger.domain.model.LinkQuality
import com.network.messenger.domain.model.TransportStatus
import com.network.messenger.domain.model.TransportType
import com.network.messenger.ui.contract.MeshUiIntent
import com.network.messenger.ui.contract.MeshUiState
import com.network.messenger.ui.theme.PttActiveRed
import com.network.messenger.ui.theme.SignalAmber
import kotlinx.coroutines.delay
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PttScreen(
    state: MeshUiState,
    onIntent: (MeshUiIntent) -> Unit,
    onToggleTransportWithPermission: (TransportType) -> Unit,
    onPttStartWithPermission: () -> Unit,
    onOpenCreateNetwork: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showChannelPicker by remember { mutableStateOf(false) }
    var elapsedSeconds by remember { mutableIntStateOf(0) }

    LaunchedEffect(state.isRecordingPtt) {
        if (state.isRecordingPtt) {
            elapsedSeconds = 0
            while (true) {
                delay(1_000L)
                elapsedSeconds++
            }
        } else {
            elapsedSeconds = 0
        }
    }

    val activeNet = state.activeNetwork
    val recentVoiceClips = remember(state.activeChatMessages) {
        state.activeChatMessages.filter { it.isVoice }.takeLast(4).reversed()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = "Push-to-Talk",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = if (state.linkQuality == LinkQuality.GOOD) "Live Opus stream ready"
                        else "Voice clip store-and-forward mode",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Channel Selector Pill
            Box {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                        .clickable {
                            if (state.networks.isEmpty()) onOpenCreateNetwork()
                            else showChannelPicker = true
                        }
                        .testTag("ptt_channel_selector")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (activeNet != null) {
                            MonogramAvatar(
                                seed = activeNet.networkId,
                                label = activeNet.name,
                                size = 40.dp,
                                showOnlineDot = state.connectedPeerCount > 0 && !activeNet.isMuted,
                                isMuted = activeNet.isMuted
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = activeNet.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${activeNet.memberCount} members • E2EE k${activeNet.keyVersion} • ${state.connectedPeerCount} peers",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "No channel selected",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Tap to create a network channel",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "Switch channel",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                DropdownMenu(
                    expanded = showChannelPicker,
                    onDismissRequest = { showChannelPicker = false }
                ) {
                    state.networks.forEach { net ->
                        DropdownMenuItem(
                            text = { Text("${net.name} (k${net.keyVersion})") },
                            onClick = {
                                showChannelPicker = false
                                onIntent(MeshUiIntent.SelectNetwork(net.networkId))
                            }
                        )
                    }
                }
            }

            if (state.lowBattery) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = SignalAmber.copy(alpha = 0.16f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.BatteryAlert, contentDescription = null, tint = SignalAmber)
                        Text(
                            text = "Low battery: mesh discovery intervals throttled to save power.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // Centerpiece Tactile Hold-to-Talk Button
            Spacer(Modifier.height(6.dp))
            PttMainButton(
                isRecording = state.isRecordingPtt,
                enabled = activeNet != null,
                elapsedSeconds = elapsedSeconds,
                linkQuality = state.linkQuality,
                onPttDown = onPttStartWithPermission,
                onPttUp = { onIntent(MeshUiIntent.StopPtt) }
            )

            Text(
                text = when {
                    activeNet == null -> "Create or join a mesh network to enable push-to-talk."
                    state.isRecordingPtt -> "Transmitting encrypted Opus 16 kbps audio…"
                    state.isPlayingVoice -> "Playing incoming encrypted voice…"
                    else -> "Press and hold to talk. Release to send."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            // Active Transports quick toggles
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TransportType.entries.forEach { type ->
                    val tState = state.transportStates[type]
                    TransportQuickChip(
                        type = type,
                        status = tState?.status ?: TransportStatus.STOPPED,
                        peerCount = tState?.peerCount ?: 0,
                        onClick = { onToggleTransportWithPermission(type) }
                    )
                }
            }

            // Auto-Play Incoming Voice Card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (state.autoPlayVoice) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                        contentDescription = null,
                        tint = if (state.autoPlayVoice) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.label_autoplay_voice),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Plays non-muted, non-blocked incoming voice automatically",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.autoPlayVoice,
                        onCheckedChange = { onIntent(MeshUiIntent.SetAutoPlayVoice(it)) },
                        modifier = Modifier.testTag("ptt_autoplay_switch")
                    )
                }
            }

            // Recent Voice Clips
            if (recentVoiceClips.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Recent Voice Messages",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        recentVoiceClips.forEach { clip ->
                            val isPlaying = state.playingVoicePacketId == clip.packetId
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = {
                                        if (isPlaying) onIntent(MeshUiIntent.StopVoicePlayback)
                                        else onIntent(MeshUiIntent.PlayVoiceMessage(clip.packetId))
                                    },
                                    enabled = clip.hasPlayableVoice
                                ) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                                        contentDescription = stringResource(if (isPlaying) R.string.cd_stop_voice else R.string.cd_play_voice),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (clip.isOutgoing) "You" else CryptoManager.shortId(clip.senderId),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "${clip.voiceCodec?.uppercase() ?: "OPUS"} • ${formatByteSize(clip.payloadSizeBytes)} • ${formatShortTime(clip.timestampMs)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (clip.isOutgoing) {
                                    DeliveryTickIndicator(state = clip.deliveryState)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PttMainButton(
    isRecording: Boolean,
    enabled: Boolean,
    elapsedSeconds: Int,
    linkQuality: LinkQuality,
    onPttDown: () -> Unit,
    onPttUp: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "ptt_pulse")
    val pulseScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (isRecording) 1.15f else 1.02f,
        animationSpec = infiniteRepeatable(tween(750), RepeatMode.Reverse),
        label = "pulse_scale"
    )
    val baseColor = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant
        isRecording -> PttActiveRed
        else -> MaterialTheme.colorScheme.primary
    }
    val secondaryColor = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant
        isRecording -> Color(0xFFDC2626)
        else -> MaterialTheme.colorScheme.secondary
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(216.dp)
    ) {
        // Outer pulsing halo
        Box(
            modifier = Modifier
                .size(204.dp)
                .scale(pulseScale)
                .clip(CircleShape)
                .background(baseColor.copy(alpha = if (isRecording) 0.22f else 0.10f))
        )

        // Tactile circular PTT button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(168.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(baseColor, secondaryColor)))
                .border(4.dp, MaterialTheme.colorScheme.surface, CircleShape)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        onPttDown()
                        waitForUpOrCancellation()
                        onPttUp()
                    }
                }
                .testTag("ptt_main_hold_button")
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = stringResource(R.string.cd_ptt_mic),
                    tint = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(46.dp)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (isRecording) {
                        String.format(Locale.US, "%02d:%02d", elapsedSeconds / 60, elapsedSeconds % 60)
                    } else {
                        "HOLD TO TALK"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (linkQuality == LinkQuality.GOOD) "LIVE STREAM" else "VOICE CLIP",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (enabled) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
