// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.subsonic

import kotlinx.coroutines.test.runTest
import nl.mattix.andamp.core.packapi.PackQuestion
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The folders, found by walking every song with `search3`. The walk here is two
 * pages: [boxSet], a full page made in the test, and `search3-walk`, a short
 * one; see [FakeSubsonicHttp].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SubsonicFoldersTest {
    @Test
    fun `the folders come from walking every song, named by their paths and counting their songs`() =
        runTest {
            val http = walking()

            val answer = SubsonicLibrary(http, SERVER).answer(PackQuestion(PackQuestion.FOLDERS))

            assertEquals(listOf("search3", "search3"), http.asked.map { it.endpoint })
            assertEquals(listOf(walkPage(0), walkPage(500)), http.asked.map { it.params })
            assertEquals(
                listOf(
                    "Brock Berrigan/Point Pleasant",
                    "Konstentyn/Can I Have Your Love Tonight",
                    "pornophonique/8-bit lagerfeuer",
                    "Various Artists/Box Set",
                ),
                answer.playlists.map { it.name },
            )
            assertEquals(answer.playlists.map { it.name }, answer.playlists.map { it.id })
            // the box set has 500 songs on the first page and one on the second; the song
            // with no path is in no folder
            assertEquals(listOf(1, 1, 4, 501), answer.playlists.map { it.trackCount })
            assertEquals(listOf<String?>(null), answer.playlists.map { it.owner }.distinct())
            assertFalse(answer.more)
            assertFalse(answer.failed)
        }

    @Test
    fun `a later page of folders is cut from the walk, and the first page walks again`() =
        runTest {
            val http = walking()
            val library = SubsonicLibrary(http, SERVER)

            val first = library.answer(PackQuestion(PackQuestion.FOLDERS, offset = 0, limit = 3))
            val last = library.answer(PackQuestion(PackQuestion.FOLDERS, offset = 3, limit = 3))

            assertEquals(2, http.asked.size)
            assertTrue(first.more)
            assertEquals(listOf("Various Artists/Box Set"), last.playlists.map { it.name })
            assertFalse(last.more)

            library.answer(PackQuestion(PackQuestion.FOLDERS, offset = 0, limit = 3))

            assertEquals(4, http.asked.size)
        }

    @Test
    fun `a folder's songs come from the kept walk, in track order, with no call`() =
        runTest {
            val http = walking()
            val library = SubsonicLibrary(http, SERVER)
            library.answer(PackQuestion(PackQuestion.FOLDERS))

            val answer = library.answer(PackQuestion(PackQuestion.FOLDER_TRACKS, id = "pornophonique/8-bit lagerfeuer"))

            assertEquals(2, http.asked.size)
            // `search3-walk` gives them as 4, 1, 3, 2
            assertEquals(
                listOf(
                    "sad robot",
                    "take me to the bonus level because i need an extra life",
                    "lemmings in love",
                    "space invaders",
                ),
                answer.tracks.map { it.title },
            )
            assertEquals("subsonic:track:Kbh4QvrgGcPgsjdK8cd448", answer.tracks.first().id)
            assertFalse(answer.more)
        }

    @Test
    fun `a folder's songs go disc by disc across both pages of the walk`() =
        runTest {
            val library = SubsonicLibrary(walking(), SERVER)
            library.answer(PackQuestion(PackQuestion.FOLDERS))

            val first = library.answer(PackQuestion(PackQuestion.FOLDER_TRACKS, id = BOX_SET, offset = 0, limit = 2))
            val turn = library.answer(PackQuestion(PackQuestion.FOLDER_TRACKS, id = BOX_SET, offset = 249, limit = 2))
            val last = library.answer(PackQuestion(PackQuestion.FOLDER_TRACKS, id = BOX_SET, offset = 499, limit = 2))

            // the first page gives the box set last song first
            assertEquals(listOf("1-1", "1-2"), first.tracks.map { it.title })
            assertTrue(first.more)
            assertEquals(listOf("1-250", "2-1"), turn.tracks.map { it.title })
            // the bonus disc is on the second page
            assertEquals(listOf("2-250", "Bonus Track"), last.tracks.map { it.title })
            assertFalse(last.more)
        }

    @Test
    fun `a folder asked for with nothing kept walks first`() =
        runTest {
            val http = walking()
            val library = SubsonicLibrary(http, SERVER)

            val answer = library.answer(PackQuestion(PackQuestion.FOLDER_TRACKS, id = "Brock Berrigan/Point Pleasant"))
            library.answer(PackQuestion(PackQuestion.FOLDER_TRACKS, id = "Konstentyn/Can I Have Your Love Tonight"))

            assertEquals(listOf(walkPage(0), walkPage(500)), http.asked.map { it.params })
            assertEquals(listOf("So In Love"), answer.tracks.map { it.title })
        }

    @Test
    fun `a page of the walk that fails is a failure and never fewer folders`() =
        runTest {
            val http =
                FakeSubsonicHttp { ask ->
                    if (ask.params["songOffset"] == "0") boxSet() else SubsonicReply.Unreachable("connection reset")
                }

            val answer = SubsonicLibrary(http, SERVER).answer(PackQuestion(PackQuestion.FOLDERS))

            assertEquals(2, http.asked.size)
            assertTrue(answer.failed)
            assertEquals(emptyList<Any>(), answer.playlists)
        }

    @Test
    fun `a server with no songs has no folders, which is not a failure`() =
        runTest {
            val http = FakeSubsonicHttp(mapOf("search3" to recorded("search3-empty")))

            val answer = SubsonicLibrary(http, SERVER).answer(PackQuestion(PackQuestion.FOLDERS))

            assertEquals(walkPage(0), http.once().params)
            assertEquals(emptyList<Any>(), answer.playlists)
            assertFalse(answer.more)
            assertFalse(answer.failed)
        }

    /** A server whose walk is [boxSet] and then `search3-walk`. */
    private fun walking(): FakeSubsonicHttp =
        FakeSubsonicHttp { ask ->
            when (ask.params["songOffset"]) {
                "0" -> boxSet()
                "500" -> recorded("search3-walk")
                else -> null
            }
        }

    /** What one page of the walk asks `search3` for. */
    private fun walkPage(offset: Int): Map<String, String> =
        mapOf(
            "query" to "",
            "songOffset" to offset.toString(),
            "songCount" to "500",
            "artistCount" to "0",
            "albumCount" to "0",
        )

    /**
     * A full first page of the walk: 500 songs of one box set, too many to keep
     * as a recording. Two discs of 250, given last song first, each titled
     * `disc-track`.
     */
    private fun boxSet(): SubsonicReply {
        val songs = JSONArray()
        for (number in FULL downTo 1) {
            val disc = (number - 1) / DISC + 1
            val track = (number - 1) % DISC + 1
            songs.put(
                JSONObject()
                    .put("id", "box-$number")
                    .put("title", "$disc-$track")
                    .put("discNumber", disc)
                    .put("track", track)
                    .put("suffix", "flac")
                    .put("path", "$BOX_SET/$disc-$track.flac"),
            )
        }
        return SubsonicReply.Answered(JSONObject().put("status", "ok").put("searchResult3", JSONObject().put("song", songs)))
    }

    private companion object {
        val SERVER = SubsonicServer("https://demo.navidrome.org", "demo", "correct-horse-battery-staple")
        const val BOX_SET = "Various Artists/Box Set"
        const val FULL = 500
        const val DISC = 250
    }
}
