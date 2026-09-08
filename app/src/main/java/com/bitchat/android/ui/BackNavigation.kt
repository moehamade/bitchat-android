package com.bitchat.android.ui

/**
 * What a Back press should unwind next on the chat screen.
 *
 * The chat screen still layers its overlays as booleans and nullable peers on
 * [ChatState] rather than as back-stack entries. Until the route conversion
 * turns each of them into a destination, Back has to be resolved by asking
 * which overlay is outermost.
 */
enum class BackAction {
    DismissAppInfo,
    DismissPasswordPrompt,
    ExitPrivateChat,
    ExitChannel,

    /** Nothing is open, so the press belongs to the system and usually exits. */
    None
}

/**
 * The unwind order, over plain values.
 *
 * The declaration order of these branches *is* the order Back unwinds in, and
 * is what ChatStateBackNavigationTest pins. It takes values rather than reading
 * [ChatState] so that the same decision can be reached two ways: once from the
 * current values, and once from a flow that recomposes the UI when it changes.
 */
internal fun backActionFor(
    showAppInfo: Boolean,
    showPasswordPrompt: Boolean,
    selectedPrivateChatPeer: String?,
    privateChatSheetPeer: String?,
    currentChannel: String?
): BackAction = when {
    showAppInfo -> BackAction.DismissAppInfo
    showPasswordPrompt -> BackAction.DismissPasswordPrompt
    selectedPrivateChatPeer != null || privateChatSheetPeer != null -> BackAction.ExitPrivateChat
    currentChannel != null -> BackAction.ExitChannel
    else -> BackAction.None
}

/**
 * Names what Back would unwind without unwinding it.
 *
 * Kept separate from [ChatViewModel.handleBackPressed] so the ordering is
 * decidable from state alone: the ViewModel owns four process-wide singletons
 * and cannot be built in a unit test, while this can.
 */
fun ChatState.pendingBackAction(): BackAction = backActionFor(
    showAppInfo = getShowAppInfoValue(),
    showPasswordPrompt = getShowPasswordPromptValue(),
    selectedPrivateChatPeer = getSelectedPrivateChatPeerValue(),
    privateChatSheetPeer = getPrivateChatSheetPeerValue(),
    currentChannel = getCurrentChannelValue()
)
