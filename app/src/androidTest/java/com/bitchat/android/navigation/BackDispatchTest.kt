package com.bitchat.android.navigation

import android.Manifest
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bitchat.android.MainActivity
import com.bitchat.android.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Ignore
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

    companion object {

        /**
         * Onboarding, not chat, is the root until the app holds its permissions,
         * and Gradle reinstalls before every run so a device that was set up by
         * hand is back to zero by the time the tests start. Granted here rather
         * than in @Before: rules launch the Activity before @Before runs, so a
         * later grant would arrive after the first composition had already
         * chosen its root.
         *
         * This is PermissionManager.getRequiredPermissions, plus background
         * location and the battery-optimization whitelist. It is not sufficient
         * on its own: the permission *explanation* step is shown before
         * permissions are ever requested, so it does not gate on the grants and
         * still claims the root on a fresh install. See the ignored test below.
         */
        @JvmStatic
        @BeforeClass
        fun grantTheAppItsPermissions() {
            val target = InstrumentationRegistry.getInstrumentation()
                .targetContext.packageName
            listOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            ).forEach { permission ->
                shell("pm grant $target $permission")
            }
            // The other two gates before COMPLETE. Bluetooth and location
            // services are the device's own settings and are left alone.
            shell("dumpsys deviceidle whitelist +$target")
        }

        /**
         * Runs a shell command and waits for it to finish.
         *
         * The output has to be read, not just closed. executeShellCommand hands
         * back a pipe and the command runs while it is open, so closing it
         * straight away can tear the command down before it does anything, and
         * it reports no error when that happens.
         */
        private fun shell(command: String) {
            val pipe = InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .executeShellCommand(command)
            ParcelFileDescriptor.AutoCloseInputStream(pipe).use { it.readBytes() }
        }
    }

    /**
     * About is opened from the brand button, which already carries a localised
     * accessibility label. Resolved from resources rather than hardcoded so the
     * test does not depend on the device's language.
     */
    private fun openAboutLabel() = rule.activity.getString(R.string.cd_open_about)

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

    @Ignore(
        "Cannot reach chat on a fresh install. Gradle reinstalls before every " +
            "run, and the onboarding permission-explanation step is shown " +
            "before permissions are requested, so granting them in @BeforeClass " +
            "does not skip it and the chat header is never on screen. Un-ignore " +
            "once the harness can complete onboarding. The Back path itself is " +
            "unproven until then; aPushedDestinationSurvivesActivityRecreation " +
            "covers opening About but not popping it."
    )
    @Test
    fun backFromAboutReturnsToChatInsteadOfLeaving() {
        rule.waitForIdle()
        val openAbout = openAboutLabel()
        rule.onNodeWithContentDescription(openAbout).assertIsDisplayed()

        rule.onNodeWithContentDescription(openAbout).performClick()
        rule.waitForIdle()
        // About is a full-screen destination, so the header it was opened from
        // is gone rather than sitting behind a sheet.
        rule.onNodeWithContentDescription(openAbout).assertDoesNotExist()

        pressBack()

        assertFalse(rule.activity.isFinishing)
        rule.onNodeWithContentDescription(openAbout).assertIsDisplayed()
    }

    /**
     * The half of saved-state restore that only a pushed destination can show.
     *
     * With just the root on the stack, restoring and re-seeding are
     * indistinguishable. Pushing About first makes them differ: a stack that
     * failed to restore falls back to chat.
     */
    @Test
    fun aPushedDestinationSurvivesActivityRecreation() {
        rule.waitForIdle()
        val openAbout = openAboutLabel()
        rule.onNodeWithContentDescription(openAbout).performClick()
        rule.waitForIdle()

        rule.activityRule.scenario.recreate()
        rule.waitForIdle()

        rule.onNodeWithContentDescription(openAboutLabel()).assertDoesNotExist()
        assertFalse(rule.activity.isFinishing)

        // Leave the stack at its root. The stack is saved and restored, which is
        // what this test asserts, so a destination left pushed here is restored
        // into the next test's Activity and starts it on About.
        pressBack()
    }
}
