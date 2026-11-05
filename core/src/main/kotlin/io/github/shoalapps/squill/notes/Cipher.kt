package io.github.shoalapps.squill.notes

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class WrongPassword : Exception("That password isn't right")

/** Seals data with AES-256-GCM. Anything without the mark is treated as plain, so a half-finished conversion still reads. */
class NoteCipher(key: ByteArray) {
    private val spec = SecretKeySpec(key, "AES")

    fun encode(plain: ByteArray): ByteArray {
        val nonce = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, spec, GCMParameterSpec(128, nonce))
        return MAGIC + nonce + cipher.doFinal(plain)
    }

    fun decode(data: ByteArray): ByteArray {
        if (!isSealed(data)) return data
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, spec, GCMParameterSpec(128, data, MAGIC.size, 12))
        return try { cipher.doFinal(data, MAGIC.size + 12, data.size - MAGIC.size - 12) } catch (e: AEADBadTagException) { throw WrongPassword() }
    }

    companion object {
        private val MAGIC = "SQENC1".toByteArray()
        internal val random = SecureRandom()

        fun isSealed(data: ByteArray) = data.size >= MAGIC.size + 12 + 16 && (MAGIC.indices).all { data[it] == MAGIC[it] }
    }
}

/**
 * A random key, kept in a file sealed with one made from a password. The password itself is never
 * stored, so a lost password can't be recovered by anyone: that's the point of it.
 */
object PasswordKey {
    private const val ITERATIONS = 310_000
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Stored(val salt: String, val iterations: Int, val wrapped: String)

    private fun derive(password: String, salt: ByteArray, iterations: Int): NoteCipher {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return NoteCipher(key)
    }

    private fun write(file: File, password: String, master: ByteArray) {
        val salt = ByteArray(16).also { NoteCipher.random.nextBytes(it) }
        val wrapped = derive(password, salt, ITERATIONS).encode(master)
        val b = Base64.getEncoder()
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(Stored.serializer(), Stored(b.encodeToString(salt), ITERATIONS, b.encodeToString(wrapped))))
        if (!tmp.renameTo(file)) throw VaultException("Couldn't save the key")
    }

    fun create(file: File, password: String): NoteCipher {
        val master = ByteArray(32).also { NoteCipher.random.nextBytes(it) }
        write(file, password, master)
        return NoteCipher(master)
    }

    private fun master(file: File, password: String): ByteArray {
        val s = json.decodeFromString(Stored.serializer(), file.readText())
        val d = Base64.getDecoder()
        return derive(password, d.decode(s.salt), s.iterations).decode(d.decode(s.wrapped))
    }

    /** The key, or [WrongPassword]. */
    fun unlock(file: File, password: String) = NoteCipher(master(file, password))

    fun changePassword(file: File, old: String, new: String) = write(file, new, master(file, old))
}

/** A note in the locked vault: even its name is sealed. */
@Serializable
data class LockedNote(val id: String, val title: String, val body: String, val modified: Long)

/**
 * The locked vault, with its own password, separate from everything else. Notes here are never
 * shown in lists, search or the graph, and aren't synced. They do go into backups, still sealed.
 */
class LockedNotes(root: File) {
    private val dir = File(root, DIR)
    private val keyFile = File(dir, "key.json")
    private val json = Json { ignoreUnknownKeys = true }
    private var cipher: NoteCipher? = null

    val isSetUp: Boolean get() = keyFile.exists()
    val isOpen: Boolean get() = cipher != null

    fun setUp(password: String) {
        if (isSetUp) throw VaultException("There's already a locked vault")
        cipher = PasswordKey.create(keyFile, password)
    }

    fun unlock(password: String) { cipher = PasswordKey.unlock(keyFile, password) }

    /** Forgets the key. Nothing here can be read until the password is given again. */
    fun lock() { cipher = null }

    fun changePassword(old: String, new: String) = PasswordKey.changePassword(keyFile, old, new)

    private fun key() = cipher ?: throw VaultException("The vault is locked")

    fun notes(): List<LockedNote> {
        val c = key()
        return dir.listFiles { f -> f.name.endsWith(".enc") }.orEmpty().mapNotNull { f ->
            runCatching { json.decodeFromString(LockedNote.serializer(), c.decode(f.readBytes()).toString(Charsets.UTF_8)) }.getOrNull()
        }.sortedByDescending { it.modified }
    }

    fun save(title: String, body: String, id: String = newId(), modified: Long = System.currentTimeMillis()): LockedNote {
        val note = LockedNote(id, title.ifBlank { "Untitled" }, body, modified)
        val sealed = key().encode(json.encodeToString(LockedNote.serializer(), note).toByteArray())
        dir.mkdirs()
        val file = File(dir, safe(id) + ".enc")
        val tmp = File(dir, safe(id) + ".tmp")
        tmp.writeBytes(sealed)
        if (!tmp.renameTo(file)) throw VaultException("Couldn't save that note")
        return note
    }

    fun delete(id: String) { key(); File(dir, safe(id) + ".enc").delete() }

    private fun newId() = ByteArray(12).also { NoteCipher.random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private fun safe(id: String) = id.filter { it.isLetterOrDigit() }

    companion object {
        const val DIR = ".locked"
        const val ALL_NOTES_KEY = ".squill-key.json"
    }
}
