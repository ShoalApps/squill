package io.github.shoalapps.squill

import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ReminderTest {
    private val original = TimeZone.getDefault()

    @Before fun london() = TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
    @After fun restore() = TimeZone.setDefault(original)

    private val hour = 3_600_000L
    private val day = 24 * hour

    private fun at(year: Int, month: Int, date: Int, h: Int, m: Int = 0): Long =
        Calendar.getInstance().apply { clear(); set(year, month - 1, date, h, m) }.timeInMillis

    private fun reminder(whenMillis: Long, repeat: Repeat = Repeat.NONE) =
        Reminder(id = 1, title = "t", whenMillis = whenMillis, repeat = repeat)

    @Test fun oneOffRingsOnceThenNever() {
        val r = reminder(1000 * day)
        assertEquals(1000 * day, r.nextAfter(999 * day))
        assertNull(r.nextAfter(1000 * day))
    }

    @Test fun dailyAndWeeklyStepForward() {
        val start = at(2026, 1, 5, 9)
        assertEquals(at(2026, 1, 8, 9), reminder(start, Repeat.DAILY).nextAfter(at(2026, 1, 7, 12)))
        assertEquals(at(2026, 1, 19, 9), reminder(start, Repeat.WEEKLY).nextAfter(at(2026, 1, 12, 9)))
    }

    @Test fun repeatsKeepTheClockTimeAcrossDaylightSaving() {
        // UK clocks went forward on 29 March 2026.
        val next = reminder(at(2026, 3, 28, 9), Repeat.DAILY).nextAfter(at(2026, 3, 28, 10))
        assertEquals(at(2026, 3, 29, 9), next)
    }

    @Test fun snoozeRingsEvenWhenTheOneOffHasPassed() {
        val r = reminder(10 * day).copy(snoozedUntil = 11 * day)
        assertEquals(11 * day, r.nextAfter(10 * day + 1))
        assertNull(r.nextAfter(11 * day))
    }

    @Test fun earlierOfSnoozeAndNextRepeatWins() {
        val start = at(2026, 1, 5, 9)
        val soon = at(2026, 1, 5, 9, 10)
        assertEquals(soon, reminder(start, Repeat.DAILY).copy(snoozedUntil = soon).nextAfter(start))
    }

    @Test fun rangKeepsAFutureSnoozeAndClearsADueOne() {
        val r = reminder(10 * day).copy(snoozedUntil = 11 * day)
        assertEquals(11 * day, r.rang(10 * day).snoozedUntil)
        assertEquals(0, r.rang(11 * day).snoozedUntil)
        assertEquals(11 * day, r.rang(11 * day).rungAt)
    }

    @Test fun onlyUnrungOneOffsAndDueSnoozesCountAsMissed() {
        val r = reminder(10 * day)
        assertFalse(r.missed(9 * day))
        assertTrue(r.missed(11 * day))
        assertFalse(r.rang(10 * day).missed(11 * day))
        assertFalse(r.copy(done = true).missed(11 * day))
        assertFalse(reminder(10 * day, Repeat.DAILY).missed(11 * day))
        assertTrue(r.rang(10 * day).copy(snoozedUntil = 10 * day + 1).missed(11 * day))
    }

    @Test fun editingTextKeepsStateButMovingTheTimeMakesItDueAgain() {
        val finished = reminder(10 * day).copy(done = true, rungAt = 10 * day)
        val renamed = finished.edited("new", "", 10 * day, Repeat.NONE)
        assertTrue(renamed.done)
        assertEquals("new", renamed.title)
        assertEquals(10 * day, renamed.rungAt)

        val moved = finished.edited("new", "", 20 * day, Repeat.NONE)
        assertFalse(moved.done)
        assertEquals(0, moved.rungAt)
    }

    @Test fun datePickerDayIsTheLocalDayNearMidnight() {
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"))
        val lateEvening = at(2026, 6, 15, 23, 30)
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(2026, 5, 15) }.timeInMillis
        assertEquals(utc, localDayAsUtc(lateEvening))

        val picked = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(2026, 5, 20) }.timeInMillis
        assertEquals(at(2026, 6, 20, 23, 30), withPickedDay(lateEvening, picked))
    }

    @Test fun oldSavedRemindersStillLoad() {
        val old = Json.decodeFromString<Reminder>("""{"id":5,"title":"t","whenMillis":100,"repeat":"DAILY"}""")
        assertEquals(Repeat.DAILY, old.repeat)
        assertEquals(0, old.snoozedUntil)
    }
}
