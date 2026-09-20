package org.a4real.skopos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactsAccessTest {

    @Test
    fun `provider queries require both permission and the SELECTED picker`() {
        assertFalse(ContactsAccess.mayQuery(granted = false, inSelectedPicker = false))
        assertFalse(ContactsAccess.mayQuery(granted = false, inSelectedPicker = true))
        // A granted permission alone must not trigger a query while in FULL/EMPTY.
        assertFalse(ContactsAccess.mayQuery(granted = true, inSelectedPicker = false))
        assertTrue(ContactsAccess.mayQuery(granted = true, inSelectedPicker = true))
    }

    @Test
    fun `retry directs to a request normally and to settings after permanent denial`() {
        assertEquals(ContactsRetry.REQUEST, ContactsAccess.retry(deniedPermanently = false))
        assertEquals(ContactsRetry.OPEN_SETTINGS, ContactsAccess.retry(deniedPermanently = true))
    }
}