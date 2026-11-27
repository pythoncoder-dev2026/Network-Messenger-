package com.network.messenger.ui.messenger

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.network.messenger.R
import com.network.messenger.crypto.CryptoManager
import com.network.messenger.data.repository.ChatMessageItem
import com.network.messenger.domain.model.NetworkSummary
import com.network.messenger.domain.model.TransportStatus
import com.network.messenger.domain.model.TransportType
import com.network.messenger.ui.contract.MeshUiIntent
import com.network.messenger.ui.contract.MeshUiState
import com.network.messenger.ui.theme.LocalMessengerColors
import com.network.messenger.ui.theme.SignalAmber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsListScreen(
    state: MeshUiState,
    logPanelExpanded: Boolean,
    onToggleLogPanelExpanded: () -> Unit,
    onOpenFullLogs: () -> Unit,
    onIntent: (MeshUiIntent) -> Unit,
    onOpenChat: (networkId: String) -> Unit,
    onToggleTransportWithPermission: (TransportType) -> Unit,
    onOpenCreateNetwork: () -> Unit,
    onOpenJoinNetwork: () -> Unit,
    onLaunchQrScanner: (onScanned: (String) -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredNetworks = remember(state.networks, searchQuery, state.latestMessageByNetwork) {
        val base = if (searchQuery.isBlank()) {
            state.networks
        } else {
            val q = searchQuery.trim().lowercase()
            state.networks.filter { it.name.lowercase().contains(q) || it.networkId.lowercase().contains(q) }
        }
        base.sortedByDescending { net ->
            state.latestMessageByNetwork[net.networkId]?.timestampMs ?: net.createdAtMs
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val compactPanelHeight = (maxHeight * 0.25f).coerceIn(128.dp, 210.dp)

        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.tab_chats),
                            style = MaterialTheme.typography.titleLarge
                        )
                        val queueTag = if (state.pendingQueueCount > 0) " • ${state.pendingQueueCount} queued" else ""
                        Text(
                            text = "${state.connectedPeerCount} peer${if (state.connectedPeerCount == 1) "" else "s"} online$queueTag",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onOpenJoinNetwork,
                        modifier = Modifier.testTag("chats_join_qr_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = stringResource(R.string.action_join_network)
                        )
                    }
                    TopBarLogToggleButton(
                        showLogsPanel = state.showLogsPanel,
                        onToggle = { onIntent(MeshUiIntent.SetShowLogsPanel(!state.showLogsPanel)) }
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )

            // Key change security banners
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

            // Compact live transport strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
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
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Main content + FAB overlay
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                if (state.networks.isEmpty()) {
                    EmptyChatsState(
                        onCreateNetwork = onOpenCreateNetwork,
                        onJoinNetwork = onOpenJoinNetwork
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("chats_list"),
                        contentPadding = PaddingValues(top = 12.dp, bottom = 84.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        item(key = "search_bar") {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = {
                                    Text(
                                        text = stringResource(R.string.hint_search_chats),
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                                        }
                                    }
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(24.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                                    .testTag("chats_search_input")
                            )
                            Spacer(Modifier.height(6.dp))
                        }

                        items(items = filteredNetworks, key = { it.networkId }) { network ->
                            val lastMsg = state.latestMessageByNetwork[network.networkId]
                            val typing = state.typingPeers.filter { it.networkId == network.networkId }
                            ChatConversationRow(
                                network = network,
                                lastMessage = lastMsg,
                                isTyping = typing.isNotEmpty(),
                                isOnline = state.connectedPeerCount > 0,
                                onClick = { onOpenChat(network.networkId) }
                            )
                        }
                    }

                    FloatingActionButton(
                        onClick = onOpenCreateNetwork,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp)
                            .testTag("chats_fab_new_network")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(R.string.action_create_network)
                        )
                    }
                }
            }

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
private fun ChatConversationRow(
    network: NetworkSummary,
    lastMessage: ChatMessageItem?,
    isTyping: Boolean,
    isOnline: Boolean,
    onClick: () -> Unit
) {
    val ext = LocalMessengerColors.current
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("chat_row_${CryptoManager.shortId(network.networkId)}")
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MonogramAvatar(
                    seed = network.networkId,
                    label = network.name,
                    size = 50.dp,
                    showOnlineDot = isOnline && !network.isMuted,
                    isMuted = network.isMuted
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = network.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (network.isMuted) {
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.VolumeOff,
                                contentDescription = "Muted network",
                                tint = SignalAmber,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = formatShortTime(lastMessage?.timestampMs ?: network.createdAtMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isTyping) {
                            Text(
                                text = "typing…",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                        } else if (lastMessage != null) {
                            if (lastMessage.isOutgoing) {
                                DeliveryTickIndicator(
                                    state = lastMessage.deliveryState,
                                    onOutgoingBubble = false
                                )
                                Spacer(Modifier.width(5.dp))
                            }
                            if (lastMessage.isVoice) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            val previewText = when {
                                lastMessage.isVoice -> "Voice message (${formatByteSize(lastMessage.payloadSizeBytes)})"
                                !lastMessage.textBody.isNullOrBlank() -> lastMessage.textBody
                                else -> "Encrypted message (${formatByteSize(lastMessage.payloadSizeBytes)})"
                            }
                            val senderPrefix = if (!lastMessage.isOutgoing) {
                                "${CryptoManager.shortId(lastMessage.senderId).take(6)}: "
                            } else ""
                            Text(
                                text = "$senderPrefix$previewText",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = ext.onlineDot,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = "E2E encrypted • ${network.memberCount} member${if (network.memberCount == 1) "" else "s"} • k${network.keyVersion}",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 80.dp)
            )
        }
    }
}

@Composable
private fun EmptyChatsState(
    onCreateNetwork: () -> Unit,
    onJoinNetwork: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
        ) {
            Icon(
                imageVector = Icons.Default.Forum,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = "Start an Encrypted Mesh Chat",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Create a private mesh network or join one via QR code to chat and push-to-talk over Bluetooth, Wi-Fi, or Internet.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(22.dp))
        Button(
            onClick = onCreateNetwork,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("empty_create_network_button")
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Create Network")
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onJoinNetwork,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("empty_join_network_button")
        ) {
            Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Join via QR or Share Code")
        }
    }
}
