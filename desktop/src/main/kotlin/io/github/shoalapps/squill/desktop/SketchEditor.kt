package io.github.shoalapps.squill.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.shoalapps.squill.notes.Sketch
import io.github.shoalapps.squill.notes.SketchStroke
import io.github.shoalapps.squill.notes.deleteStroke
import io.github.shoalapps.squill.notes.eraseStrokes
import io.github.shoalapps.squill.notes.moveStroke
import io.github.shoalapps.squill.notes.strokeAt
import kotlinx.coroutines.delay
import kotlin.math.pow

private enum class SketchTool(val label: String) { PEN("Pen"), MARKER("Marker"), HIGHLIGHTER("Highlighter"), ERASER("Eraser"), SELECT("Select"), HAND("Hand") }
private val SketchColours = listOf("Black" to 0xFF1B1B1F.toInt(), "Blue" to 0xFF4A4FA3.toInt(), "Green" to 0xFF0B7A75.toInt(), "Red" to 0xFFC62828.toInt())

/** Edits the same strokes the phone uses. Each drag is one undo step. */
@Composable
fun SketchEditor(sketch: Sketch, onSave: (Sketch) -> Unit, modifier: Modifier = Modifier, saveFailures: Int = 0, onConflict: (Sketch) -> Unit = {}) {
    var working by remember { mutableStateOf(sketch) }
    var saved by remember { mutableStateOf(sketch) }
    var seen by remember { mutableStateOf(sketch) }
    var undo by remember { mutableStateOf(emptyList<Sketch>()) }
    var redo by remember { mutableStateOf(emptyList<Sketch>()) }
    var picked by remember { mutableStateOf<Int?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var failuresSeen by remember { mutableStateOf(saveFailures) }
    val save by rememberUpdatedState(onSave)
    // What's on disk as far as this editor knows, in text: reading a drawing back can round its numbers.
    val sent = remember { mutableListOf(sketch.toSvg()) }
    if (sketch != seen) {
        seen = sketch
        // Hearing back about an earlier save of ours isn't news.
        if (sketch.toSvg() !in sent) {
            sent += sketch.toSvg()
            val dirty = working != saved || dragging
            // Both changed: the other device's drawing is kept beside this one, not overwritten.
            if (dirty) onConflict(sketch)
            else { working = sketch; saved = sketch; undo = emptyList(); redo = emptyList(); picked = null }
        }
    }
    // A save that failed leaves changes unsaved, so leaving the editor tries again.
    if (saveFailures != failuresSeen) { failuresSeen = saveFailures; saved = seen }
    LaunchedEffect(working, dragging) {
        if (working == saved || dragging) return@LaunchedEffect
        delay(SAVE_AFTER_MS)
        saved = working
        sent += working.toSvg()
        if (sent.size > 8) sent.removeAt(0)
        save(working)
    }
    DisposableEffect(Unit) { onDispose { if (working != saved) save(working) } }
    fun record(before: Sketch) {
        if (working != before) { undo = (undo + before).takeLast(100); redo = emptyList() }
    }
    fun undoChange() {
        if (!dragging && undo.isNotEmpty()) { redo = (redo + working).takeLast(100); working = undo.last(); undo = undo.dropLast(1); picked = null }
    }
    fun redoChange() {
        if (!dragging && redo.isNotEmpty()) { undo = (undo + working).takeLast(100); working = redo.last(); redo = redo.dropLast(1); picked = null }
    }
    fun deletePicked() {
        if (!dragging) picked?.let { val before = working; working = working.deleteStroke(it); record(before); picked = null }
    }
    var tool by remember { mutableStateOf(SketchTool.PEN) }
    var colour by remember { mutableIntStateOf(SketchColours[0].second) }
    var width by remember { mutableFloatStateOf(6f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var fitted by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val measurer = rememberTextMeasurer()
    fun fit() {
        if (viewport.width == 0 || viewport.height == 0) return
        val b = working.bounds()
        zoom = minOf(viewport.width / (b[2] - b[0]), viewport.height / (b[3] - b[1])).coerceAtMost(3f)
        pan = Offset((viewport.width - (b[2] - b[0]) * zoom) / 2f - b[0] * zoom, (viewport.height - (b[3] - b[1]) * zoom) / 2f - b[1] * zoom)
        fitted = true
    }
    fun world(at: Offset) = (at - pan) / zoom
    Column(modifier.onPreviewKeyEvent { e ->
        when {
            e.type != KeyEventType.KeyDown -> false
            e.isCtrlPressed && e.key == Key.Z -> { if (e.isShiftPressed) redoChange() else undoChange(); true }
            e.key == Key.Delete || e.key == Key.Backspace -> { deletePicked(); true }
            else -> false
        }
    }) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SketchTool.entries.forEach { t -> FilterChip(tool == t, { tool = t; picked = null }, label = { Text(t.label) }) }
            TextButton(onClick = ::undoChange, enabled = undo.isNotEmpty() && !dragging) { Text("Undo") }
            TextButton(onClick = ::redoChange, enabled = redo.isNotEmpty() && !dragging) { Text("Redo") }
            TextButton(onClick = ::deletePicked, enabled = picked != null && !dragging) { Text("Delete") }
            TextButton(onClick = ::fit) { Text("Fit") }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SketchColours.forEach { (name, value) -> FilterChip(colour == value, { colour = value }, label = { Text(name) }) }
            listOf(2f, 6f, 12f).forEach { value -> FilterChip(width == value, { width = value }, label = { Text("${value.toInt()} px") }) }
        }
        Text("Scroll to zoom. Alt-drag or use Hand to move around.", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp))
        Canvas(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(Color(0xFFFFFBF5))
            .onSizeChanged { viewport = it; if (!fitted) fit() }
            .focusRequester(focus).focusable()
            .semantics { contentDescription = "Drawing canvas with ${working.strokes.size} strokes" }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val down = event.changes.firstOrNull() ?: continue
                        if (event.type == PointerEventType.Scroll) {
                            val next = (zoom * 1.1f.pow(-down.scrollDelta.y)).coerceIn(.05f, 16f)
                            pan = down.position - (down.position - pan) * (next / zoom)
                            zoom = next; down.consume(); continue
                        }
                        if (event.type != PointerEventType.Press) continue
                        focus.requestFocus()
                        val before = working
                        val from = world(down.position)
                        val route = when {
                            event.keyboardModifiers.isAltPressed -> SketchTool.HAND
                            down.type == PointerType.Eraser -> SketchTool.ERASER
                            else -> tool
                        }
                        val key = when (route) { SketchTool.MARKER -> Sketch.MARKER; SketchTool.HIGHLIGHTER -> Sketch.HIGHLIGHTER; else -> Sketch.PEN }
                        val size = if (route == SketchTool.HIGHLIGHTER) width * 4f else width
                        val points = arrayListOf<Float>()
                        fun add(c: PointerInputChange) {
                            val at = world(c.position)
                            val elapsed = (c.uptimeMillis - down.uptimeMillis).toFloat()
                            // Mouse pressure is a fixed placeholder. Keep the format's unknown value.
                            val pressure = if (c.type == PointerType.Stylus) c.pressure.coerceIn(0f, 1f) else -1f
                            points.addAll(listOf(at.x, at.y, elapsed, pressure))
                            working = before.copy(strokes = before.strokes + SketchStroke(key, colour, size, points.toList()))
                        }
                        dragging = true
                        when (route) {
                            SketchTool.SELECT -> picked = working.strokeAt(from.x, from.y, 6f / zoom)
                            SketchTool.ERASER -> { working = working.eraseStrokes(from.x, from.y, from.x, from.y, 12f / zoom); picked = null }
                            SketchTool.HAND -> Unit
                            else -> { picked = null; add(down) }
                        }
                        down.consume()
                        var last = from
                        var screen = down.position
                        try {
                            while (true) {
                                val next = awaitPointerEvent()
                                val c = next.changes.firstOrNull { it.id == down.id } ?: break
                                val at = world(c.position)
                                if (c.position != screen) when (route) {
                                    SketchTool.HAND -> pan += c.position - screen
                                    SketchTool.SELECT -> picked?.let { working = before.moveStroke(it, at.x - from.x, at.y - from.y) }
                                    SketchTool.ERASER -> working = working.eraseStrokes(last.x, last.y, at.x, at.y, 12f / zoom)
                                    else -> add(c)
                                }
                                last = at; screen = c.position; c.consume()
                                if (!c.pressed) break
                            }
                        } finally { record(before); dragging = false }
                    }
                }
            }) {
            fun screen(x: Float, y: Float) = Offset(x, y) * zoom + pan
            val step = 48f * zoom
            val rule = Color(0x22000000)
            if (working.paper != Sketch.PLAIN && step >= 8f) {
                var y = ((pan.y % step) + step) % step
                while (y < size.height) {
                    if (working.paper == Sketch.DOTS) {
                        var x = ((pan.x % step) + step) % step
                        while (x < size.width) { drawCircle(rule, 1.5f, Offset(x, y)); x += step }
                    } else drawLine(rule, Offset(0f, y), Offset(size.width, y))
                    y += step
                }
                if (working.paper == Sketch.GRID) {
                    var x = ((pan.x % step) + step) % step
                    while (x < size.width) { drawLine(rule, Offset(x, 0f), Offset(x, size.height)); x += step }
                }
            }
            working.strokes.forEach { s ->
                val ink = Color(s.colour or 0xFF000000.toInt()).copy(alpha = if (s.tool == Sketch.HIGHLIGHTER) .4f else 1f)
                fun widthAt(i: Int) = s.size * zoom * if (s.p(i) < 0f || s.tool == Sketch.HIGHLIGHTER) 1f else (.45f + .75f * s.p(i).coerceIn(0f, 1f))
                if (s.count == 1) drawCircle(ink, widthAt(0) / 2f, screen(s.x(0), s.y(0)))
                if (s.tool == Sketch.HIGHLIGHTER && s.count > 1) {
                    // One translucent path avoids dark seams where neighbouring segments meet.
                    val path = Path()
                    val first = screen(s.x(0), s.y(0))
                    path.moveTo(first.x, first.y)
                    for (i in 1 until s.count) { val at = screen(s.x(i), s.y(i)); path.lineTo(at.x, at.y) }
                    drawPath(path, ink, style = Stroke(s.size * zoom, cap = StrokeCap.Round, join = StrokeJoin.Round))
                } else for (i in 0 until s.count - 1) drawLine(ink, screen(s.x(i), s.y(i)), screen(s.x(i + 1), s.y(i + 1)), widthAt(i), StrokeCap.Round)
            }
            working.texts.forEach { t -> drawText(measurer, t.text, screen(t.x, t.y - t.size), TextStyle(color = Color(t.colour or 0xFF000000.toInt()), fontSize = (t.size * zoom / density / fontScale).sp)) }
            picked?.let { i -> working.strokes.getOrNull(i)?.let { s ->
                val b = Sketch(strokes = listOf(s)).bounds(8f)
                drawRect(Color(0xFF2962FF), screen(b[0], b[1]), Size((b[2] - b[0]) * zoom, (b[3] - b[1]) * zoom), style = Stroke(2.dp.toPx()))
            } }
        }
    }
}
