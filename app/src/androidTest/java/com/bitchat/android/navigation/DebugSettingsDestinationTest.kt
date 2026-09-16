package com.bitchat.android.navigation

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bitchat.android.MainActivity
import com.bitchat.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Debug settings, a destination stacked on About: the pair the spec asks to
 * see unwind one at a time.
 */
@RunWith(AndroidJUnit4::class)
class DebugSettingsDestinationTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    companion object {
        @JvmStatic
        @BeforeClass
        fun completeOnboarding() = OnboardedApp.prepare()
    }

    @Test
    fun backFromDebugReturnsToAboutAndThenToChat() {
        openDebugOverAbout()

        rule.pressSystemBack()

        rule.waitUntil(timeoutMillis = 5_000) { rule.topOfStack() == AboutRoute }
        rule.awaitAbout()

        rule.pressSystemBack()

        rule.waitUntil(timeoutMillis = 5_000) { rule.topOfStack() == ChatRoute }
        assertFalse(rule.activity.isFinishing)
    }

    /** Two quick presses pop two destinations, one each, and leave the app open. */
    @Test
    fun aQuickDoubleBackFromDebugLandsOnChat() {
        openDebugOverAbout()

        rule.pressSystemBack(times = 2)

        rule.waitUntil(timeoutMillis = 5_000) { rule.topOfStack() == ChatRoute }
        rule.waitForIdle()
        assertEquals(listOf(ChatRoute), rule.backStack())
        assertFalse(rule.activity.isFinishing)
    }

    @Test
    fun theListStartsBelowTheTopBar() {
        openDebugOverAbout()

        val closeBottom = rule.onNodeWithContentDescription(rule.activity.getString(R.string.close_plain))
            .getUnclippedBoundsInRoot().bottom
        val firstItemTop = rule.onAllNodesWithText(rule.activity.getString(R.string.debug_tools_desc))
            .onFirst()
            .getUnclippedBoundsInRoot().top

        assertTrue("first item at $firstItemTop, top bar content ends at $closeBottom", firstItemTop >= closeBottom)
    }

    private fun openDebugOverAbout() {
        rule.awaitChat()
        rule.navigateTo(AboutRoute)
        rule.awaitAbout()
        rule.navigateTo(DebugSettingsRoute)
        val title = rule.activity.getString(R.string.debug_tools)
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText(title, ignoreCase = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
        assertEquals(listOf(ChatRoute, AboutRoute, DebugSettingsRoute), rule.backStack())
    }
}
