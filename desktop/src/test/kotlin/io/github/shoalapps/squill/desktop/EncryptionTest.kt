package io.github.shoalapps.squill.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.github.shoalapps.squill.notes.LockedNotes
import io.github.shoalapps.squill.notes.NoteCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalTestApi::class)
class EncryptionTest {
    @get:Rule val dir = TemporaryFolder()

    private fun waitOn(check: () -> Boolean) {
        val until = System.currentTimeMillis() + 20_000
        while (!check()) { assertTrue(System.currentTimeMillis() < until); Thread.sleep(50) }
    }

    private fun Model.sync(block: Model.((Result<Unit>) -> Unit) -> Unit) {
        var done: Result<Unit>? = null
        block { done = it }
        waitOn { done != null }
        done!!.getOrThrow()
    }

    @Test fun aFolderSealsLocksOpensAndComesBack() {
        val root = dir.newFolder("notes")
        val m = Model(root, hosting = false)
        m.start()
        m.vault.create("", "Secret", "treasure map")
        m.sync { encrypt("correct horse", it) }
        val file = File(root, "Secret.md")
        assertTrue(NoteCipher.isSealed(file.readBytes()))
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("treasure"))

        m.lockNow()
        waitOn { m.needsUnlock.value }
        assertTrue(m.library.value.notes.isEmpty())
        assertTrue(runCatching { m.vault.create("", "Plain", "words") }.isFailure)
        m.stop()

        // A fresh start stays locked, and reads nothing until the password's given.
        val again = Model(root, hosting = false)
        again.start()
        assertTrue(again.needsUnlock.value)
        var wrong: Boolean? = null
        again.unlock("wrong password") { wrong = it }
        waitOn { wrong != null }
        assertEquals(false, wrong)
        assertTrue(again.needsUnlock.value)
        var right: Boolean? = null
        again.unlock("correct horse") { right = it }
        waitOn { right != null }
        assertEquals(true, right)
        waitOn { again.library.value.notes.isNotEmpty() }
        assertEquals("treasure map", again.vault.read("Secret.md").body)

        again.sync { decrypt("correct horse", it) }
        assertEquals("treasure map", file.readText())
        assertFalse(File(root, LockedNotes.ALL_NOTES_KEY).exists())
        again.stop()
    }

    @Test fun theLockedScreenAsksFirstAndAcceptsThePassword() = runDesktopComposeUiTest(1100, 800) {
        val root = dir.newFolder("ui")
        val first = Model(root, hosting = false)
        first.start()
        first.vault.create("", "Hidden", "words")
        first.sync { encrypt("correct horse", it) }
        first.stop()
        val m = Model(root, hosting = false)
        m.start()
        setContent { App(m, dark = false) }
        onNodeWithText("This folder is locked").assertExists()
        onNodeWithContentDescription("Password").performTextInput("correct horse")
        onNodeWithText("Unlock").performClick()
        waitUntilAtLeastOneExists(hasText("Hidden"), 20_000)
        m.stop()
    }
}
