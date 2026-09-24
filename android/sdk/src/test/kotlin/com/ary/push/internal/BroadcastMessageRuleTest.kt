package com.ary.push.internal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The broadcast receiver sees messages another messaging service may be handling as the host
 * intends, so which ones it is allowed to touch is part of the contract.
 */
class BroadcastMessageRuleTest {

    private fun rule(
        inForeground: Boolean,
        sdkServiceDeclared: Boolean = true,
        isOwnMessage: Boolean = true,
        hasNotificationBlock: Boolean = false
    ) = PushCore.shouldHandleBroadcast(inForeground, sdkServiceDeclared, isOwnMessage, hasNotificationBlock)

    @Test
    fun `a foreground message is shown even when another service won delivery`() {
        assertTrue(rule(inForeground = true, hasNotificationBlock = true))
        assertTrue(rule(inForeground = true, isOwnMessage = false, hasNotificationBlock = true))
        assertTrue(rule(inForeground = true, isOwnMessage = false, hasNotificationBlock = false))
    }

    @Test
    fun `a host that removed the SDK service keeps its own messages to itself`() {
        assertFalse(rule(inForeground = true, sdkServiceDeclared = false, isOwnMessage = false))
        assertTrue(rule(inForeground = true, sdkServiceDeclared = false, isOwnMessage = true))
    }

    @Test
    fun `in the background the system renders anything with a notification block`() {
        assertFalse(rule(inForeground = false, hasNotificationBlock = true))
    }

    @Test
    fun `in the background only the backend's data messages are the SDK's`() {
        assertTrue(rule(inForeground = false, isOwnMessage = true))
        assertFalse(rule(inForeground = false, isOwnMessage = false))
        assertFalse(rule(inForeground = false, isOwnMessage = false, sdkServiceDeclared = false))
    }
}
