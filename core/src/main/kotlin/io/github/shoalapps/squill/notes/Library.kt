package io.github.shoalapps.squill.notes

/** A note with what's been worked out from its text, so the screens don't have to keep re-reading it. */
@kotlinx.serialization.Serializable
data class NoteInfo(val note: Note, val links: List<String>, val tags: List<String>, val excerpt: String, val done: Int, val tasks: Int, val size: Long = 0)

/** A drawing, and when its file last changed. */
data class SketchInfo(val path: String, val modified: Long, val size: Long, val sketch: Sketch) {
    val title: String get() = path.substringAfterLast('/').removeSuffix(".svg")
    val folder: String get() = path.substringBeforeLast('/', "")
}

/** A presentation, and when its file last changed. */
data class DeckInfo(val path: String, val modified: Long, val size: Long, val deck: Deck) {
    val title: String get() = path.substringAfterLast('/').removeSuffix(".deck")
    val folder: String get() = path.substringBeforeLast('/', "")
}

/** A layered painting, with the small picture stored inside its file. */
class PaintingInfo(val path: String, val modified: Long, val size: Long, val thumbnail: ByteArray?) {
    val title: String get() = path.substringAfterLast('/').removeSuffix(".ora")
    val folder: String get() = path.substringBeforeLast('/', "")
}

data class Library(
    val notes: List<NoteInfo> = emptyList(),
    val folders: List<String> = emptyList(),
    val trash: List<Note> = emptyList(),
    val sketches: List<SketchInfo> = emptyList(),
    val decks: List<DeckInfo> = emptyList(),
    val paintings: List<PaintingInfo> = emptyList(),
) {
    val paths: List<String> get() = notes.map { it.note.path }
    val tags: List<String> by lazy { notes.flatMap { it.tags }.distinct().sortedBy { it.lowercase() } }
    val resolver: Links.Resolver by lazy { Links.Resolver(paths) }
    private val linkers: Map<String, List<NoteInfo>> by lazy {
        val found = HashMap<String, MutableList<NoteInfo>>()
        notes.forEach { info ->
            info.links.mapNotNull { resolver.resolve(it) }.distinct().forEach { to ->
                if (to != info.note.path) found.getOrPut(to) { mutableListOf() } += info
            }
        }
        found
    }

    fun backlinks(path: String): List<NoteInfo> = linkers[path].orEmpty().sortedBy { it.note.title.lowercase() }

    fun graph(showUnwritten: Boolean) = Graph.of(notes.associate { it.note.path to it.links }, showUnwritten)

    /** The drawing an embed means, nearest the top if two folders share the name. */
    fun sketch(name: String): SketchInfo? = sketches.filter {
        it.path.equals(name, ignoreCase = true) || it.path.substringAfterLast('/').equals(name.substringAfterLast('/'), ignoreCase = true)
    }.minByOrNull { it.path.count { c -> c == '/' } }
}

