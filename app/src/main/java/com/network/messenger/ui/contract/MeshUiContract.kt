package com.network.messenger.ui.contract

import com.network.messenger.data.preferences.ThemeMode
import com.network.messenger.data.repository.ChatMessageItem
import com.network.messenger.domain.model.BlockedUser
import com.network.messenger.domain.model.DiscoveredNetwork
import com.network.messenger.domain.model.KeyChangeAlert
import com.network.messenger.domain.model.LinkQuality
import com.network.messenger.domain.model.LogCategory
import com.network.messenger.domain.model.LogEntry
import com.network.messenger.domain.model.MutedUser
import com.network.messenger.domain.model.NetworkMember
import com.network.messenger.domain.model.NetworkSummary
import com.network.messenger.domain.model.PeerInfo
import com.network.messenger.domain.model.PermissionStatus
import com.network.messenger.domain.model.TransportState
import com.network.messenger.domain.model.TransportType
import com.network.messenger.domain.model.TypingPeer
import com.network.messenger.domain.model.UserReport

/**
 * Decoupled presentation contract so the modern messenger UI, compact log panel,
 * full log console, and any future UI bind purely to MeshUiState and MeshUiIntent.
 */
data class MeshUiState(
    val appName: String = "Network messenger",
    val packageName: String = "com.network.messenger",
    val versionName: String = "1.0.0",
    val versionCode: Int = 1,
    val localUserId: String = "",
    val shortUserId: String = "",
    val myVerificationQrPayload: String = "",
    val logs: List<LogEntry> = emptyList(),
    val activeFilter: LogCategory? = null,
    val showLogsPanel: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val transportStates: Map<TransportType, TransportState> = TransportType.entries.associateWith {
        TransportState(type = it)
    },
    val peers: List<PeerInfo> = emptyList(),
    val networks: List<NetworkSummary> = emptyList(),
    val activeNetworkId: String? = null,
    val activeNetworkMembers: List<NetworkMember> = emptyList(),
    val activeChatMessages: List<ChatMessageItem> = emptyList(),
    val latestMessageByNetwork: Map<String, ChatMessageItem> = emptyMap(),
    val discoveredNetworks: List<DiscoveredNetwork> = emptyList(),
    val blockedUsers: List<BlockedUser> = emptyList(),
    val mutedUsers: List<MutedUser> = emptyList(),
    val userReports: List<UserReport> = emptyList(),
    val typingPeers: List<TypingPeer> = emptyList(),
    val keyChangeAlerts: List<KeyChangeAlert> = emptyList(),
    val pendingQueueCount: Int = 0,
    val queueExpiryHours: Int = 24,
    val permissions: List<PermissionStatus> = emptyList(),
    val relayServerUrl: String = "ws://10.0.2.2:8080",
    val autoPlayVoice: Boolean = true,
    val isRecordingPtt: Boolean = false,
    val isPlayingVoice: Boolean = false,
    val playingVoicePacketId: String? = null,
    val linkQuality: LinkQuality = LinkQuality.NONE,
    val lowBattery: Boolean = false,
    val latestShareCode: String? = null,
    val latestShareNetworkName: String? = null
) {
    val activeNetwork: NetworkSummary?
        get() = networks.firstOrNull { it.networkId == activeNetworkId }

    val activeTypingPeers: List<TypingPeer>
        get() = typingPeers.filter { it.networkId == activeNetworkId }

    val filteredLogs: List<LogEntry>
        get() = if (activeFilter == null) logs else logs.filter { it.isHeader || it.category == activeFilter }

    val compactRecentLogs: List<LogEntry>
        get() = logs.filterNot { it.isHeader }.takeLast(8).ifEmpty { logs.takeLast(8) }

    val connectedPeerCount: Int
        get() = peers.count { it.connected }
}

sealed interface MeshUiIntent {
    data class SetFilter(val category: LogCategory?) : MeshUiIntent
    data object ClearLogs : MeshUiIntent
    data class SetShowLogsPanel(val enabled: Boolean) : MeshUiIntent
    data class SetThemeMode(val mode: ThemeMode) : MeshUiIntent
    data class ToggleTransport(val type: TransportType) : MeshUiIntent
    data class SelectNetwork(val networkId: String) : MeshUiIntent
    data class CreateNetwork(val name: String, val password: String?) : MeshUiIntent
    data class JoinByShareCode(val code: String, val password: String?) : MeshUiIntent
    data class JoinDiscovered(val network: DiscoveredNetwork, val password: String?) : MeshUiIntent
    data class LeaveNetwork(val networkId: String) : MeshUiIntent
    data class RequestShareCode(val networkId: String, val passwordForWrap: String? = null) : MeshUiIntent
    data object DismissShareCode : MeshUiIntent
    data class RemoveMember(val networkId: String, val userId: String) : MeshUiIntent
    data class RotateNetworkKey(val networkId: String) : MeshUiIntent
    data class BlockUser(val userId: String, val reason: String) : MeshUiIntent
    data class UnblockUser(val userId: String) : MeshUiIntent
    data class MuteUser(val userId: String) : MeshUiIntent
    data class UnmuteUser(val userId: String) : MeshUiIntent
    data class SetNetworkMuted(val networkId: String, val muted: Boolean) : MeshUiIntent
    data class ReportUser(
        val userId: String,
        val networkId: String?,
        val reason: String,
        val sendToOwner: Boolean
    ) : MeshUiIntent
    data class VerifyContactFromQr(val expectedUserId: String?, val scannedQr: String) : MeshUiIntent
    data class MarkContactVerifiedManually(val userId: String) : MeshUiIntent
    data class DismissKeyChangeAlert(val userId: String) : MeshUiIntent
    data class SendText(val text: String) : MeshUiIntent
    data class OnTypingChanged(val isTyping: Boolean) : MeshUiIntent
    data object MarkActiveNetworkRead : MeshUiIntent
    data object StartPtt : MeshUiIntent
    data object StopPtt : MeshUiIntent
    data class PlayVoiceMessage(val packetId: String) : MeshUiIntent
    data object StopVoicePlayback : MeshUiIntent
    data class SetAutoPlayVoice(val enabled: Boolean) : MeshUiIntent
    data class SetQueueExpiryHours(val hours: Int) : MeshUiIntent
    data class UpdateRelayUrl(val url: String) : MeshUiIntent
    data class RefreshPermissions(val results: Map<String, Boolean>? = null) : MeshUiIntent
}
