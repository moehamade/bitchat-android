package com.bitchat.android.ui

/**
 * What a Back press should unwind next on the chat screen.
 *
 * The chat screen still layers its overlays as booleans and nullable peers on
 * [ChatState] rather than as back-stack entries, so Back is resolved by asking
 * which overlay is outermost.
 */
enum class BackAction {
    DismissPasswordPrompt,
    ExitPrivateChat,
    ExitChannel,

    /** Nothing is open, so the press belongs to the system and usually exits. */
    None
}

/**
 * The unwind order, over plain values.
 *
 * The declaration order of these branches *is* the order Back unwinds in, and is
 * what ChatStateBackNavigationTest pins. It takes values rather than reading
 * [ChatState] so the same decision can be reached from the current values or from
 * a flow.
 */
internal fun backActionFor(
    showPasswordPrompt: Boolean,
    selectedPrivateChatPeer: String?,
    privateChatSheetPeer: String?,
    currentChannel: String?
): BackAction = when {
    showPasswordPrompt -> BackAction.DismissPasswordPrompt
    selectedPrivateChatPeer != null || privateChatSheetPeer != null -> BackAction.ExitPrivateChat
    currentChannel != null -> BackAction.ExitChannel
    else -> BackAction.None
}

/**
 * Names what Back would unwind without unwinding it.
 *
 * Kept out of [ChatViewModel.handleBackPressed] so the ordering is decidable
 * from state alone: the ViewModel owns four process-wide singletons and cannot
 * be built in a unit test.
 */
fun ChatState.pendingBackAction(): BackAction = backActionFor(
    showPasswordPrompt = getShowPasswordPromptValue(),
    selectedPrivateChatPeer = getSelectedPrivateChatPeerValue(),
    privateChatSheetPeer = getPrivateChatSheetPeerValue(),
    currentChannel = getCurrentChannelValue()
)
