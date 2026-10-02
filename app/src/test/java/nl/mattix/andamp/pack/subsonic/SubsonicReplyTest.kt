// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.subsonic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [SubsonicReply.read] on recorded and hand-written bodies.
 *
 * `error.json` is the demo server's answer to a call with a wrong token: HTTP
 * 200, with a body saying the username or password is wrong.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SubsonicReplyTest {
    @Test
    fun `a server that refuses at HTTP 200 is read as a refusal`() {
        val reply = recorded("error")

        assertTrue(reply.toString(), reply is SubsonicReply.Refused)
        assertEquals(40, (reply as SubsonicReply.Refused).code)
        assertEquals("Wrong username or password", reply.why)
    }

    @Test
    fun `an answer arrives without its envelope`() {
        val reply = recorded("getArtists")

        assertTrue(reply.toString(), reply is SubsonicReply.Answered)
        // the body is the inside of the `subsonic-response` wrapper
        assertTrue((reply as SubsonicReply.Answered).body.has("artists"))
        assertEquals("ok", reply.body.optString("status"))
    }

    @Test
    fun `a body that is not a Subsonic response is nobody answering`() {
        val portal = SubsonicReply.read("<html><body>Sign in to use this network</body></html>")

        // what a captive portal answers for every URL
        assertTrue(portal.toString(), portal is SubsonicReply.Unreachable)
        assertTrue(SubsonicReply.read("") is SubsonicReply.Unreachable)
        assertTrue(SubsonicReply.read("""{"something": "else"}""") is SubsonicReply.Unreachable)
    }

    @Test
    fun `a refusal with no error object is still a refusal`() {
        val bare = SubsonicReply.read("""{"subsonic-response": {"status": "failed", "version": "1.16.1"}}""")

        assertTrue(bare.toString(), bare is SubsonicReply.Refused)
        assertEquals(0, (bare as SubsonicReply.Refused).code)
    }
}
