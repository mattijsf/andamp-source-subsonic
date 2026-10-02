// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.subsonic.pack

import android.content.Context
import kotlinx.coroutines.runBlocking
import nl.mattix.andamp.core.network.SystemNetworkWatch
import nl.mattix.andamp.core.packapi.PackAccount
import nl.mattix.andamp.core.packapi.PackAnswer
import nl.mattix.andamp.core.packapi.PackDescriptor
import nl.mattix.andamp.core.packapi.PackQuestion
import nl.mattix.andamp.core.playback.NetworkWatch
import nl.mattix.andamp.core.playback.PlaybackBackend
import nl.mattix.andamp.pack.common.PackServiceBase
import nl.mattix.andamp.pack.common.stream.StreamPlayback
import nl.mattix.andamp.pack.subsonic.BuildConfig
import nl.mattix.andamp.pack.subsonic.OkHttpSubsonic
import nl.mattix.andamp.pack.subsonic.SubsonicLibrary
import nl.mattix.andamp.pack.subsonic.SubsonicPlayback
import nl.mattix.andamp.pack.subsonic.SubsonicServer

/**
 * The service the player binds. It supplies the server and account kept on
 * this phone, the library of that server, and the backend that plays its rows;
 * the binder and the service lifetime are [PackServiceBase].
 *
 * Questions go to [SubsonicLibrary] directly, which does its own paging.
 */
class PackService : PackServiceBase() {
    /** The server and the account on this phone. Its values are read from the preferences on every call, not held in a field. */
    private val store by lazy { PackStore(this) }

    /**
     * Whether the phone has a network, so a song whose connection dropped
     * waits for one. It can be made in a field initializer because it looks
     * up the connectivity service only when first used.
     */
    internal val network: NetworkWatch = SystemNetworkWatch(this)

    /** The library as it was last built, and which server it was built for; see [library]. */
    private var shelves: SubsonicLibrary? = null
    private var shelvesFor: SubsonicServer? = null

    override val launcherAlias: String = PackIdentity.LAUNCHER_ALIAS

    override fun makeBackend(): PlaybackBackend =
        SubsonicPlayback.backend(
            server = ::server,
            tracks = emptyList(),
            startIndex = 0,
            scope = scope,
            out = audio,
            network = network,
        )

    override fun descriptor(): PackDescriptor =
        PackIdentity.descriptor(
            version = BuildConfig.VERSION_NAME,
            playback = StreamPlayback.RENDERING,
            browse = SubsonicLibrary.SHELVES,
        )

    /** Signed in means an address, a name and a password are kept. The server is not asked. */
    override fun whoIsHere(): PackAccount =
        if (store.filledIn) PackAccount(signedIn = true, name = store.user) else PackAccount(signedIn = false)

    /** Blocks the binder thread until the library has answered. Null when no server is set up. */
    override fun answer(question: PackQuestion): PackAnswer? {
        val shelf = library() ?: return null
        return runBlocking { shelf.answer(question) }
    }

    /** Drops the library. Synchronized with [library], which a binder thread may be reading. */
    @Synchronized
    override fun forgetAccount() {
        shelves = null
        shelvesFor = null
    }

    /**
     * The server as this phone holds it, or null when none is filled in. Read
     * from the store on every call, so a change made on the settings screen is
     * used by the next request.
     */
    private fun server(): SubsonicServer? =
        if (!store.filledIn) {
            null
        } else {
            SubsonicServer(address = store.address, user = store.user, password = store.password)
        }

    /**
     * The library, kept while the server and account stay the same and built
     * again when they change. It holds no cache.
     *
     * Synchronized with [forgetAccount], which runs on the main thread while a
     * question may be reading this on a binder thread.
     */
    @Synchronized
    private fun library(): SubsonicLibrary? {
        val now = server() ?: return null
        shelves?.takeIf { shelvesFor == now }?.let { return it }
        shelvesFor = now
        return SubsonicLibrary(OkHttpSubsonic(now), now).also { shelves = it }
    }

    internal companion object {
        /**
         * Tells the service that the account on this phone changed. The
         * settings screen passes this to `SettingsActions`.
         *
         * Whether it was a sign-in or a sign-out is read from the store. A
         * sign-out also stops playback.
         */
        fun announce(context: Context) {
            if (PackStore(context).filledIn) {
                signedIn(context, PackService::class.java)
            } else {
                signedOut(context, PackService::class.java)
            }
        }
    }
}
