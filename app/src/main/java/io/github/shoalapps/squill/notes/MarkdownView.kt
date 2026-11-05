package io.github.shoalapps.squill.notes

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

class MarkdownActions(
    val sketch: (String) -> Sketch? = { null },
    val painting: (String) -> ByteArray? = { null },
    val onSketch: (String) -> Unit = {},
    val onNote: (String) -> Unit,
    val onUrl: (String) -> Unit,
    val onTag: (String) -> Unit,
    val onTask: (Int) -> Unit,
)

/** A note in reading view. */
@Composable
fun MarkdownView(text: String, actions: MarkdownActions, known: (String) -> Boolean, modifier: Modifier = Modifier) {
    val blocks = remember(text) { Markdown.parse(text) }
    val type = MaterialTheme.typography
    val c = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is Block.Heading -> {
                    val style = when (block.level) {
                        1 -> type.headlineMedium
                        2 -> type.titleLarge
                        3 -> type.titleMedium
                        else -> type.titleSmall
                    }
                    Rich(block.text, style.copy(fontWeight = FontWeight.Bold), actions, known, Modifier.padding(top = if (block.level <= 2) 10.dp else 4.dp))
                }
                is Block.Paragraph -> Rich(block.text, type.bodyLarge, actions, known)
                is Block.Bullet -> Item(block.indent, "•", block.text, actions, known)
                is Block.Numbered -> Item(block.indent, "${block.number}.", block.text, actions, known)
                is Block.Task -> Row(Modifier.padding(start = 16.dp * block.indent), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(block.done, { actions.onTask(block.line) })
                    val style = if (block.done) type.bodyLarge.copy(textDecoration = TextDecoration.LineThrough, color = c.onSurfaceVariant) else type.bodyLarge
                    Rich(block.text, style, actions, known, Modifier.weight(1f))
                }
                is Block.Quote -> Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.width(4.dp).padding(vertical = 2.dp).background(c.primary, RoundedCornerShape(2.dp)))
                    Column(Modifier.background(c.surfaceContainerLow).weight(1f).padding(12.dp, 6.dp)) {
                        block.text.split('\n').forEach { Rich(it, type.bodyLarge.copy(fontStyle = FontStyle.Italic), actions, known) }
                    }
                }
                is Block.Code -> Text(
                    block.text,
                    style = type.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    softWrap = false,
                    modifier = Modifier.fillMaxWidth().background(c.surfaceContainerHigh, RoundedCornerShape(12.dp)).horizontalScroll(rememberScrollState()).padding(12.dp),
                )
                is Block.Table -> Table(block, actions, known)
                is Block.Rule -> HorizontalDivider(Modifier.padding(vertical = 8.dp))
                is Block.Embed -> {
                    val drawing = actions.sketch(block.target)
                    val bytes = if (drawing == null) actions.painting(block.target) else null
                    val image = remember(bytes) { bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() } }
                    if (image != null) Image(
                        bitmap = image,
                        contentDescription = block.target,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)
                            .aspectRatio(image.width.toFloat() / image.height, matchHeightConstraintsFirst = false)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    else if (drawing == null) Text("Missing drawing: ${block.target}", color = c.error, style = type.bodyMedium)
                    else SketchPicture(
                        drawing,
                        Modifier.fillMaxWidth().heightIn(max = 320.dp).aspectRatio(aspectOf(drawing), matchHeightConstraintsFirst = false)
                            .clip(RoundedCornerShape(12.dp)).clickable { actions.onSketch(block.target) }
                            .semantics { contentDescription = "Drawing: ${block.target.removeSuffix(".svg")}" },
                    )
                }
            }
        }
    }
}

@Composable
private fun Item(indent: Int, mark: String, text: String, actions: MarkdownActions, known: (String) -> Boolean) {
    Row(Modifier.padding(start = 16.dp * indent)) {
        Text(mark, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp))
        Rich(text, MaterialTheme.typography.bodyLarge, actions, known, Modifier.weight(1f))
    }
}

@Composable
private fun Table(table: Block.Table, actions: MarkdownActions, known: (String) -> Boolean) {
    val c = MaterialTheme.colorScheme
    val columns = (listOf(table.header) + table.rows).maxOf { it.size }
    Row(Modifier.horizontalScroll(rememberScrollState()).border(1.dp, c.outlineVariant, RoundedCornerShape(12.dp))) {
        for (col in 0 until columns) {
            Column(Modifier.width(140.dp)) {
                Box(Modifier.fillMaxWidth().background(c.surfaceContainerHigh).padding(10.dp)) {
                    Rich(table.header.getOrElse(col) { "" }, MaterialTheme.typography.titleSmall, actions, known)
                }
                table.rows.forEach { row ->
                    HorizontalDivider(color = c.outlineVariant)
                    Box(Modifier.padding(10.dp)) { Rich(row.getOrElse(col) { "" }, MaterialTheme.typography.bodyMedium, actions, known) }
                }
            }
        }
    }
}

@Composable
private fun Rich(text: String, style: TextStyle, actions: MarkdownActions, known: (String) -> Boolean, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val codeBackground = c.surfaceContainerHigh
    val written = c.primary
    val unwritten = c.tertiary
    val annotated = remember(text, written, unwritten, codeBackground, known) { render(text, written, unwritten, codeBackground, actions, known) }
    Text(annotated, style = style.copy(color = if (style.color == Color.Unspecified) c.onSurface else style.color), modifier = modifier)
}

private fun render(text: String, written: Color, unwritten: Color, codeBackground: Color, actions: MarkdownActions, known: (String) -> Boolean): AnnotatedString = buildAnnotatedString {
    Markdown.inlines(text).forEach { run ->
        val base = SpanStyle(
            fontWeight = if (run.bold) FontWeight.Bold else null,
            fontStyle = if (run.italic) FontStyle.Italic else null,
            textDecoration = if (run.strike) TextDecoration.LineThrough else null,
            fontFamily = if (run.code) FontFamily.Monospace else null,
            background = if (run.code) codeBackground else Color.Unspecified,
        )
        val note = run.note
        val tag = run.tag
        val url = run.url
        val link = when {
            note != null -> LinkAnnotation.Clickable(
                "note", TextLinkStyles(base.copy(color = if (known(note)) written else unwritten, textDecoration = TextDecoration.Underline)),
            ) { actions.onNote(note) }
            tag != null -> LinkAnnotation.Clickable("tag", TextLinkStyles(base.copy(color = written))) { actions.onTag(tag) }
            url != null -> LinkAnnotation.Clickable("url", TextLinkStyles(base.copy(color = written, textDecoration = TextDecoration.Underline))) { actions.onUrl(url) }
            else -> null
        }
        if (link != null) {
            pushLink(link); append(run.text); pop()
        } else withStyle(base) { append(run.text) }
    }
}

private fun aspectOf(sketch: Sketch): Float {
    val b = sketch.bounds()
    return ((b[2] - b[0]) / (b[3] - b[1])).coerceIn(.5f, 3f)
}
