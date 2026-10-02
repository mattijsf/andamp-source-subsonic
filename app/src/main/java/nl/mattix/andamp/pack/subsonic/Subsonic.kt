// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.subsonic

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The Subsonic API version, client name and endpoint names this source uses.
 * The endpoint names are the ones in the specification and in the recorded
 * answers under `src/test/resources`.
 */
object Subsonic {
    /**
     * The API version sent as `v`. 1.16.1 is the last version Subsonic itself
     * published.
     */
    const val API_VERSION = "1.16.1"

    /** The client name sent as `c` on every call. */
    const val CLIENT = "andamp"

    /** Answers whether the server is there and the account is accepted; the settings screen tests an account with it. */
    const val PING = "ping"

    /** Every artist, grouped under index letters. */
    const val GET_ARTISTS = "getArtists"

    /** One artist and all of their albums, with no paging. */
    const val GET_ARTIST = "getArtist"

    /** A list of albums, taking an offset and a size. */
    const val GET_ALBUM_LIST = "getAlbumList2"

    /** One album and its songs. */
    const val GET_ALBUM = "getAlbum"

    /** The playlists this account can see. */
    const val GET_PLAYLISTS = "getPlaylists"

    /** One playlist, its songs under `entry`. */
    const val GET_PLAYLIST = "getPlaylist"

    /** Artists, albums and songs at once, each with its own offset and count. */
    const val SEARCH = "search3"

    /** A cover, by the id a song or an album carries. */
    const val COVER_ART = "getCoverArt"

    /** The audio of one song. */
    const val STREAM = "stream"
}

/**
 * A server address and the account used on it.
 *
 * A malformed address does not throw: [base] and [url] are null for it, and
 * `OkHttpSubsonic` answers `Unreachable`.
 *
 * @param address what somebody typed: a host, or a whole URL, with or without a
 *   scheme and with or without the path the server is mounted under
 * @param user the account's name on the server
 * @param password the account's password; requests carry a token derived from
 *   it, see [auth]
 */
data class SubsonicServer(
    val address: String,
    val user: String,
    val password: String,
) {
    /** Leaves the password out, so it does not reach a log line. */
    override fun toString(): String =
        "SubsonicServer(address=$address, user=$user, password=${if (password.isEmpty()) "none" else "…"})"

    /**
     * The address as a URL, or null when it is not one.
     *
     * A missing scheme is read as https, because the token travels in the
     * query string of every call. A server on plain http needs the `http://`
     * typed. A path is kept, because a server behind a reverse proxy is often
     * mounted under one, such as `https://home.example/music`.
     */
    val base: HttpUrl? by lazy(LazyThreadSafetyMode.PUBLICATION) { root(address) }

    /**
     * The parameters every call carries: `u`, `t`, `s`, the API version, the
     * client name and the answer format. Subsonic authenticates each request
     * on its own; there is no session.
     *
     * Each call makes a new salt. The token is md5(password + salt), so a
     * reused salt would let anybody who saw one request replay it.
     *
     * The legacy `p` parameter, which sends the password itself, is not used.
     * Token authentication exists since API 1.13.0.
     */
    fun auth(): Map<String, String> {
        val salt = salt()
        return mapOf(
            USER to user,
            TOKEN to token(password, salt),
            SALT to salt,
            "v" to Subsonic.API_VERSION,
            "c" to Subsonic.CLIENT,
            "f" to "json",
        )
    }

    /**
     * The URL of one call: the server's `rest` endpoint with [auth] and then
     * [params] on the query, each value escaped. Null when [base] is.
     *
     * Cover art URLs are made here too. The player fetches those, so they
     * carry their own token; see `SubsonicRow`.
     */
    fun url(
        endpoint: String,
        params: Map<String, String> = emptyMap(),
    ): HttpUrl? {
        val root = base ?: return null
        val url = root.newBuilder().addPathSegments("$REST/$endpoint")
        for ((name, value) in auth()) url.addQueryParameter(name, value)
        for ((name, value) in params) url.addQueryParameter(name, value)
        return url.build()
    }

    private companion object {
        /** The address trimmed of spaces and trailing slashes, with `https://` put in front when it has no scheme. */
        fun root(address: String): HttpUrl? {
            val typed = address.trim().trimEnd('/')
            if (typed.isEmpty()) return null
            val whole = if (typed.contains(SCHEME_MARK)) typed else "https://$typed"
            return whole.toHttpUrlOrNull()
        }

        /**
         * md5 of the password and the salt, as lower-case hex: Subsonic's `t`.
         * The protocol specifies md5.
         */
        fun token(
            password: String,
            salt: String,
        ): String =
            MessageDigest
                .getInstance("MD5")
                .digest((password + salt).toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }

        /** [SALT_BYTES] bytes from [SecureRandom], as hex. */
        fun salt(): String {
            val bytes = ByteArray(SALT_BYTES)
            random.nextBytes(bytes)
            return bytes.joinToString("") { byte -> "%02x".format(byte) }
        }

        val random = SecureRandom()

        /** The specification asks for a salt of six characters or more; eight bytes are sixteen. */
        const val SALT_BYTES = 8

        const val REST = "rest"
        const val USER = "u"
        const val TOKEN = "t"
        const val SALT = "s"
        const val SCHEME_MARK = "://"
    }
}
