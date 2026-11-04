package io.github.shoalapps.squill

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

class MainActivity : ComponentActivity() {
    private val vm: RemindersViewModel by viewModels()
    private val notes: io.github.shoalapps.squill.notes.NotesViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent {
            val askForNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
            val owner = LocalLifecycleOwner.current
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= 33) askForNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            LaunchedEffect(owner) {
                owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { vm.refresh() }
            }
            SquillTheme { SquillHome(vm, notes) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Shared text and the widget's + button start a note or a reminder. */
    fun handle(intent: Intent) {
        when (intent.action) {
            ACTION_JOT -> vm.jot("")
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { text ->
                // Squill appears twice in the share sheet: as a note, and as a reminder.
                if (intent.component?.className?.endsWith(".ShareAsNote") == true) notes.share(text)
                else vm.jot(text.trim().lineSequence().first().take(200))
            }
        }
    }

    companion object {
        const val ACTION_JOT = "io.github.shoalapps.squill.JOT"
    }
}
