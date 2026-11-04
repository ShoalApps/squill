@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.github.shoalapps.squill.notes

import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp

/** Styles the Markdown while you write it, without changing a single character. */
class MarkdownHighlight(private val accent: Color, private val muted: Color) : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        val text = asCharSequence().toString()
        if (text.length > MAX) return
        for (m in Regex("(?m)^(#{1,6})\\s.*$").findAll(text)) {
            val size = when (m.groupValues[1].length) { 1 -> 26.sp; 2 -> 22.sp; 3 -> 19.sp; else -> 17.sp }
            addStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = size), m.range.first, m.range.last + 1)
        }
        fun mark(regex: Regex, style: SpanStyle) {
            for (m in regex.findAll(text)) addStyle(style, m.range.first, m.range.last + 1)
        }
        mark(Regex("\\*\\*[^*\\n]+\\*\\*"), SpanStyle(fontWeight = FontWeight.Bold))
        mark(Regex("(?<![*\\w])\\*[^*\\s][^*\\n]*\\*(?!\\*)"), SpanStyle(fontStyle = FontStyle.Italic))
        mark(Regex("~~[^~\\n]+~~"), SpanStyle(textDecoration = TextDecoration.LineThrough))
        mark(Regex("`[^`\\n]+`"), SpanStyle(fontFamily = FontFamily.Monospace, color = accent))
        mark(Regex("\\[\\[[^\\]\\n]+]]"), SpanStyle(color = accent))
        mark(Regex("(?<![\\w#])#[\\p{L}][\\p{L}\\p{N}_/-]*"), SpanStyle(color = accent))
        mark(Regex("(?m)^\\s*(?:[-*+](?: \\[[ xX]])?|\\d+[.)]|>)(?=\\s)"), SpanStyle(color = muted))
        mark(Regex("(?ms)^```.*?^```\\s*$"), SpanStyle(fontFamily = FontFamily.Monospace))
    }

    companion object {
        private const val MAX = 60_000 // Beyond this, typing matters more than colours.

        @Composable
        fun remember(): MarkdownHighlight {
            val accent = MaterialTheme.colorScheme.primary
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            return androidx.compose.runtime.remember(accent, muted) { MarkdownHighlight(accent, muted) }
        }
    }
}
