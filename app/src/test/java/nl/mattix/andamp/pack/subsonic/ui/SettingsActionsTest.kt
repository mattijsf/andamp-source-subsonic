// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.subsonic.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import nl.mattix.andamp.pack.subsonic.FakeSubsonicHttp
import nl.mattix.andamp.pack.subsonic.Subsonic
import nl.mattix.andamp.pack.subsonic.SubsonicReply
import nl.mattix.andamp.pack.subsonic.SubsonicServer
import nl.mattix.andamp.pack.subsonic.pack.PackStore
import nl.mattix.andamp.pack.subsonic.recorded
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [SettingsActions] over the real store: what is written, when the announce fires, and how a ping's reply is read. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsActionsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: PackStore
    private var announced = 0
    private val servers = mutableListOf<SubsonicServer>()

    @Before
    fun clean() {
        store = PackStore(context)
        store.forget()
    }

    /** Actions over the real store, counting announces, with every ping answered by [reply]. */
    private fun actions(reply: SubsonicReply = SubsonicReply.Answered(JSONObject())): SettingsActions {
        val http = FakeSubsonicHttp(mapOf(Subsonic.PING to reply))
        return SettingsActions(
            context,
            store,
            announce = { announced++ },
            http = { server ->
                servers += server
                http
            },
        )
    }

    @Test
    fun `keeping writes all three to the store and announces once`() {
        actions().keep(ADDRESS, USER, PASSWORD)

        assertEquals(ADDRESS, store.address)
        assertEquals(USER, store.user)
        assertEquals(PASSWORD, store.password)
        assertTrue(store.filledIn)
        assertEquals(1, announced)
    }

    /** The announce reads the store to tell a sign-in from a sign-out, so the store is written first. */
    @Test
    fun `what is kept is on disk by the time the announce reads it`() {
        val seen = mutableListOf<Boolean>()
        SettingsActions(context, store, announce = { seen += PackStore(it).filledIn }).keep(ADDRESS, USER, PASSWORD)

        assertEquals(listOf(true), seen)
    }

    @Test
    fun `keeping with the password left empty keeps the password that was there`() {
        store.password = PASSWORD

        actions().keep(ADDRESS, USER, "")

        assertEquals(PASSWORD, store.password)
        assertEquals(1, announced)
    }

    @Test
    fun `testing with the password left empty tries the kept one`() =
        runTest {
            store.password = PASSWORD

            actions().test(ADDRESS, USER, "")

            assertEquals(PASSWORD, servers.single().password)
        }

    @Test
    fun `forgetting clears the store and announces once`() {
        store.address = ADDRESS
        store.user = USER
        store.password = PASSWORD

        actions().forget()

        assertEquals("", store.address)
        assertEquals("", store.user)
        assertEquals("", store.password)
        assertFalse(store.filledIn)
        assertEquals(1, announced)
    }

    @Test
    fun `what is kept is the address and the name, and whether there is a password`() {
        store.address = ADDRESS
        store.user = USER
        store.password = PASSWORD

        assertEquals(Kept(address = ADDRESS, user = USER, signedIn = true), actions().kept())
    }

    @Test
    fun `an address that is not one is no server, without asking anybody`() =
        runTest {
            val tried = actions().test("gopher://music.example", USER, PASSWORD)

            assertTrue(tried.toString(), tried is Tried.NoServer)
            assertTrue("no client is built for the address", servers.isEmpty())
        }

    @Test
    fun `the ping goes to the server that was typed rather than the one that is kept`() =
        runTest {
            store.address = "https://old.example"

            actions().test(ADDRESS, USER, PASSWORD)

            assertEquals(listOf(SubsonicServer(ADDRESS, USER, PASSWORD)), servers)
        }

    @Test
    fun `a server that answered the ping was reached`() =
        runTest {
            assertEquals(Tried.Reached, actions(SubsonicReply.Answered(JSONObject())).test(ADDRESS, USER, PASSWORD))
        }

    @Test
    fun `a server nobody could reach is no server, and the reason travels with it`() =
        runTest {
            val tried = actions(SubsonicReply.Unreachable("connection refused")).test(ADDRESS, USER, PASSWORD)

            assertEquals(Tried.NoServer("connection refused"), tried)
        }

    @Test
    fun `an HTTP failure is something that is not a Subsonic server`() =
        runTest {
            assertEquals(Tried.NotSubsonic(404), actions(SubsonicReply.Rejected(404)).test(ADDRESS, USER, PASSWORD))
        }

    /** Uses the recorded refusal, so the reason is a real server's. */
    @Test
    fun `a refusal in the body is a refusal, in the server's words`() =
        runTest {
            val refusal = recorded("error") as SubsonicReply.Refused

            assertEquals(Tried.Refused(refusal.why), actions(refusal).test(ADDRESS, USER, PASSWORD))
        }

    private companion object {
        const val ADDRESS = "https://music.example"
        const val USER = "listener"
        const val PASSWORD = "sesame"
    }
}
