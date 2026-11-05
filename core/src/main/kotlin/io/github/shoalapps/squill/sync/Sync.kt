package io.github.shoalapps.squill.sync

import io.github.shoalapps.squill.notes.Vault
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/** One file as one device has it. */
@Serializable
data class FileState(val path: String, val hash: String, val size: Long, val modified: Long)

sealed interface SyncAction {
    /** Copy the other device's version here. */
    data class Pull(val path: String) : SyncAction
    /** Copy this device's version there. */
    data class Push(val path: String) : SyncAction
    /** The other device deleted it and this copy hadn't changed: to the trash here. */
    data class TrashHere(val path: String) : SyncAction
    /** This device deleted it and that copy hadn't changed: to the trash there. */
    data class TrashThere(val path: String) : SyncAction
    /** Both changed it. The newer keeps the name; the older becomes a copy on both devices. Nothing is lost. */
    data class Conflict(val path: String, val hereIsNewer: Boolean) : SyncAction
}

object SyncPlan {
    /**
     * Compares both devices against how they last matched. With no history, nothing is ever
     * deleted: a first sync only adds. If a file was deleted on one side and changed on the other,
     * the changed copy wins.
     */
    fun plan(
        here: Map<String, FileState>, there: Map<String, FileState>, base: Map<String, String>,
        skip: Set<String> = emptySet(),
    ): List<SyncAction> {
        val out = mutableListOf<SyncAction>()
        (here.keys + there.keys).sorted().forEach { path ->
            if (path in skip) return@forEach
            val h = here[path]; val t = there[path]; val b = base[path]
            when {
                h != null && t != null -> if (h.hash != t.hash) when {
                    b == h.hash -> out += SyncAction.Pull(path)
                    b == t.hash -> out += SyncAction.Push(path)
                    else -> out += SyncAction.Conflict(path, h.modified >= t.modified)
                }
                h != null -> out += if (b != null && b == h.hash) SyncAction.TrashHere(path) else SyncAction.Push(path)
                t != null -> out += if (b != null && b == t.hash) SyncAction.TrashThere(path) else SyncAction.Pull(path)
            }
        }
        return out
    }

    /** "Boats (from Laptop).md", numbered if that's taken too. */
    fun conflictName(path: String, device: String, taken: (String) -> Boolean): String {
        val ext = path.substringAfterLast('.', "")
        val stem = path.removeSuffix(".$ext")
        val who = device.replace(Regex("[\\\\/:*?\"<>|#^\\[\\]\\n\\r\\t]"), " ").trim().ifEmpty { "other device" }
        var candidate = "$stem (from $who).$ext"
        var n = 2
        while (taken(candidate)) candidate = "$stem (from $who $n).$ext"
            .also { n++ }
        return candidate
    }
}

fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** What's in a vault, with hashes remembered so that only changed files get re-read. */
class Manifest(private val vault: Vault) {
    private val json = Json { ignoreUnknownKeys = true }
    private val store = File(vault.root, ".sync/hashes.json")
    private val serializer = ListSerializer(FileState.serializer())

    fun scan(): Map<String, FileState> {
        val known = runCatching { json.decodeFromString(serializer, store.readText()) }.getOrDefault(emptyList()).associateBy { it.path }
        val out = HashMap<String, FileState>()
        vault.stats().forEach { s ->
            val k = known[s.path]
            out[s.path] = if (k != null && k.modified == s.modified && k.size == s.size) k
            else FileState(s.path, if (vault.cipher != null) sha256(vault.readBytes(s.path)) else sha256(vault.file(s.path)), s.size, s.modified)
        }
        store.parentFile?.mkdirs()
        runCatching { store.writeText(json.encodeToString(serializer, out.values.toList())) }
        return out
    }
}

@Serializable
data class Peer(val id: String, val name: String, val secret: String, val address: String = "", val port: Int = SyncProtocol.PORT, val lastSync: Long = 0)

/** Devices this one is paired with, and how each last matched. Kept hidden inside the vault's own folder, never copied by sync. */
class PeerStore(root: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val dir = File(root, ".sync").apply { mkdirs() }
    private val file = File(dir, "peers.json")
    private val baseSerializer = MapSerializer(String.serializer(), String.serializer())

    /** This device's permanent id, made the first time it's needed. */
    val selfId: String by lazy {
        val f = File(dir, "id")
        if (f.exists()) f.readText().trim() else SyncCrypto.randomToken(16).also { f.writeText(it) }
    }

    @Synchronized fun peers(): List<Peer> = runCatching { json.decodeFromString(ListSerializer(Peer.serializer()), file.readText()) }.getOrDefault(emptyList())

    @Synchronized fun save(peer: Peer) = write(peers().filterNot { it.id == peer.id } + peer)

    @Synchronized fun remove(id: String) {
        write(peers().filterNot { it.id == id })
        baseFile(id).delete()
    }

    private fun write(list: List<Peer>) {
        val tmp = File(dir, "peers.json.tmp")
        tmp.writeText(json.encodeToString(ListSerializer(Peer.serializer()), list))
        tmp.renameTo(file)
        // Holds the keys to this device, so only this app may read it.
        file.setReadable(false, false); file.setReadable(true, true)
    }

    private fun baseFile(id: String) = File(dir, "base-" + id.filter { it.isLetterOrDigit() } + ".json")

    fun base(id: String): Map<String, String> = runCatching { json.decodeFromString(baseSerializer, baseFile(id).readText()) }.getOrDefault(emptyMap())

    fun saveBase(id: String, base: Map<String, String>) {
        val tmp = File(dir, "base.tmp-" + id.filter { it.isLetterOrDigit() })
        tmp.writeText(json.encodeToString(baseSerializer, base))
        tmp.renameTo(baseFile(id))
    }
}
