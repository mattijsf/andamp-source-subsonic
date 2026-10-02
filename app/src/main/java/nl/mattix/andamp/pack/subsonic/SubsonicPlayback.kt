// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.subsonic

import android.os.Build
import kotlinx.coroutines.CoroutineScope
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.playback.AudioOut
import nl.mattix.andamp.core.playback.NetworkWatch
import nl.mattix.andamp.core.playback.PlaybackBackend
import nl.mattix.andamp.pack.common.stream.StreamPlayback
import nl.mattix.andamp.pack.common.stream.StreamRequest

/**
 * Builds the playback backend for a Subsonic server.
 *
 * Playback is the SDK's [StreamPlayback]. This object supplies where a row's
 * audio is: a `stream` URL with the credential in its query, so the request
 * has no headers.
 */
internal object SubsonicPlayback {
    /**
     * A backend over [server]. [server] is called each time a row is opened,
     * so an account changed on the settings screen is used from the next open.
     */
    fun backend(
        server: () -> SubsonicServer?,
        tracks: List<Track>,
        startIndex: Int,
        scope: CoroutineScope,
        out: AudioOut?,
        network: NetworkWatch,
    ): PlaybackBackend =
        StreamPlayback.backend(
            tracks = tracks,
            startIndex = startIndex,
            scope = scope,
            out = out,
            network = network,
            locate = { track, positionMs -> request(server(), track, Build.VERSION.SDK_INT, positionMs) },
        )

    /**
     * Where one row's audio is. Null when no server is set up or when the row's
     * address is not one of this source's.
     *
     * [sdk] is the phone's API level, passed in so a JVM test can choose it.
     *
     * A transcode this source asked for cannot be sought in: the request is
     * marked not seekable and carries [positionMs] as its offset, so a seek
     * makes a new request. See [StreamRequest.seekable].
     */
    fun request(
        server: SubsonicServer?,
        track: Track,
        sdk: Int,
        positionMs: Long = 0,
    ): StreamRequest? {
        val reachable = server ?: return null
        val address = track.uri ?: return null
        val url = SubsonicStream.url(reachable, address, track.defaultName, sdk, positionMs) ?: return null
        return StreamRequest(url, seekable = !SubsonicStream.transcodes(track.defaultName, sdk))
    }
}
