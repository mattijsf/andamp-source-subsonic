// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.subsonic

import org.json.JSONObject

/**
 * A [SubsonicHttp] that answers from a map and records what it was asked.
 *
 * The answers in `src/test/resources` were recorded from the public Navidrome
 * demo at demo.navidrome.org, one call each, and shortened by dropping whole
 * entries.
 */
internal class FakeSubsonicHttp(
    private val replies: Map<String, SubsonicReply>,
) : SubsonicHttp {
    val asked = mutableListOf<Ask>()

    override suspend fun get(
        endpoint: String,
        params: Map<String, String>,
    ): SubsonicReply {
        asked += Ask(endpoint, params)
        return replies[endpoint] ?: SubsonicReply.Unreachable("nothing was recorded for $endpoint")
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
