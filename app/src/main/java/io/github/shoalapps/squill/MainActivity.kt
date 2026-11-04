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
            SquillTheme { SquillScreen(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Shared text and the widget's + button both open the jot-down dialog. */
    fun handle(intent: Intent) {
        when (intent.action) {
            ACTION_JOT -> vm.jot("")
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { vm.jot(it.trim().lineSequence().first().take(200)) }
        }
    }

    companion object {
        const val ACTION_JOT = "io.github.shoalapps.squill.JOT"
    }
}
