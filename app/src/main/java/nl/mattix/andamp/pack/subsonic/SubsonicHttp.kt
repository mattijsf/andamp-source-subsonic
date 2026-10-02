// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.subsonic

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * One request to a server. An interface, so the library and the settings
 * actions are tested with no network.
 */
interface SubsonicHttp {
    /** `GET <server>/rest/<endpoint>` with the credential and [params] on the query. */
    suspend fun get(
        endpoint: String,
        params: Map<String, String> = emptyMap(),
    ): SubsonicReply
}

/**
 * What came back: an answer, or one of three kinds of failure.
 *
 * Subsonic answers HTTP 200 for its own failures: a wrong password arrives as a
 * successful response whose body says `"status": "failed"`. [read] tells that
 * apart from an answer, so a refusal is not read as an empty list.
 */
sealed interface SubsonicReply {
    /** The `subsonic-response` object of a call the server answered. */
    data class Answered(
        val body: JSONObject,
    ) : SubsonicReply

    /**
     * Nothing readable came back: a name that does not resolve, a refused
     * connection, a timeout, or a body that is not a Subsonic response, such
     * as a captive portal's login page.
     */
    data class Unreachable(
        val why: String,
    ) : SubsonicReply

    /** An HTTP status that is not success. */
    data class Rejected(
        val status: Int,
    ) : SubsonicReply

    /**
     * The server refused in the body, at HTTP 200. [code] is Subsonic's own:
     * 40 is a wrong username or password, 50 is not allowed, 70 is not found.
     */
    data class Refused(
        val code: Int,
        val why: String,
    ) : SubsonicReply

    companion object {
        /**
         * A response body as [Answered], [Refused] or [Unreachable].
         *
         * A body whose status is `failed`, or that carries an `error` object,
         * is a refusal.
         */
        fun read(body: String): SubsonicReply {
            val response =
                runCatching { JSONObject(body).optJSONObject(ENVELOPE) }.getOrNull()
                    ?: return Unreachable("the answer was not a Subsonic response")
            val error = response.optJSONObject("error")
            if (error == null && response.optString("status") != FAILED) return Answered(response)
            return Refused(
                code = error?.optInt("code") ?: 0,
                why = error?.optString("message").orEmpty(),
            )
        }

        private const val ENVELOPE = "subsonic-response"
        private const val FAILED = "failed"
    }
}

/**
 * [SubsonicHttp] over OkHttp.
 *
 * The call is enqueued, so no thread is held while the server answers, and
 * canceling the coroutine cancels the call. An `IOException` becomes
 * [SubsonicReply.Unreachable]; nothing here throws.
 */
class OkHttpSubsonic(
    private val server: SubsonicServer,
    private val client: OkHttpClient = SubsonicClient.shared,
) : SubsonicHttp {
    override suspend fun get(
        endpoint: String,
        params: Map<String, String>,
    ): SubsonicReply {
        val url = server.url(endpoint, params) ?: return SubsonicReply.Unreachable("no server address")
        return fetched(url)
    }

    private suspend fun fetched(url: HttpUrl): SubsonicReply =
        suspendCancellableCoroutine { waiting ->
            val call = client.newCall(Request.Builder().url(url).build())
            waiting.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        waiting.resume(SubsonicReply.Unreachable(e.message ?: e.javaClass.simpleName))
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        waiting.resume(response.use(::reply))
                    }
                },
            )
        }

    /**
     * One response as a reply. The status is checked before the body, so an
     * error page from a proxy is [SubsonicReply.Rejected]. A body that cannot
     * be read to the end is [SubsonicReply.Unreachable].
     */
    private fun reply(response: Response): SubsonicReply {
        if (!response.isSuccessful) return SubsonicReply.Rejected(response.code)
        val body = runCatching { response.body.string() }.getOrNull()
        return body?.let(SubsonicReply::read) ?: SubsonicReply.Unreachable("the answer stopped part way")
    }
}

/**
 * The one HTTP client this source uses, with timeouts. The whole-call timeout
 * covers a response that arrives too slowly to trip the read timeout.
 */
object SubsonicClient {
    val shared: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
            // for a server name with both address families on a network that
            // routes only one: the other is tried without waiting for the
            // connect timeout
            .fastFallback(true)
            .build()
    }

    private const val TIMEOUT_S = 15L

    /** Long enough for a large artist list in one answer. */
    private const val CALL_TIMEOUT_S = 60L
}
