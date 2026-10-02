// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.subsonic.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Golden images of the settings screen in two states: nothing kept, and a
 * server kept with a successful test, the password shown and the app list
 * switch off.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class SettingsScreenGoldenTest {
    @get:Rule
    val compose = createComposeRule()

    private class Actions(
        private val holds: Kept,
    ) : SubsonicActions {
        override fun kept() = holds

        override suspend fun test(
            address: String,
            user: String,
            password: String,
        ): Tried = Tried.Reached

        override fun keep(
            address: String,
            user: String,
            password: String,
        ) = Unit

        override fun forget() = Unit
    }

    private fun draw(
        holds: Kept,
        dark: Boolean,
        shown: Boolean,
    ) {
        compose.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { SubsonicPage(Actions(holds), FakeAppList(shown), onDone = {}) }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `a phone with no server yet`() {
        draw(Kept(address = "", user = "", signedIn = false), dark = true, shown = true)

        compose.onRoot().captureRoboImage("$SNAPSHOTS/subsonic_empty.png")
    }

    @Test
    fun `a server kept, tried, with the password shown`() {
        draw(Kept(address = "http://192.168.1.10:4533", user = "listener", signedIn = true), dark = false, shown = false)
        compose.onNodeWithTag("subsonic.password").performTextInput("hunter2")
        compose.onNodeWithTag("subsonic.password.show").performClick()
        compose.onNodeWithTag("subsonic.test").performClick()
        compose.waitForIdle()

        compose.onRoot().captureRoboImage("$SNAPSHOTS/subsonic_kept.png")
    }

    private companion object {
        const val SNAPSHOTS = "src/test/snapshots"
    }
}
