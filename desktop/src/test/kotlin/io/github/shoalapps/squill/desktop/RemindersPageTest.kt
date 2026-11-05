package io.github.shoalapps.squill.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalTestApi::class)
class RemindersPageTest {
    @get:Rule val dir = TemporaryFolder()

    @Test fun addEditCompleteAndDelete() = runDesktopComposeUiTest(1200, 1000) {
        val model = Model(dir.newFolder(), hosting = false)
        model.start()
        try {
            setContent { App(model) }
            onNodeWithText("Reminders").performClick()
            onNodeWithText("Add reminder").performClick()
            onNodeWithText("Title").performTextReplacement("Tea")
            onNodeWithText("Note").performTextReplacement("Bring a cup")
            onNodeWithText("When (yyyy-MM-dd HH:mm)").performTextReplacement("2030-11-05 09:30")
            onNodeWithText("Change repeat").performClick()
            onNodeWithText("Save").performClick()
            waitUntil(timeoutMillis = 5000) { model.reminders.value.size == 1 }
            onNodeWithText("Tea").assertExists()
            onNodeWithText("Edit").performClick()
            onNodeWithText("Title").performTextReplacement("Coffee")
            onNodeWithText("Save").performClick()
            waitUntil(timeoutMillis = 5000) { model.reminders.value.single().title == "Coffee" }
            onNodeWithContentDescription("Complete Coffee").performClick()
            waitUntil(timeoutMillis = 5000) { model.reminders.value.single().done }
            onNodeWithText("Delete").performClick()
            waitUntil(timeoutMillis = 5000) { model.reminders.value.isEmpty() }
            assertNull(model.reminderStore.entries().single().reminder)
            assertTrue(model.vault.notes().isEmpty())
        } finally { model.stop() }
    }
}
