// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.subsonic

import nl.mattix.andamp.core.model.AlbumKind
import nl.mattix.andamp.core.model.BrowseCapabilities
import nl.mattix.andamp.core.packapi.PackAlbum
import nl.mattix.andamp.core.packapi.PackAnswer
import nl.mattix.andamp.core.packapi.PackArtist
import nl.mattix.andamp.core.packapi.PackPlaylist
import nl.mattix.andamp.core.packapi.PackQuestion
import nl.mattix.andamp.core.packapi.UNKNOWN_COUNT
import org.json.JSONArray
import org.json.JSONObject

/**
 * The server's library, answered one [PackQuestion] at a time. Each question is
 * one call to the server; nothing is cached.
 *
 * `getAlbumList2` and `search3` take an offset and a size, so the server pages
 * those. `getArtists`, `getArtist`, `getAlbum`, `getPlaylists` and
 * `getPlaylist` answer everything at once, and the page is cut here. For a
 * server-paged call [PackAnswer.more] is true when the page came back full,
 * because the endpoints give no total.
 *
 * A call that fails in any way (no network, a bad status, or Subsonic's own
 * refusal at HTTP 200) is answered with `failed = true`, never as an empty
 * page. See [SubsonicReply].
 *
 * A count the server does not give is [UNKNOWN_COUNT].
 */
class SubsonicLibrary(
    private val http: SubsonicHttp,
    /** For the cover art URL on a row, which carries its own credential; see [SubsonicRow]. */
    private val server: SubsonicServer,
) {
    /** One page of one question. An unknown kind is a failure. */
    suspend fun answer(question: PackQuestion): PackAnswer =
        when (question.kind) {
            PackQuestion.ARTISTS -> artists(question)
            PackQuestion.ALBUMS -> albums(question)
            PackQuestion.TRACKS -> tracks(question)
            PackQuestion.PLAYLISTS -> playlists(question)
            PackQuestion.PLAYLIST_TRACKS -> playlistTracks(question)
            PackQuestion.SEARCH -> search(question)
            PackQuestion.FIND_ARTISTS -> findArtists(question)
            else -> FAILED
        }

    /**
     * Every artist. `getArtists` answers an array of index entries, one per
     * letter, each holding the artists filed under it. They are flattened in
     * the server's order, which is the server's own collation.
     */
    private suspend fun artists(question: PackQuestion): PackAnswer =
        asked(Subsonic.GET_ARTISTS) { body ->
            val letters = objects(body.optJSONObject("artists")?.optJSONArray("index"))
            val all = letters.flatMap { objects(it.optJSONArray("artist")) }.map(::artistOf)
            all.page(question) { page -> PackAnswer(artists = page.items, more = page.more) }
        }

    /** An artist's albums, or every album when the question's id is empty. */
    private suspend fun albums(question: PackQuestion): PackAnswer =
        if (question.id.isEmpty()) everyAlbum(question) else artistAlbums(question)

    /**
     * Every album, a page at a time, as `alphabeticalByName`. The other orders
     * `getAlbumList2` offers (recency, play count, random) can change between
     * two pages.
     */
    private suspend fun everyAlbum(question: PackQuestion): PackAnswer {
        val size = question.size()
        val params =
            mapOf(
                "type" to "alphabeticalByName",
                "size" to size.toString(),
                "offset" to question.from().toString(),
            )
        return asked(Subsonic.GET_ALBUM_LIST, params) { body ->
            val albums = objects(body.optJSONObject("albumList2")?.optJSONArray("album")).map(::albumOf)
            PackAnswer(albums = albums, more = albums.size >= size)
        }
    }

    /** One artist's albums, in the server's order. */
    private suspend fun artistAlbums(question: PackQuestion): PackAnswer =
        asked(Subsonic.GET_ARTIST, mapOf("id" to question.id)) { body ->
            val albums = objects(body.optJSONObject("artist")?.optJSONArray("album")).map(::albumOf)
            albums.page(question) { page -> PackAnswer(albums = page.items, more = page.more) }
        }

    /** An album's tracks, in the server's order. */
    private suspend fun tracks(question: PackQuestion): PackAnswer =
        asked(Subsonic.GET_ALBUM, mapOf("id" to question.id)) { body ->
            val songs = SubsonicRow.tracks(body.optJSONObject("album")?.optJSONArray("song"), server)
            songs.page(question) { page -> PackAnswer(tracks = page.items, more = page.more) }
        }

    /** The playlists this account can see. */
    private suspend fun playlists(question: PackQuestion): PackAnswer =
        asked(Subsonic.GET_PLAYLISTS) { body ->
            val lists = objects(body.optJSONObject("playlists")?.optJSONArray("playlist")).map(::playlistOf)
            lists.page(question) { page -> PackAnswer(playlists = page.items, more = page.more) }
        }

    /**
     * One playlist's tracks, in the playlist's order, repeats included. This
     * endpoint has its songs under `entry`.
     */
    private suspend fun playlistTracks(question: PackQuestion): PackAnswer =
        asked(Subsonic.GET_PLAYLIST, mapOf("id" to question.id)) { body ->
            val songs = SubsonicRow.tracks(body.optJSONObject("playlist")?.optJSONArray("entry"), server)
            songs.page(question) { page -> PackAnswer(tracks = page.items, more = page.more) }
        }

    /**
     * The server's search, for songs. `search3` searches artists, albums and
     * songs at once, so the other two counts are sent as 0.
     *
     * A blank query is answered empty with no call.
     */
    private suspend fun search(question: PackQuestion): PackAnswer {
        val terms = question.query.trim()
        if (terms.isEmpty()) return PackAnswer()
        val size = question.size()
        val params =
            mapOf(
                "query" to terms,
                "songOffset" to question.from().toString(),
                "songCount" to size.toString(),
                "artistCount" to NONE,
                "albumCount" to NONE,
            )
        return asked(Subsonic.SEARCH, params) { body ->
            val songs = SubsonicRow.tracks(body.optJSONObject("searchResult3")?.optJSONArray("song"), server)
            PackAnswer(tracks = songs, more = songs.size >= size)
        }
    }

    /** The server's search, for artists; the matching is the server's. A blank query is answered empty with no call. */
    private suspend fun findArtists(question: PackQuestion): PackAnswer {
        val terms = question.query.trim()
        if (terms.isEmpty()) return PackAnswer()
        val size = question.size()
        val params =
            mapOf(
                "query" to terms,
                "artistOffset" to question.from().toString(),
                "artistCount" to size.toString(),
                "songCount" to NONE,
                "albumCount" to NONE,
            )
        return asked(Subsonic.SEARCH, params) { body ->
            val found = objects(body.optJSONObject("searchResult3")?.optJSONArray("artist")).map(::artistOf)
            PackAnswer(artists = found, more = found.size >= size)
        }
    }

    /** One call. Any reply other than [SubsonicReply.Answered] is `failed`. */
    private suspend fun asked(
        endpoint: String,
        params: Map<String, String> = emptyMap(),
        read: (JSONObject) -> PackAnswer,
    ): PackAnswer =
        when (val reply = http.get(endpoint, params)) {
            is SubsonicReply.Answered -> read(reply.body)
            else -> FAILED
        }

    private fun artistOf(json: JSONObject): PackArtist =
        PackArtist(
            id = json.optString("id"),
            name = json.optString("name"),
            albumCount = count(json, "albumCount"),
            trackCount = count(json, "songCount"),
        )

    private fun albumOf(json: JSONObject): PackAlbum =
        PackAlbum(
            id = json.optString("id"),
            // an album's title is under `name`
            title = json.optString("name"),
            artist = json.optString("artist"),
            year = count(json, "year"),
            trackCount = count(json, "songCount"),
            kind = if (json.optBoolean("isCompilation")) AlbumKind.COMPILATION.name else AlbumKind.ALBUM.name,
        )

    private fun playlistOf(json: JSONObject): PackPlaylist =
        PackPlaylist(
            id = json.optString("id"),
            name = json.optString("name"),
            trackCount = count(json, "songCount"),
            owner = json.optString("owner").takeIf { it.isNotEmpty() },
        )

    companion object {
        /**
         * What this library can be asked, for `PackIdentity.descriptor`. A
         * constant, because the source describes itself before a server is set
         * up and a library exists.
         *
         * `hasCatalogue` is false: a Subsonic server holds only the listener's
         * own library.
         */
        val SHELVES =
            BrowseCapabilities(
                hasArtists = true,
                hasAlbums = true,
                canSearch = true,
                hasPlaylists = true,
                hasCatalogue = false,
            )

        private val FAILED = PackAnswer(failed = true)

        /** The count sent for the kinds a search is not about. */
        private const val NONE = "0"
    }
}

/**
 * A count the server gave, or [UNKNOWN_COUNT] when the key is absent. A count
 * of 0 that the server sent is kept as 0.
 */
private fun count(
    json: JSONObject,
    name: String,
): Int = if (json.has(name)) json.optInt(name, UNKNOWN_COUNT) else UNKNOWN_COUNT

/** The objects in an array, skipping anything that is not one; empty for no array. */
private fun objects(array: JSONArray?): List<JSONObject> =
    if (array == null) emptyList() else (0 until array.length()).mapNotNull(array::optJSONObject)

/** Where this question starts; a negative offset is read as 0. */
private fun PackQuestion.from(): Int = offset.coerceAtLeast(0)

/**
 * The page size for a server-paged call. A limit of 0 or less means "as much
 * as there is", which `size=0` cannot ask for, so it becomes
 * [PackQuestion.PAGE].
 */
private fun PackQuestion.size(): Int = if (limit <= 0) PackQuestion.PAGE else limit

/**
 * The slice this question asked for, out of a list the server answered whole.
 * An offset past the end gives an empty page.
 */
private fun <T> List<T>.page(
    question: PackQuestion,
    into: (Page<T>) -> PackAnswer,
): PackAnswer {
    val from = question.from().coerceAtMost(size)
    val to = if (question.limit <= 0) size else (from + question.limit).coerceAtMost(size)
    return into(Page(subList(from, to).toList(), more = to < size))
}

private class Page<T>(
    val items: List<T>,
    val more: Boolean,
)
