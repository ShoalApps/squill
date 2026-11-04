@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.shoalapps.squill

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri

private fun open(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            SupportCard { open(context, "https://liberapay.com/ShoalApps") }
            ListItem(
                headlineContent = { Text("Source code") },
                supportingContent = { Text("Free software under the GPL-3.0") },
                modifier = Modifier.clickable { open(context, "https://github.com/ShoalApps/squill") },
            )
            ListItem(
                headlineContent = { Text("Privacy") },
                supportingContent = { Text("Your reminders stay on this phone. Squill has no account, no network access and no tracking.") },
                modifier = Modifier.clickable { open(context, "https://shoalapps.github.io/privacy.html") },
            )
        }
    }
}

/** Supporting Shoal, at the top of Settings as in every Shoal app. */
@Composable
private fun SupportCard(onDonate: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(28.dp), color = c.primary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            Modifier.clickable(onClickLabel = "Donate on Liberapay", onClick = onDonate)
                .background(Brush.linearGradient(listOf(c.primary, c.tertiary))).padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Favorite, null, Modifier.size(36.dp), tint = c.onPrimary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Support Shoal", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = c.onPrimary)
                Text("Free, open source and ad-free. Donations on Liberapay keep it going.", style = MaterialTheme.typography.bodyMedium, color = c.onPrimary.copy(alpha = .9f))
                Button(
                    onClick = onDonate, modifier = Modifier.padding(top = 12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = c.onPrimary, contentColor = c.primary),
                ) { Text("Donate", fontWeight = FontWeight.Bold) }
            }
        }
    }
}
