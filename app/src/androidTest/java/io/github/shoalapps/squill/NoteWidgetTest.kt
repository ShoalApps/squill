package io.github.shoalapps.squill

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shoalapps.squill.notes.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class NoteWidgetTest {
    @Test fun realVaultFlipStaleAndLockedRefusal() {
        val root = Files.createTempDirectory("widget-vault").toFile()
        try {
            val vault = Vault(root)
            val note = vault.create("", "Shopping", "- [ ] 牛乳 🥛\r\n- [x] Tea\r\n")
            val original = "\uFEFF---\r\npinned: true\r\ncustom: retained\r\n---\r\n${note.body}"
            File(root, note.path).writeText(original)
            val token = WidgetLines.parse(note.body)[0].fingerprint
            synchronized(NoteWidgetAccess.lock) {
                assertTrue(NoteWidgetAccess.flip(vault, note.path, 0, token))
                assertEquals(original.replace("- [ ] 牛乳", "- [x] 牛乳"), File(root, note.path).readText())
                assertEquals("- [x] 牛乳 🥛\r\n- [x] Tea\r\n", vault.read(note.path).body)
                assertFalse(NoteWidgetAccess.flip(vault, note.path, 0, token))
                val changed = vault.save(note.path, "inserted\n${note.body}", true, "blue")
                assertFalse(NoteWidgetAccess.flip(vault, note.path, 0, token))
                assertEquals(changed.body, vault.read(note.path).body)
                val key = PasswordKey.create(File(root, LockedNotes.ALL_NOTES_KEY), "test password")
                vault.convert(key)
                val sealed = File(root, note.path).readBytes()
                vault.cipher = null
                assertFalse(NoteWidgetAccess.flip(vault, note.path, 1, token))
                assertArrayEquals(sealed, File(root, note.path).readBytes())
                assertFalse(NoteWidgetAccess.flip(vault, "${LockedNotes.DIR}/secret.md", 0, token))
                vault.cipher = key
                assertEquals(changed.body, vault.read(note.path).body)
                assertTrue(NoteWidgetAccess.flip(vault, note.path, 1, token))
                assertTrue(vault.read(note.path).body.contains("- [x] 牛乳 🥛"))
                assertTrue(NoteCipher.isSealed(File(root, note.path).readBytes()))
                assertTrue(vault.history.list(note.path).isNotEmpty())
            }
        } finally { root.deleteRecursively() }
    }

    /** The real widget in a real host. Needs `adb shell appwidget grantbind --package <app>`. */
    @Test fun aHostedWidgetShowsTheListAndATapTicksTheFile() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val manager = android.appwidget.AppWidgetManager.getInstance(context)
        val host = android.appwidget.AppWidgetHost(context, 4711)
        val id = host.allocateAppWidgetId()
        val provider = android.content.ComponentName(context, NoteWidgetReceiver::class.java)
        org.junit.Assume.assumeTrue(manager.bindAppWidgetIdIfAllowed(id, provider))
        val root = File(context.filesDir, "vault")
        val note = File(root, "Widget list.md").apply { parentFile!!.mkdirs(); writeText("# Shopping\n- [ ] Milk\n- [x] Tea\n") }
        val rule = androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
        var frame: android.widget.FrameLayout? = null
        try {
            host.startListening()
            NoteWidgets.choose(context, id, note.name)
            rule.onActivity { activity ->
                val view = host.createView(activity, id, manager.getAppWidgetInfo(id))
                frame = android.widget.FrameLayout(activity).apply { setBackgroundColor(0xFF777777.toInt()); addView(view, android.widget.FrameLayout.LayoutParams(900, 900)) }
                activity.addContentView(frame, android.view.ViewGroup.LayoutParams(-1, -1))
            }
            NoteWidgets.update(context)
            fun list(): android.widget.ListView? { var found: android.widget.ListView? = null; rule.onActivity { a -> found = frame?.let { findList(it) } }; return found }
            val deadline = System.currentTimeMillis() + 15000
            fun checks(): List<android.widget.TextView> { val out = mutableListOf<android.widget.TextView>(); rule.onActivity { list()?.let { all(it, out) } }; return out.filter { it.visibility == android.view.View.VISIBLE && (it.text == "\u2610" || it.text == "\u2611") } }
            while (checks().size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(200)
            assertEquals(2, checks().size)
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 90, File(context.getExternalFilesDir(null), "widget.png").outputStream())
            rule.onActivity { checks().first { it.text == "\u2610" }.performClick() }
            while (!note.readText().contains("- [x] Milk") && System.currentTimeMillis() < deadline) Thread.sleep(200)
            assertEquals("# Shopping\n- [x] Milk\n- [x] Tea\n", note.readText())
        } finally {
            host.stopListening(); host.deleteAppWidgetId(id); rule.close(); note.delete()
        }
    }

    private fun findList(v: android.view.View): android.widget.ListView? {
        if (v is android.widget.ListView) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findList(v.getChildAt(i))?.let { return it }
        return null
    }
    private fun all(v: android.view.View, out: MutableList<android.widget.TextView>) {
        if (v is android.widget.TextView) out += v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) all(v.getChildAt(i), out)
    }
}
