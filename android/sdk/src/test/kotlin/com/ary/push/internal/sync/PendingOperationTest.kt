package com.ary.push.internal.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class PendingOperationTest {

    @Test
    fun `an operation survives a JSON round trip`() {
        val original = PendingOperation(
            type = OperationType.UPDATE_TOKEN,
            payload = mapOf("token" to "abc", "provider" to "fcm"),
            attempts = 2
        )

        val restored = PendingOperation.fromJson(original.toJson())

        assertEquals(original.id, restored?.id)
        assertEquals(original.type, restored?.type)
        assertEquals(original.payload, restored?.payload)
        assertEquals(original.createdAt, restored?.createdAt)
        assertEquals(2, restored?.attempts)
    }

    @Test
    fun `an unknown operation type is dropped rather than wedging the queue`() {
        // A queue written by a newer SDK version must not stop an older one from draining.
        val json = JSONObject().put("type", "SEND_TELEPATHY").put("id", "x")

        assertNull(PendingOperation.fromJson(json))
    }

    @Test
    fun `state operations supersede earlier copies and value operations do not`() {
        assertTrue(OperationType.UPDATE_TOKEN.isLatestValueWins)
        assertTrue(OperationType.IDENTIFY_USER.isLatestValueWins)
        assertTrue(OperationType.UPDATE_PERMISSION.isLatestValueWins)

        // Tag writes merge and events accumulate, so neither may discard an earlier entry.
        assertTrue(!OperationType.UPDATE_TAGS.isLatestValueWins)
        assertTrue(!OperationType.TRACK_EVENTS.isLatestValueWins)
    }

    @Test
    fun `ordinal order encodes dependency order`() {
        // Nothing may be sent to the backend before the installation it describes exists.
        assertTrue(
            OperationType.REGISTER_INSTALLATION.ordinal < OperationType.UPDATE_TOKEN.ordinal
        )
        assertTrue(OperationType.UPDATE_TOKEN.ordinal < OperationType.IDENTIFY_USER.ordinal)
        assertTrue(OperationType.IDENTIFY_USER.ordinal < OperationType.UPDATE_TAGS.ordinal)
    }
}
