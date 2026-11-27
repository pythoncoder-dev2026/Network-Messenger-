package com.network.messenger.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.network.messenger.BuildConfig
import com.network.messenger.audio.AudioEngine
import com.network.messenger.crypto.CryptoManager
import com.network.messenger.crypto.LocalIdentity
import com.network.messenger.data.preferences.SettingsStore
import com.network.messenger.data.preferences.ThemeMode
import com.network.messenger.data.repository.BackupRepository
import com.network.messenger.data.repository.BackupSummary
import com.network.messenger.data.repository.ChatMessageItem
import com.network.messenger.data.repository.LogRepository
import com.network.messenger.data.repository.MessageRepository
import com.network.messenger.data.repository.NetworkRepository
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
import com.network.messenger.domain.model.TransportStatus
import com.network.messenger.domain.model.TransportType
import com.network.messenger.domain.model.TypingPeer
import com.network.messenger.domain.model.UserReport
import com.network.messenger.domain.model.VoiceMode
import com.network.messenger.mesh.MeshRouter
import com.network.messenger.service.MeshForegroundService
import com.network.messenger.transport.TransportManager
import com.network.messenger.ui.contract.MeshUiIntent
import com.network.messenger.ui.contract.MeshUiState
import com.network.messenger.util.PermissionHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MeshViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cryptoManager: CryptoManager,
    private val logRepository: LogRepository,
    private val networkRepository: NetworkRepository,
    private val messageRepository: MessageRepository,
    private val backupRepository: BackupRepository,
    private val transportManager: TransportManager,
    private val meshRouter: MeshRouter,
    private val audioEngine: AudioEngine,
    private val settingsStore: SettingsStore
) : ViewModel() {

    private val identityState = MutableStateFlow(cryptoManager.getOrCreateIdentity())
    private val activeFilter = MutableStateFlow<LogCategory?>(null)
    private val permissionsState = MutableStateFlow<List<PermissionStatus>>(PermissionHelper.inspectAll(context))
    private val shareCodeState = MutableStateFlow<Pair<String, String>?>(null)
    private val playingVoicePacketId = MutableStateFlow<String?>(null)
    private var typingStopJob: Job? = null

    private val resolvedActiveNetworkId: StateFlow<String?> = combine(
        networkRepository.networks,
        settingsStore.activeNetworkId
    ) { networks, activeId ->
        when {
            activeId != null && networks.any { it.networkId == activeId } -> activeId
            networks.isNotEmpty() -> networks.first().networkId
            else -> null
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val activeNetworkMembers: StateFlow<List<NetworkMember>> =
        resolvedActiveNetworkId.flatMapLatest { netId ->
            if (netId.isNullOrBlank()) flowOf(emptyList())
            else networkRepository.observeMembers(netId)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val activeChatMessages: StateFlow<List<ChatMessageItem>> =
        resolvedActiveNetworkId.flatMapLatest { netId ->
            if (netId.isNullOrBlank()) flowOf(emptyList())
            else messageRepository.observeChatMessages(netId)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val coreBundle = combine(
        logRepository.entries,
        activeFilter,
        transportManager.transportStates,
        transportManager.allPeers,
        transportManager.lowBattery
    ) { logs, filter, tStates, peers, lowBatt ->
        CoreBundle(logs, filter, tStates, peers, lowBatt)
    }

    private val networkBundle = combine(
        networkRepository.networks,
        resolvedActiveNetworkId,
        activeNetworkMembers,
        networkRepository.discoveredNetworks,
        networkRepository.blockedUsers
    ) { networks, activeId, members, discovered, blocked ->
        NetworkBundle(networks, activeId, members, discovered, blocked)
    }

    private val chatBundle = combine(
        activeChatMessages,
        messageRepository.observeLatestMessageByNetwork(),
        settingsStore.showLogsPanel,
        settingsStore.themeMode,
        playingVoicePacketId
    ) { chatMsgs, latestByNet, showLogs, themeMode, playingId ->
        ChatBundle(chatMsgs, latestByNet, showLogs, themeMode, playingId)
    }

    private val trustBundle = combine(
        networkRepository.mutedUsers,
        networkRepository.userReports,
        meshRouter.typingPeers,
        networkRepository.keyChangeAlerts,
        messageRepository.pendingQueueCount
    ) { muted, reports, typing, keyAlerts, pendingCount ->
        TrustBundle(muted, reports, typing, keyAlerts, pendingCount)
    }

    private val settingsBundle = combine(
        settingsStore.relayServerUrl,
        settingsStore.autoPlayVoice,
        settingsStore.queueExpiryHours,
        audioEngine.isRecording,
        audioEngine.isPlaying
    ) { relayUrl, autoPlay, expiryHours, recording, playing ->
        SettingsBundle(relayUrl, autoPlay, expiryHours, recording, playing)
    }

    val uiState: StateFlow<MeshUiState> = combine(
        combine(coreBundle, networkBundle, trustBundle) { c, n, t -> Triple(c, n, t) },
        combine(settingsBundle, permissionsState, shareCodeState, identityState, chatBundle) { s, p, sh, id, ch ->
            MetaBundle(s, p, sh, id, ch)
        }
    ) { (core, net, trust), meta ->
        MeshUiState(
            appName = LogRepository.APP_DISPLAY_NAME,
            packageName = BuildConfig.APPLICATION_ID,
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            localUserId = meta.identity.userId,
            shortUserId = meta.identity.shortUserId,
            myVerificationQrPayload = cryptoManager.buildVerificationQrPayload(),
            logs = core.logs,
            activeFilter = core.filter,
            showLogsPanel = meta.chat.showLogsPanel,
            themeMode = meta.chat.themeMode,
            transportStates = core.tStates,
            peers = core.peers,
            networks = net.networks,
            activeNetworkId = net.activeId,
            activeNetworkMembers = net.members,
            activeChatMessages = meta.chat.activeChatMessages,
            latestMessageByNetwork = meta.chat.latestByNetwork,
            discoveredNetworks = net.discovered,
            blockedUsers = net.blocked,
            mutedUsers = trust.mutedUsers,
            userReports = trust.reports,
            typingPeers = trust.typingPeers,
            keyChangeAlerts = trust.keyChangeAlerts,
            pendingQueueCount = trust.pendingCount,
            queueExpiryHours = meta.settings.queueExpiryHours,
            permissions = meta.permissions,
            relayServerUrl = meta.settings.relayUrl,
            autoPlayVoice = meta.settings.autoPlay,
            isRecordingPtt = meta.settings.recording,
            isPlayingVoice = meta.settings.playing,
            playingVoicePacketId = if (meta.settings.playing) meta.chat.playingVoicePacketId else null,
            linkQuality = transportManager.evaluateLinkQuality(),
            lowBattery = core.lowBatt,
            latestShareCode = meta.share?.first,
            latestShareNetworkName = meta.share?.second
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MeshUiState(
            appName = LogRepository.APP_DISPLAY_NAME,
            packageName = BuildConfig.APPLICATION_ID,
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            localUserId = identityState.value.userId,
            shortUserId = identityState.value.shortUserId,
            myVerificationQrPayload = cryptoManager.buildVerificationQrPayload(),
            logs = logRepository.entries.value,
            permissions = permissionsState.value
        )
    )

    init {
        meshRouter.start()
        logInitialPermissions()

        viewModelScope.launch {
            settingsStore.relayServerUrl.collect { url ->
                transportManager.updateRelayServerUrl(url)
            }
        }
        viewModelScope.launch {
            networkRepository.networks.collect { nets ->
                transportManager.updateSubscribedNetworks(nets.map { it.networkId }.toSet())
            }
        }
    }

    fun dispatch(intent: MeshUiIntent) {
        when (intent) {
            is MeshUiIntent.SetFilter -> activeFilter.value = intent.category
            is MeshUiIntent.ClearLogs -> logRepository.clear()
            is MeshUiIntent.SetShowLogsPanel -> viewModelScope.launch {
                settingsStore.setShowLogsPanel(intent.enabled)
                logRepository.info(
                    LogCategory.SYSTEM,
                    "Log panel visibility set to ${if (intent.enabled) "ON" else "OFF"} (background logging active)"
                )
            }
            is MeshUiIntent.SetThemeMode -> viewModelScope.launch {
                settingsStore.setThemeMode(intent.mode)
            }
            is MeshUiIntent.ToggleTransport -> toggleTransport(intent.type)
            is MeshUiIntent.SelectNetwork -> viewModelScope.launch {
                settingsStore.setActiveNetworkId(intent.networkId)
                logRepository.info(LogCategory.NETWORK, "Active network switched to ${CryptoManager.shortId(intent.networkId)}")
                meshRouter.markNetworkMessagesRead(intent.networkId)
            }
            is MeshUiIntent.CreateNetwork -> createNetwork(intent.name, intent.password)
            is MeshUiIntent.JoinByShareCode -> joinByShareCode(intent.code, intent.password)
            is MeshUiIntent.JoinDiscovered -> joinDiscovered(intent)
            is MeshUiIntent.LeaveNetwork -> viewModelScope.launch {
                networkRepository.leaveNetwork(intent.networkId)
            }
            is MeshUiIntent.RequestShareCode -> viewModelScope.launch {
                val net = networkRepository.getNetworkEntity(intent.networkId) ?: return@launch
                val code = networkRepository.buildShareCodeForExisting(
                    networkId = intent.networkId,
                    passwordForWrap = intent.passwordForWrap,
                    relayHint = uiState.value.relayServerUrl
                )
                if (code != null) {
                    shareCodeState.value = code to net.name
                }
            }
            is MeshUiIntent.DismissShareCode -> shareCodeState.value = null
            is MeshUiIntent.RemoveMember -> viewModelScope.launch {
                val rotation = networkRepository.removeMemberAndRotateKey(intent.networkId, intent.userId)
                if (rotation != null) {
                    meshRouter.distributeKeyRotation(rotation)
                }
            }
            is MeshUiIntent.RotateNetworkKey -> viewModelScope.launch {
                val rotation = networkRepository.rotateNetworkKey(intent.networkId)
                if (rotation != null) {
                    meshRouter.distributeKeyRotation(rotation)
                }
            }
            is MeshUiIntent.BlockUser -> viewModelScope.launch {
                val rotations = networkRepository.blockUser(intent.userId, intent.reason)
                rotations.forEach { meshRouter.distributeKeyRotation(it) }
            }
            is MeshUiIntent.UnblockUser -> viewModelScope.launch {
                networkRepository.unblockUser(intent.userId)
            }
            is MeshUiIntent.MuteUser -> viewModelScope.launch {
                networkRepository.muteUser(intent.userId)
            }
            is MeshUiIntent.UnmuteUser -> viewModelScope.launch {
                networkRepository.unmuteUser(intent.userId)
            }
            is MeshUiIntent.SetNetworkMuted -> viewModelScope.launch {
                networkRepository.setNetworkMuted(intent.networkId, intent.muted)
            }
            is MeshUiIntent.ReportUser -> viewModelScope.launch {
                val report = networkRepository.createLocalReport(
                    reportedUserId = intent.userId,
                    networkId = intent.networkId,
                    reason = intent.reason,
                    sentToOwner = intent.sendToOwner
                )
                if (intent.sendToOwner && intent.networkId != null) {
                    meshRouter.sendEncryptedReportToOwner(report, intent.networkId)
                }
            }
            is MeshUiIntent.VerifyContactFromQr -> viewModelScope.launch {
                networkRepository.verifyContactFromQr(intent.expectedUserId, intent.scannedQr)
            }
            is MeshUiIntent.MarkContactVerifiedManually -> viewModelScope.launch {
                networkRepository.markContactVerifiedManually(intent.userId)
            }
            is MeshUiIntent.DismissKeyChangeAlert -> {
                networkRepository.dismissKeyChangeAlert(intent.userId)
            }
            is MeshUiIntent.SendText -> sendText(intent.text)
            is MeshUiIntent.OnTypingChanged -> handleTypingChanged(intent.isTyping)
            is MeshUiIntent.MarkActiveNetworkRead -> viewModelScope.launch {
                val netId = uiState.value.activeNetworkId ?: return@launch
                meshRouter.markNetworkMessagesRead(netId)
            }
            is MeshUiIntent.StartPtt -> startPtt()
            is MeshUiIntent.StopPtt -> audioEngine.stopPttRecording()
            is MeshUiIntent.PlayVoiceMessage -> playVoiceMessage(intent.packetId)
            is MeshUiIntent.StopVoicePlayback -> {
                playingVoicePacketId.value = null
                audioEngine.stopPlayback()
            }
            is MeshUiIntent.SetAutoPlayVoice -> viewModelScope.launch {
                settingsStore.setAutoPlayVoice(intent.enabled)
                logRepository.info(LogCategory.VOICE, "Auto-play incoming voice: ${if (intent.enabled) "ON" else "OFF"}")
            }
            is MeshUiIntent.SetQueueExpiryHours -> viewModelScope.launch {
                settingsStore.setQueueExpiryHours(intent.hours)
                logRepository.info(LogCategory.SYSTEM, "Offline queue expiry updated to ${intent.hours}h")
            }
            is MeshUiIntent.UpdateRelayUrl -> viewModelScope.launch {
                settingsStore.setRelayServerUrl(intent.url)
                transportManager.updateRelayServerUrl(intent.url)
            }
            is MeshUiIntent.RefreshPermissions -> refreshPermissions(intent.results)
        }
    }

    suspend fun buildEncryptedBackup(passphrase: String): Result<Pair<ByteArray, BackupSummary>> =
        withContext(Dispatchers.IO) {
            runCatching { backupRepository.createEncryptedBackupBytes(passphrase) }
                .onFailure { err ->
                    logRepository.error(LogCategory.CRYPTO, "Encrypted backup export failed: ${err.message}")
                }
        }

    suspend fun restoreEncryptedBackup(passphrase: String, fileBytes: ByteArray): Result<BackupSummary> =
        withContext(Dispatchers.IO) {
            runCatching {
                val (restoredId, summary) = backupRepository.restoreEncryptedBackupBytes(passphrase, fileBytes)
                identityState.value = restoredId
                summary
            }.onFailure { err ->
                logRepository.error(LogCategory.CRYPTO, "Encrypted backup restore failed: ${err.message}")
            }
        }

    fun exportLogsText(): String = logRepository.formatAsPlainText(activeFilter.value)

    private fun handleTypingChanged(isTyping: Boolean) {
        val netId = uiState.value.activeNetworkId ?: return
        typingStopJob?.cancel()
        viewModelScope.launch {
            meshRouter.sendTypingIndicator(netId, isTyping)
        }
        if (isTyping) {
            typingStopJob = viewModelScope.launch {
                delay(4_000L)
                meshRouter.sendTypingIndicator(netId, false)
            }
        }
    }

    private fun toggleTransport(type: TransportType) {
        viewModelScope.launch {
            val current = uiState.value.transportStates[type]?.status ?: TransportStatus.STOPPED
            val shouldEnable = current == TransportStatus.STOPPED || current == TransportStatus.ERROR
            if (shouldEnable) {
                MeshForegroundService.start(context)
            }
            val networkIds = uiState.value.networks.map { it.networkId }.toSet()
            transportManager.setTransportEnabled(
                type = type,
                enabled = shouldEnable,
                localNodeId = identityState.value.userId,
                subscribedNetworks = networkIds
            )
        }
    }

    private fun createNetwork(name: String, password: String?) {
        viewModelScope.launch {
            val created = networkRepository.createNetwork(
                name = name,
                password = password,
                relayHint = uiState.value.relayServerUrl
            )
            settingsStore.setActiveNetworkId(created.summary.networkId)
            shareCodeState.value = created.shareCode to created.summary.name
            meshRouter.broadcastNetworkAnnounce(created.summary.networkId)
        }
    }

    private fun joinByShareCode(code: String, password: String?) {
        viewModelScope.launch {
            val invite = networkRepository.decodeInvite(code)
            if (invite == null) {
                logRepository.error(LogCategory.NETWORK, "Invalid network share/QR code format")
                return@launch
            }
            try {
                val (entity, keyReady) = networkRepository.joinFromInvite(invite, password)
                settingsStore.setActiveNetworkId(entity.networkId)
                if (!invite.relayHint.isNullOrBlank()) {
                    settingsStore.setRelayServerUrl(invite.relayHint)
                }
                if (!keyReady) {
                    val proof = if (invite.hasPassword && !password.isNullOrBlank() && invite.passwordSalt != null) {
                        val salt = CryptoManager.decodeB64(invite.passwordSalt)
                        val pwdKey = cryptoManager.derivePasswordKey(password, salt, invite.networkId)
                        CryptoManager.encodeB64(MessageDigest.getInstance("SHA-256").digest(pwdKey))
                    } else null
                    meshRouter.sendKeyExchangeRequest(
                        networkId = entity.networkId,
                        ownerId = entity.ownerId,
                        ownerX25519PubBase64 = entity.ownerX25519PubBase64,
                        passwordProofBase64 = proof
                    )
                } else {
                    logRepository.success(
                        LogCategory.CRYPTO,
                        "Symmetric AES-256-GCM group key unlocked for net=${CryptoManager.shortId(entity.networkId)} (keyVer=${entity.keyVersion})"
                    )
                }
            } catch (e: Exception) {
                logRepository.error(LogCategory.CRYPTO, "Failed to join network invite: ${e.message}")
            }
        }
    }

    private fun joinDiscovered(intent: MeshUiIntent.JoinDiscovered) {
        viewModelScope.launch {
            val entity = networkRepository.joinDiscoveredNetwork(intent.network)
            settingsStore.setActiveNetworkId(entity.networkId)
            meshRouter.sendKeyExchangeRequest(
                networkId = entity.networkId,
                ownerId = entity.ownerId,
                ownerX25519PubBase64 = entity.ownerX25519PubBase64,
                passwordProofBase64 = null
            )
        }
    }

    private fun sendText(text: String) {
        val netId = uiState.value.activeNetworkId
        if (netId == null) {
            logRepository.warn(LogCategory.MESSAGE, "Cannot send text: create or join a network first")
            return
        }
        typingStopJob?.cancel()
        viewModelScope.launch {
            meshRouter.sendTypingIndicator(netId, false)
            meshRouter.sendTextMessage(netId, text)
        }
    }

    private fun startPtt() {
        val netId = uiState.value.activeNetworkId
        if (netId == null) {
            logRepository.warn(LogCategory.VOICE, "Cannot start PTT: create or join a network first")
            return
        }
        val linkQuality = transportManager.evaluateLinkQuality()
        val mode = if (linkQuality == LinkQuality.GOOD) VoiceMode.LIVE_STREAM else VoiceMode.VOICE_CLIP
        audioEngine.startPttRecording(mode) { chunk ->
            meshRouter.sendVoiceChunk(netId, chunk, mode)
        }
    }

    private fun playVoiceMessage(packetId: String) {
        if (playingVoicePacketId.value == packetId && audioEngine.isPlaying.value) {
            playingVoicePacketId.value = null
            audioEngine.stopPlayback()
            return
        }
        val clip = messageRepository.getVoiceClip(packetId)
        if (clip == null) {
            logRepository.warn(LogCategory.VOICE, "Voice clip ${packetId.take(8)} audio buffer unavailable from previous session")
            return
        }
        val msg = uiState.value.activeChatMessages.firstOrNull { it.packetId == packetId }
        playingVoicePacketId.value = packetId
        audioEngine.playIncomingVoice(
            senderId = msg?.senderId ?: identityState.value.userId,
            streamId = clip.streamId,
            codec = clip.codec,
            sampleRate = clip.sampleRate,
            encodedBytes = clip.audioBytes
        )
    }

    private fun refreshPermissions(results: Map<String, Boolean>?) {
        val updated = PermissionHelper.inspectAll(context)
        permissionsState.value = updated
        if (results != null) {
            results.forEach { (perm, granted) ->
                val shortName = perm.substringAfterLast('.')
                if (granted) {
                    logRepository.success(LogCategory.PERMISSION, "Permission granted: $shortName")
                } else {
                    logRepository.warn(LogCategory.PERMISSION, "Permission denied: $shortName")
                }
            }
        }
    }

    private fun logInitialPermissions() {
        val statuses = permissionsState.value
        val grantedCount = statuses.count { it.granted }
        val missing = statuses.filterNot { it.granted }.joinToString(", ") { it.label }
        if (missing.isEmpty()) {
            logRepository.success(LogCategory.PERMISSION, "All $grantedCount runtime permissions granted")
        } else {
            logRepository.info(
                LogCategory.PERMISSION,
                "Permissions: $grantedCount/${statuses.size} granted (will prompt on first feature use: $missing)"
            )
        }
    }

    private data class CoreBundle(
        val logs: List<LogEntry>,
        val filter: LogCategory?,
        val tStates: Map<TransportType, TransportState>,
        val peers: List<PeerInfo>,
        val lowBatt: Boolean
    )

    private data class NetworkBundle(
        val networks: List<NetworkSummary>,
        val activeId: String?,
        val members: List<NetworkMember>,
        val discovered: List<DiscoveredNetwork>,
        val blocked: List<BlockedUser>
    )

    private data class TrustBundle(
        val mutedUsers: List<MutedUser>,
        val reports: List<UserReport>,
        val typingPeers: List<TypingPeer>,
        val keyChangeAlerts: List<KeyChangeAlert>,
        val pendingCount: Int
    )

    private data class SettingsBundle(
        val relayUrl: String,
        val autoPlay: Boolean,
        val queueExpiryHours: Int,
        val recording: Boolean,
        val playing: Boolean
    )

    private data class ChatBundle(
        val activeChatMessages: List<ChatMessageItem>,
        val latestByNetwork: Map<String, ChatMessageItem>,
        val showLogsPanel: Boolean,
        val themeMode: ThemeMode,
        val playingVoicePacketId: String?
    )

    private data class MetaBundle(
        val settings: SettingsBundle,
        val permissions: List<PermissionStatus>,
        val share: Pair<String, String>?,
        val identity: LocalIdentity,
        val chat: ChatBundle
    )
}
