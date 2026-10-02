// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.subsonic.ui

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The settings page over fake actions, asserted on what is on screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class SubsonicPageTest {
    @get:Rule
    val compose = createComposeRule()

    /**
     * Actions that record their calls and answer a test with [answer], a
     * deferred, so a test can hold the page between the press and the answer.
     */
    private class FakeActions(
        private val holds: Kept = Kept(address = "", user = "", signedIn = false),
    ) : SubsonicActions {
        var answer = CompletableDeferred<Tried>()
        val tried = mutableListOf<List<String>>()
        val kept = mutableListOf<List<String>>()
        var forgotten = 0

        override fun kept(): Kept = holds

        override suspend fun test(
            address: String,
            user: String,
            password: String,
        ): Tried {
            tried += listOf(address, user, password)
            return answer.await()
        }

        override fun keep(
            address: String,
            user: String,
            password: String,
        ) {
            kept += listOf(address, user, password)
        }

        override fun forget() {
            forgotten++
        }
    }

    private fun page(actions: SubsonicActions): () -> Int {
        var done = 0
        compose.setContent { SubsonicPage(actions, FakeAppList(), onDone = { done++ }) }
        return { done }
    }

    private fun type(
        address: String = ADDRESS,
        user: String = USER,
        password: String = PASSWORD,
    ) {
        if (address.isNotEmpty()) compose.onNodeWithTag("subsonic.address").performTextInput(address)
        if (user.isNotEmpty()) compose.onNodeWithTag("subsonic.user").performTextInput(user)
        if (password.isNotEmpty()) compose.onNodeWithTag("subsonic.password").performTextInput(password)
    }

    /** The text in a field, without its label. */
    private fun holds(tag: String): String =
        compose
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .config[SemanticsProperties.EditableText]
            .text

    @Test
    fun `nothing can be tested or saved until all three fields are filled in`() {
        page(FakeActions())

        compose.onNodeWithTag("subsonic.test").assertIsNotEnabled()
        compose.onNodeWithTag("subsonic.save").assertIsNotEnabled()

        type(password = "")
        compose.onNodeWithTag("subsonic.test").assertIsNotEnabled()
        compose.onNodeWithTag("subsonic.save").assertIsNotEnabled()

        compose.onNodeWithTag("subsonic.password").performTextInput(PASSWORD)
        compose.onNodeWithTag("subsonic.test").assertIsEnabled()
        compose.onNodeWithTag("subsonic.save").assertIsEnabled()
    }

    @Test
    fun `a user left empty is as unsaveable as a password left empty`() {
        page(FakeActions())

        type(user = "")

        compose.onNodeWithTag("subsonic.test").assertIsNotEnabled()
        compose.onNodeWithTag("subsonic.save").assertIsNotEnabled()
    }

    @Test
    fun `a server that answered says so`() {
        answered(Tried.Reached)

        compose.onNodeWithText("Connected. Save to use this server in Andamp.").assertIsDisplayed()
    }

    @Test
    fun `nothing at the address says nothing answered, and why`() {
        answered(Tried.NoServer("connection refused"))

        compose.onNodeWithText("Nothing answered at that address: connection refused").assertIsDisplayed()
    }

    @Test
    fun `something that is not a server says what it answered with`() {
        answered(Tried.NotSubsonic(404))

        compose
            .onNodeWithText("Something answered with HTTP 404, but it was not a Subsonic server.")
            .assertIsDisplayed()
    }

    @Test
    fun `a server that refused says so in its own words`() {
        answered(Tried.Refused("Wrong username or password"))

        compose.onNodeWithText("The server said no: Wrong username or password").assertIsDisplayed()
    }

    @Test
    fun `a spinner turns while the server is being tried and stops when it answers`() {
        val actions = FakeActions()
        page(actions)
        type()

        compose.onNodeWithTag("subsonic.test").performClick()

        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertExists()
        // the button is disabled while the test is out
        compose.onNodeWithTag("subsonic.test").assertIsNotEnabled()
        assertEquals(listOf(listOf(ADDRESS, USER, PASSWORD)), actions.tried)

        actions.answer.complete(Tried.Reached)
        compose.waitForIdle()

        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertDoesNotExist()
        compose.onNodeWithTag("subsonic.test").assertIsEnabled()
    }

    @Test
    fun `saving keeps exactly what was typed and closes the page`() {
        val actions = FakeActions()
        val done = page(actions)
        type()

        compose.onNodeWithTag("subsonic.save").performClick()

        assertEquals(listOf(listOf(ADDRESS, USER, PASSWORD)), actions.kept)
        assertEquals(1, done())
    }

    @Test
    fun `sign out is only offered when an account is kept`() {
        page(FakeActions())

        compose.onNodeWithTag("subsonic.forget").assertDoesNotExist()
    }

    @Test
    fun `signing out forgets the account and closes the page`() {
        val actions = FakeActions(Kept(address = ADDRESS, user = USER, signedIn = true))
        val done = page(actions)

        compose.onNodeWithText("Sign out").assertIsDisplayed().performClick()

        assertEquals(1, actions.forgotten)
        assertTrue("signing out keeps nothing", actions.kept.isEmpty())
        assertEquals(1, done())
    }

    @Test
    fun `a kept account opens with its address and name, and never its password`() {
        page(FakeActions(Kept(address = ADDRESS, user = USER, signedIn = true)))

        assertEquals(ADDRESS, holds("subsonic.address"))
        assertEquals(USER, holds("subsonic.user"))
        assertEquals("", holds("subsonic.password"))
        compose.onNodeWithText("Kept on this phone. Leave empty to keep it.").assertExists()
    }

    /** The empty password field is passed on as empty; the actions read it as the kept password. */
    @Test
    fun `a kept password need not be typed again to change the address`() {
        val actions = FakeActions(Kept(address = ADDRESS, user = USER, signedIn = true))
        val done = page(actions)

        compose.onNodeWithTag("subsonic.address").performTextInput("/moved")
        compose.onNodeWithTag("subsonic.test").assertIsEnabled()
        compose.onNodeWithTag("subsonic.save").assertIsEnabled().performClick()

        assertEquals(1, actions.kept.size)
        assertEquals(USER, actions.kept.single()[1])
        assertEquals("", actions.kept.single()[2])
        assertEquals(1, done())
    }

    @Test
    fun `the password can be shown while it is typed, and starts hidden`() {
        page(FakeActions())
        compose.onNodeWithTag("subsonic.password").performTextInput(PASSWORD)

        compose.onNodeWithContentDescription("Show password").assertExists()
        compose.onNodeWithTag("subsonic.password.show").performClick()
        compose.onNodeWithContentDescription("Hide password").assertExists()
        assertEquals("showing the password keeps the typed text", PASSWORD, holds("subsonic.password"))

        compose.onNodeWithTag("subsonic.password.show").performClick()
        compose.onNodeWithContentDescription("Show password").assertExists()
    }

    @Test
    fun `changing a field takes away what the last try said`() {
        answered(Tried.Reached)
        compose.onNodeWithTag("subsonic.said").assertIsDisplayed()

        compose.onNodeWithTag("subsonic.address").performTextInput("x")

        compose.onNodeWithTag("subsonic.said").assertDoesNotExist()
    }

    @Test
    fun `the page carries the app list switch`() {
        val list = FakeAppList(shown = true)
        compose.setContent { SubsonicPage(FakeActions(), list, onDone = {}) }

        compose
            .onNodeWithTag("pack.applist")
            .performScrollTo()
            .assertIsOn()
            .performClick()

        assertEquals(listOf(false), list.told)
        compose.onNodeWithTag("pack.applist").assertIsOff()
    }

    /** A page with all three filled in, tried, and answered with [said]. */
    private fun answered(said: Tried) {
        val actions = FakeActions()
        actions.answer.complete(said)
        page(actions)
        type()
        compose.onNodeWithTag("subsonic.test").performClick()
        compose.waitForIdle()
    }

    private companion object {
        const val ADDRESS = "https://music.example"
        const val USER = "listener"
        const val PASSWORD = "sesame"
    }
}
