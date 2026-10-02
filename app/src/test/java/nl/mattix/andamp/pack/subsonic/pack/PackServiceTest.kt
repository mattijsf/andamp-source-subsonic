// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.subsonic.pack

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.core.network.SystemNetworkWatch
import nl.mattix.andamp.core.packapi.IMusicSourcePack
import nl.mattix.andamp.core.packapi.IPackListener
import nl.mattix.andamp.core.packapi.PackAccount
import nl.mattix.andamp.core.packapi.PackApi
import nl.mattix.andamp.core.packapi.PackQuestion
import nl.mattix.andamp.core.packapi.PackState
import nl.mattix.andamp.pack.common.stream.StreamPlayback
import nl.mattix.andamp.pack.subsonic.BuildConfig
import nl.mattix.andamp.pack.subsonic.SubsonicLibrary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * [PackService] through its binder, as the player calls it. The generated stub
 * is a local interface here, so there is no second process. Nothing is played
 * and no network is used.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PackServiceTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val store = PackStore(app)
    private lateinit var service: ServiceController<PackService>
    private lateinit var wire: IMusicSourcePack

    /** A player's listener, as a plain record of everything it was told. */
    private class Heard : IPackListener.Stub() {
        val accounts = mutableListOf<PackAccount>()
        val states = mutableListOf<PackState>()

        override fun onState(state: PackState) {
            states += state
        }

        override fun onAccount(account: PackAccount) {
            accounts += account
        }
    }

    @Before
    fun bind() {
        store.forget()
        service = Robolectric.buildService(PackService::class.java).create()
        wire = IMusicSourcePack.Stub.asInterface(service.get().onBind(Intent(BIND)))
        idle()
    }

    @After
    fun unbind() {
        service.destroy()
    }

    @Test
    fun `the source speaks the contract this build was compiled against`() {
        assertEquals(PackApi.PACK_API, wire.apiVersion())
    }

    @Test
    fun `the descriptor says who the source is, which build, and that it hands its audio over`() {
        val said = wire.describe()

        assertEquals("subsonic", said.scheme)
        assertEquals("Subsonic", said.label)
        assertEquals(BuildConfig.VERSION_NAME, said.version)
        assertTrue("the source hands its audio over to the player", said.handsOverAudio)
    }

    @Test
    fun `what the descriptor says it can do is the backend's and the library's own answer`() {
        val said = wire.describe()

        assertEquals(StreamPlayback.RENDERING.canSeek, said.canSeek)
        assertEquals(StreamPlayback.RENDERING.canEditQueue, said.canEditQueue)
        assertEquals(StreamPlayback.RENDERING.canAttenuate, said.canAttenuate)
        assertEquals(SubsonicLibrary.SHELVES, said.browse)
    }

    @Test
    fun `with nothing kept the account is signed out`() {
        assertEquals(PackAccount(signedIn = false), wire.account())
    }

    @Test
    fun `with all three kept the account is signed in, under the user's name`() {
        keep()

        assertEquals(PackAccount(signedIn = true, name = USER), wire.account())
    }

    @Test
    fun `an account with a field missing is still signed out`() {
        store.address = ADDRESS
        store.user = USER

        assertEquals(false, wire.account().signedIn)
    }

    @Test
    fun `a listener is told where things stand the moment it listens`() {
        val heard = Heard()

        wire.listen(heard)

        assertEquals(1, heard.states.size)
    }

    /** The announce starts the service with the account action, and the listener hears the account that is on disk. */
    @Test
    fun `a sign-in on the settings screen reaches every listening player`() {
        val heard = Heard()
        wire.listen(heard)
        keep()

        announced()

        assertEquals(listOf(PackAccount(signedIn = true, name = USER)), heard.accounts)
    }

    @Test
    fun `a sign-out on the settings screen reaches every listening player`() {
        keep()
        val heard = Heard()
        wire.listen(heard)
        store.forget()

        announced()

        assertEquals(listOf(PackAccount(signedIn = false)), heard.accounts)
    }

    @Test
    fun `a player that stopped listening is not told`() {
        val heard = Heard()
        wire.listen(heard)
        wire.stopListening(heard)
        keep()

        announced()

        assertTrue(heard.accounts.toString(), heard.accounts.isEmpty())
    }

    @Test
    fun `a missing question fails rather than throws`() {
        keep()

        assertTrue(wire.ask(null).failed)
    }

    @Test
    fun `a question with no server kept fails rather than answers empty`() {
        assertTrue(wire.ask(PackQuestion(kind = "artists")).failed)
    }

    @Test
    fun `the audio is a pipe the player can read, and it ends when the source goes`() {
        val pipe = wire.openAudio()

        assertNotNull("the source opens an audio pipe", pipe)
        assertTrue(checkNotNull(pipe).fileDescriptor.valid())

        service.destroy()

        ParcelFileDescriptor.AutoCloseInputStream(pipe).use { read ->
            assertEquals("the pipe reads as ended once the service is destroyed", -1, read.read())
        }
    }

    /**
     * The service's network watch is a [SystemNetworkWatch], and the merged
     * manifest has ACCESS_NETWORK_STATE, which the SDK's manifest brings.
     */
    @Test
    fun `the service watches the phone's network and has the permission for it`() {
        val network = service.get().network
        assertTrue("the network watch is a SystemNetworkWatch: $network", network is SystemNetworkWatch)
        val asked = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions
        assertTrue(
            "the manifest requests ACCESS_NETWORK_STATE",
            asked.orEmpty().contains(Manifest.permission.ACCESS_NETWORK_STATE),
        )

        val connectivity = shadowOf(app.getSystemService(ConnectivityManager::class.java))
        network.watch {}
        assertEquals("watching registers one network callback", 1, connectivity.networkCallbacks.size)
        network.unwatch()
        assertTrue("unwatching removes the network callback", connectivity.networkCallbacks.isEmpty())
    }

    /** Calls the announce the settings screen calls, and delivers the start it makes to this service. */
    private fun announced() {
        PackService.announce(app)
        val started = checkNotNull(shadowOf(app).nextStartedService) { "the announce starts the service" }
        service.withIntent(started).startCommand(0, 1)
        idle()
    }

    private fun keep() {
        store.address = ADDRESS
        store.user = USER
        store.password = PASSWORD
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private companion object {
        const val BIND = "nl.mattix.andamp.source.BIND"
        const val ADDRESS = "https://music.example"
        const val USER = "listener"
        const val PASSWORD = "sesame"
    }
}
