package io.github.shoalapps.squill.desktop

import io.github.shoalapps.squill.Reminder
import io.github.shoalapps.squill.reminders.ReminderStore

import io.github.shoalapps.squill.notes.Deck
import io.github.shoalapps.squill.notes.DeckInfo
import io.github.shoalapps.squill.notes.Library
import io.github.shoalapps.squill.notes.Links
import io.github.shoalapps.squill.notes.LockedNotes
import io.github.shoalapps.squill.notes.Markdown
import io.github.shoalapps.squill.notes.Note
import io.github.shoalapps.squill.notes.NoteInfo
import io.github.shoalapps.squill.notes.NoteHistory
import io.github.shoalapps.squill.notes.NoteFile
import io.github.shoalapps.squill.notes.PasswordKey
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
    val reminderStore = ReminderStore(vault)
    private val _reminders = MutableStateFlow<List<Reminder>>(emptyList())
    val reminders: StateFlow<List<Reminder>> = _reminders.asStateFlow()
    fun changeReminders(change: (List<Reminder>) -> List<Reminder>) =
        background({ result -> result.onFailure { _problem.value = it.message } }) {
            reminderStore.update(change = change)
            reloadNow()
        }
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

    private val keyFile = File(root, LockedNotes.ALL_NOTES_KEY)
    private val _needsUnlock = MutableStateFlow(keyFile.exists())
    /** True while this folder is encrypted and its password hasn't been given. */
    val needsUnlock: StateFlow<Boolean> = _needsUnlock.asStateFlow()
    private val _encrypted = MutableStateFlow(keyFile.exists())
    val encrypted: StateFlow<Boolean> = _encrypted.asStateFlow()

    fun start() {
        worker.scheduleWithFixedDelay({ runCatching { if (!_needsUnlock.value) reloadIfChanged() } }, 2, 2, TimeUnit.SECONDS)
        if (!_needsUnlock.value) open()
    }

    /** Nothing is read, and no device can connect, until the folder can actually be opened. */
    private fun open() {
        reloadNow()
        if (hosting) runCatching {
            server = SyncProtocol.Server(vault, peers, deviceName, lock = lock).also {
                it.onChange = { _peerList.value = peers.peers(); worker.execute { runCatching { reloadNow() } } }
                it.start()
            }
        }.onFailure { _problem.value = "Sync couldn't start, so devices can't connect: ${it.message}" }
    }

    private fun <T> background(onDone: (Result<T>) -> Unit, block: () -> T) {
        worker.execute {
            val result = runCatching { synchronized(lock) { block() } }
            javax.swing.SwingUtilities.invokeLater { onDone(result) }
        }
    }

    fun unlock(password: String, onDone: (Boolean) -> Unit) = background({ onDone(it.isSuccess) }) {
        vault.cipher = PasswordKey.unlock(keyFile, password)
        runCatching { vault.finishSealing() }
        _needsUnlock.value = false
        open()
    }

    /** Forgets the key and everything read with it, and stops devices connecting. */
    fun lockNow() {
        if (!keyFile.exists()) return
        worker.execute {
            synchronized(lock) {
                runCatching { server?.close() }
                server = null
                vault.cipher = null
                known = HashMap(); knownSketches = HashMap(); knownDecks = HashMap()
                _library.value = Library()
                _paintings.value = emptyList()
                _reminders.value = emptyList()
                _needsUnlock.value = true
            }
        }
    }

    /** Seals everything here under this computer's own password. Notes reach it from phones over the sealed link. */
    fun encrypt(password: String, onDone: (Result<Unit>) -> Unit) = background(onDone) {
        if (keyFile.exists()) throw VaultException("This folder is already encrypted")
        val key = PasswordKey.create(keyFile, password)
        try {
            vault.convert(key)
        } catch (e: Exception) {
            // Nothing was sealed unless the vault took the key, and then the key has to stay.
            if (vault.cipher == null) keyFile.delete() else _encrypted.value = true
            throw e
        }
        _encrypted.value = true
        reloadNow()
    }

    fun decrypt(password: String, onDone: (Result<Unit>) -> Unit) = background(onDone) {
        PasswordKey.unlock(keyFile, password)
        vault.convert(null)
        keyFile.delete()
        _encrypted.value = false
        reloadNow()
    }

    fun stop() {
        runCatching { server?.close() }
        worker.shutdown()
    }

    private fun reloadIfChanged() {
        _reminders.value = reminderStore.items()
        val stats = vault.stats()
        val stamp = stats.fold(stats.size.toLong()) { h, s -> h * 31 + s.path.hashCode() + s.modified + s.size }
        if (stamp != lastStamp) reloadNow()
    }

    /** Re-reads only what's changed. */
    fun reloadNow() = synchronized(lock) {
        _reminders.value = reminderStore.items()
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

    private fun <T> act(onDone: (T) -> Unit = {}, onFailed: () -> Unit = {}, block: () -> T) {
        worker.execute {
            runCatching { synchronized(lock) { block() } }.onSuccess {
                runCatching { reloadNow() }
                javax.swing.SwingUtilities.invokeLater { onDone(it) }
            }.onFailure { onFailed(); _problem.value = (it as? VaultException)?.message ?: "That didn't work: ${it.message}" }
        }
    }

    fun create(folder: String, name: String, body: String = "", onDone: (Note) -> Unit = {}) = act(onDone) { vault.create(folder, name, body) }
    fun noteVersions(path: String, onDone: (List<NoteHistory.Version>) -> Unit) = background({ result ->
        result.onFailure { _problem.value = "Couldn't read version history" }
        onDone(result.getOrDefault(emptyList()))
    }) { vault.history.list(path) }
    fun previewVersion(path: String, version: NoteHistory.Version, onDone: (String) -> Unit) =
        background({ result ->
            result.onSuccess(onDone).onFailure { _problem.value = "Couldn't read that version" }
        }) { NoteFile.read(vault.history.read(path, version)).first }
    fun restoreVersion(path: String, version: NoteHistory.Version, onDone: (Note) -> Unit) =
        act(onDone) { vault.history.restore(path, version) }

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

    private val _saveFailures = MutableStateFlow(0)
    /** Goes up whenever an editor's save didn't reach the disk. */
    val saveFailures: StateFlow<Int> = _saveFailures.asStateFlow()

    private fun saving(block: () -> Unit) = act<Unit>(onFailed = { _saveFailures.value++ }) { block() }

    /** Queued immediately on leaving; the painting worker supplies the final snapshot. */
    fun savePaintingOnLeave(path: String, snapshot: () -> PaintingCopy?) = saving {
        snapshot()?.let { copy -> if (vault.exists(path)) vault.writeFile(path) { OraFile.save(copy, it) } }
    }

    /** Runs after the editor's final writes, without blocking the window. */
    fun afterSaves(onDone: () -> Unit) { worker.execute { javax.swing.SwingUtilities.invokeLater(onDone) } }

    fun savePainting(path: String, copy: PaintingCopy) = saving {
        if (vault.exists(path)) vault.writeFile(path) { OraFile.save(copy, it) }
    }

    fun keepOtherPainting(path: String, copy: PaintingCopy) = act<Unit> {
        val (folder, name) = besideIt(path)
        vault.createFile(folder, name, "ora") { output -> copy.originalBytes?.let { output.write(it) } ?: OraFile.save(copy, output) }
    }

    fun saveSketch(path: String, sketch: Sketch) = saving { if (vault.exists(path)) vault.saveSketch(path, sketch) }

    private fun besideIt(path: String) = path.substringBeforeLast('/', "") to path.substringAfterLast('/').substringBeforeLast('.') + " (from another device)"

    /** A drawing changed elsewhere while it was being edited here. Nothing's lost: it goes beside this one. */
    fun keepOtherSketch(path: String, sketch: Sketch) = act<Unit> { val (folder, name) = besideIt(path); vault.createSketch(folder, name, sketch) }

    fun keepOtherDeck(path: String, deck: Deck) = act<Unit> { val (folder, name) = besideIt(path); vault.createDeck(folder, name, deck) }

    fun saveDeck(path: String, deck: Deck) = saving { if (vault.exists(path)) vault.saveDeck(path, deck) }

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
