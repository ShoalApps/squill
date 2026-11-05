package io.github.shoalapps.squill

import kotlinx.serialization.Serializable
import java.util.Calendar
import java.util.TimeZone

enum class Repeat(val label: String) {
    NONE("Once"), DAILY("Daily"), WEEKDAYS("Weekdays"), WEEKLY("Weekly"), MONTHLY("Monthly"), YEARLY("Yearly")
}

/** The headings the list is grouped under, in order. */
enum class Section(val label: String) { OVERDUE("Overdue"), TODAY("Today"), TOMORROW("Tomorrow"), LATER("Later") }

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
    /** Shared identity. Empty only for old backups and a reminder before its first save. */
    val syncId: String = "",
) {
    /** The next time this should ring after [now], or null if it never will again. */
    fun nextAfter(now: Long): Long? {
        val snooze = snoozedUntil.takeIf { it > now }
        return listOfNotNull(snooze, nextRegular(now)).minOrNull()
    }

    private fun nextRegular(now: Long): Long? {
        if (repeat == Repeat.NONE) return whenMillis.takeIf { it > now }
        // Always count from the original date, so the 31st comes back after a short month.
        val (field, longestStep) = when (repeat) {
            Repeat.WEEKLY -> Calendar.WEEK_OF_YEAR to 7 * DAY
            Repeat.MONTHLY -> Calendar.MONTH to 31 * DAY
            Repeat.YEARLY -> Calendar.YEAR to 366 * DAY
            else -> Calendar.DAY_OF_YEAR to DAY
        }
        // Skip ahead so an old reminder doesn't walk through every day since it was set.
        var n = ((now - whenMillis) / longestStep - 1).coerceAtLeast(0).toInt()
        while (true) {
            val cal = Calendar.getInstance().apply { timeInMillis = whenMillis; add(field, n) }
            val weekend = cal.get(Calendar.DAY_OF_WEEK).let { it == Calendar.SATURDAY || it == Calendar.SUNDAY }
            if (cal.timeInMillis > now && !(repeat == Repeat.WEEKDAYS && weekend)) return cal.timeInMillis
            n++
        }
    }

    /** Which heading this belongs under. A one-off that rang but was never ticked is overdue. */
    fun section(now: Long): Section {
        val next = nextAfter(now) ?: return Section.OVERDUE
        val days = dayNumber(next) - dayNumber(now)
        return when (days) { 0L -> Section.TODAY; 1L -> Section.TOMORROW; else -> Section.LATER }
    }

    /** True if a ring came due while nothing could ring it, such as when the phone was off. */
    fun missed(now: Long): Boolean = !done && (
        (repeat == Repeat.NONE && whenMillis <= now && rungAt == 0L) || snoozedUntil in 1..now
    )

    /** An overdue copy arriving from another device must not ring as a missed local alarm. */
    fun arrived(now: Long): Reminder = if (missed(now)) rang(now).copy(snoozedUntil = 0) else this

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

private const val DAY = 86_400_000L

/** A count of local calendar days, for comparing dates across daylight saving. */
private fun dayNumber(millis: Long): Long {
    val cal = Calendar.getInstance().apply { timeInMillis = millis }
    return (cal.timeInMillis + cal.get(Calendar.ZONE_OFFSET) + cal.get(Calendar.DST_OFFSET)) / 86_400_000L
}

/** The one-tap times offered when jotting something down. */
fun quickTimes(now: Long): List<Pair<String, Long>> {
    fun at(daysAhead: Int, hour: Int) = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, daysAhead)
        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val inAnHour = (now + 3_600_000L) / 60_000L * 60_000L
    return listOfNotNull(
        "In an hour" to inAnHour,
        at(0, 20).takeIf { it > inAnHour }?.let { "Tonight" to it },
        "Tomorrow" to at(1, 9),
        "Next week" to at(7, 9),
    )
}

/**
 * A restored backup laid over what's here; the backup wins where both have the same reminder.
 * Old one-offs and snoozes count as already rung, or restoring would set them all off at the next restart.
 */
fun mergeBackup(current: List<Reminder>, backup: List<Reminder>, now: Long): List<Reminder> {
    val usable = backup.filter { it.id > 0 && it.title.isNotBlank() }.distinctBy { it.syncId.ifEmpty { "legacy:${it.id}" } }.map {
        if (it.missed(now)) it.copy(rungAt = now, snoozedUntil = 0) else it
    }
    return current.filterNot { existing -> usable.any { restored ->
        if (restored.syncId.isNotEmpty()) restored.syncId == existing.syncId else restored.id == existing.id
    } } + usable
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
