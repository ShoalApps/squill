package io.github.shoalapps.squill

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shoalapps.squill.notes.LockedNotes
import io.github.shoalapps.squill.notes.NoteCipher
import io.github.shoalapps.squill.notes.NotesViewModel
import io.github.shoalapps.squill.notes.Vault
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PrivacyFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val root = File(context.filesDir, "vault")
    private val vault = Vault(root)
    private val vm get() = ViewModelProvider(rule.activity)[NotesViewModel::class.java]

    @Before fun clean() {
        root.listFiles()?.filter { it.name != ".seeded" }?.forEach { it.deleteRecursively() }
        rule.runOnUiThread { vm.refresh() }
        rule.waitForIdle(); Thread.sleep(300)
    }

    private fun waitFor(check: () -> Boolean) = rule.waitUntil(20_000, check)
    private fun menu(label: String) {
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText(label).performClick()
    }

    @Test fun aLockedNoteIsSealedAndHiddenFromTheList() {
        menu("Locked notes")
        rule.waitUntilExactlyOneExists(hasText("Create locked vault"), 8000)
        rule.onNodeWithContentDescription("Password").performTextInput("correct horse")
        rule.onNodeWithContentDescription("Password again").performTextInput("correct horse")
        rule.onNodeWithText("Create locked vault").performClick()
        rule.waitUntilExactlyOneExists(hasText("New locked note"), 20_000)
        rule.onNodeWithText("New locked note").performClick()
        rule.waitUntilExactlyOneExists(hasContentDescription("Locked note text"), 8000)
        rule.onNodeWithContentDescription("Locked note text").performTextInput("buried treasure")
        waitFor { File(root, LockedNotes.DIR).listFiles().orEmpty().any { it.name.endsWith(".enc") } }
        val file = File(root, LockedNotes.DIR).listFiles()!!.first { it.name.endsWith(".enc") }
        waitFor { NoteCipher.isSealed(file.readBytes()) }
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("treasure"))
        assertTrue(vault.notes().isEmpty())
    }

    @Test fun aNoteCanMoveIntoTheVault() {
        LockedNotes(root).setUp("correct horse")
        vault.create("", "Diary", "dear diary")
        rule.runOnUiThread { vm.refresh() }
        rule.waitUntilExactlyOneExists(hasText("Diary"), 8000)
        rule.onNodeWithText("Diary").performClick()
        rule.waitUntilExactlyOneExists(hasContentDescription("More"), 8000)
        menu("Move to locked notes")
        rule.waitUntilExactlyOneExists(hasText("Unlock"), 8000)
        rule.onNodeWithContentDescription("Password").performTextInput("correct horse")
        rule.onNodeWithText("Unlock").performClick()
        waitFor { !File(root, "Diary.md").exists() }
        val check = LockedNotes(root); check.unlock("correct horse")
        assertEquals("dear diary", check.notes().single().body)
    }

    @Test fun encryptingAllNotesThenTurningItOffKeepsThemReadable() {
        vault.create("", "Plain", "secret words")
        rule.runOnUiThread { vm.refresh() }
        rule.waitUntilExactlyOneExists(hasText("Plain"), 8000)
        var done = false
        rule.runOnUiThread { vm.encryptAll("correct horse") { done = true } }
        waitFor { done }
        assertTrue(NoteCipher.isSealed(File(root, "Plain.md").readBytes()))
        rule.waitUntilExactlyOneExists(hasText("Plain"), 8000)
        // Locking clears everything from the screen until the password is given.
        rule.runOnUiThread { vm.lockAll() }
        rule.waitUntilExactlyOneExists(hasText("Your notes are locked"), 8000)
        rule.onNodeWithContentDescription("Password").performTextInput("wrong password")
        rule.onNodeWithText("Unlock").performClick()
        rule.waitUntilExactlyOneExists(hasText("That password isn't right"), 20_000)
        var ok = false
        rule.runOnUiThread { vm.unlockAll("correct horse") { ok = it } }
        waitFor { ok }
        rule.waitUntilExactlyOneExists(hasText("Plain"), 8000)
        done = false
        rule.runOnUiThread { vm.decryptAll("correct horse") { done = true } }
        waitFor { done }
        assertFalse(NoteCipher.isSealed(File(root, "Plain.md").readBytes()))
        assertEquals("secret words", File(root, "Plain.md").readText())
    }

    @Test fun aPictureOpensAsAPainting() {
        val png = File(context.cacheDir, "Sunset.png")
        val bmp = android.graphics.Bitmap.createBitmap(40, 30, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFFFF0000.toInt())
        png.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        var opened: String? = null
        rule.runOnUiThread { vm.importOffice(context, android.net.Uri.fromFile(png), "") { opened = it.getOrNull() } }
        waitFor { opened != null }
        val p = io.github.shoalapps.squill.paint.OraFile.load(File(root, opened!!).readBytes())
        assertEquals(40, p.width)
        assertEquals(0xFFFF0000.toInt(), p.layers.single().pixels[0])
    }
}

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TourTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun theTourWalksThroughEveryPage() {
        var result: Boolean? = null
        rule.setContent { SquillTheme { TourScreen { result = it } } }
        rule.waitUntilExactlyOneExists(hasText("Hello, I'm Squilly"), 5000)
        rule.onNodeWithContentDescription("Squilly the squid").assertExists()
        listOf("Notes", "Links and the graph", "Drawing", "Painting", "Slides and documents", "Reminders", "Keeping things private", "Where should your notes live?").forEach { title ->
            rule.onNodeWithText("Next").performClick()
            rule.waitUntilExactlyOneExists(hasText(title), 5000)
        }
        rule.onNodeWithText("Keep them private").performClick()
        rule.waitUntil(5000) { result == false }
    }

    @Test fun theTourCanBeSkipped() {
        var result: Boolean? = null
        rule.setContent { SquillTheme { TourScreen { result = it } } }
        rule.onNodeWithText("Skip").performClick()
        rule.waitUntil(5000) { result == false }
    }
}
