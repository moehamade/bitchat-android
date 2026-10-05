package com.bitchat.android.navigation

/**
 * Shows the private chat for [conversationID] over chat.
 *
 * Everything above chat goes first. Private chats open from the peer list, the
 * chat user sheet, a geohash DM and the unread shortcut, and in each case the
 * chat takes the launcher's place, so Back from it returns to chat. Opening
 * one while another is shown replaces it rather than stacking a second.
 *
 * Does nothing while chat is not on the stack, as during onboarding.
 */
fun Navigator.openPrivateChat(conversationID: String) {
    if (!popTo(ChatRoute)) return
    goTo(PrivateChatRoute(conversationID))
}
