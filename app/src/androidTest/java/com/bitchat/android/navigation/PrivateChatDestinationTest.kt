package com.bitchat.android.navigation

import android.content.Intent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bitchat.android.MainActivity
import com.bitchat.android.ui.ChatViewModel
import com.bitchat.android.ui.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The private chat as a sheet destination, the peer list it opens from and
 * the security sheet stacked over it.
 *
 * The peers are synthetic mesh ids with no device behind them. Nothing here
 * sends a message; what is under test is which conversation is selected as
 * the stack changes, since that decides where the composer sends.
 */
@RunWith(AndroidJUnit4::class)
class PrivateChatDestinationTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    companion object {
        private const val PEER_A = "0123456789abcdef"
        private const val PEER_B = "fedcba9876543210"

        @JvmStatic
        @BeforeClass
        fun completeOnboarding() = OnboardedApp.prepare()
    }

    @Test
    fun aChatOpenedFromThePeerListTakesItsPlace() {
        rule.awaitChat()
        rule.navigateTo(MeshPeerListRoute)
        rule.awaitSheet()

        openPrivateChat(PEER_A)

        assertEquals(listOf(ChatRoute, PrivateChatRoute(PEER_A)), rule.backStack())
        awaitSelection(PEER_A)
    }

    @Test
    fun backFromAPrivateChatReturnsToChatAndEndsIt() {
        rule.awaitChat()
        openPrivateChat(PEER_A)
        rule.awaitSheet()
        awaitSelection(PEER_A)

        rule.pressSystemBack()

        rule.waitUntil(timeoutMillis = 5_000) { rule.topOfStack() == ChatRoute }
        awaitSelection(null)
        assertFalse(rule.activity.isFinishing)
    }

    @Test
    fun openingOneChatFromAnotherLeavesTheNewOneSelected() {
        rule.awaitChat()
        openPrivateChat(PEER_A)
        rule.awaitSheet()
        awaitSelection(PEER_A)

        openPrivateChat(PEER_B)
        awaitSelection(PEER_B)
        // Idle once the replaced sheet has slid away and left composition.
        rule.waitForIdle()

        assertEquals(PEER_B, selection())
        assertEquals(listOf(ChatRoute, PrivateChatRoute(PEER_B)), rule.backStack())
    }

    /**
     * The replaced chat's teardown and the new chat's start race: the start
     * waits on loading history, the teardown on the old sheet sliding away.
     * Whichever loses, a late teardown must not end the chat now on screen.
     * The flow above cannot force that order, so this calls it directly.
     */
    @Test
    fun aLateTeardownOfAReplacedChatLeavesTheNewOneSelected() {
        rule.awaitChat()
        openPrivateChat(PEER_B)
        awaitSelection(PEER_B)

        rule.runOnUiThread { chatViewModel().endPrivateChat(PEER_A) }

        assertEquals(PEER_B, selection())
    }

    @Test
    fun theChatStaysSelectedAcrossRecreation() {
        rule.awaitChat()
        openPrivateChat(PEER_A)
        rule.awaitSheet()
        awaitSelection(PEER_A)

        rule.activityRule.scenario.recreate()
        rule.awaitChat()

        assertEquals(PrivateChatRoute(PEER_A), rule.topOfStack())
        awaitSelection(PEER_A)
    }

    @Test
    fun backFromSecurityVerificationReturnsToTheChat() {
        rule.awaitChat()
        openPrivateChat(PEER_A)
        rule.awaitSheet()
        awaitSelection(PEER_A)
        rule.navigateTo(SecurityVerificationRoute(PEER_A))
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodes(isSheet).fetchSemanticsNodes().size >= 2
        }

        rule.pressSystemBack()

        rule.waitUntil(timeoutMillis = 5_000) { rule.topOfStack() == PrivateChatRoute(PEER_A) }
        rule.waitForIdle()
        assertEquals(PEER_A, selection())
        assertFalse(rule.activity.isFinishing)
    }

    /** Closing a chat opened from a notification lands on the conversation list. */
    @Test
    fun aNotificationOpensTheChatOverThePeerList() {
        rule.awaitChat()
        val intent = Intent(rule.activity, MainActivity::class.java)
            .putExtra(NotificationManager.EXTRA_OPEN_PRIVATE_CHAT, true)
            .putExtra(NotificationManager.EXTRA_PEER_ID, PEER_A)

        // What the platform does for a singleTop Activity already on top. Starting
        // the Activity for real pauses the one under test and races its teardown.
        // onNewIntent replaces the Activity's intent, and ActivityScenario finds
        // its Activity by the intent it launched it with, so that is put back.
        rule.runOnUiThread {
            val launchIntent = rule.activity.intent
            InstrumentationRegistry.getInstrumentation()
                .callActivityOnNewIntent(rule.activity, intent)
            rule.activity.intent = launchIntent
        }
        rule.waitUntil(timeoutMillis = 5_000) { rule.topOfStack() == PrivateChatRoute(PEER_A) }

        assertEquals(
            listOf(ChatRoute, MeshPeerListRoute, PrivateChatRoute(PEER_A)),
            rule.backStack()
        )
        // The stack changes before anything is drawn from it. A press before
        // the sheets exist reaches the Activity, with nothing yet to pop.
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodes(isSheet).fetchSemanticsNodes().size >= 2
        }
        rule.waitForIdle()

        rule.pressSystemBack()

        rule.waitUntil(timeoutMillis = 5_000) { rule.topOfStack() == MeshPeerListRoute }
        awaitSelection(null)
    }

    private fun chatViewModel(): ChatViewModel =
        ViewModelProvider(rule.activity)[ChatViewModel::class.java]

    private fun openPrivateChat(peerID: String) {
        rule.runOnUiThread { chatViewModel().openPrivateChat(peerID) }
        rule.waitForIdle()
    }

    private fun selection(): String? {
        var selected: String? = null
        rule.runOnUiThread { selected = chatViewModel().selectedPrivateChatPeer.value }
        return selected
    }

    private fun awaitSelection(peerID: String?) {
        rule.waitUntil(timeoutMillis = 5_000) { selection() == peerID }
    }
}
