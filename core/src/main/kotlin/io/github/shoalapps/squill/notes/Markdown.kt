package io.github.shoalapps.squill.notes

/** A note's text split into the blocks it's shown as. [line] is where a block starts in the text. */
sealed interface Block {
    val line: Int

    data class Heading(val level: Int, val text: String, override val line: Int) : Block
    data class Paragraph(val text: String, override val line: Int) : Block
    data class Bullet(val indent: Int, val text: String, override val line: Int) : Block
    data class Numbered(val indent: Int, val number: Int, val text: String, override val line: Int) : Block
    data class Task(val indent: Int, val done: Boolean, val text: String, override val line: Int) : Block
    data class Quote(val text: String, override val line: Int) : Block
    data class Code(val language: String, val text: String, override val line: Int) : Block
    data class Table(val header: List<String>, val rows: List<List<String>>, override val line: Int) : Block
    data class Rule(override val line: Int) : Block
    /** A drawing shown in the note: ![[Name.svg]] on a line of its own. */
    data class Embed(val target: String, override val line: Int) : Block
}

/** A run of text with one set of styles. */
data class Inline(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    /** A web address this run links to. */
    val url: String? = null,
    /** The note this run links to, as written inside the [[brackets]]. */
    val note: String? = null,
    /** The tag this run is, without its #. */
    val tag: String? = null,
)

object Markdown {
    private val heading = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val task = Regex("^(\\s*)[-*+]\\s+\\[([ xX])]\\s?(.*)$")
    private val bullet = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val numbered = Regex("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$")
    private val rule = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    private val embed = Regex("^\\s*!\\[\\[([^\\]|]+\\.svg)(?:\\|[^\\]]*)?]]\\s*$", RegexOption.IGNORE_CASE)
    private val tableDivider = Regex("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$")

    fun parse(text: String): List<Block> {
        val lines = text.split('\n')
        val blocks = mutableListOf<Block>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trimEnd('\r')
            val trimmed = line.trimStart()
            when {
                line.isBlank() -> i++
                trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                    val fence = trimmed.take(3)
                    val start = i
                    val body = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith(fence)) body += lines[i++].trimEnd('\r')
                    i++ // The closing fence; an unclosed block runs to the end of the note.
                    blocks += Block.Code(trimmed.drop(3).trim(), body.joinToString("\n"), start)
                }
                embed.matches(line) -> blocks += Block.Embed(embed.find(line)!!.groupValues[1].trim(), i++)
                heading.matches(line) -> {
                    val m = heading.find(line)!!
                    blocks += Block.Heading(m.groupValues[1].length, m.groupValues[2], i++)
                }
                rule.matches(line) -> blocks += Block.Rule(i++)
                task.matches(line) -> {
                    val m = task.find(line)!!
                    blocks += Block.Task(indentOf(m.groupValues[1]), m.groupValues[2] != " ", m.groupValues[3], i++)
                }
                bullet.matches(line) -> {
                    val m = bullet.find(line)!!
                    blocks += Block.Bullet(indentOf(m.groupValues[1]), m.groupValues[2], i++)
                }
                numbered.matches(line) -> {
                    val m = numbered.find(line)!!
                    blocks += Block.Numbered(indentOf(m.groupValues[1]), m.groupValues[2].toInt(), m.groupValues[3], i++)
                }
                trimmed.startsWith(">") -> {
                    val start = i
                    val body = mutableListOf<String>()
                    while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                        body += lines[i++].trimStart().removePrefix(">").removePrefix(" ").trimEnd('\r')
                    }
                    blocks += Block.Quote(body.joinToString("\n"), start)
                }
                line.contains('|') && i + 1 < lines.size && tableDivider.matches(lines[i + 1].trimEnd('\r')) && lines[i + 1].contains('-') -> {
                    val start = i
                    val header = cells(line)
                    i += 2
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) rows += cells(lines[i++].trimEnd('\r'))
                    blocks += Block.Table(header, rows, start)
                }
                else -> {
                    val start = i
                    val body = mutableListOf<String>()
                    while (i < lines.size && startsParagraph(lines, i, body.isEmpty())) body += lines[i++].trim()
                    blocks += Block.Paragraph(body.joinToString(" "), start)
                }
            }
        }
        return blocks
    }

    private fun startsParagraph(lines: List<String>, i: Int, first: Boolean): Boolean {
        val line = lines[i].trimEnd('\r')
        if (first) return true
        val trimmed = line.trimStart()
        return !(line.isBlank() || trimmed.startsWith("```") || trimmed.startsWith("~~~") || trimmed.startsWith(">") ||
            embed.matches(line) || heading.matches(line) || rule.matches(line) || bullet.matches(line) || task.matches(line) || numbered.matches(line))
    }

    private fun indentOf(space: String) = space.replace("\t", "    ").length / 2

    private fun cells(line: String) = line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

    /** Splits a line of text into styled runs. Anything that doesn't close stays as plain text. */
    fun inlines(text: String): List<Inline> {
        val out = mutableListOf<Inline>()
        scan(text, Inline(""), out)
        return out
    }

    private fun scan(s: String, style: Inline, out: MutableList<Inline>) {
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) { add(out, style.copy(text = plain.toString())); plain.clear() }
        }
        fun wordStart(i: Int) = i == 0 || !s[i - 1].isLetterOrDigit()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length && !s[i + 1].isLetterOrDigit()) { plain.append(s[i + 1]); i += 2; continue }
            if (c == '`') {
                val end = s.indexOf('`', i + 1)
                if (end > i) { flush(); add(out, style.copy(text = s.substring(i + 1, end), code = true)); i = end + 1; continue }
            }
            if (s.startsWith("[[", i)) {
                val end = s.indexOf("]]", i + 2)
                if (end > i + 2) {
                    val inner = s.substring(i + 2, end)
                    flush()
                    val target = inner.substringBefore('|').trim()
                    add(out, style.copy(text = inner.substringAfter('|', target).trim(), note = target))
                    i = end + 2
                    continue
                }
            }
            if (c == '[') {
                val close = s.indexOf(']', i + 1)
                if (close > i && close + 1 < s.length && s[close + 1] == '(') {
                    val end = s.indexOf(')', close + 2)
                    if (end > close) {
                        flush()
                        val target = s.substring(close + 2, end).trim()
                        val link = if (target.endsWith(".md", ignoreCase = true) && "://" !in target) style.copy(note = target.removeSuffix(".md").removeSuffix(".MD"))
                        else style.copy(url = target)
                        scan(s.substring(i + 1, close), link, out)
                        i = end + 1
                        continue
                    }
                }
            }
            if (s.startsWith("***", i)) {
                val end = s.indexOf("***", i + 3)
                if (end > i + 3) {
                    flush()
                    scan(s.substring(i + 3, end), style.copy(bold = true, italic = true), out)
                    i = end + 3
                    continue
                }
            }
            val pair = listOf("**", "__", "~~").firstOrNull { s.startsWith(it, i) }
            if (pair != null && (pair != "__" || wordStart(i))) {
                val end = s.indexOf(pair, i + 2)
                if (end > i + 2) {
                    flush()
                    scan(s.substring(i + 2, end), if (pair == "~~") style.copy(strike = true) else style.copy(bold = true), out)
                    i = end + 2
                    continue
                }
            }
            if ((c == '*' || (c == '_' && wordStart(i))) && i + 1 < s.length && !s[i + 1].isWhitespace()) {
                val end = s.indexOf(c, i + 1)
                if (end > i + 1 && !s[end - 1].isWhitespace() && (c == '*' || end + 1 == s.length || !s[end + 1].isLetterOrDigit())) {
                    flush()
                    scan(s.substring(i + 1, end), style.copy(italic = true), out)
                    i = end + 1
                    continue
                }
            }
            if (c == '#' && wordStart(i) && i + 1 < s.length && s[i + 1].isLetter()) {
                var end = i + 1
                while (end < s.length && (s[end].isLetterOrDigit() || s[end] in "_-/")) end++
                flush()
                add(out, style.copy(text = s.substring(i, end), tag = s.substring(i + 1, end)))
                i = end
                continue
            }
            if ((s.startsWith("https://", i) || s.startsWith("http://", i)) && wordStart(i)) {
                var end = i
                while (end < s.length && !s[end].isWhitespace()) end++
                while (end > i && s[end - 1] in ".,;:!?)'\"") end--
                flush()
                add(out, style.copy(text = s.substring(i, end), url = s.substring(i, end)))
                i = end
                continue
            }
            plain.append(c)
            i++
        }
        flush()
    }

    private fun add(out: MutableList<Inline>, run: Inline) {
        if (run.text.isNotEmpty()) out += run
    }

    /** One line of plain text for a card or a search result, without the Markdown marks. */
    fun excerpt(text: String, limit: Int = 240): String =
        parse(text).asSequence().filter { it !is Block.Task }.mapNotNull { b ->
            when (b) {
                is Block.Heading -> b.text
                is Block.Paragraph -> b.text
                is Block.Bullet -> b.text
                is Block.Numbered -> b.text
                is Block.Quote -> b.text
                is Block.Code -> b.text
                else -> null
            }
        }.map { line -> inlines(line).joinToString("") { it.text } }.joinToString("\n").take(limit)

    /** How many of a note's tasks are ticked, and how many there are. */
    fun taskCount(text: String): Pair<Int, Int> {
        val tasks = parse(text).filterIsInstance<Block.Task>()
        return tasks.count { it.done } to tasks.size
    }

    /** The text with the task on [line] ticked or unticked. Any other line is left exactly as it was. */
    fun toggleTask(text: String, line: Int): String {
        val lines = text.split('\n').toMutableList()
        val current = lines.getOrNull(line) ?: return text
        val m = task.find(current.trimEnd('\r')) ?: return text
        val mark = m.groups[2]!!.range.first
        lines[line] = current.replaceRange(mark, mark + 1, if (m.groupValues[2] == " ") "x" else " ")
        return lines.joinToString("\n")
    }
}

/** Text and a selection, for the formatting buttons. */
data class Edit(val text: String, val start: Int, val end: Int)

object Formatting {
    /** Puts marks around the selection, or takes them off if they're already there. */
    fun wrap(e: Edit, before: String, after: String = before): Edit {
        val s = e.text.substring(e.start, e.end)
        val hasMarks = e.start >= before.length && e.end + after.length <= e.text.length &&
            e.text.startsWith(before, e.start - before.length) && e.text.startsWith(after, e.end)
        if (hasMarks) {
            val text = e.text.removeRange(e.end, e.end + after.length).removeRange(e.start - before.length, e.start)
            return Edit(text, e.start - before.length, e.end - before.length)
        }
        return Edit(e.text.replaceRange(e.start, e.end, before + s + after), e.start + before.length, e.end + before.length)
    }

    /** Turns the selected lines into [prefix] lines, or back again. Other list marks are swapped, not stacked. */
    fun linePrefix(e: Edit, prefix: String): Edit {
        val first = e.text.lastIndexOf('\n', (e.start - 1).coerceAtLeast(0)).let { if (e.start == 0 || it < 0) 0 else it + 1 }
        val last = e.text.indexOf('\n', e.end).let { if (it < 0) e.text.length else it }
        val lines = e.text.substring(first, last).split('\n')
        val removing = lines.all { it.startsWith(prefix) }
        val changed = lines.joinToString("\n") { line ->
            if (removing) line.removePrefix(prefix) else prefix + linePrefixMark.replaceFirst(line, "")
        }
        val text = e.text.replaceRange(first, last, changed)
        val moved = text.length - e.text.length
        return if (e.start == e.end) {
            val cursor = (e.start + moved).coerceIn(first, first + changed.length)
            Edit(text, cursor, cursor)
        } else Edit(text, first, first + changed.length)
    }

    private val linePrefixMark = Regex("^(#{1,6}\\s+|[-*+]\\s+\\[[ xX]]\\s+|[-*+]\\s+|\\d+[.)]\\s+|>\\s?)")

    /**
     * What the user is typing after an unclosed [[ just before the cursor, or null if they aren't.
     * The start of what they've typed is [first]; replace from there to the cursor with a name.
     */
    fun openLink(text: String, cursor: Int): OpenLink? {
        val open = text.lastIndexOf("[[", (cursor - 1).coerceAtLeast(0))
        if (open < 0 || open + 2 > cursor) return null
        val typed = text.substring(open + 2, cursor)
        if ('\n' in typed || "]]" in typed || '|' in typed) return null
        return OpenLink(open + 2, typed)
    }

    data class OpenLink(val first: Int, val typed: String)

    sealed interface ListEnter {
        /** Put [mark] at the start of the new line. */
        data class Continue(val mark: String) : ListEnter
        /** The list item was empty: end the list, from [lineStart] to the cursor. */
        data class End(val lineStart: Int) : ListEnter
    }

    private val listLine = Regex("^(\\s*)([-*+] \\[[ xX]] |[-*+] |(\\d+)([.)]) )(.*)$")

    /** What pressing Enter at [cursor] in [text] should do to a list, or null if it isn't on one. */
    fun continueList(text: String, cursor: Int): ListEnter? {
        val start = if (cursor == 0) 0 else text.lastIndexOf('\n', cursor - 1) + 1
        val line = text.substring(start, cursor)
        val m = listLine.find(line) ?: return null
        if (m.groupValues[5].isBlank() && text.getOrNull(cursor).let { it == null || it == '\n' }) return ListEnter.End(start)
        val mark = when {
            m.groupValues[3].isNotEmpty() -> "${m.groupValues[3].toLong() + 1}${m.groupValues[4]} "
            "[" in m.groupValues[2] -> m.groupValues[2].take(2) + "[ ] "
            else -> m.groupValues[2]
        }
        return ListEnter.Continue(m.groupValues[1] + mark)
    }
}
