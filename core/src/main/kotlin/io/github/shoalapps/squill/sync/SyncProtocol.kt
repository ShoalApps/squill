package io.github.shoalapps.squill.sync

import io.github.shoalapps.squill.notes.Vault
import io.github.shoalapps.squill.reminders.ReminderStore
import io.github.shoalapps.squill.reminders.ReminderEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SyncException(message: String) : Exception(message)

object SyncCrypto {
    private val random = SecureRandom()
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789" // No 0/O, 1/I/L: a person types this.

    fun randomBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }
    fun randomToken(bytes: Int) = randomBytes(bytes).joinToString("") { "%02x".format(it) }

    /** Sixteen letters shown on the desktop and typed on the phone: about 79 bits, used once. */
    fun pairingCode(): String = (1..16).map { ALPHABET[random.nextInt(ALPHABET.length)] }.chunked(4).joinToString("-") { it.joinToString("") }

    fun normalise(code: String) = code.uppercase().filter { it in ALPHABET }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach { mac.update(it) }
        return mac.doFinal()
    }
}

/**
 * Messages sealed with AES-GCM under keys only the two paired devices can derive. Each direction has
 * its own key and a counting nonce, so a message can't be altered, replayed, reordered or reflected.
 */
class SealedChannel(socket: Socket, secret: ByteArray, isClient: Boolean, greeting: ByteArray) : Closeable {
    private val socket = socket
    private val input = DataInputStream(socket.getInputStream().buffered())
    private val output = DataOutputStream(socket.getOutputStream().buffered())
    private val sendKey: SecretKeySpec
    private val receiveKey: SecretKeySpec
    private var sent = 0L
    private var received = 0L

    init {
        val mine = SyncCrypto.randomBytes(32)
        if (isClient) { output.write(greeting); output.writeInt(greeting.size) }
        output.write(mine)
        output.flush()
        val theirs = ByteArray(32)
        input.readFully(theirs)
        val clientNonce = if (isClient) mine else theirs
        val serverNonce = if (isClient) theirs else mine
        val c2s = SecretKeySpec(SyncCrypto.hmac(secret, "squill c2s".toByteArray(), clientNonce, serverNonce), "AES")
        val s2c = SecretKeySpec(SyncCrypto.hmac(secret, "squill s2c".toByteArray(), clientNonce, serverNonce), "AES")
        sendKey = if (isClient) c2s else s2c
        receiveKey = if (isClient) s2c else c2s
    }

    private fun nonce(counter: Long) = ByteBuffer.allocate(12).putInt(0).putLong(counter).array()

    @Synchronized fun send(message: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, sendKey, GCMParameterSpec(128, nonce(sent++)))
        val sealed = cipher.doFinal(message)
        output.writeInt(sealed.size)
        output.write(sealed)
        output.flush()
    }

    fun receive(): ByteArray {
        val size = input.readInt()
        if (size !in 16..SyncProtocol.MAX_FILE + 1024 * 1024) throw SyncException("That message is the wrong size")
        val sealed = ByteArray(size)
        input.readFully(sealed)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, receiveKey, GCMParameterSpec(128, nonce(received++)))
        return try { cipher.doFinal(sealed) } catch (e: AEADBadTagException) { throw SyncException("That device doesn't have the right key") }
    }

    override fun close() { runCatching { socket.close() } }
}

@Serializable
private data class Request(val op: String, val path: String = "", val data: Int = 0, val id: String = "", val name: String = "")

@Serializable
private data class Reply(val ok: Boolean = true, val error: String = "", val id: String = "", val name: String = "", val secret: String = "", val reminders: Boolean = false, val changed: Boolean = false)

object SyncProtocol {
    const val PORT = 47800
    const val DISCOVERY_PORT = 47801
    const val MAX_FILE = 256 * 1024 * 1024
    internal val MAGIC = "SQUILL1".toByteArray()
    private val PROBE = "SQUILL?".toByteArray()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    internal fun hello(kind: String, id: String): ByteArray = MAGIC + ("$kind $id").toByteArray()

    private fun SealedChannel.request(r: Request, body: ByteArray? = null): Reply {
        send(json.encodeToString(Request.serializer(), r).toByteArray())
        if (body != null) send(body)
        val reply = json.decodeFromString(Reply.serializer(), receive().toString(Charsets.UTF_8))
        if (!reply.ok) throw SyncException(reply.error.ifEmpty { "The other device said no" })
        return reply
    }

    private fun SealedChannel.reply(r: Reply = Reply()) = send(json.encodeToString(Reply.serializer(), r).toByteArray())

    private fun readGreeting(input: java.io.InputStream): String {
        // [MAGIC + kind + id], then its length, because the reader doesn't know it in advance.
        val buffer = ByteArrayOutputStream()
        val data = DataInputStream(input)
        while (buffer.size() < 256) {
            buffer.write(data.readUnsignedByte())
            val b = buffer.toByteArray()
            if (b.size >= 4 + MAGIC.size + 1) {
                val len = ByteBuffer.wrap(b, b.size - 4, 4).int
                if (len == b.size - 4) {
                    if (!b.copyOf(MAGIC.size).contentEquals(MAGIC)) throw SyncException("That isn't a Squill device")
                    return String(b, MAGIC.size, len - MAGIC.size)
                }
            }
        }
        throw SyncException("That isn't a Squill device")
    }

    /**
     * The desktop's side: listens for paired devices, and answers "is anyone there?" on the local
     * network with its name and port, and nothing more.
     */
    class Server(private val vault: Vault, private val peers: PeerStore, private val deviceName: String, private val port: Int = PORT, private val lock: Any = Any(), private val supportsReminders: Boolean = true) : Closeable {
        private val server = ServerSocket()
        private var probe: DatagramSocket? = null
        private var accepting: Thread? = null
        @Volatile private var pairing: String? = null
        @Volatile var onChange: () -> Unit = {}
        val boundPort: Int get() = server.localPort

        fun start() {
            server.reuseAddress = true
            server.bind(InetSocketAddress(port))
            accepting = Thread({ acceptLoop() }, "squill-sync").apply { isDaemon = true; start() }
            runCatching {
                val s = DatagramSocket(DISCOVERY_PORT)
                probe = s
                Thread({ probeLoop(s) }, "squill-discovery").apply { isDaemon = true }.start()
            }
        }

        /** Allows one new device to pair with this code, until it's used or replaced. */
        fun allowPairing(code: String?) { pairing = code?.let { SyncCrypto.normalise(it) } }

        private fun probeLoop(s: DatagramSocket) {
            val buffer = ByteArray(32)
            while (!s.isClosed) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    s.receive(packet)
                    if (packet.length == PROBE.size && packet.data.copyOf(PROBE.size).contentEquals(PROBE)) {
                        val answer = "SQUILL!$boundPort ${peers.selfId} $deviceName".toByteArray()
                        s.send(DatagramPacket(answer, answer.size, packet.address, packet.port))
                    }
                } catch (_: IOException) { }
            }
        }

        private fun acceptLoop() {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: SocketException) { return }
                // Closing doesn't stop a connection that was already on its way in.
                if (server.isClosed) { runCatching { socket.close() }; return }
                Thread({ runCatching { socket.use { handle(it) } } }, "squill-peer").apply { isDaemon = true }.start()
            }
        }

        private fun handle(socket: Socket) {
            socket.soTimeout = 60_000
            val greeting = readGreeting(socket.getInputStream())
            val kind = greeting.substringBefore(' ')
            val id = greeting.substringAfter(' ')
            if (kind == "PAIR") {
                val code = pairing ?: return
                SealedChannel(socket, code.toByteArray(), false, ByteArray(0)).use { ch ->
                    val r = json.decodeFromString(Request.serializer(), ch.receive().toString(Charsets.UTF_8))
                    val secret = SyncCrypto.randomToken(32)
                    peers.save(Peer(r.id, r.name, secret))
                    pairing = null // One use only.
                    ch.reply(Reply(id = peers.selfId, name = deviceName, secret = secret))
                    onChange()
                }
                return
            }
            val peer = peers.peers().firstOrNull { it.id == id } ?: return
            SealedChannel(socket, peer.secret.toByteArray(), false, ByteArray(0)).use { ch ->
                while (true) {
                    val bytes = try { ch.receive() } catch (_: java.io.EOFException) { return }
                    val r = json.decodeFromString(Request.serializer(), bytes.toString(Charsets.UTF_8))
                    val failure = runCatching { serve(ch, r, peer) }.exceptionOrNull()
                    if (failure != null) {
                        if (failure is IOException || failure is SyncException) return
                        ch.reply(Reply(ok = false, error = failure.message ?: "That didn't work"))
                    }
                    if (r.op == "DONE") return
                }
            }
        }

        private fun serve(ch: SealedChannel, r: Request, peer: Peer) {
            when (r.op) {
                "LIST" -> {
                    val list = synchronized(lock) { Manifest(vault).scan().values.toList() }
                    if (supportsReminders) ch.reply(Reply(reminders = true))
                    else ch.send("{\"ok\":true}".toByteArray())
                    ch.send(json.encodeToString(ListSerializer(FileState.serializer()), list).toByteArray())
                }
                "REMINDERS" -> {
                    val incoming = ch.receive()
                    if (!supportsReminders) { ch.reply(Reply(ok = false, error = "Unknown request")); return }
                    val (merged, changed) = synchronized(lock) {
                        val store = ReminderStore(vault)
                        val before = store.entries()
                        val merged = store.receive(ReminderStore.decode(incoming))
                        merged to (merged != before)
                    }
                    ch.reply(Reply(changed = changed))
                    ch.send(ReminderStore.encode(merged))
                    onChange()
                }
                "GET" -> {
                    val data = synchronized(lock) { checked(r.path); vault.readBytes(r.path) }
                    if (data.size > MAX_FILE) throw SyncException("That file is too big to sync")
                    ch.reply()
                    ch.send(data)
                }
                "PUT" -> {
                    val data = ch.receive()
                    synchronized(lock) { checked(r.path); vault.file(r.path).parentFile?.mkdirs(); vault.writeFile(r.path) { it.write(data) } }
                    ch.reply()
                    onChange()
                }
                "TRASH" -> {
                    synchronized(lock) { checked(r.path); if (vault.exists(r.path)) vault.trash(r.path) }
                    ch.reply()
                    onChange()
                }
                "DONE" -> {
                    val list = synchronized(lock) { Manifest(vault).scan() }
                    peers.saveBase(peer.id, list.mapValues { it.value.hash })
                    peers.save(peer.copy(lastSync = System.currentTimeMillis()))
                    ch.reply()
                    onChange()
                }
                else -> throw SyncException("Unknown request")
            }
        }

        override fun close() {
            runCatching { server.close() }
            runCatching { probe?.close() }
            runCatching { accepting?.join(2000) }
        }
    }

    /** Only the kinds of file sync handles, and never a hidden one such as this device's keys. */
    private fun checked(path: String) {
        val parts = path.split('/')
        if (parts.any { it.isEmpty() || it == ".." || it.startsWith(".") }) throw SyncException("That path isn't allowed")
        if (path.substringAfterLast('.', "").lowercase() !in Vault.KINDS) throw SyncException("That kind of file isn't synced")
    }

    data class Found(val address: String, val port: Int, val id: String, val name: String)

    /** Asks the local network who's there, briefly. */
    fun discover(timeoutMillis: Int = 1200): List<Found> {
        val found = LinkedHashMap<String, Found>()
        DatagramSocket().use { s ->
            s.broadcast = true
            s.soTimeout = timeoutMillis
            runCatching { s.send(DatagramPacket(PROBE, PROBE.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT)) }
            val buffer = ByteArray(512)
            val end = System.currentTimeMillis() + timeoutMillis
            while (System.currentTimeMillis() < end) {
                try {
                    val p = DatagramPacket(buffer, buffer.size)
                    s.receive(p)
                    val text = String(p.data, 0, p.length)
                    if (text.startsWith("SQUILL!")) {
                        val parts = text.removePrefix("SQUILL!").split(' ', limit = 3)
                        if (parts.size == 3) found[parts[1]] = Found(p.address.hostAddress ?: "", parts[0].toIntOrNull() ?: PORT, parts[1], parts[2])
                    }
                } catch (_: java.net.SocketTimeoutException) { break }
            }
        }
        return found.values.toList()
    }

    private fun connect(address: String, port: Int, timeoutMillis: Int): Socket =
        Socket().apply { soTimeout = 60_000; connect(InetSocketAddress(address, port), timeoutMillis) }

    /** The phone's side of pairing: proves it has the code, and receives a long-term key. */
    fun pair(address: String, port: Int, code: String, selfId: String, selfName: String, peers: PeerStore, timeoutMillis: Int = 5000): Peer {
        connect(address, port, timeoutMillis).use { socket ->
            SealedChannel(socket, SyncCrypto.normalise(code).toByteArray(), true, hello("PAIR", selfId)).use { ch ->
                val reply = try { ch.request(Request("PAIR", id = selfId, name = selfName)) } catch (_: IOException) { throw SyncException("That code wasn't accepted") }
                val peer = Peer(reply.id, reply.name, reply.secret, address, port)
                peers.save(peer)
                return peer
            }
        }
    }

    data class Result(val pulled: Int, val pushed: Int, val trashed: Int, val conflicts: Int, val reminders: Int = 0) {
        val changedHere get() = pulled + trashed + conflicts + reminders
        val total get() = pulled + pushed + trashed + conflicts + reminders
    }

    /**
     * Brings this device and a paired one into line. Nothing's marked as matched until everything
     * has gone through, so an interrupted sync simply carries on next time.
     */
    fun sync(vault: Vault, peers: PeerStore, peer: Peer, selfId: String, selfName: String, skip: Set<String> = emptySet(), lock: Any = Any(), timeoutMillis: Int = 5000, beforeReminders: (List<ReminderEntry>) -> Unit = {}, reminders: ReminderStore = ReminderStore(vault)): Result {
        connect(peer.address, peer.port, timeoutMillis).use { socket ->
            SealedChannel(socket, peer.secret.toByteArray(), true, hello("SYNC", selfId)).use { ch ->
                val capabilities = ch.request(Request("LIST"))
                val there = json.decodeFromString(ListSerializer(FileState.serializer()), ch.receive().toString(Charsets.UTF_8)).associateBy { it.path }
                // The server only lists safe names, but it's still another machine's word.
                there.keys.forEach(::checked)
                val here = synchronized(lock) { Manifest(vault).scan() }
                val base = peers.base(peer.id)
                val plan = SyncPlan.plan(here, there, base, skip)
                var pulled = 0; var pushed = 0; var trashed = 0; var conflicts = 0
                val known = (here.keys + there.keys).toMutableSet()
                fun get(path: String): ByteArray { ch.request(Request("GET", path)); return ch.receive() }
                fun put(path: String, data: ByteArray) { ch.request(Request("PUT", path), data) }
                fun write(path: String, data: ByteArray) = synchronized(lock) { vault.file(path).parentFile?.mkdirs(); vault.writeFile(path) { it.write(data) } }
                plan.forEach { a ->
                    when (a) {
                        is SyncAction.Pull -> { write(a.path, get(a.path)); pulled++ }
                        is SyncAction.Push -> { put(a.path, synchronized(lock) { vault.readBytes(a.path) }); pushed++ }
                        is SyncAction.TrashHere -> { synchronized(lock) { vault.trash(a.path) }; trashed++ }
                        is SyncAction.TrashThere -> { ch.request(Request("TRASH", a.path)); trashed++ }
                        is SyncAction.Conflict -> {
                            val theirs = get(a.path)
                            val mine = synchronized(lock) { vault.readBytes(a.path) }
                            val loserName = if (a.hereIsNewer) peer.name else selfName
                            val copy = SyncPlan.conflictName(a.path, loserName) { it in known }
                            known += copy
                            val older = if (a.hereIsNewer) theirs else mine
                            val newer = if (a.hereIsNewer) mine else theirs
                            write(copy, older); put(copy, older)
                            if (a.hereIsNewer) put(a.path, newer) else write(a.path, newer)
                            conflicts++
                        }
                    }
                }
                var reminderChanges = 0
                if (capabilities.reminders) {
                    val mine = synchronized(lock) { reminders.entries() }
                    val reply = ch.request(Request("REMINDERS"), ReminderStore.encode(mine))
                    val incoming = ReminderStore.decode(ch.receive())
                    val merged = synchronized(lock) { reminders.receive(incoming, beforeReminders) }
                    if (reply.changed || merged != mine) reminderChanges = 1
                }
                ch.request(Request("DONE"))
                val after = synchronized(lock) { Manifest(vault).scan() }
                val skipped = skip.mapNotNull { p -> base[p]?.let { p to it } }.toMap()
                peers.saveBase(peer.id, after.mapValues { it.value.hash }.filterKeys { it !in skip } + skipped)
                peers.save(peer.copy(lastSync = System.currentTimeMillis()))
                return Result(pulled, pushed, trashed, conflicts, reminderChanges)
            }
        }
    }
}
