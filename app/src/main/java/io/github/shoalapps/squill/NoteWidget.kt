package io.github.shoalapps.squill

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import io.github.shoalapps.squill.notes.Note
import io.github.shoalapps.squill.notes.WidgetLines
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel

class NoteWidgetReceiver : AppWidgetProvider() {
    /** Disk work happens off the main thread, and a tick shouldn't wait for an idle phone's job queue. */
    private fun background(block: suspend () -> Unit) {
        val pending = goAsync()
        NoteWidgets.scope.launch { try { runCatching { block() } } finally { pending.finish() } }
    }
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        background { NoteWidgets.update(context.applicationContext) }
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: android.os.Bundle) {
        onUpdate(context, manager, intArrayOf(id))
    }
    override fun onDeleted(context: Context, ids: IntArray) {
        val edit = NoteWidgets.prefs(context).edit()
        ids.forEach { edit.remove("path_$it").remove("locked_$it") }
        edit.apply()
    }
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NoteWidgets.TOGGLE) { super.onReceive(context, intent); return }
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        val line = intent.getIntExtra("line", -1)
        val token = intent.getStringExtra("fingerprint") ?: return
        val pathToken = intent.getStringExtra("pathToken") ?: return
        val app = context.applicationContext
        background { NoteWidgets.toggle(app, id, line, token, pathToken) }
    }
}

object NoteWidgets {
    const val TOGGLE = "io.github.shoalapps.squill.NOTE_WIDGET_TOGGLE"
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshes = Channel<Context>(Channel.CONFLATED)
    init {
        scope.launch {
            for (context in refreshes) runCatching { update(context) }
        }
    }
    fun toggle(context: Context, id: Int, line: Int, token: String, pathToken: String) {
        NoteWidgetAccess.withVault(context) { vault ->
            val path = path(context, id) ?: return@withVault
            if (!isLocked(context, id) && WidgetLines.fingerprint(path) == pathToken && NoteWidgetAccess.flip(vault, path, line, token)) {
                NoteWidgetAccess.onEdit?.invoke()
            }
        }
        update(context)
    }
    fun prefs(context: Context) = context.getSharedPreferences("note-widgets", Context.MODE_PRIVATE)
    fun path(context: Context, id: Int): String? = prefs(context).getString("path_$id", null)
    fun isLocked(context: Context, id: Int) = prefs(context).getBoolean("locked_$id", false)
    fun choose(context: Context, id: Int, path: String) {
        prefs(context).edit().putString("path_$id", path).remove("locked_$id").apply()
    }
    fun movedToLocked(context: Context, movedPath: String) {
        val edit = prefs(context).edit()
        ids(context).filter { path(context, it) == movedPath }.forEach { edit.putBoolean("locked_$it", true) }
        edit.apply()
    }
    private fun ids(context: Context) = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, NoteWidgetReceiver::class.java))
    fun refresh(context: Context) { refreshes.trySend(context.applicationContext) }
    fun read(context: Context, id: Int): Note? = NoteWidgetAccess.withVault(context) { vault ->
        val path = path(context, id) ?: return@withVault null
        if (isLocked(context, id) || NoteWidgetAccess.locked(vault) || !NoteWidgetAccess.allowed(path)) null
        else runCatching { vault.read(path) }.getOrNull()
    }
    fun open(context: Context, path: String?) = Intent(context, MainActivity::class.java)
        .setAction(MainActivity.ACTION_OPEN_NOTE).putExtra("notePath", path).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    private fun pending(context: Context, id: Int, intent: Intent) = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun update(context: Context) = NoteWidgetAccess.withVault(context) { vault ->
        val manager = AppWidgetManager.getInstance(context)
        ids(context).forEach { id ->
            val path = path(context, id)
            val locked = isLocked(context, id) || NoteWidgetAccess.locked(vault) || (path != null && !NoteWidgetAccess.allowed(path))
            val note = if (locked) null else read(context, id)
            val views = RemoteViews(context.packageName, R.layout.note_widget)
            val configure = Intent(context, NoteWidgetConfigureActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .setData(Uri.parse("squill-widget:configure/$id"))
            views.setOnClickPendingIntent(R.id.note_widget_choose, pending(context, id, configure))
            views.setTextViewText(R.id.note_widget_title, when {
                locked -> "Locked. Open Squill to unlock"
                note == null -> "That note has gone. Tap to choose another"
                else -> note.title
            })
            views.setOnClickPendingIntent(R.id.note_widget_title, pending(context, id, if (locked) open(context, null) else if (note == null) configure else open(context, path)))
            views.setViewVisibility(R.id.note_widget_list, if (note != null) View.VISIBLE else View.GONE)
            val adapter = Intent(context, NoteWidgetService::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .setData(Uri.parse("squill-widget:list/$id"))
            @Suppress("DEPRECATION")
            views.setRemoteAdapter(R.id.note_widget_list, adapter)
            val template = Intent(context, NoteWidgetReceiver::class.java).setAction(TOGGLE)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).setData(Uri.parse("squill-widget:toggle/$id"))
            views.setPendingIntentTemplate(R.id.note_widget_list, PendingIntent.getBroadcast(context, id, template,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)))
            manager.updateAppWidget(id, views)
            @Suppress("DEPRECATION")
            manager.notifyAppWidgetViewDataChanged(id, R.id.note_widget_list)
        }
    }
}

/** No note bodies are retained by the factory, nor written to a widget cache. */
class NoteWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = object : RemoteViewsFactory {
        private val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        override fun onCreate() {}
        override fun onDestroy() {}
        override fun onDataSetChanged() {}
        override fun getCount() = NoteWidgets.read(this@NoteWidgetService, id)?.let { WidgetLines.parse(it.body).size } ?: 0
        override fun getViewAt(position: Int): RemoteViews? = NoteWidgetAccess.withVault(this@NoteWidgetService) {
            val note = NoteWidgets.read(this@NoteWidgetService, id) ?: return@withVault null
            val line = WidgetLines.parse(note.body).getOrNull(position) ?: return@withVault null
            RemoteViews(packageName, R.layout.note_widget_line).apply {
                setTextViewText(R.id.note_widget_text, line.text)
                setViewVisibility(R.id.note_widget_check, if (line.checked == null) View.GONE else View.VISIBLE)
                setTextViewText(R.id.note_widget_check, if (line.checked == true) "☑" else "☐")
                setContentDescription(R.id.note_widget_check, "${if (line.checked == true) "Checked" else "Unchecked"}: ${line.text}")
                if (line.checked != null) setOnClickFillInIntent(R.id.note_widget_check, Intent()
                    .putExtra("line", line.index).putExtra("fingerprint", line.fingerprint)
                    .putExtra("pathToken", WidgetLines.fingerprint(note.path)))
            }
        }
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = false
    }
}
