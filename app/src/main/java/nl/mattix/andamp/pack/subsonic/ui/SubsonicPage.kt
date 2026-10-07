// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.subsonic.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.mattix.andamp.pack.common.AppListEntry
import nl.mattix.andamp.pack.subsonic.R

/**
 * The settings page: what this app is, who is signed in, the server form, and
 * the app list switch.
 *
 * Test pings the server and shows the outcome under the fields. Save does not
 * require a successful test, so the details can be entered while the server
 * is out of reach.
 */
@Composable
internal fun SubsonicPage(
    actions: SubsonicActions,
    appList: AppListEntry,
    onDone: () -> Unit,
    padding: PaddingValues = PaddingValues(),
) {
    val kept = remember { actions.kept() }
    Column(
        Modifier
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Identity()
        Spacer(Modifier.height(20.dp))
        Standing(kept, onForget = {
            actions.forget()
            onDone()
        })
        Spacer(Modifier.height(12.dp))
        ServerForm(kept, actions, onDone)
        Spacer(Modifier.height(12.dp))
        AppListRow(appList)
        Spacer(Modifier.height(12.dp))
        DonateRow()
        Spacer(Modifier.height(32.dp))
    }
}

/** The icon, the name and one line on what this app is. */
@Composable
private fun Identity() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painterResource(R.drawable.ic_pack),
            contentDescription = null,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text("Subsonic for Andamp", style = MaterialTheme.typography.titleLarge)
            Text(
                "Play the music on your own server in Andamp. Navidrome, Airsonic, Gonic and others implement the Subsonic API.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Who this phone is signed in as, and on which server. "Signed in" means
 * details are kept; the server is not asked when the page opens.
 */
@Composable
private fun Standing(
    kept: Kept,
    onForget: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (kept.signedIn) Icons.Filled.AccountCircle else Icons.Outlined.AccountCircle,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = if (kept.signedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (kept.signedIn) kept.user else "No server yet",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (kept.signedIn) host(kept.address) else "Andamp skips Subsonic tracks until you add one",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (kept.signedIn) {
                TextButton(onClick = onForget, modifier = Modifier.testTag("subsonic.forget")) { Text("Sign out") }
            }
        }
    }
}

@Composable
private fun ServerForm(
    kept: Kept,
    actions: SubsonicActions,
    onDone: () -> Unit,
) {
    var address by remember { mutableStateOf(kept.address) }
    var user by remember { mutableStateOf(kept.user) }
    var password by remember { mutableStateOf("") }
    var trying by remember { mutableStateOf(false) }
    var tried by remember { mutableStateOf<Tried?>(null) }
    val work = rememberCoroutineScope()
    // an empty password field is enough when a password is already kept
    val filled = address.isNotBlank() && user.isNotBlank() && (password.isNotBlank() || kept.signedIn)

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text("Server", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = address,
                onValueChange = {
                    address = it
                    tried = null
                },
                label = { Text("Server address") },
                placeholder = { Text("http://192.168.1.10:4533") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().testTag("subsonic.address"),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = user,
                onValueChange = {
                    user = it
                    tried = null
                },
                label = { Text("User") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().testTag("subsonic.user"),
            )
            Spacer(Modifier.height(8.dp))
            PasswordField(
                value = password,
                kept = kept.signedIn,
                onValueChange = {
                    password = it
                    tried = null
                },
            )

            tried?.let {
                Spacer(Modifier.height(12.dp))
                Outcome(it)
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    enabled = !trying && filled,
                    onClick = {
                        trying = true
                        tried = null
                        work.launch {
                            tried = actions.test(address, user, password)
                            trying = false
                        }
                    },
                    modifier = Modifier.weight(1f).testTag("subsonic.test"),
                ) {
                    if (trying) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (trying) "Testing…" else "Test")
                }
                Button(
                    enabled = filled,
                    onClick = {
                        actions.keep(address, user, password)
                        onDone()
                    },
                    modifier = Modifier.weight(1f).testTag("subsonic.save"),
                ) { Text("Save") }
            }
        }
    }
}

/**
 * The password field, hidden until the eye button is pressed. It starts empty
 * and never shows the kept password.
 */
@Composable
private fun PasswordField(
    value: String,
    kept: Boolean,
    onValueChange: (String) -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("Password") },
        supportingText = if (kept) ({ Text("Kept on this phone. Leave empty to keep it.") }) else null,
        singleLine = true,
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { shown = !shown }, modifier = Modifier.testTag("subsonic.password.show")) {
                Icon(
                    painterResource(if (shown) R.drawable.ic_eye_off else R.drawable.ic_eye),
                    contentDescription = if (shown) "Hide password" else "Show password",
                )
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth().testTag("subsonic.password"),
    )
}

/** What the last test said, in the error colors when it failed. */
@Composable
private fun Outcome(tried: Tried) {
    val good = tried == Tried.Reached
    Surface(
        color = if (good) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (good) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                contentDescription = null,
                tint = if (good) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                words(tried),
                style = MaterialTheme.typography.bodyMedium,
                color = if (good) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.testTag("subsonic.said"),
            )
        }
    }
}

/** The address without its scheme or trailing slashes. */
private fun host(address: String): String = address.substringAfter("://").trimEnd('/')

/** The sentence the page shows for each outcome of a test. */
private fun words(tried: Tried): String =
    when (tried) {
        Tried.Reached -> "Connected. Save to use this server in Andamp."
        is Tried.NoServer -> "Nothing answered at that address: ${tried.why}"
        is Tried.NotSubsonic -> "Something answered with HTTP ${tried.status}, but it was not a Subsonic server."
        is Tried.Refused -> "The server said no: ${tried.why}"
    }
