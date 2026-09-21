package org.a4real.skopos.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutLinksTest {

    @Test
    fun `https urls accepted`() {
        assertTrue(AboutLinks.isHttpsUrl("https://example.com/project"))
        assertTrue(AboutLinks.isHttpsUrl("  HTTPS://example.com/x "))
    }

    @Test
    fun `non-https rejected`() {
        assertFalse(AboutLinks.isHttpsUrl("http://example.com/project"))
        assertFalse(AboutLinks.isHttpsUrl("ftp://example.com/project"))
        assertFalse(AboutLinks.isHttpsUrl("javascript:alert(1)"))
        assertFalse(AboutLinks.isHttpsUrl(""))
        assertFalse(AboutLinks.isHttpsUrl("   "))
        assertFalse(AboutLinks.isHttpsUrl("example.com/project"))
    }
}
