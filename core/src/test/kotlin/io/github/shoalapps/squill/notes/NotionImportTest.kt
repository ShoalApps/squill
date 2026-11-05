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

class NotionImportTest {
    @get:Rule val temp = TemporaryFolder()
    private val a = "0123456789abcdef0123456789abcdef"
    private val b = "1123456789abcdef0123456789abcdef"
    private val c = "2123456789abcdef0123456789abcdef"
    private val d = "3123456789abcdef0123456789abcdef"
    private fun zip(vararg files: Pair<String, String>) = bytesZip(*files.map { it.first to it.second.toByteArray() }.toTypedArray())
    private fun bytesZip(vararg files: Pair<String, ByteArray>) = ByteArrayOutputStream().also { writeZip(it, linkedMapOf(*files)) }.toByteArray()
    private fun run(vault: Vault, bytes: ByteArray) = NotionImport.import(vault, ByteArrayInputStream(bytes))
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

    @Test fun hierarchyIdsAndOrdinaryMarkdown() {
        val vault = Vault(temp.newFolder())
        val markdown = "# Trip\n\n## To do\n- [ ] Pack\n- [x] Book\n\nSome **ordinary** text.\n"
        val data = zip("Trip Plan $a.md" to markdown,
            "Trip Plan $a/Day one $b.md" to "First day",
            "Trip Plan $a/Day one $b/Places $c.md" to "Places",
            "No id.md" to "Unchanged")
        assertEquals(NotionImport.Result(4, 0, 0, 0), run(vault, data))
        assertEquals(markdown, vault.read("Trip Plan.md").body)
        assertEquals("First day", vault.read("Trip Plan/Day one.md").body)
        assertEquals("Places", vault.read("Trip Plan/Day one/Places.md").body)
        assertEquals("Unchanged", vault.read("No id.md").body)
        val before = vault.root.walkTopDown().filter { it.isFile }.map { it.relativeTo(vault.root).path }.toSet()
        assertEquals(NotionImport.Result(0, 4, 0, 0), run(Vault(vault.root), data))
        assertEquals(before, vault.root.walkTopDown().filter { it.isFile }.map { it.relativeTo(vault.root).path }.toSet())
    }

    @Test fun linksUseFinalPathsIncludingSameNamesEncodedNamesAndBacklinks() {
        val vault = Vault(temp.newFolder())
        vault.create("", "Trip Plan", "Existing")
        val data = zip("Trip Plan $a.md" to "[First](Trip%20Plan%20$a/Same%20$b.md)\n[Second](Trip%20Plan%20$a/Same%20$c.md#Heading%20one)\n[Plus](A+B%20$d.md)",
            "Trip Plan $a/Same $b.md" to "[Parent](../Trip%20Plan%20$a.md)",
            "Trip Plan $a/Same $c.md" to "Other",
            "A+B $d.md" to "Plus")
        assertEquals(4, run(vault, data).imported)
        val paths = vault.notes().map { it.path }
        val links = Links.scan(vault.read("Trip Plan 2.md").body).first
        assertEquals(listOf("Trip Plan/Same", "Trip Plan/Same 2#Heading one", "A+B"), links)
        assertEquals(listOf("Trip Plan/Same.md", "Trip Plan/Same 2.md", "A+B.md"), links.map { Links.resolve(it, paths) })
        assertEquals("Trip Plan 2.md", Links.resolve(Links.scan(vault.read("Trip Plan/Same.md").body).first.single(), paths))
        assertEquals(3, Graph.of(vault.notes().associate { it.path to Links.scan(it.body).first }).edges.size)
        assertEquals("Existing", vault.read("Trip Plan.md").body)
    }

    @Test fun foldersWithSameNamesAndExistingFilesNeverMergeOrOverwrite() {
        val vault = Vault(temp.newFolder())
        vault.createFolder("", "Same")
        vault.create("Same", "Child", "Existing")
        vault.writeFile("File") { } // A file can occupy a desired folder name too.
        val data = zip("Same $a/Child $c.md" to "One", "Same $b/Child $d.md" to "Two", "File/Note.md" to "Three")
        assertEquals(3, run(vault, data).imported)
        assertEquals("Existing", vault.read("Same/Child.md").body)
        assertEquals("One", vault.read("Same 2/Child.md").body)
        assertEquals("Two", vault.read("Same 3/Child.md").body)
        val more = zip("Same $a/Next.md" to "Next")
        assertEquals(1, run(Vault(vault.root), more).imported)
        assertEquals("Next", vault.read("Same 2/Next.md").body)
    }

    @Test fun codeAndExternalLinksStayUnchanged() {
        val vault = Vault(temp.newFolder())
        val code = "`[Inline](Other%20$b.md)`\n````md\n[Code](Other%20$b.md)\n```\n[Still code](Other%20$b.md)\n````\n    [Indented](Other%20$b.md)\n[Web](https://example.com/a.md)\n[Mail](mailto:a@example.com)\n[Heading](#Heading)\n"
        val source = code + "[Go](Other%20$b.md)"
        assertEquals(2, run(vault, zip("Note $a.md" to source, "Other $b.md" to "Other")).imported)
        assertEquals(code + "[[Other|Go]]", vault.read("Note.md").body)
    }

    @Test fun picturesAndSkippedAttachmentsUseVaultStorage() {
        val vault = Vault(temp.newFolder())
        vault.createFile("", "photo", "ora") { it.write(byteArrayOf(1)) }
        val data = bytesZip("Page $a.md" to ("![Photo](Page%20$a/photo.png)\n[PDF](Page%20$a/manual.pdf)\n![Missing](missing.png)\n![Bad](bad.png)\n![Escape](../escape.png)").toByteArray(),
            "Page $a/photo.png" to png, "Page $a/manual.pdf" to byteArrayOf(1), "bad.png" to byteArrayOf(1))
        assertEquals(NotionImport.Result(1, 0, 4, 0), run(vault, data))
        val body = vault.read("Page.md").body
        val embed = Markdown.parse(body).filterIsInstance<Block.Embed>().single().target
        assertEquals("photo 2.ora", embed)
        assertArrayEquals(png, readZip(ByteArrayInputStream(vault.readBytes(embed)))["mergedimage.png"])
        assertTrue(body.contains("File not imported: manual.pdf"))
        assertTrue(body.contains("File not imported: missing.png"))
        assertTrue(body.contains("File not imported: bad.png"))
        assertTrue(body.contains("File not imported: escape.png"))
        assertFalse(vault.root.walkTopDown().any { it.extension == "pdf" })
        assertEquals(NotionImport.Result(0, 1, 0, 0), run(vault, data))
    }

    @Test fun csvBecomesOneTableWithQuotedCellsAndMultilineValues() {
        val vault = Vault(temp.newFolder())
        val csv = "\uFEFFName,Details,Empty\r\nAlice,\"Comma, and \"\"quotes\"\"\",\r\nBob,\"First\nSecond | end\",\r\n"
        assertEquals(NotionImport.Result(1, 0, 0, 0), run(vault, zip("Database $a.csv" to csv)))
        val table = Markdown.parse(vault.read("Database.md").body).filterIsInstance<Block.Table>().single()
        assertEquals(listOf("Name", "Details", "Empty"), table.header)
        assertEquals(listOf("Alice", "Comma, and \"quotes\"", ""), table.rows[0])
        assertEquals("First<br>Second &#124; end", table.rows[1][1])
        assertEquals(1, run(vault, zip("Database $a.csv" to csv)).duplicates)
    }

    @Test fun brokenFilesAndOversizedCsvAndMarkdownDoNotStopOtherPages() {
        val vault = Vault(temp.newFolder())
        val large = "x".repeat(2 * 1024 * 1024 + 1)
        val result = run(vault, zip("Huge.csv" to large, "Huge.md" to large, "Broken.csv" to "A\n\"unfinished", "Good.md" to "Good"))
        assertEquals(NotionImport.Result(1, 0, 1, 2), result)
        assertEquals(listOf("Good.md"), vault.notes().map { it.path })
    }

    @Test fun csvExpansionIsBoundedAndCountedAsSkipped() {
        val vault = Vault(temp.newFolder())
        val ragged = (1..3000).joinToString(",") { "x" } + "\n" + "x\n".repeat(1000)
        assertEquals(NotionImport.Result(1, 0, 1, 0), run(vault, zip("Ragged.csv" to ragged, "Good.md" to "Good")))
    }

    @Test fun nestedPartsShareLinksAndStopAtOneLevel() {
        val vault = Vault(temp.newFolder())
        val first = bytesZip("Parent $a.md" to "[Child](Parent%20$a/Child%20$b.md)".toByteArray(),
            "deeper.zip" to zip("Hidden.md" to "Hidden"))
        val second = zip("Parent $a/Child $b.md" to "[Up](../Parent%20$a.md)")
        val data = bytesZip("Export-xxx-Part-1.zip" to first, "Export-xxx-Part-2.zip" to second, "broken.zip" to byteArrayOf(1))
        assertEquals(NotionImport.Result(2, 0, 1, 1), run(vault, data))
        assertEquals("[[Parent/Child|Child]]", vault.read("Parent.md").body)
        assertEquals("[[Parent|Up]]", vault.read("Parent/Child.md").body)
        assertFalse(vault.exists("Hidden.md"))
        assertEquals(NotionImport.Result(0, 2, 1, 1), run(vault, data))
    }

    @Test fun traversalIsRejectedInOuterAndInnerArchives() {
        val vault = Vault(temp.newFolder())
        val inner = zip("../inner.md" to "No", "C:\\outside.md" to "No", "Good $a.md" to "Good")
        val data = bytesZip("../outside.md" to "No".toByteArray(), "/absolute.md" to "No".toByteArray(),
            "x/../../bad.md" to "No".toByteArray(), "nested.zip" to inner)
        assertEquals(NotionImport.Result(1, 0, 0, 5), run(vault, data))
        assertEquals(listOf("Good.md"), vault.notes().map { it.path })
    }

    @Test fun duplicateMarkersSurviveEditsAndReopening() {
        val vault = Vault(temp.newFolder())
        val data = zip("Parent $a/Note $b.md" to "Original")
        assertEquals(1, run(vault, data).imported)
        vault.save("Parent/Note.md", "Edited", false, null)
        assertEquals(NotionImport.Result(0, 1, 0, 0), run(Vault(vault.root), data))
        assertEquals("Edited", vault.read("Parent/Note.md").body)
        assertEquals(1, run(vault, zip("Elsewhere/Note $b.md" to "Original")).duplicates)
    }

    @Test fun repeatedPageInDifferentPartsIsImportedOnce() {
        val vault = Vault(temp.newFolder())
        val data = bytesZip("one.zip" to zip("Note $a.md" to "Body"), "two.zip" to zip("Copy/Note $a.md" to "Body"))
        assertEquals(NotionImport.Result(1, 1, 0, 0), run(vault, data))
        assertEquals(1, vault.notes().size)
    }

    @Test fun encryptedVaultSealsNotesImagesAndMarkers() {
        val root = temp.newFolder()
        val key = NoteCipher(ByteArray(32) { it.toByte() })
        val vault = Vault(root).apply { cipher = key }
        val data = bytesZip("Parent $a/Private $b.md" to "Secret\n![Pic](pic.png)".toByteArray(), "Parent $a/pic.png" to png)
        assertEquals(NotionImport.Result(1, 0, 0, 0), run(vault, data))
        assertTrue(root.walkTopDown().filter { it.isFile }.all { NoteCipher.isSealed(it.readBytes()) })
        val reopened = Vault(root).apply { cipher = key }
        assertTrue(reopened.read("Parent/Private.md").body.startsWith("Secret"))
        assertArrayEquals(png, readZip(ByteArrayInputStream(reopened.readBytes("Parent/pic.ora")))["mergedimage.png"])
        assertEquals(NotionImport.Result(0, 1, 0, 0), run(reopened, data))
    }

    @Test fun lockedVaultGainsNoPlaintextFiles() {
        val root = temp.newFolder()
        java.io.File(root, LockedNotes.ALL_NOTES_KEY).apply { parentFile.mkdirs(); writeText("locked") }
        assertEquals(1, run(Vault(root), zip("Parent $a/Secret $b.md" to "Secret")).failed)
        assertTrue(root.walkTopDown().filter { it.isFile }.all { it.name == java.io.File(LockedNotes.ALL_NOTES_KEY).name })
    }

    @Test fun failedPictureConversionIsSkippedAndDoesNotLeaveFiles() {
        val vault = Vault(temp.newFolder())
        val data = bytesZip("Note $a.md" to "![Image](image.png)".toByteArray(), "image.png" to png)
        val result = NotionImport.import(vault, ByteArrayInputStream(data)) { error("Bad image") }
        assertEquals(NotionImport.Result(1, 0, 1, 0), result)
        assertTrue(vault.read("Note.md").body.contains("File not imported: image.png"))
        assertEquals(0, vault.stats().count { it.path.endsWith(".ora") })
    }
    @Test fun linksInMarkdownTablesStillProduceBacklinks() {
        val vault = Vault(temp.newFolder())
        val data = zip("Index $a.md" to "| Page |\n| --- |\n| [Go](A%20(B)%20$b.md) |\n| [Heading](A%20(B)%20$b.md#Part) |", "A (B) $b.md" to "# Part")
        assertEquals(2, run(vault, data).imported)
        val links = Links.scan(vault.read("Index.md").body).first
        assertEquals(listOf("A (B)", "A (B)#Part"), links)
        assertTrue(links.all { Links.resolve(it, vault.notes().map { n -> n.path }) == "A (B).md" })
    }

    @Test fun oversizedImagesAreSkippedWithoutStoppingTextImport() {
        val vault = Vault(temp.newFolder())
        val data = bytesZip("Note.md" to "![Large](large.png)".toByteArray(), "large.png" to ByteArray(20 * 1024 * 1024 + 1))
        assertEquals(NotionImport.Result(1, 0, 1, 0), run(vault, data))
        assertTrue(vault.read("Note.md").body.contains("File not imported: large.png"))
    }

    @Test fun entryLimitIsSharedAcrossNestedParts() {
        val first = bytesZip(*(0 until 10_000).map { "a$it.txt" to byteArrayOf() }.toTypedArray())
        val second = bytesZip(*(0 until 10_000).map { "b$it.txt" to byteArrayOf() }.toTypedArray())
        val data = bytesZip("one.zip" to first, "two.zip" to second)
        try {
            run(Vault(temp.newFolder()), data)
            fail("The combined entry count must be bounded")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Too many files"))
        }
    }

    @Test fun newPagesCanLinkToPreviouslyImportedPages() {
        val vault = Vault(temp.newFolder())
        assertEquals(1, run(vault, zip("Old $a.md" to "Old")).imported)
        vault.save("Old.md", "Edited locally", false, null)
        val data = zip("Old $a.md" to "Old", "New $b.md" to "[Old](Old%20$a.md)")
        assertEquals(NotionImport.Result(1, 1, 0, 0), run(Vault(vault.root), data))
        assertEquals("[[Old|Old]]", vault.read("New.md").body)
        assertEquals("Edited locally", vault.read("Old.md").body)
    }

    @Test fun inlineImagesBecomeVisibleEmbedsAndRemoteImagesStayAsWritten() {
        val vault = Vault(temp.newFolder())
        val data = bytesZip("Note.md" to "Before ![Local](pic.png) after\n![Remote](https://example.com/pic.png)".toByteArray(), "pic.png" to png)
        assertEquals(NotionImport.Result(1, 0, 0, 0), run(vault, data))
        val body = vault.read("Note.md").body
        assertEquals("pic.ora", Markdown.parse(body).filterIsInstance<Block.Embed>().single().target)
        assertTrue(body.contains("![Remote](https://example.com/pic.png)"))
    }

    @Test fun longMalformedMarkdownLinksDoNotStopOtherPages() {
        val vault = Vault(temp.newFolder())
        val text = "[" + "x".repeat(100_000)
        assertEquals(NotionImport.Result(2, 0, 0, 0), run(vault, zip("Long.md" to text, "Good.md" to "Good")))
        assertEquals(text, vault.read("Long.md").body)
    }

    @Test fun bracketedLabelsAndEncodedUnicodeNamesResolve() {
        val vault = Vault(temp.newFolder())
        val data = zip("Index $a.md" to "[Budget [draft]](%E6%97%85%E8%A1%8C%20$b.md)", "旅行 $b.md" to "Travel")
        assertEquals(2, run(vault, data).imported)
        assertEquals(listOf("旅行"), Links.scan(vault.read("Index.md").body).first)
    }

    @Test fun longFolderNamesStillGetDistinctNumberedNames() {
        val vault = Vault(temp.newFolder())
        val name = "x".repeat(120)
        vault.createFolder("", name)
        val data = zip("$name $a/Child.md" to "Child", "$name $b/Other.md" to "Other")
        assertEquals(NotionImport.Result(2, 0, 0, 0), run(vault, data))
        assertEquals("Child", vault.read("${name.take(118)} 2/Child.md").body)
        assertEquals("Other", vault.read("${name.take(118)} 3/Other.md").body)
    }

}
