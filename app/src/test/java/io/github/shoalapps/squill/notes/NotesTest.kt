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
