package io.github.shoalapps.squill.desktop

import io.github.shoalapps.squill.notes.Deck
import io.github.shoalapps.squill.notes.DeckInfo
import io.github.shoalapps.squill.notes.Library
import io.github.shoalapps.squill.notes.Links
import io.github.shoalapps.squill.notes.Markdown
import io.github.shoalapps.squill.notes.Note
import io.github.shoalapps.squill.notes.NoteInfo
import io.github.shoalapps.squill.notes.Sketch
import io.github.shoalapps.squill.notes.SketchInfo
import io.github.shoalapps.squill.notes.Vault
import io.github.shoalapps.squill.notes.VaultException
import io.github.shoalapps.squill.sync.Peer
import io.github.shoalapps.squill.sync.PeerStore
import io.github.shoalapps.squill.sync.SyncCrypto
import io.github.shoalapps.squill.sync.SyncProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A painting on disk; its picture is decoded when shown. */
data class PaintingFile(val path: String, val modified: Long, val size: Long) {
    val title: String get() = path.substringAfterLast('/').removeSuffix(".ora")
    val folder: String get() = path.substringBeforeLast('/', "")
}

/** The desktop's view of a folder of notes. The folder is the truth: edits from other programs turn up here too. */
class Model(val root: File, private val hosting: Boolean = true, private val deviceName: String = hostName()) {
    val vault = Vault(root)
    val peers = PeerStore(root)
    private val lock = Any()
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "squill-files").apply { isDaemon = true } }
    private var server: SyncProtocol.Server? = null

    private val _library = MutableStateFlow(Library())
    val library: StateFlow<Library> = _library.asStateFlow()
    private val _paintings = MutableStateFlow<List<PaintingFile>>(emptyList())
    val paintings: StateFlow<List<PaintingFile>> = _paintings.asStateFlow()
    private val _peerList = MutableStateFlow(peers.peers())
    val peerList: StateFlow<List<Peer>> = _peerList.asStateFlow()
    private val _pairingCode = MutableStateFlow<String?>(null)
    val pairingCode: StateFlow<String?> = _pairingCode.asStateFlow()
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()
    fun clearProblem() { _problem.value = null }

    private var known = HashMap<String, NoteInfo>()
    private var knownSketches = HashMap<String, SketchInfo>()
    private var knownDecks = HashMap<String, DeckInfo>()
    private var lastStamp = 0L

    fun start() {
        reloadNow()
        worker.scheduleWithFixedDelay({ runCatching { reloadIfChanged() } }, 2, 2, TimeUnit.SECONDS)
        if (hosting) runCatching {
            server = SyncProtocol.Server(vault, peers, deviceName, lock = lock).also {
                it.onChange = { _peerList.value = peers.peers(); worker.execute { runCatching { reloadNow() } } }
                it.start()
            }
        }.onFailure { _problem.value = "Sync couldn't start, so devices can't connect: ${it.message}" }
    }

    fun stop() {
        runCatching { server?.close() }
        worker.shutdown()
    }

    private fun reloadIfChanged() {
        val stats = vault.stats()
        val stamp = stats.fold(stats.size.toLong()) { h, s -> h * 31 + s.path.hashCode() + s.modified + s.size }
        if (stamp != lastStamp) reloadNow()
    }

    /** Re-reads only what's changed. */
    fun reloadNow() = synchronized(lock) {
        val stats = vault.stats()
        lastStamp = stats.fold(stats.size.toLong()) { h, s -> h * 31 + s.path.hashCode() + s.modified + s.size }
        val notes = HashMap<String, NoteInfo>(stats.size * 2)
        val sketches = HashMap<String, SketchInfo>()
        val decks = HashMap<String, DeckInfo>()
        val paintings = mutableListOf<PaintingFile>()
        stats.forEach { s ->
            val lower = s.path.lowercase()
            when {
                lower.endsWith(".ora") -> paintings += PaintingFile(s.path, s.modified, s.size)
                lower.endsWith(".deck") -> {
                    val old = knownDecks[s.path]
                    if (old != null && old.modified == s.modified && old.size == s.size) decks[s.path] = old
                    else runCatching { Deck.parse(vault.readText(s.path)) }.getOrNull()?.let { decks[s.path] = DeckInfo(s.path, s.modified, s.size, it) }
                }
                lower.endsWith(".svg") -> {
                    val old = knownSketches[s.path]
                    if (old != null && old.modified == s.modified && old.size == s.size) sketches[s.path] = old
                    else runCatching { Sketch.parse(vault.readText(s.path)) }.getOrNull()?.let { sketches[s.path] = SketchInfo(s.path, s.modified, s.size, it) }
                }
                else -> {
                    val old = known[s.path]
                    if (old != null && old.note.modified == s.modified && old.size == s.size) notes[s.path] = old
                    else runCatching {
                        val note = vault.read(s.path)
                        val (links, tags) = Links.scan(note.body)
                        val (done, tasks) = Markdown.taskCount(note.body)
                        notes[s.path] = NoteInfo(note, links, tags, Markdown.excerpt(note.body), done, tasks, s.size)
                    }
                }
            }
        }
        known = notes; knownSketches = sketches; knownDecks = decks
        _library.value = Library(notes.values.toList(), vault.folders(), vault.trashed(), sketches.values.toList(), decks.values.toList())
        _paintings.value = paintings.sortedByDescending { it.modified }
    }

    private fun <T> act(onDone: (T) -> Unit = {}, block: () -> T) {
        worker.execute {
            runCatching { synchronized(lock) { block() } }.onSuccess {
                runCatching { reloadNow() }
                javax.swing.SwingUtilities.invokeLater { onDone(it) }
            }.onFailure { _problem.value = (it as? VaultException)?.message ?: "That didn't work: ${it.message}" }
        }
    }

    fun create(folder: String, name: String, body: String = "", onDone: (Note) -> Unit = {}) = act(onDone) { vault.create(folder, name, body) }
    fun createFolder(parent: String, name: String) = act<Any> { vault.createFolder(parent, name) }
    fun move(path: String, folder: String, name: String, onDone: (Note) -> Unit = {}) = act(onDone) { vault.move(path, folder, name) }
    fun trash(path: String, onDone: () -> Unit = {}) = act<Unit>({ onDone() }) { vault.trash(path) }
    fun restore(path: String) = act<Any> { vault.restore(path) }

    /** Saved before this returns, so nothing typed is lost when the window closes. Skipped if the note has gone. */
    /** Adds a link from one note to another, and says whether that changed anything. */
    fun linkNotes(fromPath: String, target: String, onDone: (Boolean) -> Unit = {}) = act(onDone) {
        val note = vault.read(fromPath)
        val updated = Links.addLink(note.body, target, Links.Resolver(vault.stats().map { it.path }.filter { it.endsWith(".md") }))
        if (updated != note.body) vault.save(fromPath, updated, note.pinned, note.colour)
        updated != note.body
    }

    fun unlink(fromPath: String, target: String) = act<Unit> {
        val note = vault.read(fromPath)
        val updated = Links.removeLink(note.body, target)
        if (updated != note.body) vault.save(fromPath, updated, note.pinned, note.colour)
    }

    fun saveDeck(path: String, deck: io.github.shoalapps.squill.notes.Deck) = act<Unit> { if (vault.exists(path)) vault.saveDeck(path, deck) }

    fun saveNow(path: String, body: String, pinned: Boolean, colour: String?) {
        synchronized(lock) { if (vault.exists(path)) vault.save(path, body, pinned, colour) }
    }

    fun beginPairing() {
        val code = SyncCrypto.pairingCode()
        server?.allowPairing(code)
        _pairingCode.value = code
    }

    fun endPairing() { server?.allowPairing(null); _pairingCode.value = null }

    fun forget(peer: Peer) { peers.remove(peer.id); _peerList.value = peers.peers() }

    /** Where a phone can reach this computer. */
    fun addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())
}

fun hostName(): String = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() } ?: System.getProperty("user.name") ?: "Computer"
