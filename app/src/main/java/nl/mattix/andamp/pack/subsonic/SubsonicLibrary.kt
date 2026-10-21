// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.subsonic

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.mattix.andamp.core.model.AlbumKind
import nl.mattix.andamp.core.model.BrowseCapabilities
import nl.mattix.andamp.core.packapi.PackAlbum
import nl.mattix.andamp.core.packapi.PackAnswer
import nl.mattix.andamp.core.packapi.PackArtist
import nl.mattix.andamp.core.packapi.PackPlaylist
import nl.mattix.andamp.core.packapi.PackQuestion
import nl.mattix.andamp.core.packapi.PackTrack
import nl.mattix.andamp.core.packapi.UNKNOWN_COUNT
import nl.mattix.andamp.core.packapi.toPack
import nl.mattix.andamp.pack.common.TrackFolders
import org.json.JSONArray
import org.json.JSONObject

/**
 * The server's library, answered one [PackQuestion] at a time. Each question is
 * one call to the server, except the folders.
 *
 * `getAlbumList2`, `search3` and `getSongsByGenre` take an offset and a size,
 * so the server pages those. `getArtists`, `getArtist`, `getAlbum`,
 * `getPlaylists`, `getPlaylist` and `getGenres` answer everything at once, and
 * the page is cut here. For a server-paged call [PackAnswer.more] is true when
 * the page came back full, because the endpoints give no total.
 *
 * The folders come from the `path` of every song, so listing them walks the
 * whole library with `search3`, many calls for a large one. The last walk is
 * kept in memory, and it is the one thing kept: later pages of the folders and
 * the songs in a folder are answered from it. See [folders].
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
    /** The last walk that finished, or null before one has. Written while holding [walking]. */
    @Volatile
    private var walked: Walk? = null

    /** Held for a walk, so two binder threads asking about folders at once do not walk side by side. */
    private val walking = Mutex()

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
            PackQuestion.FOLDERS -> folders(question)
            PackQuestion.FOLDER_TRACKS -> folderTracks(question)
            PackQuestion.GENRES -> genres(question)
            PackQuestion.GENRE_TRACKS -> genreTracks(question)
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

    /**
     * The folders that hold songs, by name without case. A folder's id and name
     * are its path in the server's music folder, such as `Artist/Album`.
     *
     * A question at offset 0 walks the library again, so the list follows the
     * server. A later page is cut from the walk kept by then, and walks first
     * when none is kept.
     */
    private suspend fun folders(question: PackQuestion): PackAnswer {
        val walk = walk(again = question.from() == 0) ?: return FAILED
        return walk.groups.page(question) { page -> PackAnswer(playlists = page.items, more = page.more) }
    }

    /**
     * The songs directly in the folder whose path is the question's id, in disc
     * and track order. They come from the kept walk, after a walk when none is
     * kept, as when this process started after the player listed the folders.
     * A folder the walk did not find is answered empty.
     */
    private suspend fun folderTracks(question: PackQuestion): PackAnswer {
        val walk = walk(again = false) ?: return FAILED
        val rows = walk.folders.trackIds(question.id).mapNotNull(walk.rows::get)
        return rows.page(question) { page -> PackAnswer(tracks = page.items, more = page.more) }
    }

    /**
     * The kept walk, or a new one when [again] is true or none is kept. A walk
     * that ended while this question waited for [walking] is taken as it is, so
     * questions that arrive together walk once. Null when the new walk fails,
     * which leaves the kept one in place.
     */
    private suspend fun walk(again: Boolean): Walk? {
        val before = walked
        return walking.withLock {
            val now = walked
            if (now != null && (!again || now !== before)) now else walkEverySong()?.also { walked = it }
        }
    }

    /**
     * Every song, from `search3` with an empty query, [SONGS_AT_ONCE] at a time
     * until a page comes back short. A full page of songs that were all seen
     * before also ends it, so a server that ignores the offset is not asked
     * forever. Null when any page fails.
     *
     * The songs are put in disc, track and path order before they are grouped,
     * and [TrackFolders] keeps that order inside each folder. A song's `path` is
     * relative to the server's music folder, so the root is `""` and a folder
     * is named by its whole path.
     */
    private suspend fun walkEverySong(): Walk? {
        val songs = LinkedHashMap<String, Walked>()
        var offset = 0
        do {
            val params =
                mapOf(
                    "query" to "",
                    "songOffset" to offset.toString(),
                    "songCount" to SONGS_AT_ONCE.toString(),
                    "artistCount" to NONE,
                    "albumCount" to NONE,
                )
            val reply = http.get(Subsonic.SEARCH, params) as? SubsonicReply.Answered ?: return null
            val page = objects(reply.body.optJSONObject("searchResult3")?.optJSONArray("song"))
            val known = songs.size
            for (song in page) walkedOf(song)?.let { songs.putIfAbsent(it.row.id, it) }
            offset += page.size
        } while (page.size >= SONGS_AT_ONCE && songs.size > known)
        val ordered = songs.values.sortedWith(compareBy({ it.disc }, { it.number }, { it.path }))
        return Walk(
            folders = TrackFolders.of(ordered.map { it.row.id to it.path }, roots = listOf("")),
            rows = ordered.associate { it.row.id to it.row },
        )
    }

    /** One song as the walk reads it, or null when it has no id. */
    private fun walkedOf(song: JSONObject): Walked? {
        val row = SubsonicRow.track(song, server) ?: return null
        return Walked(row, song.optString("path"), song.optInt("discNumber"), song.optInt("track"))
    }

    /**
     * The genres songs are tagged with, by name without case. `getGenres` puts
     * a genre's name under `value`; a genre with a blank name is left out. A
     * genre's id is its name, which is what `getSongsByGenre` takes.
     */
    private suspend fun genres(question: PackQuestion): PackAnswer =
        asked(Subsonic.GET_GENRES) { body ->
            val genres =
                objects(body.optJSONObject("genres")?.optJSONArray("genre"))
                    .mapNotNull(::genreOf)
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            genres.page(question) { page -> PackAnswer(playlists = page.items, more = page.more) }
        }

    /**
     * The songs tagged with the genre the question names, a page at a time, in
     * the server's order. The server answers at most [SONGS_AT_ONCE], so a
     * larger page is asked for as that many, and a full one still says there is
     * more.
     */
    private suspend fun genreTracks(question: PackQuestion): PackAnswer {
        val size = question.size().coerceAtMost(SONGS_AT_ONCE)
        val params =
            mapOf(
                "genre" to question.id,
                "count" to size.toString(),
                "offset" to question.from().toString(),
            )
        return asked(Subsonic.GET_SONGS_BY_GENRE, params) { body ->
            val songs = SubsonicRow.tracks(body.optJSONObject("songsByGenre")?.optJSONArray("song"), server)
            PackAnswer(tracks = songs, more = songs.size >= size)
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

    /** A genre as a list with no owner, or null when its name is blank. */
    private fun genreOf(json: JSONObject): PackPlaylist? {
        val name = json.optString("value").takeIf { it.isNotBlank() } ?: return null
        return PackPlaylist(id = name, name = name, trackCount = count(json, "songCount"))
    }

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

        /**
         * The most songs asked for in one call: `getSongsByGenre` answers at
         * most 500, and the walk asks `search3` for as many.
         */
        private const val SONGS_AT_ONCE = 500
    }
}

/** One walk through every song: the folders, and each song's row by its id. */
private class Walk(
    val folders: TrackFolders,
    val rows: Map<String, PackTrack>,
) {
    /** The folders as they cross to the player. */
    val groups: List<PackPlaylist> = folders.groups().map { it.toPack() }
}

/** One song as a walk reads it: its row, its file's path, and its disc and track numbers, 0 when the server gives none. */
private class Walked(
    val row: PackTrack,
    val path: String,
    val disc: Int,
    val number: Int,
)

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
