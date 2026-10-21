// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.subsonic

import org.json.JSONObject

/**
 * A [SubsonicHttp] that answers from what the test gives it and records what
 * it was asked.
 *
 * The answers in `src/test/resources` were recorded from the public Navidrome
 * demo at demo.navidrome.org, one call each, and shortened by dropping whole
 * entries. `getGenres`, `getSongsByGenre`, `search3-walk` and `search3-empty`
 * are written by hand in the same shape.
 */
internal class FakeSubsonicHttp(
    /** The reply to one call, or null when nothing was recorded for it. */
    private val reply: (Ask) -> SubsonicReply?,
) : SubsonicHttp {
    /** Answers each endpoint with one reply, whatever the call's parameters. */
    constructor(replies: Map<String, SubsonicReply>) : this({ ask -> replies[ask.endpoint] })

    val asked = mutableListOf<Ask>()

    override suspend fun get(
        endpoint: String,
        params: Map<String, String>,
    ): SubsonicReply {
        val ask = Ask(endpoint, params)
        asked += ask
        return reply(ask) ?: SubsonicReply.Unreachable("nothing was recorded for $endpoint")
    }

    /** The one call this question made; it fails the test if there was more than one. */
    fun once(): Ask = asked.single()

    data class Ask(
        val endpoint: String,
        val params: Map<String, String>,
    )
}

/** A recorded body as a reply, read with [SubsonicReply.read]. */
internal fun recorded(name: String): SubsonicReply = SubsonicReply.read(fixture(name))

/** A recorded body, verbatim. */
internal fun fixture(name: String): String =
    checkNotNull(FakeSubsonicHttp::class.java.getResourceAsStream("/$name.json")) { "no recording called $name" }
        .use { stream -> stream.readBytes().decodeToString() }

/** The `subsonic-response` object of a recording, for the readers that take one. */
internal fun body(name: String): JSONObject = (recorded(name) as SubsonicReply.Answered).body
