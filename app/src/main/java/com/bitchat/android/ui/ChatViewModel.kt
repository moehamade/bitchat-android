package com.bitchat.android.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.bitchat.android.favorites.FavoritesChangeListener
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.geohash.GeohashBookmarksStore
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.services.ConversationListPreferences
import dagger.Lazy
import javax.inject.Provider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
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
import java.util.Date
import kotlin.random.Random
import com.bitchat.android.services.VerificationService
import com.bitchat.android.navigation.Navigator
import com.bitchat.android.navigation.openPrivateChat
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.ContactIdentityResolver
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
    private val locationChannelManagerLazy: Lazy<LocationChannelManager>,
    private val geohashBookmarksStoreLazy: Lazy<GeohashBookmarksStore>,
    private val conversationListPreferences: ConversationListPreferences,
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
    private val verificationHandler: VerificationHandler,
    private val mediaSendingManager: MediaSendingManager,
    private val meshDelegateHandler: MeshDelegateHandler,
    val geohashSession: GeohashSession,
    private val messageSender: MessageSender,
    private val privateChatSession: PrivateChatSession,
    private val composerMedia: ComposerMedia,
    private val meshDelegate: ChatMeshDelegate,
    private val panicClear: PanicClear,
    private val sessionStartup: ChatSessionStartup,
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
    private val locationChannelManager: LocationChannelManager
        get() = locationChannelManagerLazy.get()
    private val geohashBookmarksStore: GeohashBookmarksStore
        get() = geohashBookmarksStoreLazy.get()
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
        // Idempotent: a recreated ViewModel finds the session already running.
        sessionStartup.start()
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

    // MARK: - QR Verification
    

    // MARK: - Debug and Troubleshooting
    
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

    // MARK: - Canonical peer identities

}
