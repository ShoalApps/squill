package io.github.shoalapps.squill

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Alarms are forgotten when the phone restarts or the app updates, and go stale when the clock
 * changes, so ink them in again,
 * and ring anything that came due while the phone was off.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED) return
        val now = System.currentTimeMillis()
        Store.load(context).filter { it.missed(now) }.forEach { AlarmReceiver.ring(context, it.id, now) }
        Scheduler.scheduleAll(context)
    }

    private companion object {
        val HANDLED = setOf(
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            // A new clock or time zone moves when "9am" is.
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}
