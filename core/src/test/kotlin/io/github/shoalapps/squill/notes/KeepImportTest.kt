package io.github.shoalapps.squill.notes

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

class KeepImportTest {
    @get:Rule val temp = TemporaryFolder()

    private fun zip(vararg files: Pair<String, String>) = bytesZip(*files.map { it.first to it.second.toByteArray() }.toTypedArray())
    private fun bytesZip(vararg files: Pair<String, ByteArray>) = ByteArrayOutputStream().also { writeZip(it, linkedMapOf(*files)) }.toByteArray()
    private fun run(vault: Vault, bytes: ByteArray) = KeepImport.import(vault, ByteArrayInputStream(bytes))
    private val png get() = ByteArrayOutputStream().also { buffer ->
        val out = DataOutputStream(buffer)
        out.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        fun chunk(type: String, data: ByteArray) {
            val name = type.toByteArray()
            out.writeInt(data.size)
            out.write(name)
            out.write(data)
            out.writeInt(CRC32().apply { update(name); update(data) }.value.toInt())
        }
        val header = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).apply { writeInt(1); writeInt(1); write(byteArrayOf(8, 6, 0, 0, 0)) }
        }.toByteArray()
        chunk("IHDR", header)
        val pixels = ByteArrayOutputStream().also { DeflaterOutputStream(it).use { z -> z.write(byteArrayOf(0, 0, 0, 0, 255.toByte())) } }.toByteArray()
        chunk("IDAT", pixels)
        chunk("IEND", byteArrayOf())
    }.toByteArray()

    @Test fun textPropertiesAndMissingFields() {
        val vault = Vault(temp.newFolder())
        val result = run(vault, zip("Takeout/Keep/one.json" to """{
            "title":"A note", "textContent":"Hello\nworld", "isPinned":true, "color":"RED",
            "labels":[{"name":"My label"},{"name":"旅行"},{"name":"123"}],
            "userEditedTimestampUsec":1700000000123000, "unknown":{"future":true}
        }""", "one.json" to "{}"))
        assertEquals(KeepImport.Result(2, 0, 0, 0), result)
        val note = vault.read("A note.md")
        assertEquals("Hello\nworld\n\n#My_label #旅行 #tag_123", note.body)
        assertEquals(listOf("My_label", "旅行", "tag_123"), Links.scan(note.body).second)
        assertTrue(note.pinned)
        assertEquals("red", note.colour)
        assertEquals(1700000000123, note.modified)
        assertTrue(vault.exists("one.md"))
    }

    @Test fun checklistsArchiveAndTrashInAnyFolder() {
        val vault = Vault(temp.newFolder())
        val result = run(vault, zip("Daten/Notizen/list.json" to """{
            "title":"Shopping", "isArchived":true, "createdTimestampUsec":"1600000000000000",
            "listContent":[{"text":"Milk","isChecked":true},{"text":"Bread"}]
        }""", "Daten/Notizen/trash.json" to """{"title":"Gone","isTrashed":true}"""))
        assertEquals(1, result.imported)
        val note = vault.read("Archive/Shopping.md")
        assertEquals("- [x] Milk\n- [ ] Bread", note.body)
        assertEquals(1600000000000, note.modified)
        assertEquals(2, Markdown.parse(note.body).filterIsInstance<Block.Task>().size)
        assertEquals(1, vault.notes().size)
    }

    @Test fun attachmentsUseVaultStorageAndEmbeds() {
        val vault = Vault(temp.newFolder())
        val data = bytesZip("Takeout/Keep/note.json" to """{
            "title":"Photo", "isArchived":true, "attachments":[
                {"filePath":"photos/pic.png","mimetype":"image/png"},
                {"filePath":"voice.ogg","mimetype":"audio/ogg"},
                {"filePath":"missing.png","mimetype":"image/png"},
                {"filePath":"../../escape.png","mimetype":"image/png"},
                {"filePath":"bad.png","mimetype":"image/png"}
            ]}""".toByteArray(), "Takeout/Keep/photos/pic.png" to png,
            "Takeout/Keep/bad.png" to byteArrayOf(0))
        val result = run(vault, data)
        assertEquals(KeepImport.Result(1, 0, 4, 0), result)
        val target = Markdown.parse(vault.read("Archive/Photo.md").body).filterIsInstance<Block.Embed>().single().target
        assertEquals("Archive/pic.ora", target)
        val ora = readZip(ByteArrayInputStream(vault.readBytes(target)))
        assertArrayEquals(png, ora["mergedimage.png"])
        assertEquals(1, io.github.shoalapps.squill.paint.OraStack.parse(ora.getValue("stack.xml")).width)
        assertEquals(KeepImport.Result(0, 1, 0, 0), run(vault, data))
        assertEquals(1, vault.stats().count { it.path.endsWith(".ora") })
    }

    @Test fun malformedAndUnsafeEntriesDoNotStopOtherNotes() {
        val vault = Vault(temp.newFolder())
        val result = run(vault, zip("broken.json" to "{", "../outside.json" to "{}",
            "C:\\outside.json" to "{}", "x/../../outside.json" to "{}", "/absolute.json" to "{}",
            "valid.json" to """{"title":"Good","textContent":"Kept"}"""))
        assertEquals(KeepImport.Result(1, 0, 0, 5), result)
        assertEquals("Kept", vault.read("Good.md").body)
        assertEquals(1, vault.notes().size)
    }

    @Test fun sizeLimitsDoNotStopTheNextNote() {
        val vault = Vault(temp.newFolder())
        val result = run(vault, zip("big.json" to " ".repeat(2 * 1024 * 1024 + 1), "deep.json" to ("[".repeat(200) + "0" + "]".repeat(200)), "fine.json" to "{}"))
        assertEquals(2, result.failed)
        assertEquals(1, result.imported)
    }

    @Test fun noOverwritesAndFingerprintsSurviveReopeningAndEdits() {
        val root = temp.newFolder()
        val vault = Vault(root)
        vault.create("", "Same", "Existing")
        val data = zip("one.json" to """{"title":"Same","textContent":"Imported"}""",
            "two.json" to """{"title":"Same","textContent":"Different"}""")
        assertEquals(2, run(vault, data).imported)
        assertEquals("Existing", vault.read("Same.md").body)
        vault.save("Same 2.md", "Edited here", false, null)
        assertEquals(KeepImport.Result(0, 2, 0, 0), run(Vault(root), data))
        assertEquals(3, vault.notes().size)
        assertEquals("Edited here", vault.read("Same 2.md").body)
        // JSON whitespace, key order and export location do not change identity.
        assertEquals(1, run(vault, zip("anders/name.json" to """{ "textContent":"Imported", "title":"Same" }""")).duplicates)
    }

    @Test fun repeatedContentInOneZipIsImportedOnceAndNamesAreSafe() {
        val vault = Vault(temp.newFolder())
        val note = """{"title":"../Safe","textContent":"Body"}"""
        assertEquals(KeepImport.Result(1, 1, 0, 0), run(vault, zip("first.json" to note, "other.json" to note)))
        assertEquals("Body", vault.read("Safe.md").body)
        assertEquals(listOf("Safe.md"), vault.notes().map { it.path })
    }

    @Test fun encryptedVaultSealsNotesImagesAndImportState() {
        val root = temp.newFolder()
        val vault = Vault(root)
        val key = NoteCipher(ByteArray(32) { it.toByte() })
        vault.cipher = key
        val data = bytesZip("note.json" to """{"title":"Private","textContent":"Secret","attachments":[{"filePath":"pic.png","mimetype":"image/png"}]}""".toByteArray(), "pic.png" to png)
        assertEquals(1, run(vault, data).imported)
        assertTrue(root.walkTopDown().filter { it.isFile }.all { NoteCipher.isSealed(it.readBytes()) })
        val reopened = Vault(root).apply { cipher = key }
        assertTrue(reopened.read("Private.md").body.startsWith("Secret"))
        assertTrue(readZip(ByteArrayInputStream(reopened.readBytes("pic.ora"))).containsKey("stack.xml"))
        assertEquals(1, run(reopened, data).duplicates)
    }

    @Test fun lockedVaultDoesNotGainPlaintextFiles() {
        val root = temp.newFolder()
        java.io.File(root, LockedNotes.ALL_NOTES_KEY).apply { parentFile.mkdirs(); writeText("locked") }
        val vault = Vault(root)
        assertEquals(1, run(vault, zip("note.json" to "{}" )).failed)
        assertTrue(vault.notes().isEmpty())
    }

    @Test fun otherGoogleProductsJsonIsLeftOut() {
        val vault = Vault(temp.newFolder("other"))
        val result = run(vault, zip(
            "Takeout/Fit/daily.json" to """{"steps": 1000}""",
            "Takeout/Keep/Real.json" to """{"title": "Real", "textContent": "words", "userEditedTimestampUsec": 1700000000000000}""",
        ))
        assertEquals(1, result.imported)
        assertEquals(0, result.failed)
        assertEquals(listOf("Real.md"), vault.notes().map { it.path })
    }
}
