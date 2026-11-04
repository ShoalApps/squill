package io.github.shoalapps.squill

import kotlinx.serialization.Serializable
import java.util.Calendar
import java.util.TimeZone

enum class Repeat(val label: String) { NONE("Once"), DAILY("Daily"), WEEKLY("Weekly") }

@Serializable
data class Reminder(
    val id: Long,
    val title: String,
    val note: String = "",
    val whenMillis: Long,
    val repeat: Repeat = Repeat.NONE,
    val done: Boolean = false,
    /** When a snoozed ring is due, or 0. Saved so a restart doesn't lose it. */
    val snoozedUntil: Long = 0,
    /** When this last rang, or 0. A one-off that never rang was missed while the phone was off. */
    val rungAt: Long = 0,
) {
    /** The next time this should ring after [now], or null if it never will again. */
    fun nextAfter(now: Long): Long? {
        val snooze = snoozedUntil.takeIf { it > now }
        val regular = if (repeat == Repeat.NONE) {
            whenMillis.takeIf { it > now }
        } else {
            val step = if (repeat == Repeat.DAILY) Calendar.DAY_OF_YEAR else Calendar.WEEK_OF_YEAR
            val cal = Calendar.getInstance().apply { timeInMillis = whenMillis }
            while (cal.timeInMillis <= now) cal.add(step, 1)
            cal.timeInMillis
        }
        return listOfNotNull(snooze, regular).minOrNull()
    }

    /** True if a ring came due while nothing could ring it, such as when the phone was off. */
    fun missed(now: Long): Boolean = !done && (
        (repeat == Repeat.NONE && whenMillis <= now && rungAt == 0L) || snoozedUntil in 1..now
    )

    /** This reminder just after ringing at [now]. A snooze still to come stays in place. */
    fun rang(now: Long): Reminder = copy(rungAt = now, snoozedUntil = snoozedUntil.takeIf { it > now } ?: 0)

    /** This reminder after the edit dialog. Moving the time makes it due again; anything else keeps its state. */
    fun edited(title: String, note: String, whenMillis: Long, repeat: Repeat): Reminder {
        val moved = whenMillis != this.whenMillis || repeat != this.repeat
        return copy(
            title = title, note = note, whenMillis = whenMillis, repeat = repeat,
            done = done && !moved,
            snoozedUntil = if (moved) 0 else snoozedUntil,
            rungAt = if (moved) 0 else rungAt,
        )
    }
}

/** The local calendar day of [millis] as UTC midnight, which is what Material's date picker works in. */
fun localDayAsUtc(millis: Long): Long {
    val local = Calendar.getInstance().apply { timeInMillis = millis }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

/** [millis] moved to the day the date picker chose ([utcDay]), keeping its local time of day. */
fun withPickedDay(millis: Long, utcDay: Long): Long {
    val picked = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcDay }
    return Calendar.getInstance().apply {
        timeInMillis = millis
        set(picked.get(Calendar.YEAR), picked.get(Calendar.MONTH), picked.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}
