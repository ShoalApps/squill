package io.github.shoalapps.squill.notes

import io.github.shoalapps.squill.sync.Manifest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

class NoteHistoryTest {
    @get:Rule val dir = TemporaryFolder()
    private var now = 100 * DAY
    private val vault by lazy { Vault(dir.newFolder("vault"), historyClock = { now }) }
    private fun save(text: String) = vault.save("A.md", text, false, null)
    private fun bodies(path: String = "A.md", history: NoteHistory = vault.history) =
        history.list(path).map { history.read(path, it) }

    @Test fun typingKeepsTheFirstOldTextAndOneCheckpointEveryFiveMinutesEvenAfterReopening() {
        vault.create("", "A", "original")
        save("one")
        now += 1_000
        save("two")
        save("three")
        assertEquals(listOf("original"), bodies())
        val reopened = Vault(vault.root, historyClock = { now })
        reopened.save("A.md", "four", false, null)
        assertEquals(listOf("original"), bodies())
        now += 5 * 60_000L
        reopened.save("A.md", "five", false, null)
        assertEquals(listOf("four", "original"), bodies())
    }

    @Test fun creatingAndSavingUnchangedTextMakesNoVersions() {
        vault.create("", "A", "original")
        repeat(3) { save("original") }
        vault.writeFile("A.md") { it.write("original".toByteArray()) }
        assertTrue(bodies().isEmpty())
        assertFalse(File(vault.root, NoteHistory.DIR).exists())
    }

    @Test fun clearingANoteKeepsWhatWasErasedEvenInABurst() {
        vault.create("", "A")
        save("precious text")
        now++
        save("")
        assertTrue("precious text" in bodies())
        assertEquals("", vault.read("A.md").body)
    }

    @Test fun thinningKeepsEveryRecentCopyThenTheLatestPerDayAndNothingAfterAMonth() {
        val end = now
        val copies = listOf(
            end - 31 * DAY to "expired",
            end - 29 * DAY + HOUR to "early month",
            end - 29 * DAY + 2 * HOUR to "late month",
            end - 2 * DAY + HOUR to "early day",
            end - 2 * DAY + 2 * HOUR to "late day",
            end - DAY + HOUR to "recent one",
            end - HOUR to "recent two",
            end to "recent three",
        )
        copies.forEach { (time, text) ->
            now = time
            vault.history.keep("A.md", text.toByteArray())
        }
        assertEquals(listOf("recent three", "recent two", "recent one", "late day", "late month"), bodies())
        now += 30 * DAY
        assertTrue(bodies().isEmpty())
        assertTrue(vault.history.files().isEmpty())
    }

    @Test fun reopeningPrunesExpiredCopiesWithoutReadingTheirContents() {
        vault.create("", "A", "old")
        save("current")
        vault.convert(NoteCipher(ByteArray(32) { 3 }))
        val root = vault.root
        assertTrue(vault.history.files().isNotEmpty())
        now += 31 * DAY
        val reopened = Vault(root, historyClock = { now })
        assertTrue(reopened.history.files().isEmpty())
    }

    @Test fun restorePreservesFullMarkdownAndPropertiesAndCanItselfBeUndoneImmediately() {
        vault.create("", "A", "body")
        val original = "---\ncustom: keep me\npinned: true\ncolour: blue\n---\nold body"
        vault.file("A.md").writeText(original)
        save("new body")
        val version = vault.history.list("A.md").single()
        val current = vault.readText("A.md")
        val restored = vault.history.restore("A.md", version)
        assertEquals(original, vault.readText("A.md"))
        assertEquals("old body", restored.body)
        assertTrue(restored.pinned)
        assertEquals("blue", restored.colour)
        val undo = vault.history.list("A.md").single { vault.history.read("A.md", it) == current }
        vault.history.restore("A.md", undo)
        assertEquals(current, vault.readText("A.md"))
        assertEquals(3, vault.history.list("A.md").size)
    }

    @Test fun renamingMovingAndRenamingAFolderCarryHistoryWithoutPuttingItAtTheOldPath() {
        vault.create("", "A", "old")
        save("current")
        vault.move("A.md", "Sub", "B")
        assertTrue(bodies("A.md").isEmpty())
        assertEquals(listOf("old"), bodies("Sub/B.md"))
        vault.renameFolder("Sub", "Other")
        assertTrue(bodies("Sub/B.md").isEmpty())
        assertEquals(listOf("old"), bodies("Other/B.md"))
        vault.moveFile("Other/B.md", "", "C")
        assertEquals(listOf("old"), bodies("C.md"))
        vault.history.restore("C.md", vault.history.list("C.md").single())
        assertEquals("old", vault.read("C.md").body)
    }

    @Test fun trashCollisionsAndRestoreCollisionsKeepEachNotesOwnHistory() {
        vault.create("", "A", "first old")
        save("first current")
        vault.trash("A.md")
        assertTrue(bodies().isEmpty())
        assertEquals(listOf("first old"), bodies(".trash/A.md"))
        vault.create("", "A", "second old")
        save("second current")
        vault.trash("A.md")
        assertEquals(listOf("second old"), bodies(".trash/A 2.md"))
        vault.create("", "A", "third current")
        val restored = vault.restore("A.md")
        assertEquals("A 2.md", restored.path)
        assertEquals(listOf("first old"), bodies(restored.path))
        vault.emptyTrash()
        assertTrue(bodies(".trash/A 2.md").isEmpty())
        assertEquals(listOf("first old"), bodies(restored.path))
        assertEquals("third current", vault.read("A.md").body)
    }

    @Test fun deletingForeverAndEmptyingTrashRemoveOnlyTheTrashedHistories() {
        vault.create("", "A", "old")
        save("current")
        vault.create("Sub", "B", "old B")
        vault.save("Sub/B.md", "current B", false, null)
        vault.trash("A.md")
        vault.trashFolder("Sub")
        assertEquals(listOf("old B"), bodies(".trash/Sub/B.md"))
        vault.deleteForever("A.md")
        assertTrue(bodies(".trash/A.md").isEmpty())
        assertEquals(listOf("old B"), bodies(".trash/Sub/B.md"))
        vault.emptyTrash()
        assertTrue(vault.history.files().isEmpty())
    }

    @Test fun restoreDoesNotBringBackADeletedNote() {
        vault.create("", "A", "old")
        save("current")
        val version = vault.history.list("A.md").single()
        vault.trash("A.md")
        assertTrue(runCatching { vault.history.restore("A.md", version) }.isFailure)
        assertFalse(vault.exists("A.md"))
    }

    @Test fun syncWriteKeepsTheExactTextItReplacesEvenDuringATypingBurst() {
        vault.create("", "A", "original")
        save("just typed")
        now++
        vault.writeFile("A.md") { it.write("from phone".toByteArray()) }
        assertEquals(listOf("just typed", "original"), bodies())
        assertEquals("from phone", vault.read("A.md").body)
    }

    @Test fun capsRemoveTheOldestCopiesAcrossNotesAndSkipCopiesLargerThanTheBudget() {
        val history = NoteHistory(vault, clock = { now }, interval = 0, perNoteBytes = 8, totalBytes = 12)
        fun keep(path: String, text: String) { now++; history.keep(path, text.toByteArray()) }
        keep("A.md", "aaaa")
        keep("A.md", "bbbb")
        keep("A.md", "cccc")
        assertEquals(listOf("cccc", "bbbb"), bodies(history = history))
        keep("B.md", "dddd")
        keep("B.md", "eeee")
        assertEquals(listOf("cccc"), bodies(history = history))
        assertEquals(listOf("eeee", "dddd"), bodies("B.md", history))
        assertTrue(history.files().sumOf { it.length() } <= 12)
        keep("C.md", "too large for this note")
        assertTrue(bodies("C.md", history).isEmpty())
        keep("A.md", "too large for this note")
        assertEquals(listOf("cccc"), bodies(history = history))
    }

    @Test fun byteCapsCountEncryptionOverhead() {
        vault.cipher = NoteCipher(ByteArray(32) { 6 })
        val history = NoteHistory(vault, clock = { now }, interval = 0, perNoteBytes = 80, totalBytes = 120)
        repeat(4) {
            now++
            history.keep("A.md", "text".toByteArray())
        }
        val kept = history.files()
        assertEquals(2, kept.size)
        assertTrue(kept.all { NoteCipher.isSealed(it.readBytes()) })
        assertTrue(kept.sumOf { it.length() } <= 80)
        now++
        history.keep("B.md", "more".toByteArray())
        now++
        history.keep("B.md", "last".toByteArray())
        assertTrue(history.files().sumOf { it.length() } <= 120)
    }

    @Test fun retentionBoundariesKeepAllFor24HoursAndExpireAt30Days() {
        vault.history.keep("A.md", "first".toByteArray())
        val first = now
        now += 10 * 60_000L
        vault.history.keep("A.md", "second".toByteArray())
        now = first + DAY - 1
        assertEquals(listOf("second", "first"), bodies())
        now = first + DAY + 10 * 60_000L
        assertEquals(listOf("second"), bodies())
        now = first + 30 * DAY + 10 * 60_000L
        assertTrue(bodies().isEmpty())
    }

    @Test fun historyFailuresDoNotStopEitherSavePath() {
        vault.create("", "A", "old")
        File(vault.root, NoteHistory.DIR).writeText("not a folder")
        save("saved locally")
        assertEquals("saved locally", vault.read("A.md").body)
        vault.writeFile("A.md") { it.write("saved from sync".toByteArray()) }
        assertEquals("saved from sync", vault.read("A.md").body)
    }

    @Test fun encryptingAndDecryptingConvertsActiveAndTrashedHistoryAndNewCopiesAreSealed() {
        vault.create("", "A", "secret old")
        save("secret current")
        vault.create("", "B", "trashed secret")
        vault.save("B.md", "B now", false, null)
        vault.trash("B.md")
        val key = NoteCipher(ByteArray(32) { it.toByte() })
        vault.convert(key)
        assertTrue(vault.history.files().isNotEmpty())
        assertTrue(vault.history.files().all { NoteCipher.isSealed(it.readBytes()) })
        assertEquals(listOf("secret old"), bodies())
        assertEquals(listOf("trashed secret"), bodies(".trash/B.md"))
        now += 5 * 60_000L
        save("sealed new")
        assertTrue(vault.history.files().all { NoteCipher.isSealed(it.readBytes()) })
        vault.history.restore("A.md", vault.history.list("A.md").first { vault.history.read("A.md", it) == "secret old" })
        assertTrue(NoteCipher.isSealed(vault.file("A.md").readBytes()))
        vault.convert(null)
        assertTrue(vault.history.files().none { NoteCipher.isSealed(it.readBytes()) })
        assertTrue("sealed new" in bodies())
        assertEquals("secret old", vault.read("A.md").body)
        assertEquals(listOf("trashed secret"), bodies(".trash/B.md"))
    }

    @Test fun finishingAnInterruptedConversionSealsPlainHistoryAndRemovesConversionTemps() {
        vault.create("", "A", "plain old")
        save("plain current")
        val version = vault.history.files().single()
        val temp = File(version.parentFile, ".${version.name}.converting.tmp").apply { writeText("unfinished") }
        val unfinishedWrite = File(version.parentFile, ".unfinished.tmp").apply { writeText("plain unfinished") }
        vault.cipher = NoteCipher(ByteArray(32) { 7 })
        vault.finishSealing()
        assertFalse(temp.exists())
        assertTrue(NoteCipher.isSealed(unfinishedWrite.readBytes()))
        assertTrue(vault.history.files().all { NoteCipher.isSealed(it.readBytes()) })
        assertEquals(listOf("plain old"), bodies())
    }

    @Test fun lockedHistoryCannotBeReadWithoutTheVaultCipherAndEmptyTrashNeedsNoKey() {
        vault.create("", "A", "secret old")
        save("secret current")
        val version = vault.history.list("A.md").single()
        vault.convert(NoteCipher(ByteArray(32) { 4 }))
        vault.cipher = null
        assertTrue(runCatching { vault.history.read("A.md", version) }.isFailure)
        vault.trash("A.md")
        vault.emptyTrash()
        assertTrue(vault.history.files().isEmpty())
    }

    @Test fun movingToLockedNotesRemovesOldAndRenamedHistoryAndLockedEditsMakeNone() {
        vault.create("", "A", "private former text")
        save("private current text")
        vault.move("A.md", "Sub", "Renamed")
        val locked = LockedNotes(vault.root).apply { setUp("test password") }
        vault.moveToLocked("Sub/Renamed.md", locked)
        assertFalse(vault.exists("Sub/Renamed.md"))
        assertTrue(vault.history.files().isEmpty())
        val note = locked.notes().single()
        assertEquals("private current text", note.body)
        locked.save(note.title, "private next text", note.id)
        assertTrue(vault.history.files().isEmpty())
        vault.root.walkTopDown().filter { it.isFile }.forEach {
            assertFalse(it.path, it.readText().contains("private former text"))
            assertFalse(it.path, it.readText().contains("private current text"))
            assertFalse(it.path, it.readText().contains("private next text"))
        }
        assertTrue(vault.trashed().isEmpty())
    }

    @Test fun aLockedDestinationDoesNotRemoveTheOrdinaryNote() {
        vault.create("", "A", "keep me")
        val locked = LockedNotes(vault.root).apply { setUp("test password") }
        locked.lock()
        assertTrue(runCatching { vault.moveToLocked("A.md", locked) }.isFailure)
        assertEquals("keep me", vault.read("A.md").body)
    }

    @Test fun hiddenHistoryIsAbsentFromNotesFoldersStatsManifestTrashSearchAndGraph() {
        vault.create("", "A", "old #secret [[Ghost]]")
        save("visible")
        assertEquals(listOf("A.md"), vault.notes().map { it.path })
        assertTrue(vault.folders().isEmpty())
        assertEquals(listOf("A.md"), vault.stats().map { it.path })
        assertEquals(setOf("A.md"), Manifest(vault).scan().keys)
        assertTrue(vault.trashed().isEmpty())
        val library = Library(vault.notes().map { note ->
            val (links, tags) = Links.scan(note.body)
            NoteInfo(note, links, tags, Markdown.excerpt(note.body), 0, 0)
        })
        assertTrue(library.notes.none { it.note.body.contains("secret") })
        assertTrue(library.tags.isEmpty())
        assertEquals(1, library.graph(true).nodes.size)
        val out = ByteArrayOutputStream()
        vault.export(out)
        val entries = mutableListOf<String>()
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; entries += entry.name }
        }
        assertEquals(listOf("A.md"), entries)
    }

    @Test fun movingTheWholeVaultCarriesItsLocalHistory() {
        vault.create("", "A", "old")
        save("current")
        val dest = dir.newFolder("destination")
        VaultMover.move(vault.root, dest)
        val moved = Vault(dest, historyClock = { now })
        assertEquals(listOf("old"), bodies(history = moved.history))
        moved.history.restore("A.md", moved.history.list("A.md").single())
        assertEquals("old", moved.read("A.md").body)
        assertTrue(vault.history.files().isEmpty())
    }

    companion object {
        private const val HOUR = 60 * 60_000L
        private const val DAY = 24 * HOUR
    }
}
