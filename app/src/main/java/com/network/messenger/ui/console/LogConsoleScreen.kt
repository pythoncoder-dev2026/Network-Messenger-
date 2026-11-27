package com.network.messenger.ui.console

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.network.messenger.R
import com.network.messenger.crypto.CryptoManager
import com.network.messenger.domain.model.DeliveryState
import com.network.messenger.domain.model.KeyChangeAlert
import com.network.messenger.domain.model.LogCategory
import com.network.messenger.domain.model.LogEntry
import com.network.messenger.domain.model.LogSeverity
import com.network.messenger.domain.model.NetworkMember
import com.network.messenger.domain.model.TransportType
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
import com.network.messenger.ui.theme.SignalSky
import com.network.messenger.ui.theme.SignalTeal
import com.network.messenger.ui.theme.SignalViolet
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogConsoleScreen(
    state: MeshUiState,
    onIntent: (MeshUiIntent) -> Unit,
    onToggleTransportWithPermission: (TransportType) -> Unit,
    onPttStartWithPermission: () -> Unit,
    onCopyLogs: () -> Unit,
    onExportLogs: () -> Unit,
    onCopyText: (String) -> Unit,
    onRequestPermissions: () -> Unit,
    onLaunchQrScanner: (onScanned: (String) -> Unit) -> Unit,
    onExportEncryptedBackup: (passphrase: String) -> Unit,
    onRestoreEncryptedBackup: (passphrase: String) -> Unit,
    onNavigateBack: (() -> Unit)? = null,
    showBottomControls: Boolean = true
) {
    if (onNavigateBack != null) {
        BackHandler(onBack = onNavigateBack)
    }

    var showCreateDialog by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }
    var showMembersDialog by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var verifyTargetMember by remember { mutableStateOf<NetworkMember?>(null) }
    var showMyVerifyQr by remember { mutableStateOf(false) }
    var reportTargetUserId by remember { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val filteredLogs = state.filteredLogs

    val isAtBottom by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= (filteredLogs.size - 2).coerceAtLeast(0)
        }
    }

    LaunchedEffect(filteredLogs.size) {
        if (filteredLogs.isNotEmpty() && isAtBottom) {
            listState.animateScrollToItem(filteredLogs.lastIndex)
        }
    }

    val missingPermissionsCount = state.permissions.count { !it.granted }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(ConsoleCanvas),
        containerColor = ConsoleCanvas,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            ConsoleTopBar(
                state = state,
                missingPermissionsCount = missingPermissionsCount,
                onNavigateBack = onNavigateBack,
                onMarkRead = { onIntent(MeshUiIntent.MarkActiveNetworkRead) },
                onCopyLogs = onCopyLogs,
                onExportLogs = onExportLogs,
                onClearLogs = { onIntent(MeshUiIntent.ClearLogs) },
                onRequestPermissions = onRequestPermissions,
                onOpenSettings = { showSettingsSheet = true },
                onSelectFilter = { onIntent(MeshUiIntent.SetFilter(it)) }
            )
        },
        bottomBar = {
            if (showBottomControls) {
                ControlsBar(
                    state = state,
                    onIntent = onIntent,
                    onToggleTransportWithPermission = onToggleTransportWithPermission,
                    onPttStartWithPermission = onPttStartWithPermission,
                    onOpenCreateNetwork = { showCreateDialog = true },
                    onOpenJoinNetwork = { showJoinDialog = true },
                    onOpenNetworkMembers = { showMembersDialog = true }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .then(if (!showBottomControls) Modifier.navigationBarsPadding() else Modifier)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(ConsoleCanvas, Color(0xFF070B10))
                    )
                )
        ) {
            // Loud Security Warning Banner if any contact's identity key changed
            state.keyChangeAlerts.forEach { alert ->
                KeyChangeWarningBanner(
                    alert = alert,
                    onVerify = {
                        onLaunchQrScanner { scanned ->
                            onIntent(MeshUiIntent.VerifyContactFromQr(alert.userId, scanned))
                        }
                    },
                    onDismiss = { onIntent(MeshUiIntent.DismissKeyChangeAlert(alert.userId)) }
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("log_console_list"),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    items(items = filteredLogs, key = { it.id }) { entry ->
                        LogLineItem(entry = entry)
                    }
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = !isAtBottom && filteredLogs.size > 8,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp)
                ) {
                    SmallFloatingActionButton(
                        onClick = {
                            coroutineScope.launch {
                                if (filteredLogs.isNotEmpty()) {
                                    listState.animateScrollToItem(filteredLogs.lastIndex)
                                }
                            }
                        },
                        containerColor = ConsoleSurfaceElevated,
                        contentColor = SignalCyan,
                        elevation = FloatingActionButtonDefaults.elevation(4.dp),
                        modifier = Modifier.testTag("scroll_to_bottom_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowDownward,
                            contentDescription = stringResource(R.string.cd_scroll_bottom)
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateNetworkDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name, pwd ->
                showCreateDialog = false
                onIntent(MeshUiIntent.CreateNetwork(name, pwd))
            }
        )
    }

    if (showJoinDialog) {
        JoinNetworkDialog(
            discoveredNetworks = state.discoveredNetworks,
            onDismiss = { showJoinDialog = false },
            onJoinCode = { code, pwd ->
                showJoinDialog = false
                onIntent(MeshUiIntent.JoinByShareCode(code, pwd))
            },
            onJoinDiscovered = { discovered, pwd ->
                showJoinDialog = false
                onIntent(MeshUiIntent.JoinDiscovered(discovered, pwd))
            },
            onLaunchQrScanner = onLaunchQrScanner
        )
    }

    if (state.latestShareCode != null && state.latestShareNetworkName != null) {
        ShareNetworkQrDialog(
            networkName = state.latestShareNetworkName,
            shareCode = state.latestShareCode,
            onCopyCode = { onCopyText(state.latestShareCode) },
            onDismiss = { onIntent(MeshUiIntent.DismissShareCode) }
        )
    }

    if (showMembersDialog && state.activeNetwork != null) {
        val activeNet = state.activeNetwork!!
        NetworkMembersDialog(
            network = activeNet,
            localUserId = state.localUserId,
            members = state.activeNetworkMembers,
            mutedUserIds = state.mutedUsers.map { it.userId }.toSet(),
            onDismiss = { showMembersDialog = false },
            onShowQr = {
                showMembersDialog = false
                onIntent(MeshUiIntent.RequestShareCode(activeNet.networkId))
            },
            onToggleNetworkMute = {
                onIntent(MeshUiIntent.SetNetworkMuted(activeNet.networkId, !activeNet.isMuted))
            },
            onRotateKey = { onIntent(MeshUiIntent.RotateNetworkKey(activeNet.networkId)) },
            onVerifyMember = { member ->
                showMembersDialog = false
                verifyTargetMember = member
            },
            onToggleMuteMember = { uid, currentlyMuted ->
                if (currentlyMuted) onIntent(MeshUiIntent.UnmuteUser(uid))
                else onIntent(MeshUiIntent.MuteUser(uid))
            },
            onReportMember = { uid ->
                showMembersDialog = false
                reportTargetUserId = uid
            },
            onRemoveMember = { uid ->
                onIntent(MeshUiIntent.RemoveMember(activeNet.networkId, uid))
            },
            onBlockMember = { uid ->
                onIntent(MeshUiIntent.BlockUser(uid, "Blocked from network members"))
            },
            onLeaveNetwork = {
                showMembersDialog = false
                onIntent(MeshUiIntent.LeaveNetwork(activeNet.networkId))
            }
        )
    }

    verifyTargetMember?.let { member ->
        VerifyContactDialog(
            contactUserId = member.userId,
            safetyNumber = member.safetyNumber,
            alreadyVerified = member.verified,
            keyChangedWarning = member.keyChangedWarning,
            myVerificationQrPayload = state.myVerificationQrPayload,
            onScanContactQr = {
                onLaunchQrScanner { scanned ->
                    onIntent(MeshUiIntent.VerifyContactFromQr(member.userId, scanned))
                    verifyTargetMember = null
                }
            },
            onMarkVerifiedManually = {
                onIntent(MeshUiIntent.MarkContactVerifiedManually(member.userId))
                verifyTargetMember = null
            },
            onDismiss = { verifyTargetMember = null }
        )
    }

    if (showMyVerifyQr) {
        VerifyContactDialog(
            contactUserId = state.localUserId,
            safetyNumber = null,
            alreadyVerified = true,
            keyChangedWarning = false,
            myVerificationQrPayload = state.myVerificationQrPayload,
            onScanContactQr = {
                onLaunchQrScanner { scanned ->
                    onIntent(MeshUiIntent.VerifyContactFromQr(null, scanned))
                    showMyVerifyQr = false
                }
            },
            onMarkVerifiedManually = null,
            onDismiss = { showMyVerifyQr = false }
        )
    }

    reportTargetUserId?.let { targetUid ->
        val activeNet = state.activeNetwork
        val canNotifyOwner = activeNet != null && activeNet.ownerId != state.localUserId
        ReportUserDialog(
            targetUserId = targetUid,
            canSendToOwner = canNotifyOwner,
            onDismiss = { reportTargetUserId = null },
            onSubmit = { reason, sendToOwner ->
                onIntent(
                    MeshUiIntent.ReportUser(
                        userId = targetUid,
                        networkId = activeNet?.networkId,
                        reason = reason,
                        sendToOwner = sendToOwner && canNotifyOwner
                    )
                )
                reportTargetUserId = null
            }
        )
    }

    if (showSettingsSheet) {
        AboutSettingsSheet(
            state = state,
            onDismiss = { showSettingsSheet = false },
            onIntent = onIntent,
            onCopyUserId = { onCopyText(state.localUserId) },
            onShowMyVerifyQr = {
                showSettingsSheet = false
                showMyVerifyQr = true
            },
            onRequestPermissions = onRequestPermissions,
            onExportEncryptedBackup = onExportEncryptedBackup,
            onRestoreEncryptedBackup = onRestoreEncryptedBackup
        )
    }
}

@Composable
private fun KeyChangeWarningBanner(
    alert: KeyChangeAlert,
    onVerify: () -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SignalRose.copy(alpha = 0.22f))
            .border(1.dp, SignalRose)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("key_change_warning_banner"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.GppMaybe,
            contentDescription = "Key changed security warning",
            tint = SignalRose,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "KEY CHANGED: ${CryptoManager.shortId(alert.userId)}",
                style = MaterialTheme.typography.labelMedium,
                color = SignalRose,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Identity key changed — re-verify safety number before trusting.",
                style = MaterialTheme.typography.labelSmall,
                color = ConsoleTextPrimary
            )
        }
        TextButton(onClick = onVerify) {
            Text("Scan QR", color = SignalCyan, style = MaterialTheme.typography.labelMedium)
        }
        TextButton(onClick = onDismiss) {
            Text("Hide", color = ConsoleTextMuted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ConsoleTopBar(
    state: MeshUiState,
    missingPermissionsCount: Int,
    onNavigateBack: (() -> Unit)?,
    onMarkRead: () -> Unit,
    onCopyLogs: () -> Unit,
    onExportLogs: () -> Unit,
    onClearLogs: () -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onSelectFilter: (LogCategory?) -> Unit
) {
    Surface(
        color = ConsoleSurface,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onNavigateBack != null) {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("full_logs_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                            tint = ConsoleTextPrimary
                        )
                    }
                } else {
                    val liveColor = when {
                        state.connectedPeerCount > 0 -> SignalEmerald
                        state.transportStates.values.any { it.status.name == "ACTIVE" } -> SignalCyan
                        else -> ConsoleTextMuted
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(liveColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.appName,
                        style = MaterialTheme.typography.titleMedium,
                        color = ConsoleTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val queueTag = if (state.pendingQueueCount > 0) " • Q:${state.pendingQueueCount}" else ""
                    Text(
                        text = "v${state.versionName} • uid:${state.shortUserId} • ${state.connectedPeerCount} peers$queueTag",
                        style = MaterialTheme.typography.labelSmall,
                        color = ConsoleTextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (state.activeNetworkId != null) {
                    IconButton(
                        onClick = onMarkRead,
                        modifier = Modifier.testTag("mark_read_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DoneAll,
                            contentDescription = "Send read receipts",
                            tint = SignalEmerald,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                if (missingPermissionsCount > 0) {
                    IconButton(
                        onClick = onRequestPermissions,
                        modifier = Modifier.testTag("permissions_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = stringResource(R.string.action_permissions),
                            tint = SignalAmber,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                IconButton(
                    onClick = onCopyLogs,
                    modifier = Modifier.testTag("copy_logs_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.action_copy_logs),
                        tint = ConsoleTextPrimary,
                        modifier = Modifier.size(19.dp)
                    )
                }
                IconButton(
                    onClick = onExportLogs,
                    modifier = Modifier.testTag("export_logs_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.IosShare,
                        contentDescription = stringResource(R.string.action_export_logs),
                        tint = ConsoleTextPrimary,
                        modifier = Modifier.size(19.dp)
                    )
                }
                IconButton(
                    onClick = onClearLogs,
                    modifier = Modifier.testTag("clear_logs_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = stringResource(R.string.action_clear_logs),
                        tint = ConsoleTextPrimary,
                        modifier = Modifier.size(19.dp)
                    )
                }
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.testTag("settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = stringResource(R.string.action_settings),
                        tint = SignalCyan,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterPill(
                    label = "ALL (${state.logs.size})",
                    selected = state.activeFilter == null,
                    accent = SignalCyan,
                    testTag = "filter_all",
                    onClick = { onSelectFilter(null) }
                )
                LogCategory.entries.forEach { cat ->
                    FilterPill(
                        label = cat.filterLabel.uppercase(),
                        selected = state.activeFilter == cat,
                        accent = categoryColor(cat),
                        testTag = "filter_${cat.name.lowercase()}",
                        onClick = { onSelectFilter(if (state.activeFilter == cat) null else cat) }
                    )
                }
            }
            HorizontalDivider(color = ConsoleBorder, thickness = 1.dp)
        }
    }
}

@Composable
private fun FilterPill(
    label: String,
    selected: Boolean,
    accent: Color,
    testTag: String,
    onClick: () -> Unit
) {
    val bg = if (selected) accent.copy(alpha = 0.2f) else ConsoleSurfaceElevated
    val border = if (selected) accent else ConsoleBorder
    val fg = if (selected) accent else ConsoleTextMuted
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp)
            .testTag(testTag)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun LogLineItem(entry: LogEntry) {
    val timeStr = remember(entry.timestampMs) {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(entry.timestampMs))
    }
    val tagColor = categoryColor(entry.category)
    val textColor = when {
        entry.isHeader -> SignalCyan
        entry.severity == LogSeverity.ERROR -> SignalRose
        entry.severity == LogSeverity.WARN -> SignalAmber
        entry.severity == LogSeverity.SUCCESS -> SignalEmerald
        else -> ConsoleTextPrimary
    }

    val rowBg = if (entry.isHeader) {
        SignalCyan.copy(alpha = 0.12f)
    } else if (entry.severity == LogSeverity.ERROR) {
        SignalRose.copy(alpha = 0.08f)
    } else {
        Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(rowBg)
            .then(
                if (entry.isHeader) Modifier.border(1.dp, SignalCyan.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                else Modifier
            )
            .padding(horizontal = 6.dp, vertical = if (entry.isHeader) 6.dp else 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 4.dp, end = 6.dp)
                .width(3.dp)
                .height(12.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(tagColor)
        )
        Text(
            text = buildAnnotatedString {
                if (!entry.isHeader) {
                    withStyle(SpanStyle(color = ConsoleTextMuted)) {
                        append(timeStr)
                        append(' ')
                    }
                    withStyle(SpanStyle(color = tagColor, fontWeight = FontWeight.Bold)) {
                        append("[${entry.category.tag}] ")
                    }
                }
                withStyle(
                    SpanStyle(
                        color = textColor,
                        fontWeight = if (entry.isHeader) FontWeight.Bold else FontWeight.Normal
                    )
                ) {
                    append(entry.message)
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )

        // Delivery & read receipt ticks shown next to message entries
        entry.deliveryState?.let { state ->
            val (symbol, color) = when (state) {
                DeliveryState.QUEUED -> "◷" to SignalAmber
                DeliveryState.SENT -> "✓" to ConsoleTextMuted
                DeliveryState.RELAYED -> "✓↻" to SignalCyan
                DeliveryState.DELIVERED -> "✓✓" to ConsoleTextPrimary
                DeliveryState.READ -> "✓✓" to SignalEmerald
                DeliveryState.FAILED -> "✕" to SignalRose
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = symbol,
                style = MaterialTheme.typography.labelMedium,
                color = color,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(top = 1.dp)
                    .testTag("delivery_tick_${state.name.lowercase()}")
            )
        }
    }
}

fun categoryColor(category: LogCategory): Color = when (category) {
    LogCategory.SYSTEM -> SignalCyan
    LogCategory.TRANSPORT -> SignalTeal
    LogCategory.PEER -> SignalEmerald
    LogCategory.NETWORK -> SignalSky
    LogCategory.CRYPTO -> SignalViolet
    LogCategory.MESSAGE -> ConsoleTextPrimary
    LogCategory.VOICE -> SignalSky
    LogCategory.BLOCK -> SignalOrange
    LogCategory.PERMISSION -> SignalAmber
    LogCategory.ERROR -> SignalRose
}
