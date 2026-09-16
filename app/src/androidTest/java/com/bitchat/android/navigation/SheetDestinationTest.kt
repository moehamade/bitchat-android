package com.bitchat.android.navigation

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bitchat.android.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A destination shown as a sheet over chat.
 *
 * Location notes stands in for every sheet destination; what is under test is
 * the sheet scene, not the notes. It is opened through the navigator rather
 * than the header, whose notes button depends on location state.
 */
@RunWith(AndroidJUnit4::class)
class SheetDestinationTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    companion object {
        @JvmStatic
        @BeforeClass
        fun completeOnboarding() = OnboardedApp.prepare()
    }

    @Test
    fun aSheetOpensOverChatAndBackClosesIt() {
        rule.awaitChat()

        rule.navigateTo(LocationNotesRoute)
        rule.awaitSheet()
        // Chat stays composed beneath the sheet rather than being replaced.
        assertTrue(chatIsComposed())

        rule.pressSystemBack()

        rule.waitUntil(timeoutMillis = 5_000) { rule.topOfStack() == ChatRoute }
        rule.waitUntil(timeoutMillis = 5_000) { !sheetIsShowing() }
        assertFalse(rule.activity.isFinishing)
    }

    /**
     * The sheet's window keeps taking Back while it animates out, and popping
     * with nothing left to pop finishes the app.
     */
    @Test
    fun twoQuickBackPressesOnASheetCloseOnlyTheSheet() {
        rule.awaitChat()
        rule.navigateTo(LocationNotesRoute)
        rule.awaitSheet()

        rule.pressSystemBack(times = 2)

        rule.waitUntil(timeoutMillis = 5_000) { rule.topOfStack() == ChatRoute }
        rule.waitUntil(timeoutMillis = 5_000) { !sheetIsShowing() }
        assertFalse(rule.activity.isFinishing)
        assertTrue(chatIsComposed())
    }

    /** A pop that does not start in the sheet, such as a reset, still closes it. */
    @Test
    fun aSheetPoppedFromOutsideCloses() {
        rule.awaitChat()
        rule.navigateTo(LocationNotesRoute)
        rule.awaitSheet()

        rule.runOnUiThread { rule.activity.navigator.goBack() }

        rule.waitUntil(timeoutMillis = 5_000) { !sheetIsShowing() }
        assertEquals(ChatRoute, rule.topOfStack())
        assertFalse(rule.activity.isFinishing)
    }

    @Test
    fun aSheetSurvivesActivityRecreation() {
        rule.awaitChat()
        rule.navigateTo(LocationNotesRoute)
        rule.awaitSheet()

        rule.activityRule.scenario.recreate()

        rule.awaitSheet()
        assertEquals(LocationNotesRoute, rule.topOfStack())

        rule.pressSystemBack()
        rule.waitUntil(timeoutMillis = 5_000) { rule.topOfStack() == ChatRoute }
    }

    /**
     * A key with arguments goes through the saved-state encoder as a data
     * class rather than an object, which only restoring it exercises.
     */
    @Test
    fun aSheetWithArgumentsSurvivesActivityRecreation() {
        rule.awaitChat()
        val route = ChatUserRoute(nickname = "someone", messageId = null)
        rule.navigateTo(route)
        rule.awaitSheet()

        rule.activityRule.scenario.recreate()

        rule.awaitSheet()
        assertEquals(route, rule.topOfStack())

        rule.pressSystemBack()
        rule.waitUntil(timeoutMillis = 5_000) { rule.topOfStack() == ChatRoute }
    }

    private fun sheetIsShowing(): Boolean =
        rule.onAllNodes(isSheet).fetchSemanticsNodes().isNotEmpty()

    private fun chatIsComposed(): Boolean =
        rule.onAllNodesWithContentDescription(rule.openAboutLabel())
            .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
}
