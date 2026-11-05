package io.github.shoalapps.squill

import android.content.Context

object Prefs {
    val SNOOZE_CHOICES = listOf(5, 10, 15, 30, 60)
    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun snoozeMinutes(context: Context): Int = prefs(context).getInt("snooze", 10)
    /** The folder notes live in, or null for private to Squill. */
    fun notesFolder(context: Context): String? = prefs(context).getString("notes-folder", null)
    fun setNotesFolder(context: Context, path: String?) { prefs(context).edit().putString("notes-folder", path).commit() }

    fun tourSeen(context: Context): Boolean = prefs(context).getBoolean("tour-seen", false)
    fun setTourSeen(context: Context) = prefs(context).edit().putBoolean("tour-seen", true).apply()

    fun setSnoozeMinutes(context: Context, minutes: Int) = prefs(context).edit().putInt("snooze", minutes).apply()
}
