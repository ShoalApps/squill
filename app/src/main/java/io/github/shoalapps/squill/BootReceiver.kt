package io.github.shoalapps.squill

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Alarms are forgotten when the phone restarts or the app updates, so ink them in again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Scheduler.scheduleAll(context)
    }
}
