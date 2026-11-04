package io.github.shoalapps.squill

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Rings a reminder, and handles its Done and Snooze buttons. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1)
        if (id < 0) return
        when (intent.action) {
            ACTION_DONE -> {
                NotificationManagerCompat.from(context).cancel(id.toInt())
                // A repeating reminder carries on with its next ring; a one-off is finished.
                change(context, id) { it.copy(done = it.repeat == Repeat.NONE, snoozedUntil = 0) }
            }
            ACTION_SNOOZE -> {
                NotificationManagerCompat.from(context).cancel(id.toInt())
                val at = System.currentTimeMillis() + Prefs.snoozeMinutes(context) * 60_000L
                change(context, id) { it.copy(snoozedUntil = at) }
            }
            else -> ring(context, id)
        }
    }

    private fun change(context: Context, id: Long, edit: (Reminder) -> Reminder) {
        var changed: Reminder? = null
        Store.update(context) { list ->
            list.map { if (it.id == id) edit(it).also { new -> changed = new } else it }
        }
        changed?.let { Scheduler.schedule(context, it) }
    }

    companion object {
        const val EXTRA_ID = "id"
        const val ACTION_DONE = "io.github.shoalapps.squill.DONE"
        const val ACTION_SNOOZE = "io.github.shoalapps.squill.SNOOZE"
        const val CHANNEL = "reminders"

        fun createChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        /** Shows the reminder, notes that it rang, and lines up its next ring if it has one. */
        fun ring(context: Context, id: Long, now: Long = System.currentTimeMillis()) {
            var rung: Reminder? = null
            Store.update(context) { list ->
                list.map { if (it.id == id && !it.done) it.rang(now).also { new -> rung = new } else it }
            }
            val reminder = rung ?: return
            notify(context, reminder)
            Scheduler.schedule(context, reminder, now)
        }

        private fun action(context: Context, id: Long, name: String, label: String): NotificationCompat.Action {
            val pi = PendingIntent.getBroadcast(
                context,
                (id.toInt() * 31) + name.hashCode(),
                Intent(context, AlarmReceiver::class.java).setAction(name).putExtra(EXTRA_ID, id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Action.Builder(0, label, pi).build()
        }

        private fun notify(context: Context, r: Reminder) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val open = PendingIntent.getActivity(
                context, r.id.toInt(), Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_ink)
                .setContentTitle(r.title)
                .setContentText(r.note.ifBlank { null })
                .setStyle(NotificationCompat.BigTextStyle().bigText(r.note.ifBlank { r.title }))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(open)
                .setAutoCancel(true)
                .addAction(action(context, r.id, ACTION_DONE, "Done"))
                .addAction(action(context, r.id, ACTION_SNOOZE, "Snooze ${Prefs.snoozeMinutes(context)} min"))
                .build()
            NotificationManagerCompat.from(context).notify(r.id.toInt(), n)
        }
    }
}
