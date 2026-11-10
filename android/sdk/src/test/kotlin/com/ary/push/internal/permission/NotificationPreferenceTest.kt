package com.ary.push.internal.permission

import com.ary.push.internal.storage.StorageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class NotificationPreferenceTest {

    private lateinit var storage: StorageManager

    @Before
    fun setUp() {
        storage = StorageManager(RuntimeEnvironment.getApplication())
        storage.clearAll()
    }

    @Test
    fun `notifications are on until the application turns them off`() {
        assertTrue(NotificationPreference(storage).enabledByUser)
    }

    @Test
    fun `the choice survives a restart`() {
        NotificationPreference(storage).setEnabledByUser(false)

        assertFalse(NotificationPreference(storage).enabledByUser)
    }

    @Test
    fun `setting the current value again reports no change`() {
        val preference = NotificationPreference(storage)

        assertFalse(preference.setEnabledByUser(true))
        assertTrue(preference.setEnabledByUser(false))
        assertFalse(preference.setEnabledByUser(false))
        assertTrue(preference.setEnabledByUser(true))
    }

    @Test
    fun `the backend sees notifications as enabled only when the system and the user allow them`() {
        val preference = NotificationPreference(storage)

        assertEquals(true, preference.effective(osAuthorized = true))
        assertEquals(false, preference.effective(osAuthorized = false))

        preference.setEnabledByUser(false)
        assertEquals(false, preference.effective(osAuthorized = true))
        assertEquals(false, preference.effective(osAuthorized = false))
    }
}
