// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.subsonic

/**
 * Builds the `stream` URL of a track.
 *
 * The URL carries a token derived from the password, so it is built when a row
 * is about to play and is not kept on the row; see [SubsonicRow.track].
 */
object SubsonicStream {
    /**
     * The `stream` URL for an address this source handed out, or null when the
     * address is not one of its own or the server address is not a URL.
     *
     * [fileName] is the row's `defaultName` and [sdk] is the phone's API level;
     * together they decide whether a transcode is asked for, see [transcoding].
     * [positionMs] is where the audio should start, and goes on the URL only
     * for a transcode; see [offset].
     */
    fun url(
        server: SubsonicServer,
        address: String,
        fileName: String?,
        sdk: Int,
        positionMs: Long = 0,
    ): String? {
        val id = SubsonicRow.trackId(address) ?: return null
        val transcode = transcoding(sdk, suffix(fileName))
        val params = mapOf("id" to id) + transcode + if (transcode.isEmpty()) emptyMap() else offset(positionMs)
        return server.url(Subsonic.STREAM, params)?.toString()
    }

    /** Whether a transcode is asked for this file, which makes the stream one that cannot be sought in. */
    fun transcodes(
        fileName: String?,
        sdk: Int,
    ): Boolean = transcoding(sdk, suffix(fileName)).isNotEmpty()

    /**
     * `timeOffset`, in whole seconds, for a transcode that starts part way.
     *
     * A transcode is sent without byte ranges, so the only way to a position
     * in it is to ask for it to start there. A file served as it is, is sought
     * by range and gets no offset.
     *
     * The position is rounded to the nearest second, so what is heard after
     * such a seek can differ from the readout by up to half a second.
     */
    private fun offset(positionMs: Long): Map<String, String> {
        val seconds = (positionMs + MS_PER_SECOND / 2) / MS_PER_SECOND
        return if (seconds > 0) mapOf("timeOffset" to seconds.toString()) else emptyMap()
    }

    /**
     * The transcoding parameters for a stream: empty, except for FLAC below
     * API 27.
     *
     * Normally no format or bitrate is asked for, and the server sends the
     * file or applies its own transcoding rules. Below API 27 the platform's
     * extractor does not read FLAC over http reliably, so mp3 at 320 kbps is
     * requested. A server with no transcoding configured ignores `format` and
     * sends the original.
     */
    fun transcoding(
        sdk: Int,
        suffix: String?,
    ): Map<String, String> =
        if (sdk < FLAC_OVER_HTTP_SDK && suffix.equals(FLAC, ignoreCase = true)) {
            mapOf("format" to TRANSCODE_FORMAT, "maxBitRate" to TRANSCODE_KBPS.toString())
        } else {
            emptyMap()
        }

    /** The extension of [fileName], or null when it has none. */
    fun suffix(fileName: String?): String? = fileName?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() }

    /** The suffix a Subsonic server gives a FLAC file. */
    const val FLAC = "flac"

    /** The first API level whose `MediaExtractor` reads FLAC over http reliably. */
    private const val FLAC_OVER_HTTP_SDK = 27

    private const val TRANSCODE_FORMAT = "mp3"

    /** The highest mp3 bitrate. */
    private const val TRANSCODE_KBPS = 320

    private const val MS_PER_SECOND = 1_000L
}
