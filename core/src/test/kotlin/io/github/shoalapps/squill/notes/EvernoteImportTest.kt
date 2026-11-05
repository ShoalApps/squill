package io.github.shoalapps.squill.notes

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

class EvernoteImportTest {
    @get:Rule val temp = TemporaryFolder()
    private fun export(vararg notes: String) = """<?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE en-export SYSTEM "http://xml.evernote.com/pub/evernote-export4.dtd">
        <en-export>${notes.joinToString("")}</en-export>"""
    private fun note(title: String = "Note", body: String = "Hello", extra: String = "") =
        "<note><title>${esc(title)}</title><content><![CDATA[<?xml version=\"1.0\"?><!DOCTYPE en-note SYSTEM \"http://xml.evernote.com/pub/enml2.dtd\"><en-note>$body</en-note>]]></content>$extra</note>"
    private fun run(vault: Vault, xml: String, name: String = "Notebook.enex", picture: (ByteArray) -> ByteArray = KeepImport::pngPainting) =
        EvernoteImport.import(vault, ByteArrayInputStream(xml.toByteArray()), name, picture)
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun resource(bytes: ByteArray, mime: String = "image/png", name: String = "pic.png") =
        "<resource><data encoding=\"base64\">${Base64.getEncoder().encodeToString(bytes)}</data><mime>$mime</mime><resource-attributes><file-name>${esc(name)}</file-name></resource-attributes></resource>"
    private val png get() = ByteArrayOutputStream().also { buffer ->
        val out = DataOutputStream(buffer)
        out.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        fun chunk(type: String, data: ByteArray) {
            val name = type.toByteArray()
            out.writeInt(data.size); out.write(name); out.write(data)
            out.writeInt(CRC32().apply { update(name); update(data) }.value.toInt())
        }
        chunk("IHDR", ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(1); writeInt(1); write(byteArrayOf(8, 6, 0, 0, 0)) } }.toByteArray())
        chunk("IDAT", ByteArrayOutputStream().also { DeflaterOutputStream(it).use { z -> z.write(byteArrayOf(0, 0, 0, 0, 255.toByte())) } }.toByteArray())
        chunk("IEND", byteArrayOf())
    }.toByteArray()

    @Test fun formattingTasksTagsAndDates() {
        val vault = Vault(temp.newFolder())
        val xml = export(note(body = """<h2>Heading</h2><div><b>Bold</b> <i>Italic</i> <a href="https://example.com">Link</a><br/>Next</div>
            <ul><li>First</li><li><en-todo checked="true"/>Done</li></ul><div><en-todo checked="false"/>Later</div>
            <ol><li>Numbered</li></ol><pre>a &lt; b
c</pre><div><code>inline</code></div><table><tr><th>A</th><th>B</th></tr><tr><td>one</td><td>two</td></tr></table>""",
            extra = "<tag>My tag</tag><tag>旅行</tag><tag>123</tag><created>20230101T000000Z</created><updated>20240131T120000Z</updated>"))
        assertEquals(EvernoteImport.Result(1, 0, 0, 0), run(vault, xml))
        val note = vault.read("Notebook/Note.md")
        assertEquals(1706702400000L, note.modified)
        listOf("## Heading", "**Bold** *Italic* [Link](https://example.com)\n\nNext", "- First", "- [x] Done", "- [ ] Later", "1. Numbered", "```\na < b\nc\n```", "`inline`", "| A | B |\n|---|---|\n| one | two |", "#My_tag #旅行 #tag_123").forEach { assertTrue("Missing $it in ${note.body}", it in note.body) }
        assertEquals(2, Markdown.parse(note.body).filterIsInstance<Block.Task>().size)
        assertTrue(Markdown.parse(note.body).filterIsInstance<Block.Paragraph>().any { it.text == "Next" })
    }

    @Test fun imagesAreMatchedByBytesAndEmbeddedInPlace() {
        val vault = Vault(temp.newFolder())
        val bytes = png
        val xml = export(note(body = "<div>Before</div><en-media hash=\"${hash(bytes).uppercase()}\"/><div>After</div><en-media hash=\"${hash(bytes)}\"/>", extra = resource(bytes, name = "../../pic.png")))
        assertEquals(EvernoteImport.Result(1, 0, 0, 0), run(vault, xml))
        val body = vault.read("Notebook/Note.md").body
        val target = Markdown.parse(body).filterIsInstance<Block.Embed>().first().target
        assertEquals("Notebook/pic.ora", target)
        assertTrue(body.indexOf("Before") < body.indexOf("![[$target]]"))
        assertTrue(body.indexOf("![[$target]]") < body.indexOf("After"))
        assertArrayEquals(bytes, readZip(ByteArrayInputStream(vault.readBytes(target)))["mergedimage.png"])
        assertEquals(1, vault.stats().count { it.path.endsWith(".ora") })
        assertEquals(EvernoteImport.Result(0, 1, 0, 0), run(vault, xml))
    }

    @Test fun missingUnsupportedAndBrokenResourcesAreCounted() {
        val vault = Vault(temp.newFolder())
        val xml = export(note(body = "Text<en-media hash=\"missing\"/>", extra = resource(byteArrayOf(1), "application/pdf") + resource(byteArrayOf(0)) + "<resource><data encoding=\"base64\">!!!</data></resource>"))
        // The invalid PNG is referenced so conversion is attempted.
        val withBadImage = xml.replace("Text<en-media", "Text<en-media hash=\"${hash(byteArrayOf(0))}\"/><en-media")
        assertEquals(EvernoteImport.Result(1, 0, 4, 0), run(vault, withBadImage))
        assertEquals("Text", vault.read("Notebook/Note.md").body)
    }

    @Test fun brokenNotesDoNotStopLaterNotes() {
        val vault = Vault(temp.newFolder())
        val xml = export("<note><title>Bad</title><wrong></note>", note("Bad ENML", "<b>unclosed"), "<note><title>Unfinished</title>", note("Good", "Kept"))
        assertEquals(EvernoteImport.Result(1, 0, 0, 3), run(vault, xml))
        assertEquals("Kept", vault.read("Notebook/Good.md").body)
    }

    @Test fun hostileXmlCannotResolveEntitiesOrCreateNotesFromCdata() {
        val vault = Vault(temp.newFolder())
        val secret = temp.newFile().apply { writeText("secret outside the vault") }
        val bad = "<note><!DOCTYPE note [<!ENTITY x SYSTEM \"${secret.toURI()}\">]><title>&x;</title></note>"
        val enml = note("Entity", "&x;").replace("<!DOCTYPE en-note SYSTEM \"http://xml.evernote.com/pub/enml2.dtd\">", "<!DOCTYPE en-note [<!ENTITY x 'expanded'>]>")
        val xml = export(bad, enml, note("Safe", "&lt;note&gt;fake&lt;/note&gt;<a href=\"javascript:alert(1)\">Text</a><script>bad</script>"))
        assertEquals(EvernoteImport.Result(1, 0, 0, 2), run(vault, xml))
        val body = vault.read("Notebook/Safe.md").body
        assertEquals("<note>fake</note>Text", body)
        assertFalse(vault.notes().any { "secret" in it.body || "expanded" in it.body })
    }

    @Test fun sizeAndDepthLimitsLeaveTheNextNoteReadable() {
        val vault = Vault(temp.newFolder())
        assertEquals(EvernoteImport.Result(1, 0, 0, 2), run(vault, export(
            note("Big", "x".repeat(2 * 1024 * 1024 + 1)), note("Deep", "<div>".repeat(130) + "x" + "</div>".repeat(130)), note("Good"))))
    }

    @Test fun duplicatesSurviveReopeningAndEditsAndNamesNeverOverwrite() {
        val root = temp.newFolder()
        val vault = Vault(root)
        vault.create("Notebook", "Same", "Existing")
        val xml = export(note("Same", "Imported"), note("Same", "Different"), note("Same", "Imported"))
        assertEquals(EvernoteImport.Result(2, 1, 0, 0), run(vault, xml))
        assertEquals("Existing", vault.read("Notebook/Same.md").body)
        vault.save("Notebook/Same 2.md", "Edited", false, null)
        assertEquals(EvernoteImport.Result(0, 3, 0, 0), run(Vault(root), xml))
        assertEquals("Edited", vault.read("Notebook/Same 2.md").body)
        assertEquals(1, run(vault, export(note("../Safe", "Body")), "../Trips.enex").imported)
        assertTrue(vault.exists("Trips/Safe.md"))
    }

    @Test fun encryptedVaultSealsNotesImagesAndMarkers() {
        val root = temp.newFolder()
        val key = NoteCipher(ByteArray(32) { it.toByte() })
        val vault = Vault(root).apply { cipher = key }
        val bytes = png
        val xml = export(note("Private", "Secret<en-media hash=\"${hash(bytes)}\"/>", resource(bytes)))
        assertEquals(1, run(vault, xml).imported)
        assertTrue(root.walkTopDown().filter { it.isFile }.all { NoteCipher.isSealed(it.readBytes()) })
        val reopened = Vault(root).apply { cipher = key }
        assertTrue(reopened.read("Notebook/Private.md").body.startsWith("Secret"))
        assertTrue(readZip(ByteArrayInputStream(reopened.readBytes("Notebook/pic.ora"))).containsKey("stack.xml"))
        assertEquals(1, run(reopened, xml).duplicates)
    }

    @Test fun evernoteCodeBlocksWhitespaceEntitiesAndNestedLists() {
        val vault = Vault(temp.newFolder())
        val xml = export(note(body = """<div>A&nbsp;B</div><div style="-en-codeblock: true;"><div>one</div><div>two</div></div><ul><li>Parent<ul><li>Child</li></ul></li></ul>"""))
        assertEquals(1, run(vault, xml).imported)
        val body = vault.read("Notebook/Note.md").body
        assertTrue(body, "A B" in body)
        assertTrue(body, "```\none\ntwo\n```" in body)
        assertTrue(body, "- Parent\n  - Child" in body)
    }

    @Test fun externalAndRecursiveEntityDeclarationsAreNeverExpanded() {
        val vault = Vault(temp.newFolder())
        val bomb = note("Bomb", "&c;").replace("<!DOCTYPE en-note SYSTEM \"http://xml.evernote.com/pub/enml2.dtd\">", "<!DOCTYPE en-note [<!ENTITY a 'payload'><!ENTITY b '&a;&a;'><!ENTITY c '&b;&b;'>]>")
        val xml = export(bomb, note("Good", "Kept")).replace("<!DOCTYPE en-export SYSTEM \"http://xml.evernote.com/pub/evernote-export4.dtd\">", "<!DOCTYPE en-export [<!ENTITY external SYSTEM 'file:///not-read'>]>")
        assertEquals(EvernoteImport.Result(1, 0, 0, 1), run(vault, xml))
    }

    @Test fun oversizedFrameAndSparseTableDoNotStopTheNextNote() {
        val vault = Vault(temp.newFolder())
        val huge = note("Big", "x".repeat(32 * 1024 * 1024 + 1))
        val table = "<table><tr>" + "<td>x</td>".repeat(1000) + "</tr>" + "<tr><td>x</td></tr>".repeat(1000) + "</table>"
        assertEquals(EvernoteImport.Result(1, 0, 0, 2), run(vault, export(huge, note("Table", table), note("Good"))))
    }

    @Test fun failedNoteRemovesNewImagesAndLeavesExistingFilesAlone() {
        val root = temp.newFolder()
        val vault = Vault(root)
        vault.create("Notebook", "Note", "Existing")
        java.io.File(root, ".evernote-import").writeText("Blocks marker folder")
        val bytes = png
        assertEquals(1, run(vault, export(note(body = "<en-media hash=\"${hash(bytes)}\"/>", extra = resource(bytes)))).failed)
        assertEquals(listOf("Notebook/Note.md"), vault.stats().map { it.path })
        assertEquals("Existing", vault.read("Notebook/Note.md").body)
    }

    @Test fun lockedVaultNeverGainsPlaintext() {
        val root = temp.newFolder()
        java.io.File(root, LockedNotes.ALL_NOTES_KEY).apply { parentFile.mkdirs(); writeText("locked") }
        assertEquals(1, run(Vault(root), export(note())).failed)
        assertEquals(listOf(LockedNotes.ALL_NOTES_KEY), root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.toList())
    }

    @Test fun encryptedTextIsCountedAndMentionedInTheNote() {
        val vault = Vault(temp.newFolder("crypt"))
        val result = run(vault, export(note(body = "Before<en-crypt cipher=\"AES\">c2VjcmV0</en-crypt>")))
        assertEquals(1, result.skippedAttachments)
        val body = vault.read("Notebook/Note.md").body
        assertTrue(body, body.contains("Before") && body.contains("wasn't brought in"))
        assertFalse(body, body.contains("c2VjcmV0"))
    }
}
