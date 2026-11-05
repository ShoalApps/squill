package io.github.shoalapps.squill.sync

import io.github.shoalapps.squill.Reminder
import io.github.shoalapps.squill.reminders.ReminderStore

import io.github.shoalapps.squill.notes.LockedNotes
import io.github.shoalapps.squill.notes.NoteCipher
import io.github.shoalapps.squill.notes.PasswordKey
import io.github.shoalapps.squill.notes.Vault
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ServerSocket
import java.net.Socket

class SyncPlanTest {
    private fun f(path: String, hash: String, modified: Long = 1) = path to FileState(path, hash, 1, modified)
    private fun plan(here: List<Pair<String, FileState>>, there: List<Pair<String, FileState>>, base: Map<String, String>) = SyncPlan.plan(here.toMap(), there.toMap(), base)

    @Test fun sameFilesDoNothing() { assertTrue(plan(listOf(f("a", "1")), listOf(f("a", "1")), mapOf("a" to "1")).isEmpty()) }
    @Test fun oneSideChanged() {
        assertEquals(listOf(SyncAction.Push("a")), plan(listOf(f("a", "2")), listOf(f("a", "1")), mapOf("a" to "1")))
        assertEquals(listOf(SyncAction.Pull("a")), plan(listOf(f("a", "1")), listOf(f("a", "2")), mapOf("a" to "1")))
    }
    @Test fun newFilesCopy() {
        assertEquals(listOf(SyncAction.Push("a")), plan(listOf(f("a", "1")), emptyList(), emptyMap()))
        assertEquals(listOf(SyncAction.Pull("a")), plan(emptyList(), listOf(f("a", "1")), emptyMap()))
    }
    @Test fun deletionsNeedHistory() {
        assertEquals(listOf(SyncAction.TrashHere("a")), plan(listOf(f("a", "1")), emptyList(), mapOf("a" to "1")))
        assertEquals(listOf(SyncAction.TrashThere("a")), plan(emptyList(), listOf(f("a", "1")), mapOf("a" to "1")))
    }
    @Test fun aChangeBeatsADeletion() {
        assertEquals(listOf(SyncAction.Push("a")), plan(listOf(f("a", "2")), emptyList(), mapOf("a" to "1")))
        assertEquals(listOf(SyncAction.Pull("a")), plan(emptyList(), listOf(f("a", "2")), mapOf("a" to "1")))
    }
    @Test fun bothChangedIsAConflict() {
        assertEquals(listOf(SyncAction.Conflict("a", false)), plan(listOf(f("a", "2", 5)), listOf(f("a", "3", 9)), mapOf("a" to "1")))
        assertEquals(listOf(SyncAction.Conflict("a", true)), plan(listOf(f("a", "2", 9)), listOf(f("a", "3", 5)), emptyMap()))
    }
    @Test fun skippedFilesAreLeftAlone() {
        assertTrue(SyncPlan.plan(mapOf(f("a", "2")), mapOf(f("a", "3")), emptyMap(), setOf("a")).isEmpty())
    }
    @Test fun conflictNamesDoNotCollide() {
        assertEquals("X/Boats (from Laptop).md", SyncPlan.conflictName("X/Boats.md", "Laptop") { false })
        val taken = setOf("Boats (from A).md")
        assertEquals("Boats (from A 2).md", SyncPlan.conflictName("Boats.md", "A") { it in taken })
        assertEquals("Boats (from a b).md", SyncPlan.conflictName("Boats.md", "a/b") { false })
    }
}

class SyncFlowTest {
    @get:Rule val dir = TemporaryFolder()
    private lateinit var desk: Vault
    private lateinit var phone: Vault
    private lateinit var deskPeers: PeerStore
    private lateinit var phonePeers: PeerStore
    private lateinit var server: SyncProtocol.Server
    private lateinit var peer: Peer

    @Before fun setUp() {
        desk = Vault(dir.newFolder("desk")); phone = Vault(dir.newFolder("phone"))
        deskPeers = PeerStore(desk.root); phonePeers = PeerStore(phone.root)
        server = SyncProtocol.Server(desk, deskPeers, "Desk", port = 0)
        server.start()
        val code = SyncCrypto.pairingCode()
        server.allowPairing(code)
        peer = SyncProtocol.pair("127.0.0.1", server.boundPort, code.lowercase(), phonePeers.selfId, "Phone", phonePeers)
    }

    @After fun tearDown() { server.close() }

    private fun sync() = SyncProtocol.sync(desk.let { phone }, phonePeers, phonePeers.peers().single(), phonePeers.selfId, "Phone")
    /** File times only tick so fast; this makes sure a rewrite counts as newer. */
    private fun write(v: Vault, path: String, text: String, modified: Long = System.currentTimeMillis()) {
        val f = File(v.root, path); f.parentFile?.mkdirs(); f.writeText(text); f.setLastModified(modified)
    }
    private fun read(v: Vault, path: String) = File(v.root, path).readText()

    @Test fun remindersTravelBothWaysAndDeletionsStayDeleted() {
        val phoneStore = ReminderStore(phone)
        val deskStore = ReminderStore(desk)
        phoneStore.update { listOf(Reminder(1, "Tea", whenMillis = 100)) }
        sync()
        assertEquals(phoneStore.entries(), deskStore.entries())
        deskStore.update { it.map { r -> r.copy(title = "Coffee", done = true) } }
        sync()
        assertEquals("Coffee", phoneStore.items().single().title)
        assertTrue(phoneStore.items().single().done)
        phoneStore.update { emptyList() }
        sync(); sync()
        assertTrue(deskStore.items().isEmpty())
        assertEquals(phoneStore.entries(), deskStore.entries())
        assertTrue(desk.stats().isEmpty())
        assertTrue(desk.trashed().isEmpty())
    }

    @Test fun concurrentReminderEditsAndDeletesConverge() {
        val p = ReminderStore(phone)
        val d = ReminderStore(desk)
        p.update(100) { listOf(Reminder(1, "Tea", whenMillis = 100)) }
        sync()
        p.update(200) { it.map { r -> r.copy(title = "Phone") } }
        d.update(300) { it.map { r -> r.copy(title = "Desk") } }
        sync()
        assertEquals("Desk", p.items().single().title)
        assertEquals(p.entries(), d.entries())
        // Both clocks moved backwards, but edits still advance past what each has observed.
        p.update(1) { it.map { r -> r.copy(title = "Again") } }
        d.update(2) { emptyList() }
        sync(); sync()
        assertEquals(p.entries(), d.entries())
    }

    @Test fun oldServersStillSyncNotes() {
        server.close()
        server = SyncProtocol.Server(desk, deskPeers, "Desk", port = 0, supportsReminders = false)
        server.start()
        phonePeers.save(peer.copy(port = server.boundPort))
        write(phone, "Old.md", "notes still work")
        ReminderStore(phone).update {
            listOf(Reminder(1, "Tea", whenMillis = 100))
        }
        sync()
        assertEquals("notes still work", read(desk, "Old.md"))
        assertFalse(desk.exists(ReminderStore.PATH))
        assertEquals(0, sync().total)
    }

    @Test fun oldClientsCanUseTheOriginalNoteRequests() {
        val reminders = ReminderStore(desk)
        reminders.update { listOf(Reminder(1, "Tea", whenMillis = 100)) }
        Socket("127.0.0.1", server.boundPort).use { socket ->
            socket.soTimeout = 5000
            SealedChannel(socket, peer.secret.toByteArray(), true, SyncProtocol.hello("SYNC", phonePeers.selfId)).use { ch ->
                // These are the original protocol's requests, with no reminder capability or operation.
                ch.send("{\"op\":\"LIST\"}".toByteArray())
                ch.receive()
                assertEquals("[]", ch.receive().toString(Charsets.UTF_8))
                ch.send("{\"op\":\"PUT\",\"path\":\"Old.md\"}".toByteArray())
                ch.send("old client".toByteArray())
                assertTrue(ch.receive().toString(Charsets.UTF_8).contains("\"ok\":true"))
                ch.send("{\"op\":\"DONE\"}".toByteArray())
                ch.receive()
            }
        }
        assertEquals("old client", desk.read("Old.md").body)
        assertEquals("Tea", reminders.items().single().title)
    }

    @Test fun pairingGivesBothTheSameKey() {
        assertEquals("Desk", peer.name)
        assertEquals(peer.secret, deskPeers.peers().single().secret)
        assertTrue(peer.secret.length >= 64)
    }

    @Test fun aPairingCodeWorksOnce() {
        val other = PeerStore(dir.newFolder("other"))
        val result = runCatching { SyncProtocol.pair("127.0.0.1", server.boundPort, "AAAA-BBBB-CCCC-DDDD", other.selfId, "Thief", other) }
        assertTrue(result.isFailure)
        assertEquals(1, deskPeers.peers().size)
    }

    @Test fun aWrongCodeIsRefused() {
        server.allowPairing("AAAA-BBBB-CCCC-DDDD")
        val other = PeerStore(dir.newFolder("other"))
        assertTrue(runCatching { SyncProtocol.pair("127.0.0.1", server.boundPort, "ZZZZ-ZZZZ-ZZZZ-ZZZZ", other.selfId, "Thief", other) }.isFailure)
        assertEquals(1, deskPeers.peers().size)
    }

    @Test fun filesTravelBothWays() {
        write(phone, "Sub/FromPhone.md", "phone")
        write(desk, "Other/FromDesk.md", "desk")
        val r = sync()
        assertEquals(1, r.pulled); assertEquals(1, r.pushed)
        assertEquals("phone", read(desk, "Sub/FromPhone.md"))
        assertEquals("desk", read(phone, "Other/FromDesk.md"))
        assertEquals(0, sync().total)
    }

    @Test fun editsAndBinaryFilesFollow() {
        write(phone, "A.md", "one")
        sync()
        write(desk, "A.md", "two")
        assertEquals(1, sync().pulled)
        assertEquals("two", read(phone, "A.md"))
        val data = ByteArray(300_000) { (it % 251).toByte() }
        File(phone.root, "Art.ora").writeBytes(data)
        sync()
        assertTrue(data.contentEquals(File(desk.root, "Art.ora").readBytes()))
    }

    @Test fun replacementsInBothSyncDirectionsKeepLocalHistory() {
        phone.create("", "A", "original")
        sync()
        phone.save("A.md", "typed on phone", false, null)
        assertEquals(1, sync().pushed)
        assertEquals(listOf("original"), desk.history.list("A.md").map { desk.history.read("A.md", it) })
        desk.save("A.md", "replacement from desktop", false, null)
        assertEquals(1, sync().pulled)
        assertEquals("replacement from desktop", phone.read("A.md").body)
        val recoverable = phone.history.list("A.md").map { phone.history.read("A.md", it) }
        assertTrue("typed on phone" in recoverable)
        assertTrue("original" in recoverable)
        listOf(phone, desk).forEach { vault ->
            assertEquals(setOf("A.md"), Manifest(vault).scan().keys)
        }
    }

    @Test fun deletionsGoToTheTrashOnTheOtherDevice() {
        write(phone, "A.md", "keep me safe")
        sync()
        File(phone.root, "A.md").delete()
        assertEquals(1, sync().trashed)
        assertFalse(File(desk.root, "A.md").exists())
        assertEquals("keep me safe", desk.trashed().single().body)
    }

    @Test fun aFirstSyncNeverDeletes() {
        write(desk, "A.md", "there")
        sync()
        assertTrue(File(phone.root, "A.md").exists())
    }

    @Test fun whenBothChangeBothVersionsSurviveOnBothDevices() {
        write(phone, "A.md", "start")
        sync()
        val now = System.currentTimeMillis()
        write(phone, "A.md", "phone version", now - 60_000)
        write(desk, "A.md", "desk version", now)
        val r = sync()
        assertEquals(1, r.conflicts)
        assertEquals("desk version", read(phone, "A.md"))
        assertEquals("desk version", read(desk, "A.md"))
        assertEquals("phone version", read(phone, "A (from Phone).md"))
        assertEquals("phone version", read(desk, "A (from Phone).md"))
        assertEquals(0, sync().total)
    }

    @Test fun itSettlesAfterAConflictTheOtherWayRoundToo() {
        write(desk, "A.md", "desk", System.currentTimeMillis() - 60_000)
        write(phone, "A.md", "phone")
        assertEquals(1, sync().conflicts)
        assertEquals("phone", read(desk, "A.md"))
        assertEquals("desk", read(phone, "A (from Desk).md"))
        assertEquals(0, sync().total)
    }

    @Test fun eachSideStaysEncryptedUnderItsOwnPassword() {
        write(phone, "A.md", "from the phone")
        write(desk, "B.md", "from the desk")
        ReminderStore(phone).update {
            listOf(Reminder(1, "Encrypted tea", whenMillis = 100))
        }
        phone.convert(PasswordKey.create(File(phone.root, LockedNotes.ALL_NOTES_KEY), "phone password"))
        desk.convert(PasswordKey.create(File(desk.root, LockedNotes.ALL_NOTES_KEY), "desk password"))
        sync()
        fun scan(v: Vault) = v.root.walkTopDown().filter { it.isFile && it.extension == "md" }.toList()
        listOf(phone, desk).forEach { v ->
            assertEquals(2, scan(v).size)
            scan(v).forEach { assertTrue(it.path, NoteCipher.isSealed(it.readBytes())) }
        }
        listOf(phone, desk).forEach { v ->
            assertTrue(NoteCipher.isSealed(v.file(ReminderStore.PATH).readBytes()))
            assertEquals("Encrypted tea", ReminderStore(v).items().single().title)
        }
        assertEquals("from the phone", desk.read("A.md").body)
        assertEquals("from the desk", phone.read("B.md").body)
        // Their sealed bytes differ, but the notes match, so nothing more needs doing.
        assertEquals(0, sync().total)
    }

    @Test fun aPhonesPrivateRemindersSyncWithAnEncryptedComputer() {
        val phoneFile = File(dir.root, "phone-reminders.json")
        val phoneStore = ReminderStore(phoneFile) { "phone" }
        phoneStore.update { it + io.github.shoalapps.squill.Reminder(1, "Ring me", whenMillis = 5000) }
        desk.convert(PasswordKey.create(File(desk.root, LockedNotes.ALL_NOTES_KEY), "desk password"))
        val s = SyncProtocol.sync(phone, phonePeers, phonePeers.peers().single(), phonePeers.selfId, "Phone", reminders = phoneStore)
        assertEquals(1, s.reminders)
        assertEquals("Ring me", ReminderStore(desk).items().single().title)
        assertTrue(NoteCipher.isSealed(File(desk.root, ReminderStore.PATH).readBytes()))
        // Still readable on the phone with nothing unlocked, and not left in the notes folder.
        assertEquals("Ring me", ReminderStore(phoneFile) { "phone" }.items().single().title)
        assertFalse(File(phone.root, ReminderStore.PATH).exists())
    }

    @Test fun anInterruptedSyncIsNotMarkedAsDone() {
        write(phone, "A.md", "x")
        server.close()
        assertTrue(runCatching { sync() }.isFailure)
        assertEquals(emptyMap<String, String>(), phonePeers.base(peer.id))
    }

    @Test fun anUnpairedDeviceGetsNothing() {
        val strangers = PeerStore(dir.newFolder("stranger"))
        val stolen = peer.copy(secret = "wrong secret")
        strangers.save(stolen)
        write(desk, "Private.md", "secret")
        assertTrue(runCatching { SyncProtocol.sync(Vault(dir.newFolder("sv")), strangers, stolen, strangers.selfId, "Stranger") }.isFailure)
        // Right id, wrong key: also refused.
        assertTrue(runCatching { SyncProtocol.sync(Vault(dir.newFolder("sv2")), strangers, stolen, phonePeers.selfId, "Stranger") }.isFailure)
    }

    @Test fun hiddenFilesKeysAndEscapesAreRefusedFromTheOtherSide() {
        File(phone.root, "Fine.md").writeText("ok")
        sync()
        // A hostile peer's requests, sent the honest way down the sealed channel.
        val secret = peer.secret.toByteArray()
        Socket("127.0.0.1", server.boundPort).use { socket ->
            SealedChannel(socket, secret, true, SyncProtocol.hello("SYNC", phonePeers.selfId)).use { ch ->
                for (path in listOf(".sync/peers.json", "../outside.md", "a/../../x.md", ".trash/x.md", "evil.sh")) {
                    ch.send("""{"op":"GET","path":"$path"}""".toByteArray())
                    val reply = runCatching { ch.receive().toString(Charsets.UTF_8) }
                    assertTrue("$path -> $reply", reply.isFailure || "\"ok\":false" in reply.getOrThrow())
                    if (reply.isFailure) return
                }
            }
        }
    }

    @Test fun peerKeysNeverSync() {
        write(phone, "A.md", "x")
        sync()
        assertFalse(File(desk.root, "peers.json").exists())
        assertTrue(desk.stats().none { it.path.contains("sync") })
    }

    @Test fun aSkippedFileWaitsItsTurn() {
        write(phone, "Open.ora", "painting")
        val r = SyncProtocol.sync(phone, phonePeers, phonePeers.peers().single(), phonePeers.selfId, "Phone", skip = setOf("Open.ora"))
        assertEquals(0, r.total)
        assertFalse(File(desk.root, "Open.ora").exists())
        assertEquals(1, sync().pushed)
    }

    @Test fun aRenameIsASafeDeleteAndAdd() {
        write(phone, "Old.md", "words")
        sync()
        File(phone.root, "Old.md").renameTo(File(phone.root, "New.md"))
        sync()
        assertEquals("words", read(desk, "New.md"))
        assertFalse(File(desk.root, "Old.md").exists())
    }
}

class SealedChannelTest {
    private fun pair(aSecret: String, bSecret: String, test: (SealedChannel, SealedChannel?) -> Unit) {
        ServerSocket(0).use { listener ->
            var server: SealedChannel? = null
            val t = Thread {
                val s = listener.accept()
                val input = s.getInputStream()
                val marker = ByteArray(SyncProtocol.MAGIC.size + 4 + 4)
                java.io.DataInputStream(input).readFully(marker)
                server = SealedChannel(s, bSecret.toByteArray(), false, ByteArray(0))
            }
            t.start()
            val client = SealedChannel(Socket("127.0.0.1", listener.localPort), aSecret.toByteArray(), true, SyncProtocol.MAGIC + "ABCD".toByteArray())
            t.join()
            test(client, server)
            client.close(); server?.close()
        }
    }

    @Test fun messagesArriveInBothDirections() = pair("k", "k") { a, b ->
        a.send("hello".toByteArray())
        assertEquals("hello", b!!.receive().toString(Charsets.UTF_8))
        b.send("back".toByteArray())
        assertEquals("back", a.receive().toString(Charsets.UTF_8))
    }

    @Test fun aWrongKeyIsDetected() = pair("right", "wrong") { a, b ->
        a.send("hello".toByteArray())
        assertTrue(runCatching { b!!.receive() }.exceptionOrNull() is SyncException)
    }

    @Test fun keysAreDifferentEachSessionAndDirection() {
        assertNotEquals(SyncCrypto.pairingCode(), SyncCrypto.pairingCode())
        assertEquals(19, SyncCrypto.pairingCode().length)
        assertEquals(16, SyncCrypto.normalise("abcd-efgh-jkmn-pqrs").length)
    }
}
