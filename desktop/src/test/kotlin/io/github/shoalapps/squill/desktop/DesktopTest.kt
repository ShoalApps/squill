package io.github.shoalapps.squill.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import io.github.shoalapps.squill.notes.Deck
import io.github.shoalapps.squill.notes.Sketch
import io.github.shoalapps.squill.sync.PeerStore
import io.github.shoalapps.squill.sync.SyncCrypto
import io.github.shoalapps.squill.sync.SyncProtocol
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Renders the real screens without opening a window, and saves pictures to look at. */
class DesktopTest {
    @get:Rule val dir = TemporaryFolder()
    private val shots = File("build/shots").apply { mkdirs() }

    private fun seeded(): Model {
        val m = Model(dir.newFolder("notes"), hosting = false, deviceName = "Test")
        m.vault.create("", "Welcome", "# Your notes\n\nWith **bold**, *italics* and a [[Boats|link]]. #getting-started\n\n- [x] Done\n- [ ] Not yet\n\n> A quote\n\n| A | B |\n|---|---|\n| 1 | 2 |\n\n![[Boat.svg]]\n")
        m.vault.create("Ideas", "Boats", "See [[Welcome]] and [[Sails]].")
        m.vault.createSketch("", "Boat", Sketch(strokes = listOf(Sketch.line(0f, 0f, 400f, 200f, 0xC62828, 8f), Sketch.ellipse(50f, 50f, 300f, 200f, 0x4A4FA3, 6f))))
        m.vault.createDeck("", "Talk", Deck())
        m.reloadNow()
        return m
    }

    private fun shot(name: String, model: Model, dark: Boolean, open: String? = null, page: String = "NOTES") {
        ImageComposeScene(1280, 820, Density(1f)) { App(model, dark = dark, initialOpen = open, initialPage = page) }.let { scene ->
            var t = 0L
            repeat(6) { scene.render(t); t += 100_000_000 }
            val image = scene.render(t)
            File(shots, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            assertTrue(image.width == 1280)
            scene.close()
        }
    }

    @Test fun everyScreenRenders() {
        val m = seeded()
        shot("notes-dark", m, true, "Welcome.md")
        shot("notes-light", m, false, "Welcome.md")
        shot("drawing", m, true, "Boat.svg")
        shot("slides", m, false, "Talk.deck")
        shot("graph", m, true, page = "GRAPH")
        shot("devices", m, true, page = "DEVICES")
    }

    @Test fun theDesktopAndAPhoneSyncThroughTheModel() {
        val m = Model(dir.newFolder("desk"), hosting = false, deviceName = "Desk")
        val server = SyncProtocol.Server(m.vault, m.peers, "Desk", port = 0)
        server.start()
        val code = SyncCrypto.pairingCode()
        server.allowPairing(code)
        val phone = io.github.shoalapps.squill.notes.Vault(dir.newFolder("phone"))
        val store = PeerStore(phone.root)
        val peer = SyncProtocol.pair("127.0.0.1", server.boundPort, code, store.selfId, "Phone", store)
        phone.create("", "From phone", "hello")
        SyncProtocol.sync(phone, store, peer, store.selfId, "Phone")
        m.reloadNow()
        assertEquals(listOf("From phone"), m.library.value.notes.map { it.note.title })
        assertEquals(listOf("Phone"), m.peers.peers().map { it.name })
        server.close()
    }

    @Test fun unsavedTextIsSavedAndADeletedNoteStaysDeleted() {
        val m = seeded()
        m.saveNow("Welcome.md", "changed", false, null)
        assertEquals("changed", m.vault.read("Welcome.md").body)
        m.vault.trash("Welcome.md")
        m.saveNow("Welcome.md", "late save", false, null)
        assertTrue(!m.vault.exists("Welcome.md"))
    }
}

/** Only for checking against a real phone: run with SQUILL_LIVE_HOST set. */
class LiveHostTest {
    @Test fun hostForARealPhone() {
        val code = System.getenv("SQUILL_LIVE_HOST") ?: return
        val log = File(System.getenv("SQUILL_LIVE_LOG"))
        val dir = java.nio.file.Files.createTempDirectory("squill-live").toFile()
        val vault = io.github.shoalapps.squill.notes.Vault(dir)
        vault.create("", "From computer", "Written on the desktop.\n")
        val peers = PeerStore(dir)
        val server = SyncProtocol.Server(vault, peers, "Test computer")
        server.start()
        server.allowPairing(code)
        fun report(s: String) = log.appendText(s + "\n")
        report("listening on ${server.boundPort}")
        val end = System.currentTimeMillis() + 240_000
        var phase = 0
        while (System.currentTimeMillis() < end) {
            if (phase == 0 && peers.peers().isNotEmpty()) { report("paired with " + peers.peers().single().name); phase = 1 }
            val notes = vault.notes().map { it.title }
            if (phase == 1 && notes.size > 1 && peers.peers().single().lastSync > 0) { report("synced, notes here: " + notes.sorted()); phase = 2; vault.create("", "Second from computer", "Later.\n") }
            if (phase == 2 && File(System.getenv("SQUILL_LIVE_LOG") + ".stop").exists()) { report("final: " + vault.notes().map { it.title + "=" + it.body.trim().take(30) }.sorted()); break }
            Thread.sleep(500)
        }
        server.close()
    }
}
