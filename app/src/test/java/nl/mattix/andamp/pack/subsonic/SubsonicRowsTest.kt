// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.subsonic

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [SubsonicRow] on recorded song objects.
 *
 * `getAlbum.json` is the demo server's answer for pornophonique's *8-bit
 * lagerfeuer*, cut to four of its eight songs. `getAlbum-bare.json` is the same
 * answer with one song and that song's `coverArt`, `bitRate` and
 * `samplingRate` removed; all three are optional in the specification.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SubsonicRowsTest {
    @Test
    fun `a song becomes a row the player can route back to this source`() {
        val row = checkNotNull(SubsonicRow.track(song("getAlbum"), SERVER))

        assertEquals("subsonic:track:Kbh4QvrgGcPgsjdK8cd448", row.id)
        assertEquals("sad robot", row.title)
        assertEquals("pornophonique", row.artist)
        // 312 seconds in the recording
        assertEquals(312_000L, row.durationMs)
        assertEquals(248, row.bitrateKbps)
        assertEquals(44, row.sampleRateKhz)
        assertFalse(row.isStream)
    }

    @Test
    fun `a row's uri is its address and not a URL with a credential in it`() {
        val row = checkNotNull(SubsonicRow.track(song("getAlbum"), SERVER))

        // the player writes this field into saved playlists
        assertEquals(row.id, row.uri)
        assertFalse(row.uri.orEmpty(), row.uri.orEmpty().contains(PASSWORD))
        assertFalse(row.uri.orEmpty(), row.uri.orEmpty().contains("http"))
    }

    @Test
    fun `a cover is an address another process can fetch on its own`() {
        val row = checkNotNull(SubsonicRow.track(song("getAlbum"), SERVER))

        val art = checkNotNull(checkNotNull(row.artworkUri).toHttpUrlOrNull())
        assertEquals("/rest/getCoverArt", art.encodedPath)
        assertEquals("mf-Kbh4QvrgGcPgsjdK8cd448_640a93a4", art.queryParameter("id"))
        // the player fetches it, so it carries the user, the token and the salt
        assertEquals("demo", art.queryParameter("u"))
        assertTrue(checkNotNull(art.queryParameter("t")).length == 32)
        assertTrue(checkNotNull(art.queryParameter("s")).isNotEmpty())
        assertFalse(art.toString(), art.toString().contains(PASSWORD))
    }

    @Test
    fun `a file the server has no art for gets no address for it`() {
        val row = checkNotNull(SubsonicRow.track(song("getAlbum-bare"), SERVER))

        assertNull(row.artworkUri)
    }

    @Test
    fun `a bitrate the server did not give is nought rather than a guess`() {
        val row = checkNotNull(SubsonicRow.track(song("getAlbum-bare"), SERVER))

        assertEquals(0, row.bitrateKbps)
        assertEquals(0, row.sampleRateKhz)
        // the rest of the row is unaffected
        assertEquals("subsonic:track:Kbh4QvrgGcPgsjdK8cd448", row.id)
        assertEquals(312_000L, row.durationMs)
    }

    @Test
    fun `an address goes out and comes back`() {
        val address = SubsonicRow.address("Kbh4QvrgGcPgsjdK8cd448")

        assertEquals("subsonic:track:Kbh4QvrgGcPgsjdK8cd448", address)
        assertEquals("Kbh4QvrgGcPgsjdK8cd448", SubsonicRow.trackId(address))
    }

    @Test
    fun `an address that is not ours is nobody's business here`() {
        assertNull(SubsonicRow.trackId("example:track:abc123"))
        // a bare server id
        assertNull(SubsonicRow.trackId("Kbh4QvrgGcPgsjdK8cd448"))
        assertNull(SubsonicRow.trackId("subsonic:track:"))
        assertNull(SubsonicRow.trackId(""))
    }

    @Test
    fun `a song with no id is not a row`() {
        assertNull(SubsonicRow.track(JSONObject("""{"title": "a song from nowhere"}"""), SERVER))
    }

    @Test
    fun `a row carries the name of the file behind it, suffix and all`() {
        val row = checkNotNull(SubsonicRow.track(song("getAlbum"), SERVER))

        // SubsonicStream reads the suffix from this field
        assertEquals("01 - sad robot.mp3", row.defaultName)
    }

    @Test
    fun `a server that hides its paths still says what kind of file it is`() {
        val flac = JSONObject("""{"id": "abc", "title": "Heartbeats", "contentType": "audio/flac"}""")
        val named = JSONObject("""{"id": "abc", "title": "Heartbeats", "suffix": "flac", "path": "hidden"}""")
        val unknown = JSONObject("""{"id": "abc", "title": "Heartbeats"}""")

        assertEquals("Heartbeats.flac", SubsonicRow.track(flac, SERVER)?.defaultName)
        assertEquals("Heartbeats.flac", SubsonicRow.track(named, SERVER)?.defaultName)
        assertNull("a song with no suffix or content type has no file name", SubsonicRow.track(unknown, SERVER)?.defaultName)
    }

    @Test
    fun `an array of songs comes out in the server's own order`() {
        val album = body("getAlbum").optJSONObject("album")

        val rows = SubsonicRow.tracks(album?.optJSONArray("song"), SERVER)

        assertEquals(
            listOf("sad robot", "take me to the bonus level because i need an extra life", "lemmings in love", "space invaders"),
            rows.map { it.title },
        )
    }

    @Test
    fun `no array at all is no rows rather than a failure`() {
        assertEquals(emptyList<Any>(), SubsonicRow.tracks(null, SERVER))
    }

    private fun song(recording: String): JSONObject =
        checkNotNull(body(recording).optJSONObject("album")?.optJSONArray("song")?.optJSONObject(0))

    private companion object {
        const val PASSWORD = "correct-horse-battery-staple"
        val SERVER = SubsonicServer("https://demo.navidrome.org", "demo", PASSWORD)
    }
}
