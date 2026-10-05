package com.bitchat.android.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivateChatNavigationTest {

    private val navigator = AppNavigator()

    private fun stack(): List<Any> = navigator.backStack.toList()

    @Test
    fun `a chat opened from the peer list takes the list's place`() {
        navigator.resetTo(ChatRoute)
        navigator.goTo(MeshPeerListRoute)

        navigator.openPrivateChat("peer-a")

        assertEquals(listOf(ChatRoute, PrivateChatRoute("peer-a")), stack())
    }

    @Test
    fun `a chat opened from a sheet over chat takes the sheet's place`() {
        navigator.resetTo(ChatRoute)
        navigator.goTo(ChatUserRoute("alice", messageId = null))

        navigator.openPrivateChat("peer-a")

        assertEquals(listOf(ChatRoute, PrivateChatRoute("peer-a")), stack())
    }

    @Test
    fun `a chat opened from another chat replaces it rather than stacking`() {
        navigator.resetTo(ChatRoute)
        navigator.openPrivateChat("peer-a")
        navigator.goTo(SecurityVerificationRoute("peer-a"))

        navigator.openPrivateChat("peer-b")

        assertEquals(listOf(ChatRoute, PrivateChatRoute("peer-b")), stack())
    }

    @Test
    fun `nothing opens while chat is not on the stack`() {
        navigator.resetTo(OnboardingRoute)

        navigator.openPrivateChat("peer-a")

        assertEquals(listOf(OnboardingRoute), stack())
    }
}
