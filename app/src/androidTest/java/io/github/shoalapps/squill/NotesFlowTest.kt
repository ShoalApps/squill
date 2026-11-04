package io.github.shoalapps.squill

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shoalapps.squill.notes.NotesViewModel
import io.github.shoalapps.squill.notes.Vault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The notes side, end to end, on a real Android: every check also looks at the file on disk. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NotesFlowTest {
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
        Thread.sleep(300)
        rule.waitForIdle()
    }

    private fun waitText(text: String) = rule.waitUntilExactlyOneExists(hasText(text), 8000)
    private fun waitDescription(text: String) = rule.waitUntilExactlyOneExists(hasContentDescription(text), 8000)
    private fun waitFor(check: () -> Boolean) = rule.waitUntil(8000, check)

    @Test fun aNewNoteIsWrittenToAFileAsYouType() {
        rule.onNodeWithContentDescription("New note").performClick()
        waitDescription("Note text")
        rule.onNodeWithContentDescription("Note text").performTextInput("Buy **ink**")
        waitFor { File(root, "Untitled.md").takeIf { it.exists() }?.readText() == "Buy **ink**" }
        rule.onNodeWithContentDescription("Back").performClick()
        waitText("Untitled")
        rule.onNodeWithText("Buy ink").assertExists()
    }

    @Test fun leavingStraightAwayStillSavesEverything() {
        rule.onNodeWithContentDescription("New note").performClick()
        waitDescription("Note text")
        rule.onNodeWithContentDescription("Note text").performTextInput("typed then gone")
        rule.onNodeWithContentDescription("Back").performClick()
        waitText("Untitled")
        assertEquals("typed then gone", File(root, "Untitled.md").readText())
    }

    @Test fun renamingANoteUpdatesLinksToIt() {
        vault.create("", "Target", "words")
        vault.create("", "Fan", "see [[Target]]")
        refresh()
        waitText("Target")
        rule.onNodeWithText("Target").performClick()
        waitDescription("Note name")
        rule.onNodeWithContentDescription("Note name").performTextInput("New ")
        rule.onNodeWithContentDescription("Note name").performImeAction()
        waitFor { File(root, "New Target.md").exists() }
        waitFor { File(root, "Fan.md").readText() == "see [[New Target]]" }
        assertFalse(File(root, "Target.md").exists())
    }

    @Test fun followingALinkOpensOrWritesTheNoteAndBacklinksShow() {
        vault.create("", "Target", "words here")
        vault.create("", "Fan", "see [[Target]]")
        refresh()
        waitText("Target")
        rule.onNodeWithText("Target").performClick()
        waitText("1 note links here")
        rule.onNodeWithText("Fan").performScrollTo().performClick()
        waitText("see Target")
    }

    @Test fun tickingABoxInReadingViewChangesTheFile() {
        vault.create("", "Shopping", "- [ ] Milk\n- [x] Eggs\n")
        refresh()
        waitText("Shopping")
        rule.onNodeWithText("Shopping").performClick()
        waitText("Milk")
        rule.runOnUiThread { }
        rule.onAllNodes(androidx.compose.ui.test.isToggleable())[0].performClick()
        waitFor { File(root, "Shopping.md").readText() == "- [x] Milk\n- [x] Eggs\n" }
    }

    @Test fun deletingGoesToTrashAndCanComeBack() {
        vault.create("Sub", "Gone", "still here")
        refresh()
        rule.runOnUiThread { vm.trash("Sub/Gone.md") }
        waitFor { !File(root, "Sub/Gone.md").exists() }
        refresh()
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Trash (1)").performClick()
        waitText("Gone")
        rule.onNodeWithText("Restore").performClick()
        waitFor { File(root, "Sub/Gone.md").exists() }
        assertEquals("still here", File(root, "Sub/Gone.md").readText())
    }

    @Test fun foldersOpenAndKeepTheirNotes() {
        vault.create("Boats", "Sails", "canvas")
        refresh()
        waitText("Boats")
        rule.onNodeWithText("Boats").performClick()
        waitText("Sails")
        rule.onNodeWithContentDescription("New note").performClick()
        waitDescription("Note text")
        assertTrue(File(root, "Boats/Untitled.md").exists())
    }

    @Test fun searchFindsTextInsideNotes() {
        vault.create("Deep/Down", "Hidden", "a unique marlin")
        vault.create("", "Other", "nothing")
        refresh()
        rule.onNodeWithContentDescription("Search").performClick()
        rule.onNode(hasText("Search your notes")).performTextInput("marlin")
        waitText("Hidden")
        rule.onNodeWithText("Other").assertDoesNotExist()
    }

    @Test fun aTagShowsItsNotes() {
        vault.create("A", "Tagged", "with #boats inside")
        vault.create("", "Plain", "nothing")
        refresh()
        waitText("#boats")
        rule.onNodeWithText("#boats").performClick()
        waitText("Tagged")
        rule.onNodeWithText("Plain").assertDoesNotExist()
    }

    @Test fun theGraphDrawsAndTheTabsWork() {
        vault.create("", "A", "[[B]]")
        vault.create("", "B", "")
        refresh()
        rule.onNodeWithText("Graph").performClick()
        waitDescription("Graph of 2 notes and 1 links")
        rule.onNodeWithText("Notes").performClick()
        waitText("A")
    }

    @Test fun sharedTextCanBecomeANote() {
        rule.runOnUiThread { vm.share("First line\nSecond line") }
        waitFor { File(root, "First line.md").exists() }
        assertEquals("First line\nSecond line", File(root, "First line.md").readText())
    }

    @Test fun aNoteTooHostileForTheVaultIsRefusedByName() {
        rule.runOnUiThread { vm.create("../..", "x") { } }
        rule.waitForIdle()
        waitFor { vm.problem.value != null }
        assertFalse(File(context.filesDir.parentFile, "x.md").exists())
    }
}
