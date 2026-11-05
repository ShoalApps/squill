package io.github.shoalapps.squill.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

class MarkdownTest {
    @Test fun blocksAreRecognised() {
        val b = Markdown.parse("# Title\n\nSome text\nmore text\n\n- one\n- [x] done\n- [ ] todo\n1. first\n> said\n\n---\n```kt\nval a = 1\n```")
        assertEquals(Block.Heading(1, "Title", 0), b[0])
        assertEquals(Block.Paragraph("Some text more text", 2), b[1])
        assertEquals(Block.Bullet(0, "one", 5), b[2])
        assertEquals(Block.Task(0, true, "done", 6), b[3])
        assertEquals(Block.Task(0, false, "todo", 7), b[4])
        assertEquals(Block.Numbered(0, 1, "first", 8), b[5])
        assertEquals(Block.Quote("said", 9), b[6])
        assertEquals(Block.Rule(11), b[7])
        assertEquals(Block.Code("kt", "val a = 1", 12), b[8])
    }

    @Test fun anUnclosedCodeBlockRunsToTheEnd() {
        assertEquals(listOf(Block.Code("", "a\nb", 0)), Markdown.parse("```\na\nb"))
    }

    @Test fun tablesAreRecognised() {
        val b = Markdown.parse("| a | b |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |") .single() as Block.Table
        assertEquals(listOf("a", "b"), b.header)
        assertEquals(listOf(listOf("1", "2"), listOf("3", "4")), b.rows)
    }

    @Test fun inlineStyles() {
        assertEquals(
            listOf(Inline("a "), Inline("b", bold = true), Inline(" "), Inline("c", italic = true), Inline(" "), Inline("d", strike = true), Inline(" "), Inline("e", code = true)),
            Markdown.inlines("a **b** *c* ~~d~~ `e`"),
        )
        assertEquals(listOf(Inline("both", bold = true, italic = true)), Markdown.inlines("***both***"))
    }

    @Test fun inlineLinks() {
        assertEquals(listOf(Inline("Boats", note = "Boats")), Markdown.inlines("[[Boats]]"))
        assertEquals(listOf(Inline("the boats", note = "Ideas/Boats#Sails")), Markdown.inlines("[[Ideas/Boats#Sails|the boats]]"))
        assertEquals(listOf(Inline("site", url = "https://a.example")), Markdown.inlines("[site](https://a.example)"))
        assertEquals(listOf(Inline("Boats", note = "Boats")), Markdown.inlines("[Boats](Boats.md)"))
        assertEquals(listOf(Inline("See "), Inline("https://a.example/x", url = "https://a.example/x"), Inline(".")), Markdown.inlines("See https://a.example/x."))
    }

    @Test fun tagsNeedAWordStartAndALetter() {
        assertEquals(listOf(Inline("#work/home", tag = "work/home")), Markdown.inlines("#work/home"))
        assertEquals(listOf(Inline("a#b")), Markdown.inlines("a#b"))
        assertEquals(listOf(Inline("#1")), Markdown.inlines("#1"))
    }

    @Test fun marksThatDoNotCloseStayPlain() {
        assertEquals(listOf(Inline("2 * 3 and **x and [[y and `z")), Markdown.inlines("2 * 3 and **x and [[y and `z"))
        assertEquals(listOf(Inline("snake_case_name")), Markdown.inlines("snake_case_name"))
        assertEquals(listOf(Inline("*not*")), Markdown.inlines("\\*not\\*"))
    }

    @Test fun togglingATaskChangesOnlyThatLine() {
        val text = "- [ ] one\r\n- [x] two\nplain"
        assertEquals("- [x] one\r\n- [x] two\nplain", Markdown.toggleTask(text, 0))
        assertEquals("- [ ] one\r\n- [ ] two\nplain", Markdown.toggleTask(text, 1))
        assertEquals(text, Markdown.toggleTask(text, 2))
        assertEquals(text, Markdown.toggleTask(text, 9))
    }

    @Test fun excerptsDropTheMarks() {
        assertEquals("Title\nbold link", Markdown.excerpt("# Title\n**bold** [[target|link]]"))
        assertEquals(1 to 2, Markdown.taskCount("- [x] a\n- [ ] b\n"))
    }
}

class FormattingTest {
    @Test fun wrappingAddsAndRemovesMarks() {
        val on = Formatting.wrap(Edit("a word b", 2, 6), "**")
        assertEquals(Edit("a **word** b", 4, 8), on)
        assertEquals(Edit("a word b", 2, 6), Formatting.wrap(on, "**"))
    }

    @Test fun linePrefixesSwapRatherThanStack() {
        assertEquals("- [ ] a", Formatting.linePrefix(Edit("- a", 3, 3), "- [ ] ").text)
        assertEquals("# a", Formatting.linePrefix(Edit("## a", 4, 4), "# ").text)
        assertEquals("a", Formatting.linePrefix(Edit("- a", 3, 3), "- ").text)
        assertEquals("x\n- a\n- b\ny", Formatting.linePrefix(Edit("x\na\nb\ny", 2, 5), "- ").text)
    }

    @Test fun anEmptyDocumentCanBeFormatted() {
        assertEquals(Edit("- ", 2, 2), Formatting.linePrefix(Edit("", 0, 0), "- "))
        assertEquals(Edit("****", 2, 2), Formatting.wrap(Edit("", 0, 0), "**"))
    }

    @Test fun anOpenLinkIsSpotted() {
        assertEquals(Formatting.OpenLink(5, "Bo"), Formatting.openLink("see [[Bo", 8).let { it?.copy(first = it.first - 1) })
        assertNull(Formatting.openLink("see [[Boats]] x", 15))
        assertNull(Formatting.openLink("see [[Bo\nat", 11))
        assertNull(Formatting.openLink("plain", 5))
    }
}

class LinksTest {
    private val paths = listOf("Boats.md", "Ideas/Boats.md", "Ideas/Sails.md", "Deep/Ideas/Sails.md", "Other.md")

    @Test fun resolvingPrefersAFullPathThenTheShallowest() {
        assertEquals("Boats.md", Links.resolve("Boats", paths))
        assertEquals("Ideas/Boats.md", Links.resolve("Ideas/Boats", paths))
        assertEquals("Ideas/Sails.md", Links.resolve("sails", paths))
        assertEquals("Deep/Ideas/Sails.md", Links.resolve("Deep/Ideas/Sails#Top", paths))
        assertNull(Links.resolve("Nowhere", paths))
        assertNull(Links.resolve("#Just a heading", paths))
    }

    @Test fun codeIsNotScannedForLinksOrTags() {
        val (links, tags) = Links.scan("[[A]] #one\n`[[B]] #two`\n```\n[[C]]\n#three\n```\n- [ ] [[D]]")
        assertEquals(listOf("A", "D"), links)
        assertEquals(listOf("one"), tags)
    }

    @Test fun retargetingKeepsAliasesHeadingsAndCode() {
        val text = "[[Other]] [[Other|alias]] [[Other#Top]] `[[Other]]` [[Boats]]\n```\n[[Other]]\n```"
        assertEquals(
            "[[New]] [[New|alias]] [[New#Top]] `[[Other]]` [[Boats]]\n```\n[[Other]]\n```",
            Links.retarget(text, "Other.md", "New.md", paths),
        )
    }

    @Test fun retargetingWithAPathKeepsAPath() {
        assertEquals("[[Boats]] [[Elsewhere/Fish]]", Links.retarget("[[Boats]] [[Ideas/Boats]]", "Ideas/Boats.md", "Elsewhere/Fish.md", paths))
    }

    @Test fun theGraphJoinsNotesAndUnwrittenOnes() {
        val g = Graph.of(mapOf("A.md" to listOf("B", "Nowhere", "A"), "B.md" to listOf("A"), "Alone.md" to emptyList()))
        assertEquals(listOf("A", "Alone", "B", "Nowhere"), g.nodes.map { it.title })
        assertEquals(2, g.edges.size) // A-B once, however many ways round, A-Nowhere, and no A-A.
        assertNull(g.nodes.last().path)
        assertEquals(2, g.nodes.first().links)
        assertEquals(3, Graph.of(mapOf("A.md" to listOf("B", "Nowhere"), "B.md" to emptyList(), "C.md" to emptyList()), showUnwritten = false).nodes.size)
    }

    @Test fun aroundKeepsOnlyTheNeighbours() {
        val g = Graph.of(mapOf("A.md" to listOf("B"), "B.md" to listOf("C"), "C.md" to listOf("D"), "D.md" to emptyList()))
        assertEquals(listOf("B", "A", "C"), g.around("B.md").nodes.map { it.title })
        assertEquals(2, g.around("B.md").edges.size)
        assertTrue(g.around("Missing.md").nodes.isEmpty())
    }

    @Test fun theLayoutSettlesAndLinkedNotesEndUpCloser() {
        val g = Graph.of(mapOf("A.md" to listOf("B"), "B.md" to emptyList(), "C.md" to emptyList(), "D.md" to emptyList(), "E.md" to emptyList()))
        val layout = GraphLayout(g)
        var moving = Float.MAX_VALUE
        repeat(600) { moving = layout.step() }
        assertTrue("still moving: $moving", moving < .5f)
        fun gap(a: Int, b: Int) = kotlin.math.hypot(layout.x[a] - layout.x[b], layout.y[a] - layout.y[b])
        assertTrue(gap(0, 1) < gap(2, 3))
        assertNotNull(layout.nearest(layout.x[2], layout.y[2], 5f))
    }

    @Test fun fittingKeepsEveryNodeInView() {
        val g = Graph.of((0 until 40).associate { "n$it.md" to listOf("n${(it + 1) % 40}") })
        val layout = GraphLayout(g)
        repeat(200) { layout.step() }
        val (zoom, dx, dy) = layout.fit(800f, 600f, 40f)
        for (i in layout.x.indices) {
            assertTrue(layout.x[i] * zoom + dx in -400f..400f)
            assertTrue(layout.y[i] * zoom + dy in -300f..300f)
        }
    }

    @Test fun anEmptyGraphIsFine() {
        assertEquals(0f, GraphLayout(Graph(emptyList(), emptyList())).step())
    }
}

class VaultTest {
    @get:Rule val dir = TemporaryFolder()
    private val vault by lazy { Vault(dir.newFolder("vault")) }

    @Test fun notesRoundTripWithProperties() {
        val n = vault.create("Ideas", "Boats", "Hello")
        assertEquals("Ideas/Boats.md", n.path)
        assertEquals("Boats", n.title)
        assertEquals("Ideas", n.folder)
        val saved = vault.save(n.path, "Hello again", pinned = true, colour = "blue")
        assertEquals("Hello again", saved.body)
        assertTrue(saved.pinned)
        assertEquals("blue", saved.colour)
        assertEquals(listOf(saved.path), vault.notes().map { it.path })
    }

    @Test fun otherAppsPropertiesSurvive() {
        val file = File(vault.root, "Theirs.md")
        file.writeText("---\ntags: [a, b]\naliases: x\n---\nBody\n")
        val note = vault.read("Theirs.md")
        assertEquals("Body\n", note.body)
        vault.save("Theirs.md", "New\n", pinned = true, colour = null)
        assertEquals("---\ntags: [a, b]\naliases: x\npinned: true\n---\nNew\n", file.readText())
        vault.save("Theirs.md", "New\n", pinned = false, colour = null)
        assertEquals("---\ntags: [a, b]\naliases: x\n---\nNew\n", file.readText())
    }

    @Test fun textThatLooksLikeARuleIsNotFrontMatter() {
        File(vault.root, "R.md").writeText("---\nJust a rule with no end")
        assertEquals("---\nJust a rule with no end", vault.read("R.md").body)
    }

    @Test fun namesAreUniqueAndCleaned() {
        vault.create("", "Same")
        assertEquals("Same 2.md", vault.create("", "Same").path)
        assertEquals("a b c.md", vault.create("", "a/b:c").path)
    }

    @Test(expected = VaultException::class) fun aBlankNameIsRefused() { vault.create("", " / ") }

    @Test fun pathsCannotEscapeTheVault() {
        File(dir.root, "outside.md").writeText("secret")
        assertFails { vault.read("../outside.md") }
        assertFails { vault.create("../..", "x") }
        assertFails { vault.save("../outside.md", "gone", false, null) }
        assertFails { vault.trash("../outside.md") }
        assertEquals("secret", File(dir.root, "outside.md").readText())
    }

    private fun assertFails(block: () -> Unit) {
        assertTrue(runCatching(block).exceptionOrNull() is VaultException)
    }

    @Test fun movingANoteUpdatesLinksEverywhere() {
        vault.create("", "Target")
        vault.create("", "Fan", "[[Target]] and [[Target|alias]] `[[Target]]`")
        vault.create("Sub", "Self", "[[Self]]")
        val moved = vault.move("Target.md", "Sub", "Renamed")
        assertEquals("Sub/Renamed.md", moved.path)
        assertEquals("[[Renamed]] and [[Renamed|alias]] `[[Target]]`", vault.read("Fan.md").body)
        val self = vault.move("Sub/Self.md", "", "Me")
        assertEquals("[[Me]]", self.body)
    }

    @Test fun movingOntoAnExistingNameKeepsBoth() {
        vault.create("", "A", "a")
        vault.create("", "B", "b")
        assertEquals("B 2.md", vault.move("A.md", "", "B").path)
        assertEquals("b", vault.read("B.md").body)
    }

    @Test fun onlyTheCaseOfANameCanChange() {
        vault.create("", "boats", "x")
        val m = vault.move("boats.md", "", "Boats")
        assertEquals("x", m.body)
        assertEquals(1, vault.notes().size)
    }

    @Test fun renamingAFolderKeepsLinks() {
        vault.create("Old", "Inner", "[[Old/Other]]")
        vault.create("Old", "Other")
        vault.create("", "Out", "[[Old/Inner]]")
        assertEquals("New", vault.renameFolder("Old", "New"))
        assertEquals("[[New/Inner]]", vault.read("Out.md").body)
        assertEquals("[[New/Other]]", vault.read("New/Inner.md").body)
        assertFalse(File(vault.root, "Old").exists())
    }

    @Test fun trashCanBeRestoredWithoutOverwriting() {
        vault.create("Sub", "Gone", "first")
        vault.trash("Sub/Gone.md")
        assertTrue(vault.notes().isEmpty())
        assertEquals(listOf("Sub/Gone.md"), vault.trashed().map { it.path })
        vault.create("Sub", "Gone", "second")
        val back = vault.restore("Sub/Gone.md")
        assertEquals("Sub/Gone 2.md", back.path)
        assertEquals("first", back.body)
        assertEquals("second", vault.read("Sub/Gone.md").body)
    }

    @Test fun trashingTheSameNameTwiceKeepsBoth() {
        vault.create("", "X", "1"); vault.trash("X.md")
        vault.create("", "X", "2"); vault.trash("X.md")
        assertEquals(setOf("1", "2"), vault.trashed().map { it.body }.toSet())
    }

    @Test fun deletingAFolderTrashesWhatsInIt() {
        vault.create("F/G", "Deep", "d")
        vault.create("F", "Near", "n")
        vault.trashFolder("F")
        assertTrue(vault.notes().isEmpty())
        assertTrue(vault.folders().isEmpty())
        assertEquals(2, vault.trashed().size)
    }

    @Test fun trashIsHiddenAndCanBeEmptied() {
        vault.create("", "X"); vault.trash("X.md")
        assertTrue(vault.folders().isEmpty())
        vault.emptyTrash()
        assertTrue(vault.trashed().isEmpty())
    }

    @Test fun forAZipBackupEverythingComesBackBesideWhatsThere() {
        vault.create("Sub", "A", "a")
        vault.create("", "B", "b")
        val bytes = ByteArrayOutputStream().also { assertEquals(2, vault.export(it)) }.toByteArray()
        vault.save("Sub/A.md", "changed", false, null)
        assertEquals(2, vault.importZip(ByteArrayInputStream(bytes)))
        assertEquals("changed", vault.read("Sub/A.md").body)
        assertEquals("a", vault.read("Sub/A 2.md").body)
        assertEquals("b", vault.read("B 2.md").body)
    }

    @Test fun importingSkipsHiddenAndOtherFilesAndHostilePaths() {
        fun put(path: String) = vault.importFile(path, ByteArrayInputStream("x".toByteArray()))
        assertEquals(0, put(".obsidian/workspace.md"))
        assertEquals(0, put("picture.png"))
        assertEquals(0, put("../../evil.md"))
        assertEquals(0, put("a/../../evil.md"))
        assertEquals(1, put("Fine/Note.md"))
        assertEquals(listOf("Fine/Note.md"), vault.notes().map { it.path })
        assertFalse(File(dir.root, "evil.md").exists())
    }
}

class ListEnterTest {
    @Test fun listsCarryOn() {
        assertEquals(Formatting.ListEnter.Continue("- "), Formatting.continueList("- a", 3))
        assertEquals(Formatting.ListEnter.Continue("- [ ] "), Formatting.continueList("- [x] done", 10))
        assertEquals(Formatting.ListEnter.Continue("3. "), Formatting.continueList("2. b", 4))
        assertEquals(Formatting.ListEnter.Continue("  - "), Formatting.continueList("  - nested", 10))
    }

    @Test fun anEmptyItemEndsTheList() {
        assertEquals(Formatting.ListEnter.End(4), Formatting.continueList("- a\n- ", 6))
        assertEquals(Formatting.ListEnter.End(0), Formatting.continueList("- [ ] ", 6))
    }

    @Test fun plainTextIsLeftAlone() {
        assertNull(Formatting.continueList("hello", 5))
        assertNull(Formatting.continueList("", 0))
        assertNull(Formatting.continueList("-not a list", 11))
    }
}

class SketchTest {
    @get:Rule val dir = TemporaryFolder()
    private val vault by lazy { Vault(dir.newFolder("vault")) }
    private val red = 0xFF0000

    @Test fun drawingsRoundTripThroughSvg() {
        val s = Sketch(
            paper = Sketch.GRID,
            strokes = listOf(SketchStroke(Sketch.PEN, red, 6f, listOf(1f, 2f, 0f, .5f, 30f, 40f, 8f, .9f)), Sketch.line(0f, 0f, 50f, 50f, red, 4f)),
            texts = listOf(SketchText(10f, 20f, "a < b & \"c\"", red)),
        )
        val svg = s.toSvg()
        assertTrue(svg.startsWith("<svg "))
        assertEquals(s, Sketch.parse(svg))
        assertTrue("a < b" !in svg.substringAfter("</metadata>"))
    }

    @Test fun otherPicturesAreNotDrawings() {
        assertNull(Sketch.parse("<svg><rect/></svg>"))
        assertNull(Sketch.parse("<svg><metadata id=\"squill-sketch\">not json</metadata></svg>"))
    }

    @Test fun anEmptyDrawingStillMakesAPicture() {
        assertEquals(Sketch(), Sketch.parse(Sketch().toSvg()))
        assertEquals(Sketch(strokes = listOf(SketchStroke(Sketch.PEN, red, 6f, listOf(5f, 5f, 0f, -1f)))).strokes.size, 1)
        assertTrue(Sketch(strokes = listOf(SketchStroke(Sketch.PEN, red, 6f, listOf(5f, 5f, 0f, -1f)))).toSvg().contains("<path"))
    }

    @Test fun theEraserFindsStrokes() {
        val s = Sketch.line(0f, 0f, 100f, 0f, red, 4f)
        assertTrue(s.touches(50f, 3f, 2f))
        assertFalse(s.touches(50f, 30f, 2f))
        assertTrue(s.inside(-1f, -1f, 101f, 1f))
        assertFalse(s.inside(10f, -1f, 101f, 1f))
        assertEquals(10f, s.moved(10f, 0f).x(0))
    }

    @Test fun shapesAreStrokes() {
        assertEquals(3, Sketch.arrow(0f, 0f, 100f, 0f, red, 4f).size)
        val box = Sketch.rectangle(0f, 0f, 10f, 10f, red, 2f)
        assertEquals(box.x(0), box.x(box.count - 1))
        val round = Sketch.ellipse(0f, 0f, 20f, 10f, red, 2f)
        assertEquals(20f, round.x(0), .01f)
    }

    @Test fun drawingsMoveAndTheirEmbedsFollow() {
        val path = vault.createSketch("", "Boat")
        assertEquals("Boat.svg", path)
        vault.create("", "Note", "before\n![[Boat.svg]]\nafter ![[Boat.svg|small]]")
        val moved = vault.moveSketch(path, "Pics", "Ship")
        assertEquals("Pics/Ship.svg", moved)
        assertEquals("before\n![[Ship.svg]]\nafter ![[Ship.svg|small]]", vault.read("Note.md").body)
    }

    @Test fun drawingsGoThroughTrashAndBackups() {
        val path = vault.createSketch("Pics", "Boat", Sketch(texts = listOf(SketchText(1f, 1f, "hi", red))))
        assertEquals(listOf(FileStat(path, 0, 0).path), vault.stats().map { it.path })
        vault.trash(path)
        assertEquals(listOf(path), vault.trashed().map { it.path })
        vault.restore(path)
        assertEquals("hi", Sketch.parse(vault.readText(path))!!.texts.single().text)
        val zip = ByteArrayOutputStream().also { vault.export(it) }.toByteArray()
        assertEquals(1, vault.importZip(ByteArrayInputStream(zip)))
        assertTrue(vault.exists("Pics/Boat 2.svg"))
    }

    @Test fun foreignSvgsAreNotImported() {
        assertEquals(0, vault.importFile("logo.svg", ByteArrayInputStream("<svg/>".toByteArray())))
    }

    @Test fun anEmbedIsItsOwnBlock() {
        assertEquals(
            listOf(Block.Paragraph("Text", 0), Block.Embed("Boat.svg", 1), Block.Paragraph("More ![[x.svg]] inline", 2)),
            Markdown.parse("Text\n![[Boat.svg]]\nMore ![[x.svg]] inline"),
        )
    }
}

class ScaleTest {
    @get:Rule val dir = TemporaryFolder()

    /** The size where Obsidian users report waiting about 20 seconds on Android. */
    @Test fun tenThousandNotesStayFast() {
        val vault = Vault(dir.newFolder("big"))
        repeat(10_000) { i ->
            File(vault.root, "n$i.md").writeText("# Note $i\nSee [[n${(i * 7) % 10_000}]] and [[n${(i * 13) % 10_000}]] #tag${i % 40}\n- [ ] task\n")
        }
        val stats = kotlin.system.measureTimeMillis { assertEquals(10_000, vault.stats().size) }
        val notes = vault.notes()
        val infos = notes.map { NoteInfo(it, Links.scan(it.body).first, Links.scan(it.body).second, "", 0, 0) }
        val library = Library(infos)
        val lookups = kotlin.system.measureTimeMillis { repeat(1000) { library.backlinks("n$it.md") } }
        val graph = library.graph(true)
        assertEquals(10_000, graph.nodes.size)
        val layout = GraphLayout(graph)
        val step = kotlin.system.measureTimeMillis { layout.step() }
        println("10k notes: stat ${stats}ms, 1000 backlinks ${lookups}ms, graph step ${step}ms")
        assertTrue("checking files took ${stats}ms", stats < 3000)
        assertTrue("backlinks took ${lookups}ms", lookups < 2000)
        assertTrue("a graph step took ${step}ms", step < 3000)
    }
}

class OfficeTest {
    private val samples = File("build/office-samples").apply { mkdirs() }
    private val markdown = """# Title & more

Some **bold**, *italic*, ~~gone~~ and `code` with a [link](https://example.com/a?b=1&c=2).

## Second

- one
- two <tag>
  - nested

1. first
2. second

> A quote

| Name | Size |
|---|---|
| Boat | 3 |
| Sail | 4 |

Ending paragraph.
"""

    private fun roundTrip(md: String): String {
        val bytes = ByteArrayOutputStream().also { Docx.export(md, it) }.toByteArray()
        return Docx.import(ByteArrayInputStream(bytes))
    }

    @Test fun wordFilesKeepTheirStructure() {
        File(samples, "sample.docx").outputStream().use { Docx.export(markdown, it) }
        val back = roundTrip(markdown)
        assertTrue(back, "# Title & more" in back)
        assertTrue(back, "**bold**" in back)
        assertTrue(back, "*italic*" in back)
        assertTrue(back, "~~gone~~" in back)
        assertTrue(back, "`code`" in back)
        assertTrue(back, "[link](https://example.com/a?b=1&c=2)" in back)
        assertTrue(back, "## Second" in back)
        assertTrue(back, "- one" in back)
        assertTrue(back, "  - nested" in back)
        assertTrue(back, "1. first" in back)
        assertTrue(back, "2. second" in back)
        assertTrue(back, "> A quote" in back)
        assertTrue(back, "| Boat | 3 |" in back)
        assertTrue(back, "Ending paragraph." in back)
    }

    @Test fun markdownCharactersInWordTextStayLiteral() {
        val md = roundTrip("Price is 2 \\* 3 and snake\\_case")
        assertEquals("Price is 2 * 3 and snake_case", Markdown.inlines(md.trim()).joinToString("") { it.text })
    }

    @Test fun hostileAndWrongFilesAreRefusedPolitely() {
        val notZip = ByteArrayInputStream("not a zip".toByteArray())
        assertTrue(runCatching { Docx.import(notZip) }.exceptionOrNull() is OfficeException)
        val wrong = ByteArrayOutputStream().also { writeZip(it, mapOf("a.txt" to "x".toByteArray())) }.toByteArray()
        assertTrue(runCatching { Docx.import(ByteArrayInputStream(wrong)) }.exceptionOrNull() is OfficeException)
        assertTrue(runCatching { Pptx.import(ByteArrayInputStream(wrong)) }.exceptionOrNull() is OfficeException)
        val bomb = ByteArrayOutputStream().also { writeZip(it, mapOf("big" to ByteArray(4 * 1024 * 1024))) }.toByteArray()
        assertTrue(runCatching { readZip(ByteArrayInputStream(bomb), limit = 1024) }.exceptionOrNull() is OfficeException)
    }

    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M/wHwAEBgIApD5fRAAAAABJRU5ErkJggg==")
    private val deck = Deck(
        listOf(
            Deck.titleSlide(1),
            Slide(
                2, background = 0xEEEEFF,
                elements = listOf(
                    Element(21, "text", 100f, 50f, 800f, 100f, "Heading <one> & two\nSecond line", size = 40f, bold = true, italic = true, align = "center", colour = 0xC62828),
                    Element(22, "rect", 100f, 200f, 300f, 200f, "In a box", size = 24f, fill = 0xE0A800, colour = 0x1B1B1F),
                    Element(23, "ellipse", 500f, 200f, 200f, 200f, fill = 0x0B7A75),
                    Element(24, "line", 100f, 500f, 800f, 0f, colour = 0x4A4FA3, size = 8f),
                    Element(25, "image", 900f, 400f, 200f, 200f, image = Base64.getEncoder().encodeToString(png)),
                ),
            ),
        ),
    )

    @Test fun powerPointFilesKeepWhatsOnTheSlide() {
        File(samples, "sample.pptx").outputStream().use { Pptx.export(deck, it) }
        val bytes = ByteArrayOutputStream().also { Pptx.export(deck, it) }.toByteArray()
        val back = Pptx.import(ByteArrayInputStream(bytes))
        assertEquals(2, back.slides.size)
        val s = back.slides[1]
        assertEquals(0xEEEEFF, s.background)
        assertEquals(listOf("text", "rect", "ellipse", "line", "image"), s.elements.map { it.kind })
        val heading = s.elements[0]
        assertEquals("Heading <one> & two\nSecond line", heading.text)
        assertTrue(heading.bold && heading.italic)
        assertEquals("center", heading.align)
        assertEquals(0xC62828, heading.colour)
        assertEquals(40f, heading.size, .5f)
        assertEquals(100f, heading.x, .5f)
        assertEquals(800f, heading.w, .5f)
        assertEquals(0xE0A800, s.elements[1].fill)
        assertEquals(0x0B7A75, s.elements[2].fill)
        assertTrue(png.contentEquals(Base64.getDecoder().decode(s.elements[4].image)))
    }

    /** Files made by other software, when they've been generated: they use templates and placeholders Squill's own don't. */
    @Test fun filesFromOtherSoftwareOpen() {
        File(samples, "real.docx").takeIf { it.exists() }?.let { f ->
            val md = f.inputStream().use { Docx.import(it) }
            println("real.docx:\n$md")
            assertTrue(md, "# Real title" in md)
            assertTrue(md, "**bold**" in md && "*italic*" in md)
            assertTrue(md, "- Bullet one" in md)
            assertTrue(md, "1. Number one" in md && "2. Number two" in md)
            assertTrue(md, "| a | b |" in md)
        }
        File(samples, "real.pptx").takeIf { it.exists() }?.let { f ->
            val d = f.inputStream().use { Pptx.import(it) }
            assertEquals(3, d.slides.size)
            assertEquals("Placeholder title", d.slides[0].elements[0].text)
            assertTrue(d.slides[1].elements.any { it.text == "Point one\nPoint two" })
            assertTrue(d.slides[2].elements.any { it.text == "Shape text" })
        }
    }

    @Test fun decksRoundTripAsJson() {
        val withSteps = deck.copy(slides = deck.slides.map { it.copy(elements = it.elements.map { e -> e.copy(step = 1) }, notes = "Say this") })
        assertEquals(withSteps, Deck.parse(withSteps.toJson()))
        assertEquals(1, withSteps.slides[0].steps)
        assertNull(Deck.parse("not json"))
        assertNull(Deck.parse("""{"slides":[]}"""))
    }
}

class CipherTest {
    @get:Rule val dir = TemporaryFolder()

    @Test fun passwordsOpenTheirKeyAndNothingElse() {
        val f = File(dir.root, "k.json")
        val c = PasswordKey.create(f, "hunter22")
        val sealed = c.encode("secret".toByteArray())
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("secret"))
        assertFalse(f.readText().contains("hunter22"))
        assertEquals("secret", PasswordKey.unlock(f, "hunter22").decode(sealed).toString(Charsets.UTF_8))
        assertTrue(runCatching { PasswordKey.unlock(f, "wrong") }.exceptionOrNull() is WrongPassword)
        PasswordKey.changePassword(f, "hunter22", "new one")
        assertEquals("secret", PasswordKey.unlock(f, "new one").decode(sealed).toString(Charsets.UTF_8))
        assertTrue(runCatching { PasswordKey.unlock(f, "hunter22") }.isFailure)
    }

    @Test fun tamperingIsCaught() {
        val c = PasswordKey.create(File(dir.root, "k.json"), "p")
        val sealed = c.encode("secret".toByteArray())
        sealed[sealed.size - 1] = (sealed[sealed.size - 1] + 1).toByte()
        assertTrue(runCatching { c.decode(sealed) }.isFailure)
    }

    @Test fun aWholeVaultCanBeSealedAndOpened() {
        val v = Vault(dir.newFolder("v"))
        v.create("Sub", "Plain", "#tag private words")
        v.createSketch("", "Art", Sketch(texts = listOf(SketchText(1f, 1f, "drawn", 0))))
        v.create("", "Gone", "trashed words"); v.trash("Gone.md")
        val key = PasswordKey.create(File(v.root, LockedNotes.ALL_NOTES_KEY), "pw")
        v.convert(key)
        v.root.walkTopDown().filter { it.isFile && it.extension in setOf("md", "svg") }.forEach { f ->
            assertTrue(f.name, NoteCipher.isSealed(f.readBytes()))
            assertFalse(f.name, f.readBytes().toString(Charsets.ISO_8859_1).contains("private"))
        }
        assertEquals("#tag private words", v.read("Sub/Plain.md").body)
        assertEquals("drawn", Sketch.parse(v.readText("Art.svg"))!!.texts.single().text)
        assertEquals("trashed words", v.trashed().single().body)
        // New and changed notes are sealed too, and renames keep working.
        v.create("", "New", "[[Sub/Plain]]")
        assertTrue(NoteCipher.isSealed(File(v.root, "New.md").readBytes()))
        v.move("Sub/Plain.md", "", "Moved")
        assertEquals("[[Moved]]", v.read("New.md").body)
        // A backup is readable anywhere, so saying what you're doing matters.
        val zip = ByteArrayOutputStream().also { v.export(it) }.toByteArray()
        val other = Vault(dir.newFolder("other"))
        other.importZip(ByteArrayInputStream(zip))
        assertEquals("[[Moved]]", other.read("New.md").body)
        // A locked vault with no key refuses rather than showing rubbish.
        val cold = Vault(v.root)
        assertTrue(runCatching { cold.read("New.md") }.exceptionOrNull() is VaultException)
        v.convert(null)
        assertFalse(NoteCipher.isSealed(File(v.root, "New.md").readBytes()))
        assertEquals("[[Moved]]", Vault(v.root).read("New.md").body)
    }

    @Test fun aHalfFinishedConversionStillReads() {
        val v = Vault(dir.newFolder("v"))
        v.create("", "A", "one"); v.create("", "B", "two")
        val key = PasswordKey.create(File(dir.root, "k.json"), "pw")
        File(v.root, "A.md").writeBytes(key.encode(File(v.root, "A.md").readBytes()))
        v.cipher = key
        assertEquals("one", v.read("A.md").body)
        assertEquals("two", v.read("B.md").body)
    }

    @Test fun theLockedVaultKeepsNamesAndWordsSealed() {
        val root = dir.newFolder("r")
        val locked = LockedNotes(root)
        assertFalse(locked.isSetUp)
        assertTrue(runCatching { locked.notes() }.isFailure)
        locked.setUp("pw")
        val n = locked.save("Secret name", "Secret words")
        val files = File(root, LockedNotes.DIR).listFiles()!!.filter { it.name.endsWith(".enc") }
        assertEquals(1, files.size)
        assertFalse(files[0].readBytes().toString(Charsets.ISO_8859_1).contains("Secret"))
        assertFalse(files[0].name.contains("Secret"))
        locked.lock()
        assertTrue(runCatching { locked.notes() }.isFailure)
        val again = LockedNotes(root)
        assertTrue(runCatching { again.unlock("nope") }.isFailure)
        assertFalse(again.isOpen)
        again.unlock("pw")
        assertEquals(listOf("Secret name"), again.notes().map { it.title })
        again.save("Renamed", "x", id = n.id)
        assertEquals(1, again.notes().size)
        again.delete(n.id)
        assertTrue(again.notes().isEmpty())
    }

    @Test fun theLockedVaultStaysOutOfListsAndTravelsSealedInBackups() {
        val v = Vault(dir.newFolder("v"))
        val locked = LockedNotes(v.root)
        locked.setUp("pw")
        locked.save("Hidden", "words")
        assertTrue(v.notes().isEmpty()); assertTrue(v.stats().isEmpty()); assertTrue(v.folders().isEmpty())
        val zip = ByteArrayOutputStream().also { v.export(it) }.toByteArray()
        assertFalse(zip.toString(Charsets.ISO_8859_1).contains("words"))
        val restored = Vault(dir.newFolder("back"))
        restored.importZip(ByteArrayInputStream(zip))
        val back = LockedNotes(restored.root)
        assertTrue(back.isSetUp)
        back.unlock("pw")
        assertEquals("words", back.notes().single().body)
    }
}

class GraphLinkTest {
    @Test fun addingALinkIsPoliteAndUndoable() {
        assertEquals("Words\n\n[[Boats]]\n", Links.addLink("Words\n", "Boats"))
        assertEquals("[[Boats]]\n", Links.addLink("", "Boats"))
        assertEquals("See [[Boats]]", Links.addLink("See [[Boats]]", "boats"))
        assertEquals("Words\n", Links.removeLink(Links.addLink("Words\n", "Boats"), "Boats"))
        assertEquals("", Links.removeLink(Links.addLink("", "Boats"), "Boats"))
        val r = Links.Resolver(listOf("Ideas/Boats.md"))
        assertEquals("[[Ideas/Boats]]", Links.addLink("[[Ideas/Boats]]", "Boats", r))
    }

    @Test fun nodesStayPutAcrossChangesAndWhileHeld() {
        val a = Graph.of(mapOf("A.md" to emptyList(), "B.md" to emptyList()))
        val first = GraphLayout(a)
        first.place(0, 500f, 500f)
        val b = Graph.of(mapOf("A.md" to listOf("B"), "B.md" to emptyList(), "C.md" to emptyList()))
        val second = GraphLayout(b, from = first)
        assertEquals(500f, second.x[b.nodes.indexOfFirst { it.title == "A" }])
        second.held = 0
        second.place(0, 100f, 100f)
        repeat(20) { second.step() }
        assertEquals(100f, second.x[0])
    }
}

class VaultMoverTest {
    @get:Rule val dir = TemporaryFolder()

    @Test fun everythingArrivesAndHiddenPartsToo() {
        val a = Vault(dir.newFolder("a"))
        a.create("Sub", "Note", "words")
        a.create("", "Gone", "x"); a.trash("Gone.md")
        File(a.root, ".sync").mkdirs(); File(a.root, ".sync/id").writeText("me")
        LockedNotes(a.root).also { it.setUp("pw"); it.save("L", "locked") }
        val to = File(dir.root, "b/deep")
        assertEquals(5, VaultMover.move(a.root, to))
        assertEquals("words", Vault(to).read("Sub/Note.md").body)
        assertEquals("x", Vault(to).trashed().single().body)
        assertEquals("me", File(to, ".sync/id").readText())
        assertEquals(1, LockedNotes(to).also { it.unlock("pw") }.notes().size)
        assertTrue(a.root.walkTopDown().none { it.isFile })
    }

    @Test fun nothingIsOverwritten() {
        val a = Vault(dir.newFolder("a")); val b = Vault(dir.newFolder("b"))
        a.create("", "Same", "same"); b.create("", "Same", "same")
        a.create("", "Clash", "from a"); b.create("", "Clash", "from b")
        VaultMover.move(a.root, b.root)
        assertEquals("from b", b.read("Clash.md").body)
        assertEquals("from a", b.read("Clash (moved 2).md").body)
        assertEquals(3, b.notes().size)
    }

    @Test fun aFolderInsideItselfIsRefused() {
        val a = Vault(dir.newFolder("a"))
        assertTrue(runCatching { VaultMover.move(a.root, File(a.root, "inner")) }.isFailure)
        assertEquals(0, VaultMover.move(a.root, a.root))
    }
}

class LockedWriteTest {
    @get:Rule val dir = TemporaryFolder()

    @Test fun aLockedVaultRefusesPlainWrites() {
        val v = Vault(dir.newFolder("v"))
        v.create("", "A", "words")
        val key = PasswordKey.create(File(v.root, LockedNotes.ALL_NOTES_KEY), "correct horse")
        v.convert(key)
        v.cipher = null
        assertTrue(runCatching { v.create("", "B", "plain words") }.isFailure)
        assertFalse(File(v.root, "B.md").exists())
        v.cipher = key
        v.create("", "B", "sealed words")
        assertTrue(NoteCipher.isSealed(File(v.root, "B.md").readBytes()))
        v.convert(null)
        File(v.root, LockedNotes.ALL_NOTES_KEY).delete()
        assertEquals("sealed words", v.read("B.md").body)
    }
}

class EncryptionSafetyTest {
    @get:Rule val dir = TemporaryFolder()

    private fun backup(v: Vault) = java.io.ByteArrayOutputStream().also { v.export(it) }.toByteArray()

    @Test fun aFailedConversionChangesNothing() {
        val v = Vault(dir.newFolder("v"))
        v.create("", "Plain", "plain words")
        v.create("", "Stuck", "stuck words")
        // A file sealed under some other key can't be read, so the conversion has to stop.
        val other = PasswordKey.create(File(dir.root, "other.json"), "other password")
        File(v.root, "Stuck.md").writeBytes(other.encode("stuck words".toByteArray()))
        val key = PasswordKey.create(File(dir.root, "k.json"), "correct horse")
        assertTrue(runCatching { v.convert(key) }.isFailure)
        assertEquals(null, v.cipher)
        assertEquals("plain words", File(v.root, "Plain.md").readText())
        assertTrue(v.root.walkTopDown().none { it.name.endsWith(".tmp") })
    }

    @Test fun lockedNotesFromAnotherPasswordWaitForIt() {
        val from = Vault(dir.newFolder("from"))
        LockedNotes(from.root).also { it.setUp("old password"); it.save("Old", "from the backup", "same") }
        val zip = backup(from)

        val to = Vault(dir.newFolder("to"))
        val here = LockedNotes(to.root).also { it.setUp("new password"); it.save("New", "already here", "same") }
        to.importZip(zip.inputStream())
        assertEquals(listOf("New"), here.notes().map { it.title })
        assertEquals(1, here.restoredSets())

        assertTrue(runCatching { here.mergeRestored("wrong password") }.isFailure)
        assertEquals(1, here.restoredSets())
        assertEquals(1, here.mergeRestored("old password"))
        assertEquals(0, here.restoredSets())
        assertEquals(setOf("New", "Old"), here.notes().map { it.title }.toSet())
        assertEquals("from the backup", here.notes().first { it.title == "Old" }.body)
        assertFalse(File(to.root, LockedNotes.INCOMING).exists())
    }

    @Test fun aBackupRestoresIntoANewVaultAndItsOwnWhole() {
        val from = Vault(dir.newFolder("from"))
        LockedNotes(from.root).also { it.setUp("the password"); it.save("A", "a words") }
        val zip = backup(from)
        val fresh = Vault(dir.newFolder("fresh"))
        fresh.importZip(zip.inputStream())
        assertEquals("a words", LockedNotes(fresh.root).also { it.unlock("the password") }.notes().single().body)
        // Its own backup coming back changes nothing and asks for nothing.
        from.importZip(zip.inputStream())
        val again = LockedNotes(from.root).also { it.unlock("the password") }
        assertEquals(1, again.notes().size)
        assertEquals(0, again.restoredSets())
    }
}

