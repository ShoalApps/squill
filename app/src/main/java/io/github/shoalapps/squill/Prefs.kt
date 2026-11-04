package io.github.shoalapps.squill

import android.content.Context

object Prefs {
    val SNOOZE_CHOICES = listOf(5, 10, 15, 30, 60)
    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun snoozeMinutes(context: Context): Int = prefs(context).getInt("snooze", 10)
    fun setSnoozeMinutes(context: Context, minutes: Int) = prefs(context).edit().putInt("snooze", minutes).apply()
}
