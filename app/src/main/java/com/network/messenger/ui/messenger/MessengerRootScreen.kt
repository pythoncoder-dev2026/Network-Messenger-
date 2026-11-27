package com.network.messenger.ui.messenger

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.network.messenger.R
import com.network.messenger.domain.model.NetworkMember
import com.network.messenger.domain.model.TransportType
import com.network.messenger.ui.console.CreateNetworkDialog
import com.network.messenger.ui.console.JoinNetworkDialog
import com.network.messenger.ui.console.LogConsoleScreen
import com.network.messenger.ui.console.NetworkMembersDialog
import com.network.messenger.ui.console.ReportUserDialog
import com.network.messenger.ui.console.ShareNetworkQrDialog
import com.network.messenger.ui.console.VerifyContactDialog
import com.network.messenger.ui.contract.MeshUiIntent
import com.network.messenger.ui.contract.MeshUiState

enum class MessengerTab(
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
) {
    CHATS(R.string.tab_chats, Icons.Filled.ChatBubble, Icons.Outlined.ChatBubbleOutline, "nav_tab_chats"),
    NETWORKS(R.string.tab_networks, Icons.Filled.Hub, Icons.Outlined.Hub, "nav_tab_networks"),
    PTT(R.string.tab_ptt, Icons.Filled.Mic, Icons.Outlined.MicNone, "nav_tab_ptt"),
    SETTINGS(R.string.tab_settings, Icons.Filled.Settings, Icons.Outlined.Settings, "nav_tab_settings")
}

@Composable
fun MessengerRootScreen(
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
    onRestoreEncryptedBackup: (passphrase: String) -> Unit
) {
    var selectedTab by rememberSaveable { mutableStateOf(MessengerTab.CHATS) }
    var isInChatDetail by rememberSaveable { mutableStateOf(false) }
    var showFullLogScreen by rememberSaveable { mutableStateOf(false) }
    var logPanelExpanded by rememberSaveable { mutableStateOf(true) }

    var showCreateDialog by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }
    var showMembersDialog by remember { mutableStateOf(false) }
    var verifyTargetMember by remember { mutableStateOf<NetworkMember?>(null) }
    var showMyVerifyQr by remember { mutableStateOf(false) }
    var reportTargetUserId by remember { mutableStateOf<String?>(null) }

    val openChat: (String) -> Unit = { networkId ->
        onIntent(MeshUiIntent.SelectNetwork(networkId))
        isInChatDetail = true
    }

    when {
        showFullLogScreen -> {
            LogConsoleScreen(
                state = state,
                onIntent = onIntent,
                onToggleTransportWithPermission = onToggleTransportWithPermission,
                onPttStartWithPermission = onPttStartWithPermission,
                onCopyLogs = onCopyLogs,
                onExportLogs = onExportLogs,
                onCopyText = onCopyText,
                onRequestPermissions = onRequestPermissions,
                onLaunchQrScanner = onLaunchQrScanner,
                onExportEncryptedBackup = onExportEncryptedBackup,
                onRestoreEncryptedBackup = onRestoreEncryptedBackup,
                onNavigateBack = { showFullLogScreen = false },
                showBottomControls = false
            )
        }

        isInChatDetail && state.activeNetwork != null -> {
            ChatScreen(
                state = state,
                logPanelExpanded = logPanelExpanded,
                onToggleLogPanelExpanded = { logPanelExpanded = !logPanelExpanded },
                onOpenFullLogs = { showFullLogScreen = true },
                onNavigateBack = { isInChatDetail = false },
                onOpenMembersDialog = { showMembersDialog = true },
                onIntent = onIntent,
                onPttStartWithPermission = onPttStartWithPermission,
                onLaunchQrScanner = onLaunchQrScanner
            )
        }

        else -> {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val useNavRail = maxWidth >= 720.dp

                val tabContent: @Composable (Modifier) -> Unit = { contentMod ->
                    when (selectedTab) {
                        MessengerTab.CHATS -> ChatsListScreen(
                            state = state,
                            logPanelExpanded = logPanelExpanded,
                            onToggleLogPanelExpanded = { logPanelExpanded = !logPanelExpanded },
                            onOpenFullLogs = { showFullLogScreen = true },
                            onIntent = onIntent,
                            onOpenChat = openChat,
                            onToggleTransportWithPermission = onToggleTransportWithPermission,
                            onOpenCreateNetwork = { showCreateDialog = true },
                            onOpenJoinNetwork = { showJoinDialog = true },
                            onLaunchQrScanner = onLaunchQrScanner,
                            modifier = contentMod
                        )

                        MessengerTab.NETWORKS -> NetworksScreen(
                            state = state,
                            logPanelExpanded = logPanelExpanded,
                            onToggleLogPanelExpanded = { logPanelExpanded = !logPanelExpanded },
                            onOpenFullLogs = { showFullLogScreen = true },
                            onIntent = onIntent,
                            onToggleTransportWithPermission = onToggleTransportWithPermission,
                            onOpenChat = openChat,
                            onOpenMembersForNetwork = { netId ->
                                onIntent(MeshUiIntent.SelectNetwork(netId))
                                showMembersDialog = true
                            },
                            onOpenCreateNetwork = { showCreateDialog = true },
                            onOpenJoinNetwork = { showJoinDialog = true },
                            onLaunchQrScanner = onLaunchQrScanner,
                            modifier = contentMod
                        )

                        MessengerTab.PTT -> PttScreen(
                            state = state,
                            onIntent = onIntent,
                            onToggleTransportWithPermission = onToggleTransportWithPermission,
                            onPttStartWithPermission = onPttStartWithPermission,
                            onOpenCreateNetwork = { showCreateDialog = true },
                            modifier = contentMod
                        )

                        MessengerTab.SETTINGS -> SettingsScreen(
                            state = state,
                            onIntent = onIntent,
                            onOpenFullLogs = { showFullLogScreen = true },
                            onCopyUserId = { onCopyText(state.localUserId) },
                            onShowMyVerifyQr = { showMyVerifyQr = true },
                            onRequestPermissions = onRequestPermissions,
                            onExportEncryptedBackup = onExportEncryptedBackup,
                            onRestoreEncryptedBackup = onRestoreEncryptedBackup,
                            modifier = contentMod
                        )
                    }
                }

                if (useNavRail) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        NavigationRail(
                            containerColor = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxHeight()
                        ) {
                            MessengerTab.entries.forEach { tab ->
                                val selected = selectedTab == tab
                                NavigationRailItem(
                                    selected = selected,
                                    onClick = { selectedTab = tab },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                            contentDescription = stringResource(tab.labelRes)
                                        )
                                    },
                                    label = { Text(stringResource(tab.labelRes)) },
                                    modifier = Modifier.testTag(tab.testTag)
                                )
                            }
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            tabContent(Modifier.fillMaxSize())
                        }
                    }
                } else {
                    Scaffold(
                        containerColor = MaterialTheme.colorScheme.background,
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        bottomBar = {
                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surface,
                                tonalElevation = 2.dp
                            ) {
                                MessengerTab.entries.forEach { tab ->
                                    val selected = selectedTab == tab
                                    NavigationBarItem(
                                        selected = selected,
                                        onClick = { selectedTab = tab },
                                        icon = {
                                            Icon(
                                                imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                                contentDescription = stringResource(tab.labelRes)
                                            )
                                        },
                                        label = { Text(stringResource(tab.labelRes)) },
                                        modifier = Modifier.testTag(tab.testTag)
                                    )
                                }
                            }
                        }
                    ) { innerPadding ->
                        tabContent(
                            Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        )
                    }
                }
            }
        }
    }

    // Shared Modal Dialogs (Create, Join, Share QR, Members, Verify, Report)
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
                isInChatDetail = false
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
}
