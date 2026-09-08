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
 * Names what Back would unwind without unwinding it.
 *
 * Kept separate from [ChatViewModel.handleBackPressed] so the ordering is
 * decidable from state alone: the ViewModel owns four process-wide singletons
 * and cannot be built in a unit test, while this can. The declaration order
 * of the branches below *is* the unwind order, and is what
 * ChatStateBackNavigationTest pins.
 */
fun ChatState.pendingBackAction(): BackAction = when {
    getShowAppInfoValue() -> BackAction.DismissAppInfo
    getShowPasswordPromptValue() -> BackAction.DismissPasswordPrompt
    getSelectedPrivateChatPeerValue() != null ||
        getPrivateChatSheetPeerValue() != null -> BackAction.ExitPrivateChat
    getCurrentChannelValue() != null -> BackAction.ExitChannel
    else -> BackAction.None
}
