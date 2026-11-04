package io.github.shoalapps.squill

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Alarms are forgotten when the phone restarts or the app updates, so ink them in again,
 * and ring anything that came due while the phone was off.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val now = System.currentTimeMillis()
        Store.load(context).filter { it.missed(now) }.forEach { AlarmReceiver.ring(context, it.id, now) }
        Scheduler.scheduleAll(context)
    }
}
