// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.subsonic

import nl.mattix.andamp.core.packapi.PackTrack
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads a Subsonic song object into a [PackTrack].
 *
 * The `song` array of `getAlbum`, the `entry` array of `getPlaylist` and the
 * `song` array of `search3` hold the same object. Every field except the id is
 * read as optional.
 */
object SubsonicRow {
    /**
     * This source's scheme, the first word of every address it hands out. The
     * player routes a row by it and saved playlists record it, so it cannot
     * change after a release.
     */
    const val SCHEME = "subsonic"

    /** A server's song id as an address, `subsonic:track:<id>`. The id is kept verbatim. */
    fun address(id: String): String = TRACK + id

    /** The server id inside an address, or null when the address is not one of this source's. */
    fun trackId(address: String): String? = address.removePrefix(TRACK).takeIf { it != address && it.isNotEmpty() }

    /**
     * One song object as a row, or null when it has no id.
     *
     * [PackTrack.uri] is the address and not the stream URL. The player writes
     * a row's uri into saved playlists, and a stream URL carries the
     * listener's credential. [SubsonicStream] builds the URL when the row is
     * played.
     */
    fun track(
        song: JSONObject,
        server: SubsonicServer,
    ): PackTrack? {
        val id = song.optString("id").takeIf { it.isNotEmpty() } ?: return null
        val address = address(id)
        return PackTrack(
            id = address,
            // `displayArtist` is OpenSubsonic's field and names every credited
            // artist; `artist` is the classic one
            artist = song.optString("displayArtist").ifEmpty { song.optString("artist") },
            title = song.optString("title"),
            // `duration` is in whole seconds
            durationMs = song.optLong("duration") * MILLIS_PER_SECOND,
            uri = address,
            // 0 when the server does not say
            bitrateKbps = song.optInt("bitRate"),
            sampleRateKhz = song.optInt("samplingRate") / HZ_PER_KHZ,
            isStream = false,
            artworkUri = artwork(song, server),
            defaultName = fileName(song, id),
        )
    }

    /** Every song in an array that has an id; other entries are dropped. */
    fun tracks(
        songs: JSONArray?,
        server: SubsonicServer,
    ): List<PackTrack> {
        if (songs == null) return emptyList()
        return (0 until songs.length()).mapNotNull { at -> songs.optJSONObject(at)?.let { track(it, server) } }
    }

    /**
     * The URL of this song's cover, or null when the song has no `coverArt`.
     *
     * The player fetches the cover in its own process, so the URL carries its
     * own salt and token from [SubsonicServer.url].
     */
    private fun artwork(
        song: JSONObject,
        server: SubsonicServer,
    ): String? {
        val cover = song.optString("coverArt").takeIf { it.isNotEmpty() } ?: return null
        return server.url(Subsonic.COVER_ART, mapOf("id" to cover))?.toString()
    }

    /**
     * The name of the file behind this song, or null when the server gives
     * neither a path nor a suffix.
     *
     * It goes into the row's `defaultName`, which the player keeps with the row
     * and hands back when the row is played. [SubsonicStream] reads the file's
     * suffix from it.
     *
     * The result is the last segment of `path` when that ends in the suffix,
     * and otherwise the title (or the id) with the suffix. A missing `suffix`
     * is taken from `contentType` for FLAC.
     */
    private fun fileName(
        song: JSONObject,
        id: String,
    ): String? {
        val suffix = song.optString("suffix").ifEmpty { SUFFIXES[song.optString("contentType")].orEmpty() }
        val file = song.optString("path").substringAfterLast('/')
        return when {
            suffix.isEmpty() -> file.ifEmpty { null }
            file.endsWith(".$suffix", ignoreCase = true) -> file
            else -> "${song.optString("title").ifEmpty { id }}.$suffix"
        }
    }

    /** The content types whose suffix [SubsonicStream.transcoding] depends on. */
    private val SUFFIXES = mapOf("audio/flac" to SubsonicStream.FLAC, "audio/x-flac" to SubsonicStream.FLAC)

    private const val TRACK = "$SCHEME:track:"
    private const val MILLIS_PER_SECOND = 1_000L

    /** [PackTrack.sampleRateKhz] is in whole kHz. */
    private const val HZ_PER_KHZ = 1_000
}
