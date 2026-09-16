package com.bitchat.android.navigation

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bitchat.android.MainActivity
import com.bitchat.android.R
import com.bitchat.android.ui.ChatViewModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verification as a full-screen destination, and the peer list it closes to
 * make way for.
 */
@RunWith(AndroidJUnit4::class)
class VerificationDestinationTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    companion object {
        @JvmStatic
        @BeforeClass
        fun completeOnboarding() = OnboardedApp.prepare()
    }

    /**
     * The entry's own Back handler has to win over NavDisplay's, or Back pops
     * the route without reopening the list.
     */
    @Test
    fun backFromVerificationOpenedFromThePeerListReopensTheList() {
        rule.awaitChat()
        rule.navigateTo(VerificationRoute(peerID = null, reopenPeerList = true))
        awaitVerification()

        rule.pressSystemBack()

        rule.waitUntil(timeoutMillis = 5_000) { rule.topOfStack() == ChatRoute }
        rule.waitUntil(timeoutMillis = 5_000) { peerListIsOpen() }
        assertFalse(rule.activity.isFinishing)

        rule.runOnUiThread { chatViewModel().hideMeshPeerList() }
    }

    @Test
    fun backFromVerificationOpenedElsewhereReturnsToChat() {
        rule.awaitChat()
        rule.navigateTo(VerificationRoute(peerID = null, reopenPeerList = false))
        awaitVerification()

        rule.pressSystemBack()

        rule.waitUntil(timeoutMillis = 5_000) { rule.topOfStack() == ChatRoute }
        rule.waitForIdle()
        assertFalse(peerListIsOpen())
        assertFalse(rule.activity.isFinishing)
    }

    @Test
    fun aQuickDoubleBackReopensTheListOnceAndStaysInTheApp() {
        rule.awaitChat()
        rule.navigateTo(VerificationRoute(peerID = null, reopenPeerList = true))
        awaitVerification()

        rule.pressSystemBack(times = 2)

        rule.waitUntil(timeoutMillis = 5_000) { rule.topOfStack() == ChatRoute }
        rule.waitForIdle()
        assertFalse(rule.activity.isFinishing)

        rule.runOnUiThread { chatViewModel().hideMeshPeerList() }
    }

    private fun awaitVerification() {
        val title = rule.activity.getString(R.string.verify_title)
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText(title, ignoreCase = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
    }

    private fun chatViewModel(): ChatViewModel =
        ViewModelProvider(rule.activity)[ChatViewModel::class.java]

    private fun peerListIsOpen(): Boolean {
        var open = false
        rule.runOnUiThread { open = chatViewModel().showMeshPeerList.value }
        return open
    }
}
