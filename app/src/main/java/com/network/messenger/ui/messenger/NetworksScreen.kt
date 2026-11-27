package com.network.messenger.ui.messenger

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.network.messenger.R
import com.network.messenger.crypto.CryptoManager
import com.network.messenger.domain.model.DiscoveredNetwork
import com.network.messenger.domain.model.NetworkSummary
import com.network.messenger.domain.model.TransportStatus
import com.network.messenger.domain.model.TransportType
import com.network.messenger.ui.contract.MeshUiIntent
import com.network.messenger.ui.contract.MeshUiState
import com.network.messenger.ui.theme.SignalAmber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworksScreen(
    state: MeshUiState,
    logPanelExpanded: Boolean,
    onToggleLogPanelExpanded: () -> Unit,
    onOpenFullLogs: () -> Unit,
    onIntent: (MeshUiIntent) -> Unit,
    onToggleTransportWithPermission: (TransportType) -> Unit,
    onOpenChat: (networkId: String) -> Unit,
    onOpenMembersForNetwork: (networkId: String) -> Unit,
    onOpenCreateNetwork: () -> Unit,
    onOpenJoinNetwork: () -> Unit,
    onLaunchQrScanner: (onScanned: (String) -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val compactPanelHeight = (maxHeight * 0.25f).coerceIn(128.dp, 210.dp)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.tab_networks),
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "${state.networks.size} joined • ${state.connectedPeerCount} peer${if (state.connectedPeerCount == 1) "" else "s"} • Link: ${state.linkQuality.name}",
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

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("networks_screen_list"),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Quick Create / Join row
                item(key = "actions_row") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onOpenCreateNetwork,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("create_network_button")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Create")
                        }
                        OutlinedButton(
                            onClick = onOpenJoinNetwork,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("join_network_button")
                        ) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Join / QR")
                        }
                    }
                }

                // Mesh Transports Card
                item(key = "transports_card") {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Mesh Transports",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Enable one or several transports together — the mesh router bridges encrypted packets between them.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TransportType.entries.forEachIndexed { idx, type ->
                                val tState = state.transportStates[type]
                                val status = tState?.status ?: TransportStatus.STOPPED
                                val enabled = status == TransportStatus.ACTIVE || status == TransportStatus.STARTING
                                TransportCardRow(
                                    type = type,
                                    status = status,
                                    detail = tState?.detail ?: "Stopped",
                                    peerCount = tState?.peerCount ?: 0,
                                    enabled = enabled,
                                    onToggle = { onToggleTransportWithPermission(type) }
                                )
                                if (idx < TransportType.entries.lastIndex) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                                }
                            }
                        }
                    }
                }

                // Joined Networks Header
                item(key = "joined_header") {
                    Text(
                        text = "Your Mesh Networks (${state.networks.size})",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }

                if (state.networks.isEmpty()) {
                    item(key = "no_networks") {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Hub,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp)
                                )
                                Text(
                                    text = "No networks yet. Create one to generate a 128-bit mesh ID and QR code, or join a nearby network.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(items = state.networks, key = { it.networkId }) { network ->
                        JoinedNetworkCard(
                            network = network,
                            isSelected = network.networkId == state.activeNetworkId,
                            onOpenChat = { onOpenChat(network.networkId) },
                            onShareQr = { onIntent(MeshUiIntent.RequestShareCode(network.networkId)) },
                            onManageMembers = { onOpenMembersForNetwork(network.networkId) }
                        )
                    }
                }

                // Nearby Discovered Networks
                if (state.discoveredNetworks.isNotEmpty()) {
                    item(key = "discovered_header") {
                        Text(
                            text = "Nearby Discovered (${state.discoveredNetworks.size})",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    items(items = state.discoveredNetworks, key = { "disc_${it.networkId}" }) { disc ->
                        DiscoveredNetworkCard(
                            network = disc,
                            onJoin = { onIntent(MeshUiIntent.JoinDiscovered(disc, null)) }
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
private fun TransportCardRow(
    type: TransportType,
    status: TransportStatus,
    detail: String,
    peerCount: Int,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    val icon: ImageVector = when (type) {
        TransportType.NEARBY -> Icons.Default.Bluetooth
        TransportType.WIFI_LAN -> Icons.Default.Wifi
        TransportType.INTERNET_WS -> Icons.Default.Public
    }
    val title = when (type) {
        TransportType.NEARBY -> "Bluetooth / BLE Nearby"
        TransportType.WIFI_LAN -> "Wi-Fi Local / Direct"
        TransportType.INTERNET_WS -> "Internet Relay (WS)"
    }
    val statusColor = when (status) {
        TransportStatus.ACTIVE -> MaterialTheme.colorScheme.secondary
        TransportStatus.STARTING -> MaterialTheme.colorScheme.primary
        TransportStatus.DEGRADED -> SignalAmber
        TransportStatus.ERROR -> MaterialTheme.colorScheme.error
        TransportStatus.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(statusColor.copy(alpha = 0.14f))
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = statusColor,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (peerCount > 0) "$detail • $peerCount peer${if (peerCount == 1) "" else "s"}" else detail,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = { onToggle() },
            modifier = Modifier.testTag("transport_switch_${type.name.lowercase()}")
        )
    }
}

@Composable
private fun JoinedNetworkCard(
    network: NetworkSummary,
    isSelected: Boolean,
    onOpenChat: () -> Unit,
    onShareQr: () -> Unit,
    onManageMembers: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onOpenChat)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MonogramAvatar(
                    seed = network.networkId,
                    label = network.name,
                    size = 44.dp,
                    isMuted = network.isMuted
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = network.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (network.isMuted) {
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Default.VolumeOff,
                                contentDescription = "Muted network",
                                tint = SignalAmber,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Text(
                        text = "${network.role.name} • ${network.memberCount} member${if (network.memberCount == 1) "" else "s"} • key v${network.keyVersion} • id:${CryptoManager.shortId(network.networkId).take(8)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onOpenChat,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.ChatBubbleOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Chat")
                }
                OutlinedButton(
                    onClick = onShareQr
                ) {
                    Icon(Icons.Default.QrCode2, contentDescription = "Share QR", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("QR")
                }
                IconButton(onClick = onManageMembers) {
                    Icon(
                        imageVector = Icons.Default.Group,
                        contentDescription = "Manage members",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscoveredNetworkCard(
    network: DiscoveredNetwork,
    onJoin: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MonogramAvatar(seed = network.networkId, label = network.name, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = network.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Via ${network.viaTransport.label} • id:${CryptoManager.shortId(network.networkId).take(8)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(onClick = onJoin) {
                Text("Join")
            }
        }
    }
}
