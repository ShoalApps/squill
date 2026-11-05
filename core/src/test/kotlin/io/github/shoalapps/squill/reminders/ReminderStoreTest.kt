package io.github.shoalapps.squill.reminders

import io.github.shoalapps.squill.Reminder
import io.github.shoalapps.squill.notes.NoteCipher
import io.github.shoalapps.squill.notes.PasswordKey
import io.github.shoalapps.squill.notes.LockedNotes
import io.github.shoalapps.squill.notes.Vault
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReminderStoreTest {
    @get:Rule val dir = TemporaryFolder()
    private val r = Reminder(1, "Tea", whenMillis = 100, syncId = "tea")
    private fun entry(time: Long, writer: String = "a", item: Reminder? = r) = ReminderEntry("tea", item, time, writer)

    @Test fun laterEditAndCompletionWin() {
        val a = entry(1)
        val b = entry(2, "b", r.copy(done = true))
        assertEquals(listOf(b), ReminderMerge.merge(listOf(a), listOf(b)))
        assertEquals(listOf(b), ReminderMerge.merge(listOf(b), listOf(a)))
    }

    @Test fun deletionDoesNotComeBackAndANewerEditCanRestoreIt() {
        val deleted = entry(3, item = null)
        assertEquals(listOf(deleted), ReminderMerge.merge(listOf(deleted), listOf(entry(2))))
        assertEquals(listOf(deleted), ReminderMerge.merge(listOf(entry(2)), listOf(deleted)))
        assertEquals(listOf(entry(4)), ReminderMerge.merge(listOf(deleted), listOf(entry(4))))
    }

    @Test fun equalClocksConvergeAndBackwardsClocksStillAdvance() {
        assertEquals(listOf(entry(10, "b")), ReminderMerge.merge(listOf(entry(10)), listOf(entry(10, "b"))))
        assertEquals(1001L, ReminderMerge.nextTime(2, listOf(entry(1000))))
        assertEquals(2000L, ReminderMerge.nextTime(2000, listOf(entry(1000))))
        assertEquals(ReminderMerge.merge(listOf(entry(10)), listOf(entry(10, "b"))), ReminderMerge.merge(listOf(entry(10, "b")), listOf(entry(10))))
    }

    @Test fun migrationIsRepeatableAndOldIdsFromDifferentPhonesAreDistinct() {
        val store = ReminderStore(Vault(dir.newFolder()))
        val old = r.copy(syncId = "")
        store.migrate(listOf(old), "phone")
        store.migrate(listOf(old), "phone")
        store.migrate(listOf(old), "other")
        assertEquals(2, store.items().size)
        assertEquals(setOf("phone:1", "other:1"), store.items().map { it.syncId }.toSet())
        store.update(10) { emptyList() }
        store.migrate(listOf(old), "phone")
        assertTrue(store.items().isEmpty())
    }

    @Test fun storedEditsAdvancePastAnAheadPeerAndKeepTombstones() {
        val store = ReminderStore(Vault(dir.newFolder()))
        store.receive(listOf(entry(9000)))
        store.update(10) { it.map { r -> r.copy(title = "Coffee") } }
        assertEquals(9001L, store.entries().single().edited)
        store.update(11) { emptyList() }
        assertEquals(9002L, store.entries().single().edited)
        store.receive(listOf(entry(9000)))
        assertTrue(store.items().isEmpty())
    }

    @Test fun overdueArrivalsDoNotBecomeMissedAlarms() {
        val overdue = r.copy(snoozedUntil = 50)
        val arrived = overdue.arrived(200)
        assertFalse(arrived.missed(300))
        assertNull(arrived.nextAfter(300))
        val future = r.copy(whenMillis = 400)
        assertEquals(future, future.arrived(200))
        val repeating = r.copy(repeat = io.github.shoalapps.squill.Repeat.DAILY)
        assertTrue(repeating.arrived(200).nextAfter(200)!! > 200)
    }

    @Test fun failedArrivalPreparationLeavesTheOriginalUntouched() {
        val store = ReminderStore(Vault(dir.newFolder()))
        store.receive(listOf(entry(1)))
        assertTrue(runCatching { store.receive(listOf(entry(2))) { error("No space") } }.isFailure)
        assertEquals(listOf(entry(1)), store.entries())
    }

    @Test fun backupUsesSharedIdentityEvenWhenAlarmIdsCollide() {
        val a = r.copy(syncId = "a")
        val b = r.copy(syncId = "b")
        val restored = io.github.shoalapps.squill.mergeBackup(listOf(a, b), listOf(a.copy(title = "New")), 0)
        assertEquals(2, restored.size)
        assertTrue(b in restored)
        assertEquals("New", restored.single { it.syncId == "a" }.title)
    }

    @Test fun hiddenDataConvertsWithTheVaultAndLockedWritesKeepItSafe() {
        val v = Vault(dir.newFolder())
        val store = ReminderStore(v)
        store.receive(listOf(entry(1)))
        assertTrue(v.notes().isEmpty())
        assertTrue(v.stats().isEmpty())
        assertTrue(v.trashed().isEmpty())
        val key = PasswordKey.create(File(v.root, LockedNotes.ALL_NOTES_KEY), "password")
        v.convert(key)
        assertTrue(NoteCipher.isSealed(v.file(ReminderStore.PATH).readBytes()))
        assertEquals(listOf(r), store.items())
        v.cipher = null
        assertTrue(runCatching { store.update { emptyList() } }.isFailure)
        v.cipher = key
        assertEquals(listOf(r), store.items())
        v.convert(null)
        assertFalse(NoteCipher.isSealed(v.file(ReminderStore.PATH).readBytes()))
    }
}
