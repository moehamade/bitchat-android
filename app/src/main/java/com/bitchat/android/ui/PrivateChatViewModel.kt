package com.bitchat.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitchat.android.features.voice.VoiceRecorder
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.ConversationListPreferences
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One private conversation, for as long as its entry is on the stack.
 *
 * The chat starts when the ViewModel is created and ends in [onCleared],
 * which runs when the entry leaves the stack however it left, and not when
 * the Activity is recreated. Ending goes through the guarded end, so a chat
 * replaced by another does not end the one now on screen.
 *
 * [routeConversationID] is the id the route was opened with. The conversation
 * shown is that id canonicalized again whenever the selection changes, since
 * the resolvers move the selection when a conversation resolves to a contact.
 */
@HiltViewModel(assistedFactory = PrivateChatViewModel.Factory::class)
class PrivateChatViewModel @AssistedInject constructor(
    @Assisted private val routeConversationID: String,
    private val state: ChatState,
    private val sessionMesh: ChatSessionMesh,
    private val privateChatSession: PrivateChatSession,
    private val messageSender: MessageSender,
    private val composerMedia: ComposerMedia,
    private val contactFavorites: ContactFavorites,
    private val verificationHandler: VerificationHandler,
    val geohashSession: GeohashSession,
    private val conversationListPreferences: ConversationListPreferences,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(routeConversationID: String): PrivateChatViewModel
    }

    val conversationID: StateFlow<String> = state.selectedPrivateChatPeer
        .map { ContactDirectory.canonicalConversationId(routeConversationID) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = ContactDirectory.canonicalConversationId(routeConversationID),
        )

    // The session's state the screen reads. Derived display values stay in
    // the screen for now; each flow here is the one it read from ChatViewModel.
    val privateChats: StateFlow<Map<String, List<BitchatMessage>>> = state.privateChats
    val peerNicknames: StateFlow<Map<String, String>> = state.peerNicknames
    val nickname: StateFlow<String> = state.nickname
    val connectedPeers: StateFlow<List<String>> = state.connectedPeers
    val peerDirect: StateFlow<Map<String, Boolean>> = state.peerDirect
    val peerSessionStates: StateFlow<Map<String, String>> = state.peerSessionStates
    val favoritePeers: StateFlow<Set<String>> = state.favoritePeers
    val peerFavoritedUs: StateFlow<Set<String>> = state.peerFavoritedUs
    val peerFingerprints: StateFlow<Map<String, String>> = state.peerFingerprints
    val verifiedFingerprints: StateFlow<Set<String>> = verificationHandler.verifiedFingerprints

    val mesh: MeshService
        get() = sessionMesh.unified

    init {
        // Once per conversation id, which changes only when it resolves to a
        // contact; starting again then is what the rewritten sheet peer did.
        viewModelScope.launch {
            var started: String? = null
            conversationID.collect { id ->
                if (id != started) {
                    started = id
                    privateChatSession.start(id)
                }
            }
        }
    }

    override fun onCleared() {
        privateChatSession.end(routeConversationID)
    }

    fun send(content: String, onAccepted: (Boolean) -> Unit) =
        messageSender.send(content, onAccepted)

    fun sendVoiceNote(peerID: String?, channel: String?, path: String) =
        composerMedia.sendVoiceNote(peerID, channel, path)

    fun sendImageNote(peerID: String?, channel: String?, path: String) =
        composerMedia.sendImageNote(peerID, channel, path)

    fun sendFileNote(peerID: String?, channel: String?, path: String) =
        composerMedia.sendFileNote(peerID, channel, path)

    fun createVoiceRecorder(peerID: String?, channel: String?): VoiceRecorder =
        composerMedia.createVoiceRecorder(peerID, channel)

    fun cancelMediaSend(messageId: String) = composerMedia.cancelMediaSend(messageId)

    fun toggleFavorite(peerID: String) = contactFavorites.toggle(peerID)

    fun isFavorite(peerID: String): Boolean = contactFavorites.isFavorite(peerID)

    fun isPeerVerified(peerID: String, verifiedFingerprints: Set<String>): Boolean {
        if (peerID.startsWith("nostr_") || peerID.startsWith("nostr:")) return false
        val fingerprint = verificationHandler.getPeerFingerprintForDisplay(peerID)
        return fingerprint != null && verifiedFingerprints.contains(fingerprint)
    }

    fun resolvePeerDisplayNameForFingerprint(peerID: String): String =
        verificationHandler.resolvePeerDisplayNameForFingerprint(peerID)

    fun draft(conversationID: String): String =
        conversationListPreferences.composerDraft(conversationID)

    fun saveDraft(conversationID: String, text: String) =
        conversationListPreferences.saveComposerDraft(conversationID, text)
}
