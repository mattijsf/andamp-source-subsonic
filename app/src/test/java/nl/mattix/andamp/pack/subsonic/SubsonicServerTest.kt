// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.subsonic

import okhttp3.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/** The credential and the address on the URLs [SubsonicServer] builds. */
class SubsonicServerTest {
    @Test
    fun `the token is the password and the salt in that order, hashed`() {
        val url = checkNotNull(SERVER.url("getArtists"))

        val salt = checkNotNull(url.queryParameter("s"))
        assertEquals(md5(PASSWORD + salt), url.queryParameter("t"))
        // and not the salt first
        assertNotEquals(md5(salt + PASSWORD), url.queryParameter("t"))
    }

    /** Checks this test's own [md5] against the example in the Subsonic API documentation. */
    @Test
    fun `the hash is the one the protocol documents`() {
        assertEquals("26719a1196d2a940705a59634eb18eab", md5("sesame" + "c19b2d"))
    }

    @Test
    fun `two calls carry two salts`() {
        val first = checkNotNull(SERVER.url("getArtists"))
        val second = checkNotNull(SERVER.url("getArtists"))

        assertNotEquals(first.queryParameter("s"), second.queryParameter("s"))
        // and so two tokens
        assertNotEquals(first.queryParameter("t"), second.queryParameter("t"))
    }

    @Test
    fun `a salt is long enough to be one`() {
        val salt = checkNotNull(SERVER.url("getArtists")?.queryParameter("s"))

        // the specification asks for six characters or more
        assertTrue(salt, salt.length >= 6)
        assertTrue(salt, salt.all { it in "0123456789abcdef" })
    }

    @Test
    fun `the password itself is never on a URL, and neither is the parameter that would carry it`() {
        val urls =
            listOf(
                SERVER.url("getArtists"),
                SERVER.url("stream", mapOf("id" to "abc")),
                SERVER.url("getCoverArt", mapOf("id" to "mf-abc")),
            ).map { checkNotNull(it) }

        for (url in urls) {
            assertFalse(url.toString(), url.toString().contains(PASSWORD))
            // the legacy parameter that sends the password itself
            assertNull(url.toString(), url.queryParameter("p"))
        }
    }

    @Test
    fun `every call says which API it speaks and who is speaking`() {
        val url = checkNotNull(SERVER.url("getArtists"))

        assertEquals("1.16.1", url.queryParameter("v"))
        assertEquals("andamp", url.queryParameter("c"))
        assertEquals("json", url.queryParameter("f"))
        assertEquals("demo", url.queryParameter("u"))
    }

    @Test
    fun `what somebody types becomes an address that works`() {
        val typed = SubsonicServer("  demo.navidrome.org/ ", "demo", PASSWORD)

        // a missing scheme is read as https
        assertEquals("https://demo.navidrome.org:443/rest/getArtists", where(checkNotNull(typed.url("getArtists"))))
    }

    @Test
    fun `a server mounted under a path keeps it`() {
        val proxied = SubsonicServer("https://home.example/music", "demo", PASSWORD)

        assertEquals("https://home.example:443/music/rest/getAlbum", where(checkNotNull(proxied.url("getAlbum"))))
    }

    @Test
    fun `a listener who really means plain http is taken at their word`() {
        val local = SubsonicServer("http://nas.local:4533", "demo", PASSWORD)

        assertEquals("http://nas.local:4533/rest/ping", where(checkNotNull(local.url("ping"))))
    }

    @Test
    fun `an address that is not one has no URL rather than an exception`() {
        assertNull(SubsonicServer("", "demo", PASSWORD).url("getArtists"))
        assertNull(SubsonicServer("   ", "demo", PASSWORD).url("getArtists"))
        assertNull(SubsonicServer("gopher://nope", "demo", PASSWORD).url("getArtists"))
    }

    @Test
    fun `somebody else's text on a query is escaped rather than pasted`() {
        val url = checkNotNull(SERVER.url("search3", mapOf("query" to "AC/DC & 100%")))

        assertEquals("AC/DC & 100%", url.queryParameter("query"))
        assertTrue(url.toString(), url.toString().contains("100%25"))
    }

    /** The URL without its query. */
    private fun where(url: HttpUrl): String = "${url.scheme}://${url.host}:${url.port}${url.encodedPath}"

    /** An md5 written separately from the implementation's. */
    private fun md5(of: String): String =
        MessageDigest
            .getInstance("MD5")
            .digest(of.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val PASSWORD = "correct-horse-battery-staple"
        val SERVER = SubsonicServer("https://demo.navidrome.org", "demo", PASSWORD)
    }
}
