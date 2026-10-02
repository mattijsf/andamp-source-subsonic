// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.subsonic.ui

import android.content.Context
import nl.mattix.andamp.pack.subsonic.OkHttpSubsonic
import nl.mattix.andamp.pack.subsonic.Subsonic
import nl.mattix.andamp.pack.subsonic.SubsonicHttp
import nl.mattix.andamp.pack.subsonic.SubsonicReply
import nl.mattix.andamp.pack.subsonic.SubsonicServer
import nl.mattix.andamp.pack.subsonic.pack.PackStore

/**
 * What the settings page does to the store and the server. An interface, so
 * the page is tested with a fake in its place.
 */
internal interface SubsonicActions {
    /** What is kept on this phone now, for the page to open with. */
    fun kept(): Kept

    /** Pings the server with what was typed, and says what happened. An empty [password] means the one already kept. */
    suspend fun test(
        address: String,
        user: String,
        password: String,
    ): Tried

    /** Keeps what was typed. An empty [password] leaves the kept one in place. */
    fun keep(
        address: String,
        user: String,
        password: String,
    )

    /** Forgets the server and the account. */
    fun forget()
}

/** What the phone holds, without the password. */
internal data class Kept(
    val address: String,
    val user: String,
    val signedIn: Boolean,
)

/** The outcome of [SubsonicActions.test]. */
internal sealed interface Tried {
    data object Reached : Tried

    /** The address is not one, or nothing is listening there. */
    data class NoServer(
        val why: String,
    ) : Tried

    /** Something answered with an HTTP status that is not success. */
    data class NotSubsonic(
        val status: Int,
    ) : Tried

    /** A Subsonic server that refused the call, such as for a wrong user or password. */
    data class Refused(
        val why: String,
    ) : Tried
}

/** [SubsonicActions] over the store on disk and a real server. */
internal class SettingsActions(
    context: Context,
    private val store: PackStore,
    /** Tells every bound player that the account changed. A parameter, so a test starts no service. */
    private val announce: (Context) -> Unit = {},
    /** Makes the client a ping goes out over. A parameter, so a test can answer with each kind of reply. */
    private val http: (SubsonicServer) -> SubsonicHttp = { OkHttpSubsonic(it) },
) : SubsonicActions {
    private val app = context.applicationContext

    override fun kept(): Kept = Kept(address = store.address, user = store.user, signedIn = store.filledIn)

    override suspend fun test(
        address: String,
        user: String,
        password: String,
    ): Tried {
        val server = SubsonicServer(address = address, user = user, password = password.ifEmpty { store.password })
        if (server.base == null) return Tried.NoServer("that is not an address a server could be at")
        return when (val said = http(server).get(Subsonic.PING)) {
            is SubsonicReply.Answered -> Tried.Reached
            is SubsonicReply.Unreachable -> Tried.NoServer(said.why)
            is SubsonicReply.Rejected -> Tried.NotSubsonic(said.status)
            is SubsonicReply.Refused -> Tried.Refused(said.why)
        }
    }

    override fun keep(
        address: String,
        user: String,
        password: String,
    ) {
        store.address = address
        store.user = user
        if (password.isNotEmpty()) store.password = password
        told()
    }

    override fun forget() {
        store.forget()
        told()
    }

    /** Tells every bound player now. Otherwise the player asks again only when its Preferences next come forward. */
    private fun told() = announce(app)
}
