package com.bitchat.android.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bitchat.android.favorites.FavoritesChangeListener
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.geohash.GeohashBookmarksStore
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.nostr.NostrTransport
import com.bitchat.android.services.ConversationListPreferences
import com.bitchat.android.services.MessageRouter
import com.bitchat.android.services.SeenMessageStore
import com.bitchat.android.ui.debug.DebugSettingsManager
import dagger.Lazy
import javax.inject.Provider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.service.MeshServiceHolder
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.nostr.NostrIdentityBridge
import com.bitchat.android.nostr.GeohashConversationRegistry
import com.bitchat.android.protocol.BitchatPacket


import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.util.Date
import kotlin.random.Random
import com.bitchat.android.services.VerificationService
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.noise.NoiseSession
import com.bitchat.android.navigation.Navigator
import com.bitchat.android.navigation.openPrivateChat
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.ContactIdentityResolver
import com.bitchat.android.util.hexEncodedString
import com.bitchat.android.features.voice.LiveVoicePreferences
import com.bitchat.android.features.voice.LiveVoiceTarget
import com.bitchat.android.features.voice.VoiceRecorder

/**
 * Refactored ChatViewModel - Main coordinator for bitchat functionality
 * Delegates specific responsibilities to specialized managers while maintaining 100% iOS compatibility
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    application: Application,
    private val sessionMesh: ChatSessionMesh,
    // Injected as dagger.Lazy because every one of these was previously reached
    // through getInstance(...) at the point of use. Resolving them eagerly would
    // construct them during ViewModel creation, which is earlier than before.
    private val seenMessageStoreLazy: Lazy<SeenMessageStore>,
    private val locationChannelManagerLazy: Lazy<LocationChannelManager>,
    private val geohashBookmarksStoreLazy: Lazy<GeohashBookmarksStore>,
    private val nostrTransportLazy: Lazy<NostrTransport>,
    // Provider, not Lazy: MessageRouter.getInstance both returns the router and
    // re-points it at the current mesh, so it has to be re-resolved each time.
    // Caching it would skip the refresh and leave the router on the mesh service
    // that panic-clear replaced.
    private val messageRouterProvider: Provider<MessageRouter>,
    private val conversationListPreferences: ConversationListPreferences,
    private val debugSettingsManager: DebugSettingsManager,
    // Several openers of a private chat run in here rather than in a screen: a
    // geohash DM resolves its conversation first, and the unread shortcut picks
    // one. The ViewModel shares the Activity's retained scope, as the navigator
    // does, so it never outlives the stack it drives.
    private val navigator: Navigator,
    // Shared with the screens' own ViewModels, so it is injected rather than
    // built here; it lives exactly as long as this ViewModel.
    private val state: ChatState,
    // The session's managers, shared with the screens' own ViewModels.
    private val dataManager: DataManager,
    private val messageManager: MessageManager,
    private val channelManager: ChannelManager,
    val privateChatManager: PrivateChatManager,
    private val commandProcessor: CommandProcessor,
    private val notificationManager: NotificationManager,
    private val identityManager: SecureIdentityStateManager,
    private val verificationHandler: VerificationHandler,
    private val mediaSendingManager: MediaSendingManager,
    private val meshDelegateHandler: MeshDelegateHandler,
    val geohashSession: GeohashSession,
    private val messageSender: MessageSender,
    private val privateChatSession: PrivateChatSession,
    private val composerMedia: ComposerMedia,
    private val contactFavorites: ContactFavorites,
    private val conversationDirectory: ConversationDirectory,
    private val meshDelegate: ChatMeshDelegate,
    private val panicClear: PanicClear,
) : AndroidViewModel(application) {

    private val mesh: MeshService
        get() = sessionMesh.unified

    companion object {
        private const val TAG = "ChatViewModel"
    }





    fun sendVoiceNote(toPeerIDOrNull: String?, channelOrNull: String?, filePath: String) =
        composerMedia.sendVoiceNote(toPeerIDOrNull, channelOrNull, filePath)

    fun createVoiceRecorder(toPeerIDOrNull: String?, channelOrNull: String?): VoiceRecorder =
        composerMedia.createVoiceRecorder(toPeerIDOrNull, channelOrNull)

    fun sendFileNote(toPeerIDOrNull: String?, channelOrNull: String?, filePath: String) =
        composerMedia.sendFileNote(toPeerIDOrNull, channelOrNull, filePath)

    fun sendImageNote(toPeerIDOrNull: String?, channelOrNull: String?, filePath: String) =
        composerMedia.sendImageNote(toPeerIDOrNull, channelOrNull, filePath)

    fun cancelMediaSend(messageId: String) = composerMedia.cancelMediaSend(messageId)

    fun approveLegacyPrivateMedia(requestId: String) {
        mediaSendingManager.approveLegacyPrivateMedia(requestId)
    }

    fun cancelLegacyPrivateMedia(requestId: String) {
        mediaSendingManager.cancelLegacyPrivateMedia(requestId)
    }


    // Transfer progress tracking
    private val transferMessageMap = mutableMapOf<String, String>()
    private val messageTransferMap = mutableMapOf<String, String>()

    // dagger.Lazy caches, so these resolve once and stay cheap thereafter.
    private val seenMessageStore: SeenMessageStore get() = seenMessageStoreLazy.get()
    private val locationChannelManager: LocationChannelManager
        get() = locationChannelManagerLazy.get()
    private val geohashBookmarksStore: GeohashBookmarksStore
        get() = geohashBookmarksStoreLazy.get()
    private val nostrTransport: NostrTransport get() = nostrTransportLazy.get()
    val verifiedFingerprints = verificationHandler.verifiedFingerprints

    val messages: StateFlow<List<BitchatMessage>> = state.messages
    val connectedPeers: StateFlow<List<String>> = state.connectedPeers
    val nickname: StateFlow<String> = state.nickname
    val isConnected: StateFlow<Boolean> = state.isConnected
    val privateChats: StateFlow<Map<String, List<BitchatMessage>>> = state.privateChats
    val selectedPrivateChatPeer: StateFlow<String?> = state.selectedPrivateChatPeer
    val unreadPrivateMessages: StateFlow<Set<String>> = state.unreadPrivateMessages
    val joinedChannels: StateFlow<Set<String>> = state.joinedChannels
    val currentChannel: StateFlow<String?> = state.currentChannel
    val channelMessages: StateFlow<Map<String, List<BitchatMessage>>> = state.channelMessages
    val unreadChannelMessages: StateFlow<Map<String, Int>> = state.unreadChannelMessages
    val showPasswordPrompt: StateFlow<Boolean> = state.showPasswordPrompt
    val passwordPromptChannel: StateFlow<String?> = state.passwordPromptChannel
    val showCommandSuggestions: StateFlow<Boolean> = state.showCommandSuggestions
    val commandSuggestions: StateFlow<List<CommandSuggestion>> = state.commandSuggestions
    val showMentionSuggestions: StateFlow<Boolean> = state.showMentionSuggestions
    val mentionSuggestions: StateFlow<List<String>> = state.mentionSuggestions
    val favoritePeers: StateFlow<Set<String>> = state.favoritePeers
    val peerFavoritedUs: StateFlow<Set<String>> = state.peerFavoritedUs
    val peerSessionStates: StateFlow<Map<String, String>> = state.peerSessionStates
    val peerFingerprints: StateFlow<Map<String, String>> = state.peerFingerprints
    val peerNicknames: StateFlow<Map<String, String>> = state.peerNicknames
    val peerRSSI: StateFlow<Map<String, Int>> = state.peerRSSI
    val peerDirect: StateFlow<Map<String, Boolean>> = state.peerDirect
    val legacyPrivateMediaConsent: StateFlow<LegacyPrivateMediaConsentRequest?> =
        mediaSendingManager.legacyPrivateMediaConsent
    val selectedLocationChannel: StateFlow<com.bitchat.android.geohash.ChannelID?> = state.selectedLocationChannel
    val isTeleported: StateFlow<Boolean> = state.isTeleported
    val geohashPeople: StateFlow<List<GeoPerson>> = state.geohashPeople
    val teleportedGeo: StateFlow<Set<String>> = state.teleportedGeo
    val meshServiceFacade: MeshService
        get() = mesh
    val myPeerID: String
        get() = mesh.myPeerID

    init {
        conversationDirectory.observePresenceWithDisconnectGrace()
        // Note: Mesh service delegate is now set by MainActivity
        loadAndInitialize()
        ContactDirectory.initialize(getApplication()) { mesh }
        com.bitchat.android.services.AppStateStore.canonicalizePrivateChats()
        conversationDirectory.observeDisplayNames()
        // Application startup performs the initial restore. Repeat it for every new UI owner
        // because a quick reopen can reuse a process whose in-memory state was cleared during
        // controlled shutdown.
        com.bitchat.android.services.AppStateStore.reloadConversationPersistence(
            getApplication()
        )
        // Mark queued private messages as failed when the router gives up on them
        try {
            messageRouterProvider.get().onMessageExpired = { messageID ->
                messageManager.updateMessageDeliveryStatus(
                    messageID,
                    com.bitchat.android.model.DeliveryStatus.Failed("Message expired before delivery")
                )
            }
        } catch (_: Exception) { }
        // Hydrate UI state from process-wide AppStateStore to survive Activity recreation
        viewModelScope.launch {
            try { com.bitchat.android.services.AppStateStore.peers.collect { peers ->
                state.setConnectedPeers(peers)
                state.setIsConnected(peers.isNotEmpty())
            } } catch (_: Exception) { }
        }
        viewModelScope.launch {
            try { com.bitchat.android.services.AppStateStore.publicMessages.collect { msgs ->
                // Source of truth is AppStateStore; replace to avoid duplicate keys in LazyColumn
                state.setMessages(msgs)
            } } catch (_: Exception) { }
        }
        viewModelScope.launch {
            try {
                combine(
                    com.bitchat.android.services.AppStateStore.privateMessages,
                    com.bitchat.android.services.AppStateStore.unreadPrivateMessageCounts
                ) { byPeer, unreadCounts -> byPeer to unreadCounts }
                    .collect { (byPeer, unreadCounts) ->
                val (canonicalChats, unreadConversationIDs) = withContext(Dispatchers.IO) {
                    val canonical = ContactDirectory.canonicalizePrivateChats(byPeer)
                    val unread = try {
                        val myNick = state.getNicknameValue().ifBlank { mesh.myPeerID }
                        canonical
                            .filterValues { messages ->
                                messages.any { message ->
                                    message.sender != myNick &&
                                        message.sender != "system" &&
                                        !com.bitchat.android.services.AppStateStore
                                            .isPrivateMessageRead(message.id) &&
                                        !seenMessageStore.hasBeenReadLocally(message.id)
                                }
                            }
                            .keys + unreadCounts
                                .filterValues { it > 0 }
                                .keys
                    } catch (_: Exception) {
                        state.getUnreadPrivateMessagesValue()
                    }
                    canonical to unread
                }
                state.setPrivateChats(canonicalChats)
                // Recompute unread set using SeenMessageStore for robustness across Activity recreation
                state.setUnreadPrivateMessages(unreadConversationIDs)
            } } catch (_: Exception) { }
        }
        viewModelScope.launch {
            try { com.bitchat.android.services.AppStateStore.channelMessages.collect { byChannel ->
                // Replace with store snapshot
                state.setChannelMessages(byChannel)
            } } catch (_: Exception) { }
        }
        // Subscribe to BLE transfer progress and reflect in message deliveryStatus
        viewModelScope.launch {
            com.bitchat.android.mesh.TransferProgressManager.events.collect { evt ->
                mediaSendingManager.handleTransferProgressEvent(evt)
            }
        }
        
        // Removed background location notes subscription. Notes now load only when sheet opens.
    }



    
    private fun loadAndInitialize() {
        // Load nickname
        val nickname = dataManager.loadNickname()
        state.setNickname(nickname)
        
        // Load data
        val (joinedChannels, protectedChannels) = channelManager.loadChannelData()
        state.setJoinedChannels(joinedChannels)
        state.setPasswordProtectedChannels(protectedChannels)
        
        // Initialize channel messages
        joinedChannels.forEach { channel ->
            if (!state.getChannelMessagesValue().containsKey(channel)) {
                val updatedChannelMessages = state.getChannelMessagesValue().toMutableMap()
                updatedChannelMessages[channel] = emptyList()
                state.setChannelMessages(updatedChannelMessages)
            }
        }
        
        // Load other data
        dataManager.loadFavorites()
        state.setFavoritePeers(dataManager.favoritePeers.toSet())
        dataManager.loadBlockedUsers()
        dataManager.loadGeohashBlockedUsers()

        // Log all favorites at startup
        dataManager.logAllFavorites()
        contactFavorites.logCurrentFavoriteState()
        
        // Initialize session state monitoring
        initializeSessionStateMonitoring()

        // Bridge DebugSettingsManager -> Chat messages when verbose logging is on
        viewModelScope.launch {
            debugSettingsManager.debugMessages.collect { msgs ->
                if (debugSettingsManager.verboseLoggingEnabled.value) {
                    // Only show debug logs in the Mesh chat timeline to avoid leaking into geohash chats
                    val selectedLocation = state.selectedLocationChannel.value
                    if (selectedLocation is com.bitchat.android.geohash.ChannelID.Mesh) {
                        // Append only latest debug message as system message to avoid flooding
                        msgs.lastOrNull()?.let { dm ->
                            messageManager.addSystemMessage(dm.content)
                        }
                    }
                }
            }
        }
        
        // Initialize new geohash architecture
        geohashSession.initialize()

        // Initialize favorites persistence service
        com.bitchat.android.favorites.FavoritesPersistenceService.initialize(getApplication())

        // Reflect "they favorited us" changes into reactive UI state (drives star celebrations)
        conversationDirectory.startFavoriteTracking()

        // Load verified fingerprints from secure storage
        verificationHandler.loadVerifiedFingerprints()


        // Ensure NostrTransport knows our mesh peer ID for embedded packets
        try {
            nostrTransport.senderPeerID = mesh.myPeerID
        } catch (_: Exception) { }

        // Note: Mesh service is now started by MainActivity

        // BLE receives are inserted by MessageHandler path; no VoiceNoteBus for Tor in this branch.
    }
    
    override fun onCleared() {
        conversationDirectory.stopFavoriteTracking()
        geohashSession.shutdownUiSubscriptions()
        com.bitchat.android.services.AppStateStore.setSelectedPrivateChatPeer(null)
        // Note: Mesh service lifecycle is now managed by MainActivity
    }
    
    // MARK: - Nickname Management
    
    fun setNickname(newNickname: String) {
        state.setNickname(newNickname)
        dataManager.saveNickname(newNickname)
        mesh.sendBroadcastAnnounce()
    }
    
    /**
     * Ensure Nostr DM subscription for a geohash conversation key if known
     */
    private fun ensureGeohashDMSubscriptionIfNeeded(convKey: String) {
        geohashSession.ensureGeohashDMSubscriptionForConversation(convKey)
    }

    // MARK: - Channel Management (delegated)
    
    fun joinChannel(channel: String, password: String? = null): Boolean {
        return channelManager.joinChannel(channel, password, mesh.myPeerID)
    }
    
    fun switchToChannel(channel: String?) {
        channelManager.switchToChannel(channel)
    }
    
    fun leaveChannel(channel: String) {
        channelManager.leaveChannel(channel)
        mesh.sendMessage("left $channel", emptyList(), null)
    }
    
    // MARK: - Private Chat Management (delegated)
    
    


    fun endPrivateChat() = privateChatSession.end()

    /** Ends [conversationID]'s chat, unless another has been selected since. */
    fun endPrivateChat(conversationID: String) = privateChatSession.end(conversationID)






    internal fun conversationDraft(conversationID: String?): String =
        conversationListPreferences.composerDraft(conversationID)

    internal fun setConversationDraft(conversationID: String?, text: String) =
        conversationListPreferences.saveComposerDraft(conversationID, text)

    // MARK: - Open Latest Unread Private Chat

    fun openLatestUnreadPrivateChat() {
        try {
            val unreadKeys = state.getUnreadPrivateMessagesValue()
            if (unreadKeys.isEmpty()) return

            val me = state.getNicknameValue() ?: mesh.myPeerID
            val chats = state.getPrivateChatsValue()

            // Pick the latest incoming message among unread conversations
            var bestKey: String? = null
            var bestTime: Long = Long.MIN_VALUE

            unreadKeys.forEach { key ->
                val list = chats[key]
                if (!list.isNullOrEmpty()) {
                    // Prefer the latest incoming message (sender != me), fallback to last message
                    val latestIncoming = list.lastOrNull { it.sender != me }
                    val candidateTime = (latestIncoming ?: list.last()).timestamp.time
                    if (candidateTime > bestTime) {
                        bestTime = candidateTime
                        bestKey = key
                    }
                }
            }

            val targetKey = bestKey ?: unreadKeys.firstOrNull() ?: return

            val openPeer: String = if (targetKey.startsWith("nostr_")) {
                // Use the exact conversation key for geohash DMs and ensure DM subscription
                ensureGeohashDMSubscriptionIfNeeded(targetKey)
                targetKey
            } else {
                // Resolve to a canonical mesh peer if needed
                val canonical = com.bitchat.android.services.ConversationAliasResolver.resolveCanonicalPeerID(
                selectedPeerID = targetKey,
                connectedPeers = state.getConnectedPeersValue(),
                meshNoiseKeyForPeer = { pid -> mesh.getPeerInfo(pid)?.noisePublicKey },
                nostrPubHexForAlias = { alias -> com.bitchat.android.nostr.GeohashAliasRegistry.get(alias) },
                findNoiseKeyForNostr = { key -> com.bitchat.android.favorites.FavoritesPersistenceService.shared.findNoiseKey(key) }
                )
                canonical ?: targetKey
            }

            openPrivateChat(openPeer)
        } catch (e: Exception) {
            Log.w(TAG, "openLatestUnreadPrivateChat failed: ${e.message}")
        }
    }

    // END - Open Latest Unread Private Chat

    
    // MARK: - Message Sending
    
    fun sendMessage(
        content: String,
        onAccepted: (Boolean) -> Unit = {}
    ) = messageSender.send(content, onAccepted)

    // MARK: - Utility Functions
    
    


    private fun isConnectedOnMesh(peerID: String): Boolean {
        return try {
            mesh.getPeerInfo(peerID)?.isConnected == true
        } catch (_: Exception) {
            false
        }
    }

    private fun sessionStateForPeer(peerID: String): NoiseSession.NoiseSessionState {
        return try { mesh.getSessionState(peerID) } catch (_: Exception) { NoiseSession.NoiseSessionState.Uninitialized }
    }
    
    /**
     * Initialize session state monitoring for reactive UI updates
     */
    private fun initializeSessionStateMonitoring() {
        viewModelScope.launch {
            while (true) {
                delay(1000) // Check session states every second
                updateReactiveStates()
            }
        }
    }
    
    // Location notes subscription management moved to LocationNotesViewModelExtensions.kt
    
    /**
     * Update reactive states for all connected peers (session states, fingerprints, nicknames, RSSI)
     */
    private fun updateReactiveStates() {
        val currentPeers = state.getConnectedPeersValue()
        
        // Update session states
        val prevStates = state.getPeerSessionStatesValue()
        val sessionStates = currentPeers.associateWith { peerID ->
            sessionStateForPeer(peerID).toString()
        }
        state.setPeerSessionStates(sessionStates)
        // Detect new established sessions and flush router outbox for them and their noiseHex aliases
        sessionStates.forEach { (peerID, newState) ->
            val old = prevStates[peerID]
            if (old != "established" && newState == "established") {
                messageRouterProvider.get().onSessionEstablished(peerID)
            }
        }
        // Update fingerprint mappings from centralized manager
        val fingerprints = privateChatManager.getAllPeerFingerprints()
        state.setPeerFingerprints(fingerprints)
        fingerprints.forEach { (peerID, fingerprint) ->
            identityManager.cachePeerFingerprint(peerID, fingerprint)
            val info = try { mesh.getPeerInfo(peerID) } catch (_: Exception) { null }
            val noiseKeyHex = info?.noisePublicKey?.hexEncodedString()
            if (noiseKeyHex != null) {
                identityManager.cachePeerNoiseKey(peerID, noiseKeyHex)
                identityManager.cacheNoiseFingerprint(noiseKeyHex, fingerprint)
            }
            info?.nickname?.takeIf { it.isNotBlank() }?.let { nickname ->
                identityManager.cacheFingerprintNickname(fingerprint, nickname)
            }
        }

        state.setPeerNicknames(mesh.getPeerNicknames())

        state.setPeerRSSI(mesh.getPeerRSSI())

        // Update directness per peer (driven by PeerManager state)
        try {
            val directMap = state.getConnectedPeersValue().associateWith { pid ->
                mesh.getPeerInfo(pid)?.isDirectConnection == true
            }
            state.setPeerDirect(directMap)
        } catch (_: Exception) { }

        // Flush any pending QR verification once a Noise session is established
        currentPeers.forEach { peerID ->
            if (sessionStateForPeer(peerID) is NoiseSession.NoiseSessionState.Established) {
                verificationHandler.sendPendingVerificationIfNeeded(peerID)
            }
        }
    }

    // MARK: - QR Verification
    

    // MARK: - Debug and Troubleshooting
    
    fun setCurrentGeohash(geohash: String?) {
        notificationManager.setCurrentGeohash(geohash)
    }

    fun clearNotificationsForSender(peerID: String) {
        notificationManager.clearNotificationsForSender(peerID)
    }
    
    fun clearNotificationsForGeohash(geohash: String) {
        notificationManager.clearNotificationsForGeohash(geohash)
    }

    /** Shows the private chat with [peerID], in place of whatever opened it. */
    fun openPrivateChat(peerID: String) {
        navigator.openPrivateChat(ContactDirectory.canonicalConversationId(peerID))
    }

    // MARK: - Command Autocomplete (delegated)
    
    fun updateCommandSuggestions(input: String) {
        commandProcessor.updateCommandSuggestions(input)
    }

    fun clearSuggestions() {
        commandProcessor.clearSuggestions()
    }

    fun selectCommandSuggestion(suggestion: CommandSuggestion): String {
        return commandProcessor.selectCommandSuggestion(suggestion)
    }
    
    // MARK: - Mention Autocomplete
    
    fun updateMentionSuggestions(input: String) {
        commandProcessor.updateMentionSuggestions(input, mesh)
    }
    
    fun selectMentionSuggestion(nickname: String, currentText: String): String {
        return commandProcessor.selectMentionSuggestion(nickname, currentText)
    }
    
    fun panicClearAllData() = panicClear.run()

    fun selectLocationChannel(channel: com.bitchat.android.geohash.ChannelID) {
        geohashSession.selectLocationChannel(channel)
    }

    // MARK: - Navigation Management
    

    /**
     * Closes the join-password dialog.
     *
     * Both Back and the dialog's own buttons route here, so the flag cannot be
     * left set by one path and cleared by another. While it is set,
     * [backActionFor] ranks it above exiting a private chat or a channel.
     */
    fun dismissPasswordPrompt() {
        state.clearPasswordPrompt()
    }

    /** What Back would unwind next; drives the chat screen's back handler. */
    val pendingBackAction = state.pendingBackAction

    /**
     * Handle Android back navigation
     * Returns true if the back press was handled, false if it should be passed to the system
     */
    fun handleBackPressed(): Boolean {
        return when (state.pendingBackAction()) {
            BackAction.DismissPasswordPrompt -> {
                dismissPasswordPrompt()
                true
            }
            BackAction.ExitPrivateChat -> {
                endPrivateChat()
                true
            }
            BackAction.ExitChannel -> {
                switchToChannel(null)
                true
            }
            BackAction.None -> false
        }
    }

    // MARK: - Canonical peer identities

}
