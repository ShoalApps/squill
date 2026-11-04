package io.github.shoalapps.squill.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.shoalapps.squill.notes.Block
import io.github.shoalapps.squill.notes.Deck
import io.github.shoalapps.squill.notes.Element
import io.github.shoalapps.squill.notes.Graph
import io.github.shoalapps.squill.notes.GraphLayout
import io.github.shoalapps.squill.notes.GraphNode
import io.github.shoalapps.squill.notes.Library
import io.github.shoalapps.squill.notes.Markdown
import io.github.shoalapps.squill.notes.SLIDE_H
import io.github.shoalapps.squill.notes.SLIDE_W
import io.github.shoalapps.squill.notes.Sketch
import io.github.shoalapps.squill.notes.Slide
import io.github.shoalapps.squill.notes.readZip
import java.io.ByteArrayInputStream
import java.util.Base64

class ReadActions(
    val onNote: (String) -> Unit,
    val onUrl: (String) -> Unit,
    val onTag: (String) -> Unit,
    val onTask: (Int) -> Unit,
    val sketch: (String) -> Sketch?,
)

/** A note in reading view. */
@Composable
fun MarkdownPane(text: String, actions: ReadActions, known: (String) -> Boolean, modifier: Modifier = Modifier) {
    val blocks = remember(text) { Markdown.parse(text) }
    val type = MaterialTheme.typography
    val c = MaterialTheme.colorScheme
    SelectionContainer {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEach { b ->
                when (b) {
                    is Block.Heading -> Rich(b.text, (when (b.level) { 1 -> type.headlineMedium; 2 -> type.titleLarge; 3 -> type.titleMedium; else -> type.titleSmall }).copy(fontWeight = FontWeight.Bold), actions, known, Modifier.padding(top = if (b.level <= 2) 10.dp else 4.dp))
                    is Block.Paragraph -> Rich(b.text, type.bodyLarge, actions, known)
                    is Block.Bullet -> Item(b.indent, "•", b.text, actions, known)
                    is Block.Numbered -> Item(b.indent, "${b.number}.", b.text, actions, known)
                    is Block.Task -> Row(Modifier.padding(start = 16.dp * b.indent), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(b.done, { actions.onTask(b.line) })
                        Rich(b.text, if (b.done) type.bodyLarge.copy(textDecoration = TextDecoration.LineThrough, color = c.onSurfaceVariant) else type.bodyLarge, actions, known, Modifier.weight(1f))
                    }
                    is Block.Quote -> Row(Modifier.fillMaxWidth()) {
                        Box(Modifier.width(4.dp).heightIn(min = 24.dp).background(c.primary, RoundedCornerShape(2.dp)))
                        Column(Modifier.background(c.surfaceContainerLow).weight(1f).padding(12.dp, 6.dp)) {
                            b.text.split('\n').forEach { Rich(it, type.bodyLarge.copy(fontStyle = FontStyle.Italic), actions, known) }
                        }
                    }
                    is Block.Code -> Text(b.text, style = type.bodyMedium.copy(fontFamily = FontFamily.Monospace), softWrap = false, modifier = Modifier.fillMaxWidth().background(c.surfaceContainerHigh, RoundedCornerShape(12.dp)).horizontalScroll(rememberScrollState()).padding(12.dp))
                    is Block.Table -> {
                        val cols = (listOf(b.header) + b.rows).maxOf { it.size }
                        Column(Modifier.border(1.dp, c.outlineVariant, RoundedCornerShape(12.dp))) {
                            Row(Modifier.background(c.surfaceContainerHigh)) { for (i in 0 until cols) Box(Modifier.weight(1f).padding(10.dp)) { Rich(b.header.getOrElse(i) { "" }, type.titleSmall, actions, known) } }
                            b.rows.forEach { row ->
                                HorizontalDivider(color = c.outlineVariant)
                                Row { for (i in 0 until cols) Box(Modifier.weight(1f).padding(10.dp)) { Rich(row.getOrElse(i) { "" }, type.bodyMedium, actions, known) } }
                            }
                        }
                    }
                    is Block.Rule -> HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    is Block.Embed -> {
                        val s = actions.sketch(b.target)
                        if (s == null) Text("Missing drawing: ${b.target}", color = c.error, style = type.bodyMedium)
                        else SketchPane(s, Modifier.fillMaxWidth().heightIn(max = 360.dp).aspectRatio(((s.bounds()[2] - s.bounds()[0]) / (s.bounds()[3] - s.bounds()[1])).coerceIn(.5f, 3f), matchHeightConstraintsFirst = false).clip(RoundedCornerShape(12.dp)))
                    }
                }
            }
        }
    }
}

@Composable
private fun Item(indent: Int, mark: String, text: String, actions: ReadActions, known: (String) -> Boolean) {
    Row(Modifier.padding(start = 16.dp * indent)) {
        Text(mark, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp))
        Rich(text, MaterialTheme.typography.bodyLarge, actions, known, Modifier.weight(1f))
    }
}

@Composable
private fun Rich(text: String, style: TextStyle, actions: ReadActions, known: (String) -> Boolean, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val written = c.primary; val unwritten = c.tertiary; val codeBack = c.surfaceContainerHigh
    val annotated = remember(text, written, unwritten, codeBack) { rich(text, written, unwritten, codeBack, actions, known) }
    Text(annotated, style = style.copy(color = if (style.color == Color.Unspecified) c.onSurface else style.color), modifier = modifier)
}

private fun rich(text: String, written: Color, unwritten: Color, codeBack: Color, actions: ReadActions, known: (String) -> Boolean): AnnotatedString = buildAnnotatedString {
    Markdown.inlines(text).forEach { run ->
        val base = SpanStyle(
            fontWeight = if (run.bold) FontWeight.Bold else null, fontStyle = if (run.italic) FontStyle.Italic else null,
            textDecoration = if (run.strike) TextDecoration.LineThrough else null, fontFamily = if (run.code) FontFamily.Monospace else null,
            background = if (run.code) codeBack else Color.Unspecified,
        )
        val note = run.note; val tag = run.tag; val url = run.url
        val link = when {
            note != null -> LinkAnnotation.Clickable("note", TextLinkStyles(base.copy(color = if (known(note)) written else unwritten, textDecoration = TextDecoration.Underline))) { actions.onNote(note) }
            tag != null -> LinkAnnotation.Clickable("tag", TextLinkStyles(base.copy(color = written))) { actions.onTag(tag) }
            url != null -> LinkAnnotation.Clickable("url", TextLinkStyles(base.copy(color = written, textDecoration = TextDecoration.Underline))) { actions.onUrl(url) }
            else -> null
        }
        if (link != null) { pushLink(link); append(run.text); pop() } else withStyle(base) { append(run.text) }
    }
}

private val PaperColour = Color(0xFFFFFBF5)
private fun ink(c: Int, alpha: Float = 1f) = Color(c.toLong() and 0xFFFFFF or 0xFF000000L).copy(alpha = alpha)

/** A drawing, fitted to its space. */
@Composable
fun SketchPane(sketch: Sketch, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier.clipToBounds().background(PaperColour)) {
        val b = sketch.bounds()
        val scale = minOf(size.width / (b[2] - b[0]), size.height / (b[3] - b[1])).coerceAtMost(3f)
        val dx = (size.width - (b[2] - b[0]) * scale) / 2f - b[0] * scale
        val dy = (size.height - (b[3] - b[1]) * scale) / 2f - b[1] * scale
        sketch.strokes.forEach { s ->
            val colour = ink(s.colour, if (s.tool == Sketch.HIGHLIGHTER) .4f else 1f)
            if (s.count == 1) { drawCircle(colour, s.size * scale / 2f, Offset(s.x(0) * scale + dx, s.y(0) * scale + dy)); return@forEach }
            val path = Path()
            path.moveTo(s.x(0) * scale + dx, s.y(0) * scale + dy)
            for (i in 1 until s.count) path.lineTo(s.x(i) * scale + dx, s.y(i) * scale + dy)
            drawPath(path, colour, style = Stroke(s.size * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        sketch.texts.forEach { t ->
            drawText(measurer, t.text, Offset(t.x * scale + dx, (t.y - t.size) * scale + dy), TextStyle(color = ink(t.colour), fontSize = (t.size * scale * .75f).sp))
        }
    }
}

/** One slide, at whatever size it's given. */
@Composable
fun SlidePane(slide: Slide, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.aspectRatio(SLIDE_W / SLIDE_H).clipToBounds().background(ink(slide.background))) {
        val unit = maxWidth.value / SLIDE_W
        slide.elements.forEach { e ->
            Box(Modifier.offset((e.x * unit).dp, (e.y * unit).dp).size((e.w * unit).dp, (e.h * unit).dp)) { ElementPane(e, unit) }
        }
    }
}

@Composable
private fun ElementPane(e: Element, unit: Float) {
    when (e.kind) {
        "image" -> {
            val picture = remember(e.image) { runCatching { decodeImage(Base64.getDecoder().decode(e.image.orEmpty())) }.getOrNull() }
            if (picture != null) Image(picture, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        "line" -> Canvas(Modifier.fillMaxSize()) { drawLine(ink(e.colour), Offset.Zero, Offset(size.width, size.height), (e.size / 4f * unit).dp.toPx().coerceAtLeast(1f)) }
        else -> {
            if (e.kind == "rect" || e.kind == "ellipse") Canvas(Modifier.fillMaxSize()) {
                val line = Stroke(2.dp.toPx() * unit.coerceAtLeast(.3f))
                if (e.kind == "rect") { if (e.fill >= 0) drawRect(ink(e.fill)); drawRect(ink(e.colour), style = line) }
                else { if (e.fill >= 0) drawOval(ink(e.fill)); drawOval(ink(e.colour), style = line) }
            } else if (e.fill >= 0) Box(Modifier.fillMaxSize().background(ink(e.fill)))
            if (e.text.isNotEmpty()) Text(
                e.text, color = ink(e.colour), fontSize = (e.size * unit).sp, lineHeight = (e.size * unit * 1.2f).sp,
                fontWeight = if (e.bold) FontWeight.Bold else FontWeight.Normal, fontStyle = if (e.italic) FontStyle.Italic else FontStyle.Normal,
                textAlign = when (e.align) { "center" -> TextAlign.Center; "right" -> TextAlign.End; else -> TextAlign.Start },
                modifier = Modifier.fillMaxWidth().padding((6f * unit).dp),
            )
        }
    }
}

fun decodeImage(bytes: ByteArray): ImageBitmap = org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()

/** A presentation: the slides down the side, the chosen one large. */
@Composable
fun DeckPane(deck: Deck, modifier: Modifier = Modifier) {
    var index by remember(deck) { mutableIntStateOf(0) }
    val c = MaterialTheme.colorScheme
    Row(modifier) {
        Column(Modifier.width(170.dp).fillMaxSize().padding(8.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            deck.slides.forEachIndexed { i, s ->
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).border(if (i == index) 3.dp else 1.dp, if (i == index) c.primary else c.outlineVariant, RoundedCornerShape(6.dp)).clickable { index = i }) {
                    SlidePane(s, Modifier.fillMaxWidth())
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxSize().background(c.surfaceContainerLow).padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            val slide = deck.slides[index.coerceIn(deck.slides.indices)]
            SlidePane(slide, Modifier.fillMaxWidth())
            Text("Slide ${index + 1} of ${deck.slides.size}. Edit slides in Squill on your phone.", style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
            if (slide.notes.isNotBlank()) Text(slide.notes, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        }
    }
}


/** A layered painting's flattened picture. */
fun paintingPicture(bytes: ByteArray): ImageBitmap? = runCatching {
    val files = readZip(ByteArrayInputStream(bytes))
    decodeImage(files["mergedimage.png"] ?: files.getValue("Thumbnails/thumbnail.png"))
}.getOrNull()

/** Every note as a dot and every link as a line. Drag the empty space to look around, scroll to zoom, click a dot to open it. */
@Composable
fun GraphPane(library: Library, onOpen: (GraphNode) -> Unit, modifier: Modifier = Modifier) {
    var showUnwritten by rememberSaveable { mutableStateOf(true) }
    val graph: Graph = remember(library.notes, showUnwritten) { library.graph(showUnwritten) }
    Column(modifier) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Graph", style = MaterialTheme.typography.titleLarge)
            FilterChip(showUnwritten, { showUnwritten = !showUnwritten }, label = { Text("Unwritten notes") })
            Text("${graph.nodes.size} notes, ${graph.edges.size} links", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (graph.nodes.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No notes to show yet") }
        else GraphCanvas(graph, onOpen, Modifier.fillMaxSize())
    }
}

@Composable
private fun GraphCanvas(graph: Graph, onOpen: (GraphNode) -> Unit, modifier: Modifier) {
    val layout = remember(graph) { GraphLayout(graph) }
    var frame by remember(graph) { mutableIntStateOf(0) }
    var zoom by remember(graph) { mutableFloatStateOf(1f) }
    var panX by remember(graph) { mutableFloatStateOf(0f) }
    var panY by remember(graph) { mutableFloatStateOf(0f) }
    LaunchedEffect(layout) {
        var moving = Float.MAX_VALUE
        var steps = 0
        while (moving > .05f && steps < 2000) {
            withFrameNanos { }
            repeat(if (graph.nodes.size > 300) 1 else 3) { moving = layout.step() }
            steps++
            frame++
        }
    }
    val c = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer(cacheSize = 256)
    Canvas(
        modifier
            .pointerInput(graph) { detectDragGestures { change, drag -> change.consume(); panX += drag.x; panY += drag.y } }
            .pointerInput(graph) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.type == androidx.compose.ui.input.pointer.PointerEventType.Scroll) {
                            val ch = e.changes.first()
                            zoom = (zoom * if (ch.scrollDelta.y < 0) 1.1f else .9f).coerceIn(.1f, 6f)
                            ch.consume()
                        }
                    }
                }
            }
            .pointerInput(graph) {
                detectTapGestures { tap ->
                    val px = (tap.x - size.width / 2f - panX) / zoom
                    val py = (tap.y - size.height / 2f - panY) / zoom
                    layout.nearest(px, py, 20f / zoom)?.let { onOpen(graph.nodes[it]) }
                }
            },
    ) {
        frame.let { }
        val middle = Offset(size.width / 2f + panX, size.height / 2f + panY)
        fun at(i: Int) = Offset(middle.x + layout.x[i] * zoom, middle.y + layout.y[i] * zoom)
        graph.edges.forEach { (a, b) -> drawLine(c.outlineVariant, at(a), at(b), 1.5f) }
        val labels = graph.nodes.size <= 120 || zoom > 1.2f
        graph.nodes.forEachIndexed { i, node ->
            val p = at(i)
            if (p.x < -200f || p.y < -60f || p.x > size.width + 200f || p.y > size.height + 60f) return@forEachIndexed
            val radius = (5f + 1.6f * node.links.coerceAtMost(12)) * zoom.coerceIn(.6f, 1.6f)
            if (node.path != null) drawCircle(c.primary, radius, p) else drawCircle(c.tertiary, radius, p, style = Stroke(2f))
            if (labels) {
                val laid = measurer.measure(node.title, TextStyle(color = if (node.path != null) c.onSurface else c.onSurfaceVariant, fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = 200))
                drawText(laid, topLeft = Offset(p.x - laid.size.width / 2f, p.y + radius + 3f))
            }
        }
    }
}
