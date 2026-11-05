package io.github.shoalapps.squill

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NoteWidgetConfigureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        setContent {
            SquillTheme {
                var search by remember { mutableStateOf("") }
                var paths by remember { mutableStateOf<List<String>>(emptyList()) }
                var locked by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    withContext(Dispatchers.IO) {
                        NoteWidgetAccess.withVault(this@NoteWidgetConfigureActivity) { vault ->
                            locked = NoteWidgetAccess.locked(vault)
                            paths = if (locked) emptyList() else vault.stats().map { it.path }.filter(NoteWidgetAccess::allowed).sorted()
                        }
                    }
                }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(20.dp)) {
                        Text("Choose a note", style = MaterialTheme.typography.headlineSmall)
                        if (locked) {
                            Text("Locked. Open Squill to unlock")
                            TextButton(onClick = { startActivity(NoteWidgets.open(this@NoteWidgetConfigureActivity, null)); finish() }) { Text("Open Squill") }
                        } else {
                            OutlinedTextField(search, { search = it }, label = { Text("Search notes and folders") }, modifier = Modifier.fillMaxWidth())
                            LazyColumn {
                                items(paths.filter { it.contains(search, ignoreCase = true) }) { path ->
                                    Column(Modifier.fillMaxWidth().clickable {
                                        lifecycleScope.launch {
                                            val chosen = withContext(Dispatchers.IO) {
                                                NoteWidgetAccess.withVault(this@NoteWidgetConfigureActivity) { vault ->
                                                    if (NoteWidgetAccess.locked(vault) || !vault.exists(path)) false else {
                                                        NoteWidgets.choose(this@NoteWidgetConfigureActivity, id, path)
                                                        NoteWidgets.update(this@NoteWidgetConfigureActivity)
                                                        true
                                                    }
                                                }
                                            }
                                            if (chosen) {
                                                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                                                finish()
                                            }
                                        }
                                    }.padding(vertical = 12.dp)) {
                                        Text(path.substringAfterLast('/').removeSuffix(".md"), style = MaterialTheme.typography.titleMedium)
                                        Text(path.substringBeforeLast('/', "All notes"), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
