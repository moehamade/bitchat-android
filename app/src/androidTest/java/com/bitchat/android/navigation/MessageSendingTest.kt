package com.bitchat.android.navigation

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bitchat.android.MainActivity
import com.bitchat.android.ui.ChatViewModel
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The composer's send path, wired end to end through the session graph.
 *
 * The emulator has no peers, so nothing leaves the device; what is checked is
 * that the send reaches the right timeline and reports back to the composer.
 * The text is synthetic.
 */
@RunWith(AndroidJUnit4::class)
class MessageSendingTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    companion object {
        @JvmStatic
        @BeforeClass
        fun completeOnboarding() = OnboardedApp.prepare()
    }

    @Test
    fun aPublicMessageLandsInTheMeshTimeline() {
        rule.awaitChat()
        var accepted: Boolean? = null

        rule.runOnUiThread {
            chatViewModel().sendMessage("synthetic mesh message") { accepted = it }
        }

        rule.waitUntil(timeoutMillis = 5_000) { accepted != null }
        assertEquals(true, accepted)
        rule.waitUntil(timeoutMillis = 5_000) {
            timeline().any { it == "synthetic mesh message" }
        }
    }

    @Test
    fun aCommandRunsInsteadOfBeingSent() {
        rule.awaitChat()

        rule.runOnUiThread { chatViewModel().sendMessage("/w") }

        rule.waitUntil(timeoutMillis = 5_000) {
            timeline().any { it == "no one else is around right now." }
        }
    }

    private fun chatViewModel(): ChatViewModel =
        ViewModelProvider(rule.activity)[ChatViewModel::class.java]

    private fun timeline(): List<String> {
        var contents: List<String> = emptyList()
        rule.runOnUiThread { contents = chatViewModel().messages.value.map { it.content } }
        return contents
    }
}
