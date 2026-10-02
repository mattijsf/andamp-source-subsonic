// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.subsonic.pack

import android.content.Context

/**
 * Keeps the server address and the account, in this app's private preferences.
 *
 * The password is stored in the clear. Subsonic authenticates every request
 * with `md5(password + salt)` and a new salt, so the client has to keep the
 * password itself; the API has no token to store in its place. It is protected
 * by the app's private storage, which no other app can read, the player
 * included, and `allowBackup` is off. A separate, non-admin account on the
 * server for this source limits what the stored password can reach.
 */
internal class PackStore(
    context: Context,
) {
    private val kept = context.applicationContext.getSharedPreferences("subsonic", Context.MODE_PRIVATE)

    /** The server's address as the listener typed it, trimmed; empty when none is kept. */
    var address: String
        get() = kept.getString(ADDRESS, "").orEmpty()
        set(value) {
            kept.edit().putString(ADDRESS, value.trim()).apply()
        }

    var user: String
        get() = kept.getString(USER, "").orEmpty()
        set(value) {
            kept.edit().putString(USER, value.trim()).apply()
        }

    var password: String
        get() = kept.getString(PASSWORD, "").orEmpty()
        set(value) {
            kept.edit().putString(PASSWORD, value).apply()
        }

    /** Whether an address, a user and a password are all kept. Whether the server accepts them is not checked here. */
    val filledIn: Boolean get() = address.isNotBlank() && user.isNotBlank() && password.isNotBlank()

    /** Signs out: clears the address, the user and the password. */
    fun forget() {
        kept.edit().clear().apply()
    }

    private companion object {
        const val ADDRESS = "address"
        const val USER = "user"
        const val PASSWORD = "password"
    }
}
