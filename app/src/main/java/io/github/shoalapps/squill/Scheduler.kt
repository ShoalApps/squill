package io.github.shoalapps.squill

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object Scheduler {
    private fun pending(context: Context, id: Long, legacy: Boolean = false): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            id.toInt(),
            Intent(context, AlarmReceiver::class.java).apply { if (!legacy) data = android.net.Uri.parse("squill:reminder/$id") }.putExtra(AlarmReceiver.EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun schedule(context: Context, reminder: Reminder, now: Long = System.currentTimeMillis()) {
        cancel(context, reminder.id)
        if (reminder.done) return
        val at = reminder.nextAfter(now) ?: return
        val alarms = context.getSystemService(AlarmManager::class.java)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        if (canExact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context, reminder.id))
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context, reminder.id))
    }

    fun cancel(context: Context, id: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.cancel(pending(context, id))
        alarms.cancel(pending(context, id, legacy = true))
    }

    fun scheduleAll(context: Context) = Store.load(context).forEach { schedule(context, it) }
}
