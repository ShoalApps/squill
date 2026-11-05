package io.github.shoalapps.squill.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
private fun PasswordBox(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
}

/** Shown instead of everything else while the folder is locked. */
@Composable
fun UnlockPane(model: Model) {
    var password by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 420.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("This folder is locked", style = MaterialTheme.typography.headlineSmall)
            PasswordBox(password, "Password") { password = it; wrong = false }
            if (wrong) Text("That password isn't right", color = MaterialTheme.colorScheme.error)
            Button(enabled = !busy && password.isNotEmpty(), onClick = {
                busy = true
                model.unlock(password) { ok -> busy = false; wrong = !ok; if (ok) password = "" }
            }) { Text(if (busy) "Unlocking…" else "Unlock") }
        }
    }
}

/** Turning encryption for this folder on and off. */
@Composable
fun EncryptSection(model: Model) {
    val encrypted by model.encrypted.collectAsState()
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    fun finished(result: Result<Unit>, message: String?) {
        busy = false
        result.onSuccess { first = ""; second = ""; failure = null }.onFailure { failure = message ?: it.message }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Encrypt this folder", style = MaterialTheme.typography.titleMedium)
        if (encrypted) {
            Text("On. Squill asks for the password when it opens, and devices can only sync while it's unlocked.", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { model.lockNow() }) { Text("Lock now") }
            PasswordBox(first, "Password") { first = it; failure = null }
            OutlinedButton(enabled = !busy && first.isNotEmpty(), onClick = { busy = true; model.decrypt(first) { finished(it, "That password isn't right") } }) { Text("Stop encrypting") }
        } else {
            Text("Seals everything here under a password for this computer, which can differ from your phone's. File names stay visible, and other programs won't be able to read your notes.", style = MaterialTheme.typography.bodyMedium)
            Text("If you forget this password, nobody can get these notes back. Not even Squill.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            PasswordBox(first, "Password") { first = it; failure = null }
            PasswordBox(second, "Password again") { second = it }
            if (second.isNotEmpty() && first != second) Text("Those don't match yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (first.isNotEmpty() && first.length < 8) Text("Use at least 8 characters", style = MaterialTheme.typography.bodySmall)
            Button(enabled = !busy && first.length >= 8 && first == second, onClick = { busy = true; model.encrypt(first) { finished(it, null) } }) { Text(if (busy) "Working…" else "Encrypt") }
        }
        failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
