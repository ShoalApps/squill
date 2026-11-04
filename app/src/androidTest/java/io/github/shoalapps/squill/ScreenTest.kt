package io.github.shoalapps.squill

import android.content.Context
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val day = 86_400_000L

    @Before fun clean() {
        Store.load(context).forEach { Scheduler.cancel(context, it.id) }
        Store.save(context, emptyList())
        rule.waitForIdle()
    }

    private fun seed(vararg reminders: Reminder) {
        Store.save(context, reminders.toList())
        rule.waitForIdle()
    }

    @Test fun jotDownSavesAReminder() {
        rule.onNodeWithContentDescription("Jot down").performClick()
        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onNodeWithText("Reminder").performTextInput("Buy ink")
        rule.onNodeWithText("Save").performClick()
        rule.onNodeWithText("Buy ink").assertExists()
        assertEquals(listOf("Buy ink"), Store.load(context).map { it.title })
    }

    @Test fun deleteCanBeUndone() {
        seed(Reminder(3001, "Keep me", whenMillis = System.currentTimeMillis() + day))
        rule.onNodeWithContentDescription("Delete").performClick()
        rule.waitForIdle()
        assertTrue(Store.load(context).isEmpty())
        rule.onNodeWithText("Undo").performClick()
        rule.waitForIdle()
        assertEquals(listOf("Keep me"), Store.load(context).map { it.title })
        rule.onNodeWithText("Keep me").assertExists()
    }

    @Test fun renamingAFinishedReminderKeepsItFinished() {
        seed(Reminder(3002, "Old name", whenMillis = System.currentTimeMillis() - day, done = true))
        rule.onNodeWithText("Old name").performClick()
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()
        assertTrue(Store.load(context).single().done)
    }

    @Test fun aOneOffInThePastCannotBeSaved() {
        seed(Reminder(3003, "Was weekly", whenMillis = System.currentTimeMillis() - day, repeat = Repeat.WEEKLY))
        rule.onNodeWithText("Was weekly").performClick()
        rule.onNodeWithText("Once").performClick()
        rule.onNodeWithText("That time has already passed.").assertExists()
        rule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test fun sharedTextOpensTheDialogWithItsTitle() {
        rule.activity.runOnUiThread { rule.activity.handle(
            android.content.Intent(android.content.Intent.ACTION_SEND).putExtra(android.content.Intent.EXTRA_TEXT, "Reply to Sam\nmore text"),
        ) }
        rule.waitForIdle()
        rule.onNodeWithText("Reply to Sam").assertExists()
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()
        assertEquals(listOf("Reply to Sam"), Store.load(context).map { it.title })
    }

    @Test fun clearDoneCanBeUndone() {
        seed(Reminder(3004, "Finished", whenMillis = System.currentTimeMillis() - day, done = true))
        rule.onNodeWithText("Clear").performClick()
        rule.waitForIdle()
        assertTrue(Store.load(context).isEmpty())
        rule.onNodeWithText("Undo").performClick()
        rule.waitForIdle()
        assertEquals(1, Store.load(context).size)
    }

    @Test fun overdueGetsItsOwnHeading() {
        seed(Reminder(3005, "Late", whenMillis = System.currentTimeMillis() - day, rungAt = 1))
        rule.onNodeWithText("Overdue").assertExists()
    }

    @Test fun settingsShowsTheSupportCardAndComesBack() {
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithText("Support Shoal").assertExists()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithContentDescription("Jot down").assertExists()
    }
}
