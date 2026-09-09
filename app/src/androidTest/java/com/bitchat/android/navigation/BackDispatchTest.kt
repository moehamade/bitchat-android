package com.bitchat.android.navigation

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bitchat.android.MainActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Back dispatch on a real device.
 *
 * These exist because Back dispatch has no unit-test cover and cannot get any:
 * enabling `unitTests.isIncludeAndroidResources` drops collection from 622 tests
 * to 506 and fails 24 with "failed to configure", because targetSdk 37 exceeds
 * Robolectric's maximum.
 *
 * They drive the real MainActivity and replace no binding, which is what lets
 * them skip HiltTestApplication and a custom runner entirely.
 */
@RunWith(AndroidJUnit4::class)
class BackDispatchTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun pressBack() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    /**
     * The saved-state round trip, which has no other cover.
     *
     * Recreating the Activity runs the rememberSaveable saver in MainActivity:
     * it encodes the back stack with NavKeySerializer into a Bundle and decodes
     * it again. A route key that is not @Serializable fails here and nowhere
     * else, because nothing else forces the encoder to run.
     *
     * "Don't keep activities" cannot stand in for this: the mesh foreground
     * service keeps the Activity alive, so the setting never destroys it.
     */
    @Test
    fun theBackStackSurvivesActivityRecreation() {
        rule.waitForIdle()

        rule.activityRule.scenario.recreate()
        rule.waitForIdle()

        assertFalse(rule.activity.isFinishing)
    }

    @Test
    fun backAtTheRootDestinationLeavesTheApp() {
        rule.waitForIdle()

        pressBack()

        rule.waitUntil(timeoutMillis = 5_000) { rule.activity.isFinishing }
        assertTrue(rule.activity.isFinishing)
    }
}
