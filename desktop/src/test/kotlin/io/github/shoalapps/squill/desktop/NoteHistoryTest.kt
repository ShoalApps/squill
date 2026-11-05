package io.github.shoalapps.squill.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalTestApi::class)
class NoteHistoryTest {
    @get:Rule val dir = TemporaryFolder()

    @Test fun anOldVersionCanBePreviewedRestoredAndTheRestoreUndone() = runDesktopComposeUiTest(1600, 1000) {
        val model = Model(dir.newFolder("notes"), hosting = false, deviceName = "Test")
        try {
            model.vault.create("", "Recover", "earlier words")
            model.vault.save("Recover.md", "current words", false, null)
            model.reloadNow()
            setContent { App(model, dark = false, initialOpen = "Recover.md") }
            onNodeWithText("Version history").performClick()
            onNodeWithText("Restore").assertIsNotEnabled()
            waitUntilAtLeastOneExists(hasContentDescription("Saved version 1"), 8000)
            onNodeWithContentDescription("Saved version 1").performClick()
            waitUntilAtLeastOneExists(hasContentDescription("Version preview"), 8000)
            onNodeWithContentDescription("Version preview").assertTextEquals("earlier words")
            assertEquals("current words", model.vault.read("Recover.md").body)
            onNodeWithText("Restore").performClick()
            waitUntil(timeoutMillis = 8000) { model.vault.read("Recover.md").body == "earlier words" }
            waitUntil(timeoutMillis = 8000) { onAllNodes(hasContentDescription("Version preview")).fetchSemanticsNodes().isEmpty() }
            assertTrue(model.vault.history.list("Recover.md").any {
                model.vault.history.read("Recover.md", it) == "current words"
            })
            onNodeWithText("Version history").performClick()
            waitUntilAtLeastOneExists(hasContentDescription("Saved version 1"), 8000)
            onNodeWithContentDescription("Saved version 1").performClick()
            waitUntilAtLeastOneExists(hasContentDescription("Version preview"), 8000)
            onNodeWithContentDescription("Version preview").assertTextEquals("current words")
            onNodeWithText("Restore").performClick()
            waitUntil(timeoutMillis = 8000) { model.vault.read("Recover.md").body == "current words" }
        } finally {
            model.stop()
        }
    }

    @Test fun aNewNoteExplainsThatThereAreNoSavedVersions() = runDesktopComposeUiTest(1600, 1000) {
        val model = Model(dir.newFolder("empty"), hosting = false, deviceName = "Test")
        try {
            model.vault.create("", "New", "words")
            model.reloadNow()
            setContent { App(model, dark = false, initialOpen = "New.md") }
            onNodeWithText("Version history").performClick()
            waitUntil(timeoutMillis = 8000) { onAllNodes(androidx.compose.ui.test.hasText("No saved versions yet")).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Restore").assertIsNotEnabled()
            onNodeWithText("Close").performClick()
            assertEquals("words", model.vault.read("New.md").body)
        } finally {
            model.stop()
        }
    }
}
