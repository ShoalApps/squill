package io.github.shoalapps.squill.notes

import io.github.shoalapps.squill.Store

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import io.github.shoalapps.squill.sync.Peer
import io.github.shoalapps.squill.sync.PeerStore
import io.github.shoalapps.squill.sync.SyncProtocol
import java.io.File
import java.time.LocalDate

/** One note at a time goes through a single-file queue, so a save can't overtake a rename. */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModel(app: Application) : AndroidViewModel(app) {
    val privateRoot = File(app.filesDir, "vault")
    val root: File = io.github.shoalapps.squill.Prefs.notesFolder(app)?.let { File(it) } ?: privateRoot
    val vault = Vault(root)
    val locked = LockedNotes(root)
    private val allNotesKey = File(root, LockedNotes.ALL_NOTES_KEY)

    /** True while all notes are encrypted and the password hasn't been given. */
    private val _needsUnlock = MutableStateFlow(allNotesKey.exists())
    val needsUnlock: StateFlow<Boolean> = _needsUnlock.asStateFlow()
    private val _encrypted = MutableStateFlow(allNotesKey.exists())
    val encrypted: StateFlow<Boolean> = _encrypted.asStateFlow()
    val peers = PeerStore(root)
    /** Held for each file operation, so sync and saving can't touch the same file at once. */
    private val vaultLock = io.github.shoalapps.squill.NoteWidgetAccess.lock
    private val deviceName: String = android.os.Build.MODEL ?: "Android"

    private val _syncStatus = MutableStateFlow<String?>(null)
    val syncStatus: StateFlow<String?> = _syncStatus.asStateFlow()
    private val _peerList = MutableStateFlow(peers.peers())
    val peerList: StateFlow<List<Peer>> = _peerList.asStateFlow()
    /** A drawing, painting or presentation being worked on: sync leaves it until it's closed. */
    @Volatile var openFile: String? = null
    @Volatile private var syncing = false
    private var pendingSync: kotlinx.coroutines.Job? = null
    private val queue = Dispatchers.IO.limitedParallelism(1)

    private val _library = MutableStateFlow(Library())
    val library: StateFlow<Library> = _library.asStateFlow()

    private val _problem = MutableStateFlow<String?>(null)
    /** Something the user should be told went wrong. */
    val problem: StateFlow<String?> = _problem.asStateFlow()
    fun clearProblem() { _problem.value = null }

    private val _shared = MutableStateFlow<String?>(null)
    /** Text shared into Squill, waiting to become a note. */
    val shared: StateFlow<String?> = _shared.asStateFlow()
    fun share(text: String) { _shared.value = text }
    fun clearShared() { _shared.value = null }

    private val _quick = MutableStateFlow<String?>(null)
    /** A launcher shortcut waiting to be acted on. */
    val quick: StateFlow<String?> = _quick.asStateFlow()
    fun quick(action: String) { _quick.value = action }
    fun clearQuick() { _quick.value = null }

    private val _widgetNote = MutableStateFlow<String?>(null)
    val widgetNote: StateFlow<String?> = _widgetNote.asStateFlow()
    fun openWidgetNote(path: String?) { _widgetNote.value = path?.takeIf(io.github.shoalapps.squill.NoteWidgetAccess::allowed) }
    fun clearWidgetNote() { _widgetNote.value = null }

    private val _incoming = MutableStateFlow<android.net.Uri?>(null)
    /** A file another app asked Squill to open. */
    val incoming: StateFlow<android.net.Uri?> = _incoming.asStateFlow()
    fun open(uri: android.net.Uri) { _incoming.value = uri }
    fun clearIncoming() { _incoming.value = null }

    private val indexFile = File(app.cacheDir, "notes-index.json")
    private val indexJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val indexSerializer = kotlinx.serialization.builtins.ListSerializer(NoteInfo.serializer())
    private var known = HashMap<String, NoteInfo>()
    private var knownSketches = HashMap<String, SketchInfo>()
    private var knownDecks = HashMap<String, DeckInfo>()
    private var knownPaintings = HashMap<String, PaintingInfo>()
    private var indexWrite: kotlinx.coroutines.Job? = null

    init {
        io.github.shoalapps.squill.NoteWidgetAccess.attach(vault)
        val owner = java.lang.ref.WeakReference(this)
        io.github.shoalapps.squill.NoteWidgetAccess.onEdit = { owner.get()?.refresh() }
        io.github.shoalapps.squill.NoteWidgets.refresh(app)
        Store.onEdit = { syncSoon() }
        if (!allNotesKey.exists()) open()
    }

    override fun onCleared() {
        io.github.shoalapps.squill.NoteWidgetAccess.detach(vault)
        io.github.shoalapps.squill.NoteWidgets.refresh(getApplication())
        super.onCleared()
    }

    private fun open() {
        viewModelScope.launch(queue) {
            // Show what was here last time straight away, then quietly catch up with any changes.
            // Encrypted notes never go in this index, as it isn't encrypted.
            if (vault.cipher == null) runCatching {
                val saved = indexJson.decodeFromString(indexSerializer, indexFile.readText())
                known = saved.associateByTo(HashMap(saved.size * 2)) { it.note.path }
                _library.value = Library(saved)
            }
            synchronized(vaultLock) {
                seedOnce()
                reload()
            }
        }
    }

    fun unlockAll(password: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.Default) {
            val key = runCatching { PasswordKey.unlock(allNotesKey, password) }.getOrNull()
            if (key != null) {
                synchronized(vaultLock) { vault.cipher = key }
                _needsUnlock.value = false
                viewModelScope.launch(queue) { runCatching { synchronized(vaultLock) { vault.finishSealing() } } }
                open()
            }
            withContext(Dispatchers.Main) { onDone(key != null) }
        }
    }

    /** Forgets the key and everything read with it, until the password is given again. */
    fun lockAll() {
        if (!allNotesKey.exists()) return
        viewModelScope.launch(queue) {
            synchronized(vaultLock) { vault.cipher = null }
            io.github.shoalapps.squill.NoteWidgets.refresh(getApplication())
            known = HashMap(); knownSketches = HashMap(); knownDecks = HashMap(); knownPaintings = HashMap()
            _library.value = Library()
            _needsUnlock.value = true
        }
    }

    /** Seals every note under a new password. */
    fun encryptAll(password: String, onDone: (Result<Unit>) -> Unit) {
        viewModelScope.launch(queue) {
            val r = runCatching {
                synchronized(vaultLock) {
                    if (allNotesKey.exists()) throw VaultException("Your notes are already encrypted")
                    val key = PasswordKey.create(allNotesKey, password)
                    try {
                        vault.convert(key)
                    } catch (e: Exception) {
                        // Nothing was sealed unless the vault took the key, and then the key has to stay.
                        if (vault.cipher == null) allNotesKey.delete() else _encrypted.value = true
                        throw e
                    }
                    indexFile.delete()
                    reload()
                }
                _encrypted.value = true
            }
            withContext(Dispatchers.Main) { onDone(r) }
        }
    }

    /** Takes encryption off, once the password has proved it's you. */
    fun decryptAll(password: String, onDone: (Result<Unit>) -> Unit) {
        viewModelScope.launch(queue) {
            val r = runCatching {
                PasswordKey.unlock(allNotesKey, password)
                synchronized(vaultLock) {
                    vault.convert(null)
                    allNotesKey.delete()
                    reload()
                }
                _encrypted.value = false
            }
            withContext(Dispatchers.Main) { onDone(r) }
        }
    }

    /** Moves everything to a new home; the app restarts afterwards to open it from there. */
    fun moveVault(to: File, onDone: (Result<Int>) -> Unit) {
        viewModelScope.launch(queue) {
            val r = runCatching { synchronized(vaultLock) { VaultMover.move(root, to) } }
            withContext(Dispatchers.Main) { onDone(r) }
        }
    }

    // The locked vault. Everything here runs in the queue, so nothing blocks the screen.
    fun setUpVault(password: String, onDone: () -> Unit) = viewModelScope.launch(queue) {
        runCatching { locked.setUp(password) }
        withContext(Dispatchers.Main) { onDone() }
    }

    fun unlockVault(password: String, onDone: (Boolean) -> Unit) = viewModelScope.launch(queue) {
        val ok = runCatching { locked.unlock(password) }.isSuccess
        withContext(Dispatchers.Main) { onDone(ok) }
    }

    fun lockVault() = locked.lock()

    fun restoredLockedSets(onDone: (Int) -> Unit) = viewModelScope.launch(queue) {
        val n = runCatching { locked.restoredSets() }.getOrDefault(0)
        withContext(Dispatchers.Main) { onDone(n) }
    }

    fun mergeRestoredLocked(password: String, onDone: (Boolean) -> Unit) = viewModelScope.launch(queue) {
        val ok = runCatching { locked.mergeRestored(password) }.isSuccess
        withContext(Dispatchers.Main) { onDone(ok) }
    }

    fun lockedNotes(onDone: (List<LockedNote>) -> Unit) = viewModelScope.launch(queue) {
        val list = runCatching { locked.notes() }.getOrDefault(emptyList())
        withContext(Dispatchers.Main) { onDone(list) }
    }

    fun saveLocked(id: String, title: String, body: String, onDone: (LockedNote) -> Unit) = viewModelScope.launch(queue) {
        runCatching { if (id.isEmpty()) locked.save(title, body) else locked.save(title, body, id) }
            .onSuccess { n -> withContext(Dispatchers.Main) { onDone(n) } }
            .onFailure { _problem.value = "Couldn't save that locked note" }
    }

    fun deleteLocked(id: String, onDone: () -> Unit) = viewModelScope.launch(queue) {
        runCatching { locked.delete(id) }
        withContext(Dispatchers.Main) { onDone() }
    }

    fun changeVaultPassword(old: String, new: String, onDone: (Boolean) -> Unit) = viewModelScope.launch(queue) {
        val ok = runCatching { locked.changePassword(old, new) }.isSuccess
        withContext(Dispatchers.Main) { onDone(ok) }
    }

    /** Seals a note and removes the ordinary copy for good: leaving it in the trash would defeat the point. */
    fun moveToLocked(path: String, onDone: () -> Unit) = act<Unit>({ onDone() }) {
        vault.moveToLocked(path, locked)
        io.github.shoalapps.squill.NoteWidgets.movedToLocked(getApplication(), path)
        // An old list cache can contain the ordinary note's text too.
        indexWrite?.cancel()
        indexFile.delete()
        File(indexFile.path + ".tmp").delete()
    }

    fun moveOutOfLocked(id: String, title: String, body: String, onDone: () -> Unit) = act<Unit>({ onDone() }) {
        vault.create("", title.ifBlank { "Untitled" }, body)
        locked.delete(id)
    }

    /** Adds a link from one note to another, and says whether that changed anything. */
    fun linkNotes(fromPath: String, target: String, onDone: (Boolean) -> Unit) = act(onDone) {
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

    private fun seedOnce() {
        val marker = File(root, ".seeded")
        if (marker.exists()) return
        if (vault.notes().isEmpty()) {
            vault.create("", "Welcome to Squill", WELCOME)
            vault.create("", "How links work", LINKS_NOTE)
        }
        marker.writeText("")
    }

    /** Re-reads only what's changed, so saving one note in a big vault stays instant. */
    private fun reload() {
        io.github.shoalapps.squill.NoteWidgets.refresh(getApplication())
        val stats = vault.stats()
        val seen = HashMap<String, NoteInfo>(stats.size * 2)
        val seenSketches = HashMap<String, SketchInfo>()
        var changed = false
        val seenDecks = HashMap<String, DeckInfo>()
        val seenPaintings = HashMap<String, PaintingInfo>()
        stats.forEach { stat ->
            if (stat.path.endsWith(".ora", ignoreCase = true)) {
                val old = knownPaintings[stat.path]
                seenPaintings[stat.path] = if (old != null && old.modified == stat.modified && old.size == stat.size) old
                else PaintingInfo(stat.path, stat.modified, stat.size, if (vault.cipher == null) io.github.shoalapps.squill.paint.OraFile.thumbnail(vault.file(stat.path)) else runCatching { io.github.shoalapps.squill.paint.OraFile.thumbnail(vault.readBytes(stat.path)) }.getOrNull())
                return@forEach
            }
            if (stat.path.endsWith(".deck", ignoreCase = true)) {
                val old = knownDecks[stat.path]
                if (old != null && old.modified == stat.modified && old.size == stat.size) seenDecks[stat.path] = old
                else Deck.parse(vault.readText(stat.path))?.let { seenDecks[stat.path] = DeckInfo(stat.path, stat.modified, stat.size, it) }
                return@forEach
            }
            if (stat.path.endsWith(".svg", ignoreCase = true)) {
                val old = knownSketches[stat.path]
                if (old != null && old.modified == stat.modified && old.size == stat.size) seenSketches[stat.path] = old
                else Sketch.parse(vault.readText(stat.path))?.let { seenSketches[stat.path] = SketchInfo(stat.path, stat.modified, stat.size, it) }
                return@forEach
            }
            val old = known[stat.path]
            if (old != null && old.note.modified == stat.modified && old.size == stat.size) {
                seen[stat.path] = old
            } else runCatching {
                val note = vault.read(stat.path)
                val (links, tags) = Links.scan(note.body)
                val (done, tasks) = Markdown.taskCount(note.body)
                seen[stat.path] = NoteInfo(note, links, tags, Markdown.excerpt(note.body), done, tasks, stat.size)
                changed = true
            }
        }
        if (seen.size != known.size) changed = true
        known = seen
        knownSketches = seenSketches
        knownDecks = seenDecks
        knownPaintings = seenPaintings
        _library.value = Library(seen.values.toList(), vault.folders(), vault.trashed(), seenSketches.values.toList(), seenDecks.values.toList(), seenPaintings.values.toList())
        if (changed && vault.cipher == null) writeIndexSoon()
    }

    /** Written a moment later, not after every keystroke's save. */
    private fun writeIndexSoon() {
        indexWrite?.cancel()
        indexWrite = viewModelScope.launch(queue) {
            kotlinx.coroutines.delay(3000)
            if (vault.cipher == null && !allNotesKey.exists()) runCatching {
                val tmp = File(indexFile.path + ".tmp")
                tmp.writeText(indexJson.encodeToString(indexSerializer, known.values.toList()))
                tmp.renameTo(indexFile)
            }
        }
    }

    private fun <T> act(onDone: (T) -> Unit = {}, block: () -> T) {
        viewModelScope.launch(queue) {
            runCatching { synchronized(vaultLock) { block() } }.onSuccess { result ->
                synchronized(vaultLock) { reload() }
                syncSoon()
                withContext(Dispatchers.Main) { onDone(result) }
            }.onFailure { e ->
                _problem.value = (e as? VaultException)?.message ?: "Something went wrong saving that"
            }
        }
    }

    fun noteVersions(path: String, onDone: (List<NoteHistory.Version>) -> Unit) = viewModelScope.launch(queue) {
        val list = runCatching { synchronized(vaultLock) { vault.history.list(path) } }
            .onFailure { _problem.value = "Couldn't read version history" }.getOrDefault(emptyList())
        withContext(Dispatchers.Main) { onDone(list) }
    }

    fun previewVersion(path: String, version: NoteHistory.Version, onDone: (String) -> Unit) = viewModelScope.launch(queue) {
        runCatching { synchronized(vaultLock) { NoteFile.read(vault.history.read(path, version)).first } }
            .onSuccess { withContext(Dispatchers.Main) { onDone(it) } }
            .onFailure { _problem.value = "Couldn't read that version" }
    }

    fun restoreVersion(path: String, version: NoteHistory.Version, onDone: (Note) -> Unit) =
        act(onDone) { vault.history.restore(path, version) }

    fun refresh() = act<Unit> { }

    fun create(folder: String, name: String, body: String = "", onDone: (Note) -> Unit) = act(onDone) { vault.create(folder, name, body) }

    /** Today's note, made if it isn't there yet. */
    fun today(onDone: (Note) -> Unit) = act(onDone) {
        val name = LocalDate.now().toString()
        runCatching { vault.read("$DAILY/$name.md") }.getOrElse { vault.create(DAILY, name, "") }
    }

    /** Opens the note a link means, writing it first if it doesn't exist yet. */
    fun openOrCreate(target: String, fromFolder: String, onDone: (Note) -> Unit) = act(onDone) {
        val path = Links.resolve(target, vault.notes().map { it.path })
        if (path != null) vault.read(path)
        else {
            val clean = target.substringBefore('#').trim()
            val folder = if ("/" in clean) clean.substringBeforeLast('/') else fromFolder
            vault.create(folder, clean.substringAfterLast('/'))
        }
    }

    /** A note deleted or moved meanwhile stays that way: it isn't brought back by a late save. */
    fun save(path: String, body: String, pinned: Boolean, colour: String?, onDone: (Note) -> Unit = {}) =
        act<Note?>({ it?.let(onDone) }) { if (File(root, path).exists()) vault.save(path, body, pinned, colour) else null }

    /** For when the screen is going away: saved before this returns, so nothing typed is lost. */
    fun saveNow(path: String, body: String, pinned: Boolean, colour: String?) {
        runBlocking(queue) {
            synchronized(vaultLock) {
                if (File(root, path).exists()) runCatching { vault.save(path, body, pinned, colour) }
                runCatching { reload() }
            }
        }
    }

    fun createSketch(folder: String, name: String, onDone: (String) -> Unit) = act(onDone) { vault.createSketch(folder, name) }

    /** Saved in the queue, unless the drawing's gone. */
    fun saveSketch(path: String, sketch: Sketch) = act<Unit> { if (vault.exists(path)) vault.saveSketch(path, sketch) }

    fun saveSketchNow(path: String, sketch: Sketch) {
        runBlocking(queue) {
            synchronized(vaultLock) {
                if (vault.exists(path)) runCatching { vault.saveSketch(path, sketch) }
                runCatching { reload() }
            }
        }
    }

    fun createPainting(folder: String, name: String, width: Int = 2048, height: Int = 1536, onDone: (String) -> Unit) = act(onDone) {
        val blank = io.github.shoalapps.squill.paint.Painting.blank(width, height)
        vault.createFile(folder, name, "ora") { out -> io.github.shoalapps.squill.paint.OraFile.save(blank.run { io.github.shoalapps.squill.paint.PaintingCopy(snapshot(true)) }, out) }
    }

    /** Paintings save in the background, in order, from a copy taken when they were asked for. */
    fun savePainting(path: String, copy: io.github.shoalapps.squill.paint.PaintingCopy) = act<Unit> {
        if (vault.exists(path)) vault.writeFile(path) { io.github.shoalapps.squill.paint.OraFile.save(copy, it) }
    }

    fun moveFile(path: String, folder: String, name: String, onDone: (String) -> Unit = {}) = act(onDone) { vault.moveFile(path, folder, name) }

    fun createDeck(folder: String, name: String, deck: Deck = Deck(), onDone: (String) -> Unit) = act(onDone) { vault.createDeck(folder, name, deck) }
    fun saveDeck(path: String, deck: Deck) = act<Unit> { if (vault.exists(path)) vault.saveDeck(path, deck) }
    fun saveDeckNow(path: String, deck: Deck) {
        runBlocking(queue) {
            synchronized(vaultLock) {
                if (vault.exists(path)) runCatching { vault.saveDeck(path, deck) }
                runCatching { reload() }
            }
        }
    }
    fun moveDeck(path: String, folder: String, name: String, onDone: (String) -> Unit = {}) = act(onDone) { vault.moveDeck(path, folder, name) }

    fun moveSketch(path: String, folder: String, name: String, onDone: (String) -> Unit = {}) = act(onDone) { vault.moveSketch(path, folder, name) }

    fun move(path: String, folder: String, name: String, onDone: (Note) -> Unit = {}) = act(onDone) { vault.move(path, folder, name) }
    fun trash(path: String, onDone: () -> Unit = {}) = act<Unit>({ onDone() }) { vault.trash(path) }
    fun restore(path: String) = act { vault.restore(path) }
    fun deleteForever(path: String) = act { vault.deleteForever(path) }
    fun emptyTrash() = act { vault.emptyTrash() }
    fun createFolder(parent: String, name: String) = act { vault.createFolder(parent, name) }
    fun renameFolder(path: String, name: String, onDone: (String) -> Unit = {}) = act(onDone) { vault.renameFolder(path, name) }
    fun trashFolder(path: String) = act { vault.trashFolder(path) }

    /** A Word file becomes a note and a PowerPoint file a presentation, beside what's here. The original isn't touched. */
    fun importOffice(context: android.content.Context, uri: android.net.Uri, folder: String, onDone: (Result<String>) -> Unit) {
        viewModelScope.launch(queue) {
            val result = runCatching {
                val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                } ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Imported"
                // Some apps hand over a file with no useful name, only a type.
                val mime = context.contentResolver.getType(uri).orEmpty()
                val typed = if ('.' in name) name else when {
                    mime.startsWith("image/") -> "$name.png"
                    mime.contains("wordprocessingml") -> "$name.docx"
                    mime.contains("presentationml") -> "$name.pptx"
                    mime.contains("markdown") || mime == "text/plain" -> "$name.md"
                    else -> name
                }
                val stem = typed.substringBeforeLast('.').ifBlank { "Imported" }
                val open = { context.contentResolver.openInputStream(uri) ?: throw OfficeException("That file couldn't be opened") }
                when {
                    typed.endsWith(".ora", ignoreCase = true) -> {
                        val bytes = open().use { it.readBytes() }
                        io.github.shoalapps.squill.paint.OraFile.load(bytes)
                        vault.createFile(folder, stem, "ora") { it.write(bytes) }
                    }
                    typed.endsWith(".kra", ignoreCase = true) || listOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp", ".heic", ".heif", ".avif").any { typed.endsWith(it, ignoreCase = true) } -> {
                        val painting = io.github.shoalapps.squill.paint.OraFile.fromPicture(open().use { it.readBytes() })
                        vault.createFile(folder, stem, "ora") { io.github.shoalapps.squill.paint.OraFile.save(painting.run { io.github.shoalapps.squill.paint.PaintingCopy(snapshot(true)) }, it) }
                    }
                    typed.endsWith(".md", ignoreCase = true) || typed.endsWith(".txt", ignoreCase = true) -> vault.create(folder, stem, open().use { it.readBytes().toString(Charsets.UTF_8) }).path
                    typed.endsWith(".pptx", ignoreCase = true) -> vault.createDeck(folder, stem, open().use { Pptx.import(it) })
                    typed.endsWith(".docx", ignoreCase = true) -> vault.create(folder, stem, open().use { Docx.import(it) }).path
                    else -> throw OfficeException("Squill can open pictures, .ora, .kra, .md, .docx and .pptx files")
                }
            }
            runCatching { reload() }
            withContext(Dispatchers.Main) { onDone(result) }
        }
    }

    /** Sync starts a little after the last change, not after every keystroke's save. */
    private fun syncSoon() {
        if (_peerList.value.isEmpty()) return
        pendingSync?.cancel()
        pendingSync = viewModelScope.launch {
            kotlinx.coroutines.delay(30_000)
            sync(manual = false)
        }
    }

    /** Syncs with every paired computer it can reach. When it's automatic, being out of range isn't worth mentioning. */
    fun sync(manual: Boolean = true) {
        if (syncing || _peerList.value.isEmpty() || _needsUnlock.value) return
        syncing = true
        if (manual) _syncStatus.value = "Syncing…"
        viewModelScope.launch(Dispatchers.IO) {
            val remindersBefore = Store.load(getApplication())
            val skip = setOfNotNull(openFile)
            var moved = 0
            var failure: String? = null
            peers.peers().forEach { peer ->
                val result = runCatching { syncOne(peer, skip) }
                result.onSuccess { moved += it.total }
                result.onFailure { failure = it.message ?: "Couldn't reach ${peer.name}" }
            }
            Store.received(getApplication(), remindersBefore)
            syncing = false
            withContext(queue) { synchronized(vaultLock) { runCatching { reload() } } }
            _peerList.value = peers.peers()
            if (manual || moved > 0) _syncStatus.value = failure?.let { "Couldn't reach your computer. Is Squill open on it, on the same network?" }
                ?: if (moved == 0) "Up to date" else if (moved == 1) "Synced 1 file" else "Synced $moved files"
        }
    }

    private fun syncOne(peer: Peer, skip: Set<String>): SyncProtocol.Result = try {
        SyncProtocol.sync(vault, peers, peer, peers.selfId, deviceName, skip, vaultLock, timeoutMillis = 2500, beforeReminders = { Store.arriving(getApplication(), it) }, reminders = Store.repository(getApplication()))
    } catch (e: java.io.IOException) {
        // Addresses change: ask who's on the network before giving up.
        val found = SyncProtocol.discover().firstOrNull { it.id == peer.id } ?: throw e
        val moved = peer.copy(address = found.address, port = found.port)
        peers.save(moved)
        SyncProtocol.sync(vault, peers, moved, peers.selfId, deviceName, skip, vaultLock, timeoutMillis = 2500, beforeReminders = { Store.arriving(getApplication(), it) }, reminders = Store.repository(getApplication()))
    }

    /** Looks for computers running Squill on this network. */
    fun discover(onDone: (List<SyncProtocol.Found>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val found = runCatching { SyncProtocol.discover() }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) { onDone(found) }
        }
    }

    fun pair(address: String, port: Int, code: String, onDone: (Result<Peer>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { SyncProtocol.pair(address.trim(), port, code, peers.selfId, deviceName, peers) }
            _peerList.value = peers.peers()
            withContext(Dispatchers.Main) { onDone(result) }
            if (result.isSuccess) sync()
        }
    }

    fun forget(peer: Peer) { peers.remove(peer.id); _peerList.value = peers.peers() }

    /** Runs bulk file work (backup, import) in the queue, then reports the result. */
    fun <T> bulk(work: (Vault) -> T, onDone: (Result<T>) -> Unit) {
        viewModelScope.launch(queue) {
            val result = runCatching { work(vault) }
            runCatching { reload() }
            withContext(Dispatchers.Main) { onDone(result) }
        }
    }

    companion object {
        const val DAILY = "Daily"
    }
}

private const val WELCOME = """# Your notes, your files

Every note in Squill is an ordinary **Markdown** file in an ordinary folder, kept on this phone. Back them up from Settings and any Markdown app can open them.

## Try these

- [ ] Tick this box in reading view
- [ ] Press the pencil at the top to edit, and the eye to read again
- [ ] Pin this note, or give it a colour, from the menu
- [ ] Follow a link: [[How links work]]
- [ ] Open the Graph tab to see how your notes join up

## Formatting

You can write *italics*, **bold**, ~~crossed out~~ and `code`, and tag a note like this: #getting-started

> Quotes look like this.

| Type this | To get |
|---|---|
| `# Title` | A heading |
| `- item` | A list |
| `- [ ] item` | A checkbox |
"""

private const val LINKS_NOTE = """Type two square brackets to link to another note: [[Welcome to Squill]].

Linking to a note you haven't written yet is fine: [[An idea for later]] shows up in the graph, and following it makes the note.

Rename a note and every link to it is updated for you. Each note shows what links to it at the bottom.

#getting-started
"""
