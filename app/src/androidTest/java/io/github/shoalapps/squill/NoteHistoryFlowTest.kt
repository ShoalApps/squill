package io.github.shoalapps.squill

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shoalapps.squill.notes.NotesViewModel
import io.github.shoalapps.squill.notes.Vault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NoteHistoryFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val root = File(context.filesDir, "vault")
    private val vault = Vault(root)
    private val vm get() = ViewModelProvider(rule.activity)[NotesViewModel::class.java]

    @Before fun clean() {
        root.listFiles()?.filter { it.name != ".seeded" }?.forEach { it.deleteRecursively() }
        refresh()
    }

    private fun refresh() {
        rule.runOnUiThread { vm.refresh() }
        rule.waitForIdle()
    }

    private fun history() {
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Version history").performClick()
    }

    @Test fun previewIsReadOnlyAndRestoreKeepsTheReplacedTextAfterReopening() {
        vault.create("", "Recover", "earlier words")
        vault.save("Recover.md", "current words", false, null)
        refresh()
        rule.waitUntilExactlyOneExists(hasText("Recover"), 8000)
        rule.onNodeWithText("Recover").performClick()
        rule.waitUntilExactlyOneExists(hasContentDescription("Note name"), 8000)
        history()
        rule.onNodeWithText("Restore").assertIsNotEnabled()
        rule.waitUntilExactlyOneExists(hasContentDescription("Saved version 1"), 8000)
        rule.onNodeWithContentDescription("Saved version 1").performClick()
        rule.waitUntilExactlyOneExists(hasContentDescription("Version preview"), 8000)
        rule.onNodeWithContentDescription("Version preview").assertTextEquals("earlier words")
        assertEquals("current words", vault.read("Recover.md").body)
        rule.onNodeWithText("Restore").performClick()
        rule.waitUntil(8000) { vault.read("Recover.md").body == "earlier words" }
        rule.waitUntil(8000) { rule.onAllNodes(hasContentDescription("Version preview")).fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitUntilExactlyOneExists(hasText("Recover"), 8000)
        rule.onNodeWithText("Recover").performClick()
        rule.waitUntilExactlyOneExists(hasContentDescription("Note name"), 8000)
        history()
        rule.waitUntilExactlyOneExists(hasContentDescription("Saved version 1"), 8000)
        rule.onNodeWithContentDescription("Saved version 1").performClick()
        rule.waitUntilExactlyOneExists(hasContentDescription("Version preview"), 8000)
        rule.onNodeWithContentDescription("Version preview").assertTextEquals("current words")
        rule.onNodeWithText("Restore").performClick()
        rule.waitUntil(8000) { vault.read("Recover.md").body == "current words" }
        val reopened = Vault(root)
        assertTrue(reopened.history.list("Recover.md").any {
            reopened.history.read("Recover.md", it) == "earlier words"
        })
    }
}
