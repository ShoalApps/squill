package io.github.shoalapps.squill

import android.Manifest
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
}
