package io.github.shoalapps.squill

import kotlinx.serialization.Serializable
import java.util.Calendar

enum class Repeat(val label: String) { NONE("Once"), DAILY("Daily"), WEEKLY("Weekly") }

@Serializable
data class Reminder(
    val id: Long,
    val title: String,
    val note: String = "",
    val whenMillis: Long,
    val repeat: Repeat = Repeat.NONE,
    val done: Boolean = false,
) {
    /** The next time this should ring after [now], or null if it never will again. */
    fun nextAfter(now: Long): Long? {
        if (repeat == Repeat.NONE) return whenMillis.takeIf { it > now }
        val step = if (repeat == Repeat.DAILY) Calendar.DAY_OF_YEAR else Calendar.WEEK_OF_YEAR
        val cal = Calendar.getInstance().apply { timeInMillis = whenMillis }
        while (cal.timeInMillis <= now) cal.add(step, 1)
        return cal.timeInMillis
    }
}
