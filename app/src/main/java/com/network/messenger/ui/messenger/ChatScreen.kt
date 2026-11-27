package com.network.messenger.ui.messenger

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.network.messenger.R
import com.network.messenger.crypto.CryptoManager
import com.network.messenger.data.repository.ChatMessageItem
import com.network.messenger.domain.model.LinkQuality
import com.network.messenger.ui.contract.MeshUiIntent
import com.network.messenger.ui.contract.MeshUiState
import com.network.messenger.ui.theme.LocalMessengerColors
import com.network.messenger.ui.theme.PttActiveRed
import com.network.messenger.ui.theme.SignalEmerald
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: MeshUiState,
    logPanelExpanded: Boolean,
    onToggleLogPanelExpanded: () -> Unit,
    onOpenFullLogs: () -> Unit,
    onNavigateBack: () -> Unit,
    onOpenMembersDialog: () -> Unit,
    onIntent: (MeshUiIntent) -> Unit,
    onPttStartWithPermission: () -> Unit,
    onLaunchQrScanner: (onScanned: (String) -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onNavigateBack)

    val activeNet = state.activeNetwork
    val messages = state.activeChatMessages
    val listState = rememberLazyListState()
    var composerText by remember { mutableStateOf("") }

    val memberMap = remember(state.activeNetworkMembers) {
        state.activeNetworkMembers.associateBy { it.userId }
    }

    // Mark incoming messages read when opening or receiving in the active chat
    LaunchedEffect(activeNet?.networkId, messages.size) {
        if (activeNet != null) {
            onIntent(MeshUiIntent.MarkActiveNetworkRead)
        }
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .imePadding()
    ) {
        val compactPanelHeight = (maxHeight * 0.24f).coerceIn(116.dp, 200.dp)

        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("chat_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (activeNet != null) {
                            MonogramAvatar(
                                seed = activeNet.networkId,
                                label = activeNet.name,
                                size = 38.dp,
                                showOnlineDot = state.connectedPeerCount > 0 && !activeNet.isMuted,
                                isMuted = activeNet.isMuted
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Column {
                            Text(
                                text = activeNet?.name ?: stringResource(R.string.label_no_network),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val typers = state.activeTypingPeers
                            val subtitle = when {
                                typers.isNotEmpty() -> {
                                    val who = typers.joinToString(", ") { CryptoManager.shortId(it.userId).take(6) }
                                    "$who typing…"
                                }
                                activeNet != null -> {
                                    val muteLabel = if (activeNet.isMuted) " • Muted" else ""
                                    "${activeNet.memberCount} members • E2EE k${activeNet.keyVersion}$muteLabel"
                                }
                                else -> "Select a network"
                            }
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (typers.isNotEmpty()) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                actions = {
                    TopBarLogToggleButton(
                        showLogsPanel = state.showLogsPanel,
                        onToggle = { onIntent(MeshUiIntent.SetShowLogsPanel(!state.showLogsPanel)) }
                    )
                    if (activeNet != null) {
                        IconButton(
                            onClick = onOpenMembersDialog,
                            modifier = Modifier.testTag("chat_members_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Group,
                                contentDescription = stringResource(R.string.label_network_members)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Key change warning banner
            state.keyChangeAlerts.forEach { alert ->
                KeyChangeAlertBanner(
                    alert = alert,
                    onVerify = {
                        onLaunchQrScanner { scanned ->
                            onIntent(MeshUiIntent.VerifyContactFromQr(alert.userId, scanned))
                        }
                    },
                    onDismiss = { onIntent(MeshUiIntent.DismissKeyChangeAlert(alert.userId)) }
                )
            }

            // Messages timeline
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("chat_message_list"),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (messages.isEmpty()) {
                    item(key = "e2e_notice") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "Messages & voice clips are end-to-end encrypted with AES-256-GCM.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                items(items = messages, key = { it.packetId }) { msg ->
                    val senderMember = memberMap[msg.senderId]
                    MessageBubbleRow(
                        message = msg,
                        isSenderVerified = senderMember?.verified == true,
                        isPlayingThisVoice = state.playingVoicePacketId == msg.packetId,
                        onTogglePlayVoice = {
                            if (state.playingVoicePacketId == msg.packetId) {
                                onIntent(MeshUiIntent.StopVoicePlayback)
                            } else {
                                onIntent(MeshUiIntent.PlayVoiceMessage(msg.packetId))
                            }
                        }
                    )
                }

                if (state.activeTypingPeers.isNotEmpty()) {
                    item(key = "typing_bubble") {
                        TypingIndicatorBubble(
                            peerIds = state.activeTypingPeers.map { it.userId }
                        )
                    }
                }
            }

            // Modern composer bar with pill text field, send button, and hold-to-talk mic/PTT button
            ChatComposerBar(
                text = composerText,
                isRecordingPtt = state.isRecordingPtt,
                linkQuality = state.linkQuality,
                enabled = activeNet != null,
                onTextChange = { updated ->
                    composerText = updated
                    onIntent(MeshUiIntent.OnTypingChanged(updated.isNotBlank()))
                },
                onSend = {
                    if (composerText.isNotBlank()) {
                        onIntent(MeshUiIntent.SendText(composerText.trim()))
                        composerText = ""
                    }
                },
                onPttDown = onPttStartWithPermission,
                onPttUp = { onIntent(MeshUiIntent.StopPtt) }
            )

            // Collapsible compact live logs drawer (about 25% screen height) when enabled
            if (state.showLogsPanel) {
                CollapsibleLogPanel(
                    recentLogs = state.compactRecentLogs,
                    totalLogCount = state.logs.size,
                    expanded = logPanelExpanded,
                    onToggleExpanded = onToggleLogPanelExpanded,
                    onOpenFullLogs = onOpenFullLogs,
                    bodyHeight = compactPanelHeight
                )
            }
        }
    }
}

@Composable
private fun MessageBubbleRow(
    message: ChatMessageItem,
    isSenderVerified: Boolean,
    isPlayingThisVoice: Boolean,
    onTogglePlayVoice: () -> Unit
) {
    val ext = LocalMessengerColors.current
    val isOutgoing = message.isOutgoing
    val bubbleShape = if (isOutgoing) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp)
    }
    val bubbleBg = if (isOutgoing) ext.outgoingBubble else ext.incomingBubble
    val bubbleFg = if (isOutgoing) ext.onOutgoingBubble else ext.onIncomingBubble

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (!isOutgoing) {
            MonogramAvatar(
                seed = message.senderId,
                label = CryptoManager.shortId(message.senderId),
                size = 30.dp,
                isVerified = isSenderVerified
            )
            Spacer(Modifier.width(8.dp))
        }

        Column(
            modifier = Modifier
                .widthIn(min = 96.dp, max = 290.dp)
                .clip(bubbleShape)
                .background(bubbleBg)
                .then(
                    if (!isOutgoing) Modifier.border(1.dp, ext.incomingBubbleBorder.copy(alpha = 0.7f), bubbleShape)
                    else Modifier
                )
                .padding(horizontal = 12.dp, vertical = 9.dp)
                .testTag("message_bubble_${message.packetId.take(8)}")
        ) {
            if (!isOutgoing) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = CryptoManager.shortId(message.senderId),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    if (isSenderVerified) {
                        Icon(
                            imageVector = Icons.Default.VerifiedUser,
                            contentDescription = "Verified sender",
                            tint = SignalEmerald,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
                Spacer(Modifier.height(3.dp))
            }

            if (message.isVoice) {
                VoiceBubbleContent(
                    message = message,
                    isOutgoing = isOutgoing,
                    fgColor = bubbleFg,
                    isPlaying = isPlayingThisVoice,
                    onTogglePlay = onTogglePlayVoice
                )
            } else {
                Text(
                    text = message.textBody ?: "Encrypted text (${formatByteSize(message.payloadSizeBytes)})",
                    style = MaterialTheme.typography.bodyLarge,
                    color = bubbleFg
                )
            }

            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.align(Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                if (message.hopCount > 0) {
                    Text(
                        text = "${message.hopCount}h",
                        style = MaterialTheme.typography.labelSmall,
                        color = bubbleFg.copy(alpha = 0.65f)
                    )
                }
                Text(
                    text = formatShortTime(message.timestampMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = bubbleFg.copy(alpha = 0.7f)
                )
                if (isOutgoing) {
                    DeliveryTickIndicator(
                        state = message.deliveryState,
                        onOutgoingBubble = true
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceBubbleContent(
    message: ChatMessageItem,
    isOutgoing: Boolean,
    fgColor: Color,
    isPlaying: Boolean,
    onTogglePlay: () -> Unit
) {
    val bars = remember(message.packetId) {
        val hash = abs(message.packetId.hashCode())
        List(16) { idx ->
            val v = ((hash shr (idx % 12)) + idx * 7) % 10
            (0.28f + (v / 10f) * 0.72f)
        }
    }
    val playBtnBg = if (isOutgoing) {
        Color.White.copy(alpha = 0.22f)
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val playBtnFg = if (isOutgoing) Color.White else MaterialTheme.colorScheme.primary

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.widthIn(min = 190.dp)
    ) {
        IconButton(
            onClick = onTogglePlay,
            enabled = message.hasPlayableVoice,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(playBtnBg)
                .testTag("voice_play_${message.packetId.take(8)}")
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = stringResource(if (isPlaying) R.string.cd_stop_voice else R.string.cd_play_voice),
                tint = if (message.hasPlayableVoice) playBtnFg else playBtnFg.copy(alpha = 0.45f)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.5.dp),
                modifier = Modifier.height(24.dp)
            ) {
                bars.forEachIndexed { idx, frac ->
                    val barColor = if (isPlaying && idx % 2 == 0) {
                        SignalEmerald
                    } else {
                        fgColor.copy(alpha = if (message.hasPlayableVoice) 0.78f else 0.4f)
                    }
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height((22f * frac).dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(barColor)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            val modeLabel = if (message.type == "VOICE_LIVE") "Live PTT" else "Voice clip"
            val codecLabel = message.voiceCodec?.uppercase() ?: "OPUS"
            Text(
                text = "$modeLabel • $codecLabel • ${formatByteSize(message.payloadSizeBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = fgColor.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TypingIndicatorBubble(peerIds: List<String>) {
    val ext = LocalMessengerColors.current
    val transition = rememberInfiniteTransition(label = "typing_dots")
    val alpha1 by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(550), RepeatMode.Reverse),
        label = "dot1"
    )
    val alpha2 by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(550), RepeatMode.Reverse),
        label = "dot2"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(ext.incomingBubble)
            .border(1.dp, ext.incomingBubbleBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("typing_indicator_banner")
    ) {
        Text(
            text = peerIds.joinToString(", ") { CryptoManager.shortId(it).take(6) },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(8.dp))
        listOf(alpha1, alpha2, alpha1).forEach { a ->
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = a))
            )
        }
    }
}

@Composable
private fun ChatComposerBar(
    text: String,
    isRecordingPtt: Boolean,
    linkQuality: LinkQuality,
    enabled: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onPttDown: () -> Unit,
    onPttUp: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    enabled = enabled,
                    placeholder = {
                        Text(
                            text = if (isRecordingPtt) "Recording voice… release to send"
                            else stringResource(R.string.hint_chat_message),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    },
                    singleLine = false,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("message_text_input")
                )

                if (text.isNotBlank()) {
                    IconButton(
                        onClick = onSend,
                        enabled = enabled,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .testTag("send_message_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.action_send),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }

                // Hold-to-talk Mic / PTT tactile button
                val pttBg = if (isRecordingPtt) PttActiveRed else MaterialTheme.colorScheme.secondary
                val modeTag = if (linkQuality == LinkQuality.GOOD) "LIVE" else "PTT"
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .height(48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(pttBg)
                        .pointerInput(enabled) {
                            if (!enabled) return@pointerInput
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                onPttDown()
                                waitForUpOrCancellation()
                                onPttUp()
                            }
                        }
                        .padding(horizontal = 14.dp)
                        .testTag("ptt_hold_button")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = stringResource(R.string.cd_ptt_mic),
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = if (isRecordingPtt) "TX" else modeTag,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
