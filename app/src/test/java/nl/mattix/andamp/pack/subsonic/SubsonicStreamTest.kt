// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.subsonic

import nl.mattix.andamp.core.model.Track
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stream URLs and requests [SubsonicStream] and [SubsonicPlayback] build. */
class SubsonicStreamTest {
    @Test
    fun `a row's address becomes a stream URL on the listener's own server`() {
        val url =
            checkNotNull(
                SubsonicStream
                    .url(
                        SERVER,
                        "subsonic:track:Kbh4QvrgGcPgsjdK8cd448",
                        "01 - sad robot.mp3",
                        MODERN,
                    )?.toHttpUrlOrNull(),
            )

        assertEquals("/rest/stream", url.encodedPath)
        assertEquals("Kbh4QvrgGcPgsjdK8cd448", url.queryParameter("id"))
        assertEquals("demo", url.queryParameter("u"))
        assertTrue(checkNotNull(url.queryParameter("t")).isNotEmpty())
        assertFalse(url.toString(), url.toString().contains(PASSWORD))
    }

    @Test
    fun `nothing is asked for beyond the id`() {
        val url =
            checkNotNull(SubsonicStream.url(SERVER, "subsonic:track:abc", "01 - Heartbeats.flac", MODERN)?.toHttpUrlOrNull())

        // no format and no bitrate cap
        assertNull(url.queryParameter("format"))
        assertNull(url.queryParameter("maxBitRate"))
    }

    @Test
    fun `an address from another source never reaches this listener's server`() {
        assertNull(SubsonicStream.url(SERVER, "example:track:abc123", null, MODERN))
        assertNull(SubsonicStream.url(SERVER, "Kbh4QvrgGcPgsjdK8cd448", null, MODERN))
    }

    @Test
    fun `a server with no address has nothing to stream from`() {
        assertNull(SubsonicStream.url(SubsonicServer("", "demo", PASSWORD), "subsonic:track:abc", null, MODERN))
    }

    @Test
    fun `a FLAC file is asked for as an mp3 where the phone cannot read FLAC over http`() {
        val url = checkNotNull(SubsonicStream.url(SERVER, "subsonic:track:abc", "01 - Heartbeats.flac", OREO)?.toHttpUrlOrNull())

        assertEquals("abc", url.queryParameter("id"))
        assertEquals("mp3", url.queryParameter("format"))
        assertEquals("320", url.queryParameter("maxBitRate"))
    }

    @Test
    fun `only FLAC, and only below API 27, is transcoded`() {
        assertEquals(mapOf("format" to "mp3", "maxBitRate" to "320"), SubsonicStream.transcoding(OREO, "flac"))
        assertEquals("an upper-case FLAC suffix is transcoded", 2, SubsonicStream.transcoding(OREO, "FLAC").size)
        assertEquals("an mp3 is not transcoded", emptyMap<String, String>(), SubsonicStream.transcoding(OREO, "mp3"))
        assertEquals("an unknown suffix is not transcoded", emptyMap<String, String>(), SubsonicStream.transcoding(OREO, null))
        assertEquals(emptyMap<String, String>(), SubsonicStream.transcoding(OREO_MR1, "flac"))
        assertEquals(emptyMap<String, String>(), SubsonicStream.transcoding(MODERN, "flac"))
    }

    @Test
    fun `the suffix is read off the file name a row carries`() {
        assertEquals("flac", SubsonicStream.suffix("01 - Heartbeats.flac"))
        assertNull(SubsonicStream.suffix("Heartbeats"))
        assertNull(SubsonicStream.suffix(null))
    }

    @Test
    fun `a row is handed to the shared playback as its stream URL, with no headers`() {
        val row = Track("abc", "The Knife", "Heartbeats", 0, uri = "subsonic:track:abc", defaultName = "01 - Heartbeats.flac")

        val request = checkNotNull(SubsonicPlayback.request(SERVER, row, OREO))
        val url = checkNotNull(request.url.toHttpUrlOrNull())

        // the stream URL, with the transcode parameters
        assertEquals("/rest/stream", url.encodedPath)
        assertEquals("abc", url.queryParameter("id"))
        assertEquals("mp3", url.queryParameter("format"))
        // the credential is in the query string
        assertEquals("demo", url.queryParameter("u"))
        assertEquals(emptyMap<String, String>(), request.headers)
    }

    @Test
    fun `a transcoded row starts at the position it is asked for, and says it cannot be sought`() {
        val flac = Track("abc", "The Knife", "Heartbeats", 0, uri = "subsonic:track:abc", defaultName = "01 - Heartbeats.flac")

        val request = checkNotNull(SubsonicPlayback.request(SERVER, flac, OREO, positionMs = 61_600))

        assertFalse(request.seekable)
        assertEquals("62", checkNotNull(request.url.toHttpUrlOrNull()).queryParameter("timeOffset"))
    }

    @Test
    fun `a row served as it is carries no offset and can be sought in place`() {
        val flac = Track("abc", "The Knife", "Heartbeats", 0, uri = "subsonic:track:abc", defaultName = "01 - Heartbeats.flac")
        val mp3 = flac.copy(defaultName = "01 - Heartbeats.mp3")

        val asIs =
            listOf(SubsonicPlayback.request(SERVER, flac, MODERN, 61_600), SubsonicPlayback.request(SERVER, mp3, OREO, 61_600))
        for (request in asIs) {
            assertTrue(checkNotNull(request).seekable)
            assertNull(checkNotNull(request.url.toHttpUrlOrNull()).queryParameter("timeOffset"))
        }
    }

    @Test
    fun `a transcode from the top carries no offset at all`() {
        val flac = Track("abc", "The Knife", "Heartbeats", 0, uri = "subsonic:track:abc", defaultName = "01 - Heartbeats.flac")

        val url = checkNotNull(checkNotNull(SubsonicPlayback.request(SERVER, flac, OREO)).url.toHttpUrlOrNull())

        assertNull(url.queryParameter("timeOffset"))
    }

    @Test
    fun `a row with no server or no address of ours has no request`() {
        val row = Track("abc", "The Knife", "Heartbeats", 0, uri = "subsonic:track:abc")

        assertNull(SubsonicPlayback.request(null, row, MODERN))
        assertNull(SubsonicPlayback.request(SERVER, row.copy(uri = null), MODERN))
        assertNull(SubsonicPlayback.request(SERVER, row.copy(uri = "file:///sdcard/Music/a.mp3"), MODERN))
    }

    private companion object {
        const val PASSWORD = "correct-horse-battery-staple"
        val SERVER = SubsonicServer("https://demo.navidrome.org", "demo", PASSWORD)

        /** API 26, where a FLAC file is transcoded, and 27, where it is not. */
        const val OREO = 26
        const val OREO_MR1 = 27
        const val MODERN = 36
    }
}
