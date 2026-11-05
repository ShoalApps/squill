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
import io.github.shoalapps.squill.paint.frozen
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

    @Test fun aKeepExportBringsInNotesAndPhotos() {
        val bmp = android.graphics.Bitmap.createBitmap(40, 30, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFF00FF00.toInt())
        val jpeg = java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        val zip = java.io.ByteArrayOutputStream().also { out ->
            io.github.shoalapps.squill.notes.writeZip(out, linkedMapOf(
                "Takeout/Keep/Shopping.json" to """{"title":"Shopping","listContent":[{"text":"Eggs","isChecked":true},{"text":"Tea","isChecked":false}],"labels":[{"name":"Home list"}],"attachments":[{"filePath":"photo.jpg","mimetype":"image/jpeg"}],"isPinned":true,"userEditedTimestampUsec":1700000000000000}""".toByteArray(),
                "Takeout/Keep/photo.jpg" to jpeg,
            ))
        }.toByteArray()
        val result = io.github.shoalapps.squill.notes.KeepImport.import(vault, zip.inputStream()) { bytes ->
            java.io.ByteArrayOutputStream().also { out ->
                io.github.shoalapps.squill.paint.OraFile.save(io.github.shoalapps.squill.paint.OraFile.fromPicture(bytes).frozen(), out)
            }.toByteArray()
        }
        assertEquals(1, result.imported)
        assertEquals(0, result.skippedAttachments)
        val note = vault.read("Shopping.md")
        assertTrue(note.pinned)
        assertTrue(note.body, note.body.contains("- [x] Eggs") && note.body.contains("- [ ] Tea") && note.body.contains("#Home_list"))
        assertTrue(note.body, note.body.contains(".ora]]"))
        val embed = Regex("!\\[\\[(.+?)]]").find(note.body)!!.groupValues[1]
        assertEquals(40, io.github.shoalapps.squill.paint.OraFile.load(vault.readBytes(embed)).width)
    }

    @Test fun anEvernoteExportComesInOnThisPhonesParser() {
        val bmp = android.graphics.Bitmap.createBitmap(40, 30, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFF0000FF.toInt())
        val png = java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val hash = java.security.MessageDigest.getInstance("MD5").digest(png).joinToString("") { "%02x".format(it) }
        val data = android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP)
        val enml = """<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE en-note SYSTEM "http://xml.evernote.com/pub/enml2.dtd"><en-note><h1>Trip</h1><div>Fish&nbsp;and <b>chips</b></div><div><en-todo checked="true"/>Pack</div><div><en-todo/>Go</div><en-media type="image/png" hash="$hash"/></en-note>"""
        val enex = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE en-export SYSTEM "http://xml.evernote.com/pub/evernote-export4.dtd">
<en-export export-date="20240101T000000Z" application="Evernote" version="10.0">
<note><title>Holiday</title><created>20240101T000000Z</created><updated>20240102T030405Z</updated><tag>Big trip</tag>
<content><![CDATA[$enml]]></content>
<resource><data encoding="base64">$data</data><mime>image/png</mime><resource-attributes><file-name>beach.png</file-name></resource-attributes></resource>
</note></en-export>"""
        val picture = { bytes: ByteArray ->
            java.io.ByteArrayOutputStream().also { out ->
                io.github.shoalapps.squill.paint.OraFile.save(io.github.shoalapps.squill.paint.OraFile.fromPicture(bytes).frozen(), out)
            }.toByteArray()
        }
        val result = io.github.shoalapps.squill.notes.EvernoteImport.import(vault, enex.toByteArray().inputStream(), "Travel.enex", picture)
        assertEquals(1, result.imported)
        assertEquals(0, result.failed)
        assertEquals(0, result.skippedAttachments)
        val note = vault.read("Travel/Holiday.md")
        assertTrue(note.body, note.body.contains("# Trip") && note.body.contains("**chips**"))
        assertTrue(note.body, note.body.contains("- [x] Pack") && note.body.contains("- [ ] Go"))
        assertTrue(note.body, note.body.contains("#Big_trip") && note.body.contains(".ora]]"))
        assertEquals(1, io.github.shoalapps.squill.notes.EvernoteImport.import(vault, enex.toByteArray().inputStream(), "Travel.enex", picture).duplicates)
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
