// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.subsonic

import kotlinx.coroutines.test.runTest
import nl.mattix.andamp.core.model.AlbumKind
import nl.mattix.andamp.core.packapi.PackAnswer
import nl.mattix.andamp.core.packapi.PackQuestion
import nl.mattix.andamp.core.packapi.UNKNOWN_COUNT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every question the browse contract asks, answered from recordings; see [FakeSubsonicHttp]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SubsonicLibraryTest {
    @Test
    fun `every artist comes out of the alphabet the server files them under`() =
        runTest {
            val answer = library("getArtists").answer(PackQuestion(PackQuestion.ARTISTS))

            // three index letters in the recording, flattened in the server's order
            assertEquals(
                listOf(
                    "2 Mello",
                    "Back On Earth",
                    "Binaerpilot",
                    "Brad Sucks",
                    "Brock Berrigan",
                    "Carter Vail",
                    "Chillhop Music",
                    "CrumbSnatchers",
                ),
                answer.artists.map { it.name },
            )
            assertFalse(answer.failed)
        }

    @Test
    fun `an artist says how many records the server has and never guesses at a track count`() =
        runTest {
            val answer = library("getArtists").answer(PackQuestion(PackQuestion.ARTISTS))

            val first = answer.artists.first()
            assertEquals("33X3V2Xn7Cip7WqqfXNkL8", first.id)
            assertEquals(1, first.albumCount)
            // the recording has no `songCount` on an artist
            assertEquals(UNKNOWN_COUNT, first.trackCount)
        }

    @Test
    fun `a page with more behind it says so, and the last one does not`() =
        runTest {
            val library = library("getArtists")

            val first = library.answer(PackQuestion(PackQuestion.ARTISTS, offset = 0, limit = 3))
            val last = library.answer(PackQuestion(PackQuestion.ARTISTS, offset = 6, limit = 3))

            assertEquals(3, first.artists.size)
            assertTrue(first.more)
            assertEquals(listOf("Chillhop Music", "CrumbSnatchers"), last.artists.map { it.name })
            assertFalse(last.more)
        }

    @Test
    fun `an offset past the end is an empty page and not a failure`() =
        runTest {
            val answer = library("getArtists").answer(PackQuestion(PackQuestion.ARTISTS, offset = 400, limit = 50))

            assertEquals(emptyList<Any>(), answer.artists)
            assertFalse(answer.more)
            assertFalse(answer.failed)
        }

    @Test
    fun `the catalog is asked for a page at a time, in name order`() =
        runTest {
            val http = FakeSubsonicHttp(mapOf("getAlbumList2" to recorded("getAlbumList2")))

            val answer = SubsonicLibrary(http, SERVER).answer(PackQuestion(PackQuestion.ALBUMS, offset = 20, limit = 10))

            assertEquals("getAlbumList2", http.once().endpoint)
            assertEquals(
                mapOf("type" to "alphabeticalByName", "size" to "10", "offset" to "20"),
                http.once().params,
            )
            assertEquals("8-bit lagerfeuer", answer.albums.first().title)
            assertEquals("pornophonique", answer.albums.first().artist)
            assertEquals(2007, answer.albums.first().year)
            assertEquals(8, answer.albums.first().trackCount)
            assertEquals(AlbumKind.ALBUM.name, answer.albums.first().kind)
        }

    @Test
    fun `a full page from a server that pages is taken as there being more`() =
        runTest {
            val library = library("getAlbumList2")

            val full = library.answer(PackQuestion(PackQuestion.ALBUMS, limit = 10))
            val room = library.answer(PackQuestion(PackQuestion.ALBUMS, limit = 25))

            // ten asked for and ten answered: the endpoint gives no total
            assertEquals(10, full.albums.size)
            assertTrue(full.more)
            // twenty-five asked for and ten answered
            assertFalse(room.more)
        }

    @Test
    fun `a named artist's records come from the artist rather than from the catalog`() =
        runTest {
            val http = FakeSubsonicHttp(mapOf("getArtist" to recorded("getArtist")))

            val question = PackQuestion(PackQuestion.ALBUMS, id = "24jpYAI4N8TG0cCAYyzZk5")
            val answer = SubsonicLibrary(http, SERVER).answer(question)

            assertEquals("getArtist", http.once().endpoint)
            assertEquals(mapOf("id" to "24jpYAI4N8TG0cCAYyzZk5"), http.once().params)
            assertEquals(listOf("8-bit lagerfeuer", "the procacci remixes, vol. I"), answer.albums.map { it.title })
            // the endpoint answers everything at once
            assertFalse(answer.more)
        }

    @Test
    fun `an album's tracks keep the server's order and carry addresses`() =
        runTest {
            val http = FakeSubsonicHttp(mapOf("getAlbum" to recorded("getAlbum")))

            val question = PackQuestion(PackQuestion.TRACKS, id = "2cahnhu6UPen2wbYDm4CHK")
            val answer = SubsonicLibrary(http, SERVER).answer(question)

            assertEquals("getAlbum", http.once().endpoint)
            assertEquals(mapOf("id" to "2cahnhu6UPen2wbYDm4CHK"), http.once().params)
            assertEquals(4, answer.tracks.size)
            assertEquals("sad robot", answer.tracks.first().title)
            assertEquals("subsonic:track:Kbh4QvrgGcPgsjdK8cd448", answer.tracks.first().id)
            assertEquals("space invaders", answer.tracks.last().title)
        }

    @Test
    fun `the playlists say who owns them and how long they are`() =
        runTest {
            val answer = library("getPlaylists").answer(PackQuestion(PackQuestion.PLAYLISTS))

            assertEquals(6, answer.playlists.size)
            val shuffled = answer.playlists.first { it.name == "ShuffleOrderTest" }
            assertEquals("cGsh1UvqLdB3FcaWhLqHG8", shuffled.id)
            assertEquals(5, shuffled.trackCount)
            assertEquals("demo", shuffled.owner)
            // a count of 0 from the server stays 0
            assertEquals(0, answer.playlists.first { it.name == "asdf" }.trackCount)
        }

    @Test
    fun `a playlist's tracks come out of its entries`() =
        runTest {
            val http = FakeSubsonicHttp(mapOf("getPlaylist" to recorded("getPlaylist")))

            val question = PackQuestion(PackQuestion.PLAYLIST_TRACKS, id = "3gYtg8NQqOlS5yJvloApOU")
            val answer = SubsonicLibrary(http, SERVER).answer(question)

            // the songs are under `entry` on this endpoint
            assertEquals("getPlaylist", http.once().endpoint)
            assertEquals(listOf("Pleasant Melody"), answer.tracks.map { it.title })
            assertEquals("subsonic:track:xT1cvVP3cC6zeQIkBk3xlw", answer.tracks.first().id)
        }

    @Test
    fun `a search asks the server for songs and for nothing else`() =
        runTest {
            val http = FakeSubsonicHttp(mapOf("search3" to recorded("search3-songs")))

            val question = PackQuestion(PackQuestion.SEARCH, query = " love ", offset = 0, limit = 5)
            val answer = SubsonicLibrary(http, SERVER).answer(question)

            assertEquals("search3", http.once().endpoint)
            assertEquals(
                mapOf(
                    "query" to "love",
                    "songOffset" to "0",
                    "songCount" to "5",
                    "artistCount" to "0",
                    "albumCount" to "0",
                ),
                http.once().params,
            )
            assertEquals(5, answer.tracks.size)
            assertEquals("Can I Have Your Love Tonight", answer.tracks.first().title)
            assertTrue(answer.more)
        }

    @Test
    fun `a search for artists asks the other side of the same endpoint`() =
        runTest {
            val http = FakeSubsonicHttp(mapOf("search3" to recorded("search3-artists")))

            val question = PackQuestion(PackQuestion.FIND_ARTISTS, query = "the", offset = 0, limit = 5)
            val answer = SubsonicLibrary(http, SERVER).answer(question)

            assertEquals(
                mapOf(
                    "query" to "the",
                    "artistOffset" to "0",
                    "artistCount" to "5",
                    "songCount" to "0",
                    "albumCount" to "0",
                ),
                http.once().params,
            )
            // the matching is the server's
            assertEquals(
                listOf("The Cancel", "The Fakers", "The Grits", "The Knife", "Forget the Whale"),
                answer.artists.map { it.name },
            )
            assertTrue(answer.more)
            // a count of 0 from the server stays 0
            assertEquals(0, answer.artists.first().albumCount)
        }

    @Test
    fun `an empty search asks the server nothing`() =
        runTest {
            val http = FakeSubsonicHttp(mapOf("search3" to recorded("search3-songs")))

            val answer = SubsonicLibrary(http, SERVER).answer(PackQuestion(PackQuestion.SEARCH, query = "   "))

            assertEquals(emptyList<Any>(), http.asked)
            assertEquals(PackAnswer(), answer)
        }

    @Test
    fun `the server's own refusal is a failure and never an empty shelf`() =
        runTest {
            val answer = library("error" to "getArtists").answer(PackQuestion(PackQuestion.ARTISTS))

            // the recording is an HTTP 200 whose body says the password is wrong
            assertTrue(answer.failed)
            assertEquals(emptyList<Any>(), answer.artists)
            assertFalse(answer.more)
        }

    @Test
    fun `a status that is not success is a failure too`() =
        runTest {
            val http = FakeSubsonicHttp(mapOf("getAlbum" to SubsonicReply.Rejected(500)))

            val answer = SubsonicLibrary(http, SERVER).answer(PackQuestion(PackQuestion.TRACKS, id = "abc"))

            assertTrue(answer.failed)
            assertEquals(emptyList<Any>(), answer.tracks)
        }

    @Test
    fun `a server that cannot be reached is a failure too`() =
        runTest {
            val http = FakeSubsonicHttp(mapOf("getPlaylists" to SubsonicReply.Unreachable("no route to host")))

            val answer = SubsonicLibrary(http, SERVER).answer(PackQuestion(PackQuestion.PLAYLISTS))

            assertTrue(answer.failed)
            assertEquals(emptyList<Any>(), answer.playlists)
        }

    @Test
    fun `a question this source has never heard of fails rather than answering nothing`() =
        runTest {
            val answer = library("getArtists").answer(PackQuestion("sonnets"))

            assertTrue(answer.failed)
        }

    @Test
    fun `a row from a search carries the cover the player will draw`() =
        runTest {
            val question = PackQuestion(PackQuestion.SEARCH, query = "love", limit = 5)
            val answer = library("search3-songs" to "search3").answer(question)

            val art = checkNotNull(answer.tracks.first().artworkUri)
            assertTrue(art, art.startsWith("https://demo.navidrome.org/rest/getCoverArt?"))
            assertFalse(art, art.contains(PASSWORD))
            // the file's name, which SubsonicStream reads the suffix from
            assertEquals("01 - Can I Have Your Love Tonight.mp3", answer.tracks.first().defaultName)
        }

    /** A library whose only recording is [named], filed under the endpoint it was recorded from. */
    private fun library(named: String): SubsonicLibrary = library(named to named)

    /** A library that answers the endpoint `served.second` with the recording `served.first`. */
    private fun library(served: Pair<String, String>): SubsonicLibrary =
        SubsonicLibrary(FakeSubsonicHttp(mapOf(served.second to recorded(served.first))), SERVER)

    private companion object {
        const val PASSWORD = "correct-horse-battery-staple"
        val SERVER = SubsonicServer("https://demo.navidrome.org", "demo", PASSWORD)
    }
}
