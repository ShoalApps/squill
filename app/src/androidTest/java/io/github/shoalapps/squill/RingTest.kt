package io.github.shoalapps.squill

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real alarms and real notifications, on a real Android. */
@RunWith(AndroidJUnit4::class)
class RingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val minute = 60_000L

    @Before fun clean() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        wipe()
    }

    @After fun wipe() {
        Store.load(context).forEach { Scheduler.cancel(context, it.id) }
        Store.save(context, emptyList())
        notifications.cancelAll()
    }

    private fun showing(id: Long) = notifications.activeNotifications.firstOrNull { it.id == id.toInt() }

    private fun waitFor(timeoutMillis: Long, check: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < end) {
            if (check()) return true
            Thread.sleep(200)
        }
        return check()
    }

    private fun stored(id: Long) = Store.load(context).first { it.id == id }

    private fun press(action: String, id: Long) =
        AlarmReceiver().onReceive(context, Intent(context, AlarmReceiver::class.java).setAction(action).putExtra(AlarmReceiver.EXTRA_ID, id))

    private fun restart() = BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

    @Test fun anAlarmRingsAtItsTime() {
        val at = System.currentTimeMillis() + 3_000
        val r = Reminder(id = 2001, title = "Soon", whenMillis = at)
        Store.save(context, listOf(r))
        Scheduler.schedule(context, r)
        assertNull("rang early", showing(r.id))
        assertTrue("never rang", waitFor(20_000) { showing(r.id) != null })
        assertTrue(stored(r.id).rungAt >= at)
    }

    @Test fun doneFinishesAOneOffButNotARepeat() {
        val past = System.currentTimeMillis() - minute
        val once = Reminder(id = 2002, title = "Once", whenMillis = past)
        val daily = Reminder(id = 2003, title = "Daily", whenMillis = past, repeat = Repeat.DAILY)
        Store.save(context, listOf(once, daily))
        AlarmReceiver.ring(context, once.id)
        AlarmReceiver.ring(context, daily.id)
        assertNotNull(showing(once.id))

        press(AlarmReceiver.ACTION_DONE, once.id)
        press(AlarmReceiver.ACTION_DONE, daily.id)
        assertNull(showing(once.id))
        assertTrue(stored(once.id).done)
        assertFalse(stored(daily.id).done)
    }

    @Test fun snoozeRingsAgainAndSurvivesARestart() {
        val r = Reminder(id = 2004, title = "Later", whenMillis = System.currentTimeMillis() - minute, rungAt = 1)
        Store.save(context, listOf(r))
        press(AlarmReceiver.ACTION_SNOOZE, r.id)
        val snoozed = stored(r.id)
        assertTrue(snoozed.snoozedUntil > System.currentTimeMillis())
        assertEquals(snoozed.snoozedUntil, snoozed.nextAfter(System.currentTimeMillis()))

        restart()
        assertEquals("restart lost the snooze", snoozed.snoozedUntil, stored(r.id).snoozedUntil)
        assertNull("rang before the snooze ended", showing(r.id))
    }

    @Test fun aRestartRingsWhatWasMissedAndNothingElse() {
        val past = System.currentTimeMillis() - minute
        val missed = Reminder(id = 2005, title = "Missed", whenMillis = past)
        val alreadyRung = Reminder(id = 2006, title = "Rang", whenMillis = past, rungAt = past)
        val finished = Reminder(id = 2007, title = "Finished", whenMillis = past, done = true)
        Store.save(context, listOf(missed, alreadyRung, finished))

        restart()
        assertNotNull(showing(missed.id))
        assertNull(showing(alreadyRung.id))
        assertNull(showing(finished.id))

        notifications.cancelAll()
        restart()
        assertNull("rang twice", showing(missed.id))
    }

    @Test fun aDeletedReminderStaysSilent() {
        val r = Reminder(id = 2008, title = "Gone", whenMillis = System.currentTimeMillis() - minute)
        Store.save(context, emptyList())
        AlarmReceiver.ring(context, r.id)
        assertNull(showing(r.id))
    }
}
