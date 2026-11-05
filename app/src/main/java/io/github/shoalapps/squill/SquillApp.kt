package io.github.shoalapps.squill

import android.app.Application
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SquillApp : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        AlarmReceiver.createChannel(this)
        NoteWidgets.refresh(this)
        // The widget shows whatever's next, so it follows every change.
        scope.launch { Store.changes.collect { runCatching { SquillWidget().updateAll(this@SquillApp) } } }
    }
}
