package io.github.shoalapps.squill

import android.content.Context
import io.github.shoalapps.squill.notes.LockedNotes
import io.github.shoalapps.squill.notes.Vault
import java.io.File
import java.lang.ref.WeakReference

/** Shares the existing unlocked Vault, never a password or a second plaintext cache. */
object NoteWidgetAccess {
    val lock = Any()
    private var live = WeakReference<Vault>(null)
    var onEdit: (() -> Unit)? = null
    fun attach(vault: Vault) = synchronized(lock) { live = WeakReference(vault) }
    fun detach(vault: Vault) = synchronized(lock) {
        if (live.get() === vault) {
            live.clear()
            onEdit = null
        }
    }
    fun <T> withVault(context: Context, block: (Vault) -> T): T = synchronized(lock) {
        val root = Prefs.notesFolder(context)?.let(::File) ?: File(context.filesDir, "vault")
        val vault = live.get()?.takeIf { it.root == root } ?: Vault(root)
        block(vault)
    }
    fun locked(vault: Vault) = File(vault.root, LockedNotes.ALL_NOTES_KEY).exists() && vault.cipher == null
    fun allowed(path: String) = path.endsWith(".md", true) && path.split('/').none { it.startsWith(".") || it.isEmpty() } && '\\' !in path

    /** Caller holds the same lock as saving and sync. No read or write is attempted when locked. */
    fun flip(vault: Vault, path: String, index: Int, fingerprint: String): Boolean {
        if (locked(vault) || !allowed(path) || !vault.exists(path)) return false
        return vault.flipWidgetTask(path, index, fingerprint)
    }
}
