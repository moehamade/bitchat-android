package com.bitchat.android.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The order in which Back unwinds the chat screen's overlays.
 *
 * [ChatViewModel.handleBackPressed] performs whatever [pendingBackAction]
 * names, so pinning the decision here pins the behaviour without standing up
 * a ViewModel. The order matters more than any single case: it is the
 * contract the route conversion has to preserve when these overlays stop
 * being booleans and become back-stack entries.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatStateBackNavigationTest {

    private lateinit var state: ChatState

    @Before
    fun setUp() {
        state = ChatState(TestScope(UnconfinedTestDispatcher()))
    }

    @Test
    fun `the bare chat screen leaves Back to the system`() {
        assertEquals(BackAction.None, state.pendingBackAction())
    }

    @Test
    fun `the app info dialog is unwound`() {
        state.setShowAppInfo(true)

        assertEquals(BackAction.DismissAppInfo, state.pendingBackAction())
    }

    @Test
    fun `the password prompt is unwound`() {
        state.setShowPasswordPrompt(true)

        assertEquals(BackAction.DismissPasswordPrompt, state.pendingBackAction())
    }

    @Test
    fun `a selected private chat is unwound`() {
        state.setSelectedPrivateChatPeer("peer-a")

        assertEquals(BackAction.ExitPrivateChat, state.pendingBackAction())
    }

    @Test
    fun `the private chat sheet is unwound by the same branch as a selected chat`() {
        state.setPrivateChatSheetPeer("peer-a")

        assertEquals(BackAction.ExitPrivateChat, state.pendingBackAction())
    }

    @Test
    fun `an open channel is unwound`() {
        state.setCurrentChannel("#bitchat")

        assertEquals(BackAction.ExitChannel, state.pendingBackAction())
    }

    @Test
    fun `overlays unwind innermost first and then hand Back back to the system`() {
        state.setCurrentChannel("#bitchat")
        state.setSelectedPrivateChatPeer("peer-a")
        state.setShowPasswordPrompt(true)
        state.setShowAppInfo(true)

        assertEquals(BackAction.DismissAppInfo, state.pendingBackAction())

        state.setShowAppInfo(false)
        assertEquals(BackAction.DismissPasswordPrompt, state.pendingBackAction())

        state.setShowPasswordPrompt(false)
        assertEquals(BackAction.ExitPrivateChat, state.pendingBackAction())

        state.setSelectedPrivateChatPeer(null)
        assertEquals(BackAction.ExitChannel, state.pendingBackAction())

        state.setCurrentChannel(null)
        assertEquals(BackAction.None, state.pendingBackAction())
    }
}
