package com.bitchat.android.navigation

import androidx.navigation3.runtime.NavKey
import com.bitchat.android.onboarding.OnboardingState
import kotlinx.serialization.Serializable

/**
 * Top-level destinations.
 *
 * Onboarding is one destination, not eight. Its steps are driven by permission
 * results and adapter state changes rather than by the user navigating, and the
 * app has never supported going back from one step to the previous one. Giving
 * each step its own entry would invent a history that does not exist.
 */
@Serializable
data object OnboardingRoute : NavKey

@Serializable
data object ChatRoute : NavKey

/**
 * About, hosted as a destination rather than as a sheet.
 *
 * It has internal tabs and stacks Debug on top of itself, which is what makes
 * it a route rather than a bottom-sheet scene.
 */
@Serializable
data object AboutRoute : NavKey

/**
 * Location notes, shown as a sheet over chat.
 *
 * No argument: the notes belong to the building the device is in, which the
 * screen reads from the location manager when it opens.
 */
@Serializable
data object LocationNotesRoute : NavKey

/**
 * Actions on a user, shown as a sheet over chat.
 *
 * [messageId] names the message that was long-pressed, not the message
 * itself: a key is saved to a Bundle, and message text has no business there.
 * The sheet looks the message up and offers only user actions when it is
 * gone.
 */
@Serializable
data class ChatUserRoute(val nickname: String, val messageId: String?) : NavKey

/**
 * QR verification, a full-screen destination.
 *
 * [peerID] is the private conversation it was opened from, for the unverify
 * action. [reopenPeerList] is set when it was opened from the peer list, which
 * closes to make way for it and reopens on the way back. Both travel in the
 * key, so they survive process death along with the stack.
 */
@Serializable
data class VerificationRoute(val peerID: String?, val reopenPeerList: Boolean) : NavKey

/**
 * The destination that should be at the root for a given onboarding state.
 *
 * CHECKING and INITIALIZING map to chat, matching the behaviour this replaced:
 * the app shows the chat screen while it verifies its own readiness rather than
 * flashing an onboarding step.
 */
fun rootRouteFor(state: OnboardingState): NavKey = when (state) {
    OnboardingState.CHECKING,
    OnboardingState.INITIALIZING,
    OnboardingState.COMPLETE -> ChatRoute

    else -> OnboardingRoute
}
