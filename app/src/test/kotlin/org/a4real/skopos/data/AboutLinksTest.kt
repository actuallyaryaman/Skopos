package org.a4real.skopos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `github username derived from profile and repo urls`() {
        assertEquals("octocat", AboutLinks.githubUsername("https://github.com/octocat"))
        assertEquals("octocat", AboutLinks.githubUsername("https://github.com/octocat/"))
        assertEquals("octocat", AboutLinks.githubUsername("https://github.com/octocat/Hello-World"))
        assertEquals(
            "octocat",
            AboutLinks.githubUsername("https://github.com/octocat/Hello-World/issues/1"),
        )
    }

    @Test
    fun `non-github urls yield no username`() {
        assertNull(AboutLinks.githubUsername("https://example.com/octocat/project"))
        assertNull(AboutLinks.githubUsername("http://github.com/octocat"))
        assertNull(AboutLinks.githubUsername("not a url"))
        assertNull(AboutLinks.githubUsername(""))
        assertNull(AboutLinks.githubUsername("https://github.com/"))
    }

    @Test
    fun `username from project url ignores repository path`() {
        assertEquals("alice", AboutLinks.githubUsername("https://github.com/alice/skopos", ""))
        assertEquals(
            "alice",
            AboutLinks.githubUsername("https://github.com/alice/skopos", "@someone-else"),
        )
    }

    @Test
    fun `profile url is exactly host plus username`() {
        assertEquals("https://github.com/alice", AboutLinks.githubProfileUrl("alice"))
    }

    @Test
    fun `profile and project urls stay distinct`() {
        val project = "https://github.com/alice/skopos"
        val username = AboutLinks.githubUsername(project, "")
        val profile = username?.let { AboutLinks.githubProfileUrl(it) }
        assertEquals("https://github.com/alice", profile)
        // Project source is used verbatim elsewhere; the profile must never equal it
        // and must never gain a duplicated path segment.
        assertTrue(profile != project)
        assertEquals(listOf("https:", "", "github.com", "alice"), profile!!.split("/"))
    }

    @Test
    fun `non-github project falls back to handle only`() {
        assertEquals(
            "bob",
            AboutLinks.githubUsername("https://example.com/some/project", "bob"),
        )
        assertNull(AboutLinks.githubUsername("https://example.com/some/project", ""))
        assertNull(AboutLinks.githubUsername("https://example.com/some/project", "not a user!"))
    }

    @Test
    fun `profile url rejects bad usernames`() {
        assertNull(AboutLinks.githubProfileUrl(""))
        assertNull(AboutLinks.githubProfileUrl("   "))
        assertNull(AboutLinks.githubProfileUrl("not a user!"))
    }
}
