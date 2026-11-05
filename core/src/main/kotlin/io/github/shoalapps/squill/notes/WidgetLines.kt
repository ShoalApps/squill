package io.github.shoalapps.squill.notes

import java.security.MessageDigest

/** Widget line numbers refer to the body (front matter excluded), including blank lines. */
object WidgetLines {
    data class Line(val index: Int, val text: String, val checked: Boolean?, val fingerprint: String)
    private val task = Regex("^[-*+] \\[([ x])](?: (.*))?$")

    fun fingerprint(line: String): String = MessageDigest.getInstance("SHA-256")
        .digest(line.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun parse(body: String): List<Line> {
        var fence: Char? = null
        var fenceLength = 0
        return body.split('\n').take(200).mapIndexed { index, raw ->
            val line = raw.removeSuffix("\r")
            val trimmed = line.trimStart()
            val marker = trimmed.firstOrNull()
            val run = trimmed.takeWhile { it == marker }.length
            val delimiter = (marker == '`' || marker == '~') && run >= 3
            val wasCode = fence != null
            if (delimiter) {
                if (fence == null) { fence = marker; fenceLength = run }
                else if (marker == fence && run >= fenceLength && trimmed.drop(run).isBlank()) fence = null
            }
            val match = if (!wasCode && !delimiter) task.matchEntire(line) else null
            val plain = if (delimiter) "" else if (wasCode) line else {
                val text = match?.groupValues?.get(2) ?: line.replace(Regex("^\\s*(?:#{1,6} +|> ?|[-*+] +|[0-9]+[.)] +)"), "")
                Markdown.inlines(text).joinToString("") { it.text }
            }
            Line(index, plain, match?.let { it.groupValues[1] == "x" }, fingerprint(line))
        }
    }

    /** Null means stale or ineligible. Replaces one ASCII character; all line endings survive. */
    fun flip(body: String, index: Int, expectedLine: String): String? {
        val raw = body.split('\n').getOrNull(index)?.removeSuffix("\r") ?: return null
        if (raw != expectedLine || parse(body).getOrNull(index)?.checked == null) return null
        val start = body.split('\n').take(index).sumOf { it.length + 1 }
        return body.replaceRange(start + 3, start + 4, if (raw[3] == ' ') "x" else " ")
    }

    fun flipFingerprint(body: String, index: Int, expected: String): String? {
        val raw = body.split('\n').getOrNull(index)?.removeSuffix("\r") ?: return null
        return if (fingerprint(raw) == expected) flip(body, index, raw) else null
    }
}
