@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)

package io.github.shoalapps.squill.notes

import android.graphics.Bitmap
import android.graphics.Matrix
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.ink.authoring.compose.InProgressStrokes
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.shoalapps.squill.R
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop

/** The paper is the same in light and dark, so a drawing always looks as it was drawn. */
val Paper = Color(0xFFFFFBF5)
private val PaperLine = Color(0x22000000)
private val Palette = listOf(0xFF1B1B1F, 0xFF4A4FA3, 0xFF0B7A75, 0xFF2E7D32, 0xFFE0A800, 0xFFE65100, 0xFFC62828, 0xFFAD1457).map { it.toInt() }

private enum class Tool(val label: String) {
    PEN("Pen"), MARKER("Marker"), HIGHLIGHTER("Highlighter"), ERASER("Eraser"),
    LINE("Line"), ARROW("Arrow"), RECTANGLE("Box"), ELLIPSE("Circle"), TEXT("Text"), SELECT("Select"), HAND("Move around"),
}

/** What the current touch is doing, decided the moment it lands. */
private enum class Route { INK, ERASE, SHAPE, SELECT, TEXT, PAN }

private class Drawn(val data: SketchStroke, val ink: Stroke?)

private fun brushFor(tool: String, colour: Int, size: Float): Brush {
    val family = when (tool) {
        Sketch.MARKER -> StockBrushes.marker()
        Sketch.HIGHLIGHTER -> StockBrushes.highlighter()
        else -> StockBrushes.pressurePen()
    }
    val argb = if (tool == Sketch.HIGHLIGHTER) (colour and 0xFFFFFF) or (0x66 shl 24) else colour or (0xFF shl 24)
    return Brush.createWithColorIntArgb(family, argb, size, .1f)
}

private fun toInk(s: SketchStroke): Stroke? = runCatching {
    val batch = MutableStrokeInputBatch()
    var last = -1L
    for (i in 0 until s.count) {
        val t = maxOf(s.t(i).toLong(), last + 1)
        last = t
        val p = s.p(i)
        if (p >= 0f) batch.add(InputToolType.STYLUS, s.x(i), s.y(i), t, pressure = p.coerceIn(0f, 1f))
        else batch.add(InputToolType.STYLUS, s.x(i), s.y(i), t)
    }
    Stroke(brushFor(s.tool, s.colour, s.size), batch)
}.getOrNull()

private fun fromInk(stroke: Stroke, tool: String, colour: Int, size: Float): SketchStroke {
    val input = StrokeInput()
    val points = ArrayList<Float>(stroke.inputs.size * 4)
    for (i in 0 until stroke.inputs.size) {
        stroke.inputs.populate(i, input)
        points += input.x; points += input.y; points += input.elapsedTimeMillis.toFloat(); points += if (input.hasPressure) input.pressure else -1f
    }
    return SketchStroke(tool, colour, size, points)
}

/** A drawing. A pen draws; once one's been used, fingers only move around, so a resting palm draws nothing. */
@Composable
fun DrawingScreen(path: String, library: Library, vm: NotesViewModel, onClose: () -> Unit, onPathChanged: (String) -> Unit) {
    val context = LocalContext.current
    val start = remember(path) { library.sketches.firstOrNull { it.path == path }?.sketch ?: Sketch() }
    var drawn by remember(path) { mutableStateOf(start.strokes.map { Drawn(it, toInk(it)) }) }
    var texts by remember(path) { mutableStateOf(start.texts) }
    var paper by remember(path) { mutableStateOf(start.paper) }
    var version by remember(path) { mutableIntStateOf(0) }
    var savedVersion by remember(path) { mutableIntStateOf(0) }
    val undo = remember(path) { ArrayDeque<Pair<List<Drawn>, List<SketchText>>>() }
    val redo = remember(path) { ArrayDeque<Pair<List<Drawn>, List<SketchText>>>() }
    var tool by remember { mutableStateOf(Tool.PEN) }
    var colour by remember { mutableIntStateOf(Palette[0]) }
    var size by remember { mutableFloatStateOf(6f) }
    var penSeen by remember { mutableStateOf(false) }
    var penOnly by remember { mutableStateOf(false) }
    var zoom by remember(path) { mutableFloatStateOf(1f) }
    var panX by remember(path) { mutableFloatStateOf(0f) }
    var panY by remember(path) { mutableFloatStateOf(0f) }
    var route by remember { mutableStateOf(Route.INK) }
    var shape by remember { mutableStateOf<List<Offset>?>(null) }
    var box by remember { mutableStateOf<List<Offset>?>(null) }
    var picked by remember(path) { mutableStateOf<Set<Int>>(emptySet()) }
    var pickedTexts by remember(path) { mutableStateOf<Set<Int>>(emptySet()) }
    var typingAt by remember { mutableStateOf<Offset?>(null) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var eraseStart by remember { mutableStateOf<Pair<List<Drawn>, List<SketchText>>?>(null) }
    val renderer = remember { CanvasStrokeRenderer.create() }
    val title = path.substringAfterLast('/').removeSuffix(".svg")

    fun current() = Sketch(paper, drawn.map { it.data }, texts)
    fun flush() {
        if (version != savedVersion) { savedVersion = version; vm.saveSketchNow(path, current()) }
    }
    fun commit(strokes: List<Drawn>, words: List<SketchText>, remember: Pair<List<Drawn>, List<SketchText>> = drawn to texts) {
        undo.addLast(remember)
        if (undo.size > 100) undo.removeFirst()
        redo.clear()
        drawn = strokes
        texts = words
        version++
    }
    LaunchedEffect(path) {
        snapshotFlow { version }.drop(1).debounce(800).collect { v ->
            savedVersion = v
            vm.saveSketch(path, current())
        }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, path) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) flush() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); flush() }
    }
    BackHandler { onClose() }

    val toScreen = remember(zoom, panX, panY) { Matrix().apply { postScale(zoom, zoom); postTranslate(panX, panY) } }
    val toWorld = remember(zoom, panX, panY) {
        androidx.compose.ui.graphics.Matrix().apply { scale(1f / zoom, 1f / zoom); translate(-panX, -panY) }
    }
    fun world(o: Offset) = Offset((o.x - panX) / zoom, (o.y - panY) / zoom)

    val inkKey = when (tool) { Tool.MARKER -> Sketch.MARKER; Tool.HIGHLIGHTER -> Sketch.HIGHLIGHTER; else -> Sketch.PEN }
    val inkSize = if (tool == Tool.HIGHLIGHTER) size * 4f else size
    val brush = remember(inkKey, colour, inkSize) { brushFor(inkKey, colour, inkSize) }

    val exportPng = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) {
            val result = runCatching {
                val sketch = current()
                val b = sketch.bounds()
                val scale = 2f
                val bitmap = Bitmap.createBitmap(((b[2] - b[0]) * scale).toInt().coerceIn(1, 8000), ((b[3] - b[1]) * scale).toInt().coerceIn(1, 8000), Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(bitmap)
                c.drawColor(Paper.toArgb())
                val m = Matrix().apply { postTranslate(-b[0], -b[1]); postScale(scale, scale) }
                c.concat(m)
                drawn.forEach { d -> d.ink?.let { renderer.draw(c, it, m) } }
                drawTexts(c, sketch.texts)
                context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            Toast.makeText(context, if (result.isSuccess) "Picture saved" else "Couldn't save the picture", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    IconButton(enabled = undo.isNotEmpty() && version >= 0, onClick = {
                        undo.removeLastOrNull()?.let { (s, t) -> redo.addLast(drawn to texts); drawn = s; texts = t; picked = emptySet(); pickedTexts = emptySet(); version++ }
                    }) { Icon(painterResource(R.drawable.ic_undo), contentDescription = "Undo") }
                    IconButton(enabled = redo.isNotEmpty() && version >= 0, onClick = {
                        redo.removeLastOrNull()?.let { (s, t) -> undo.addLast(drawn to texts); drawn = s; texts = t; picked = emptySet(); pickedTexts = emptySet(); version++ }
                    }) { Icon(painterResource(R.drawable.ic_redo), contentDescription = "Redo") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text(if (penOnly) "Fingers draw too" else "Only the pen draws") }, onClick = { menu = false; penOnly = !penOnly })
                            listOf(Sketch.PLAIN to "Plain paper", Sketch.LINES to "Lined paper", Sketch.GRID to "Grid paper", Sketch.DOTS to "Dotted paper").forEach { (key, label) ->
                                DropdownMenuItem({ Text(if (paper == key) "$label ✓" else label) }, onClick = { menu = false; paper = key; version++ })
                            }
                            DropdownMenuItem({ Text("Fit on screen") }, onClick = { menu = false; zoom = 1f; panX = 0f; panY = 0f })
                            DropdownMenuItem({ Text("Rename") }, onClick = { menu = false; renaming = true })
                            DropdownMenuItem({ Text("Save as a picture") }, onClick = { menu = false; flush(); runCatching { exportPng.launch("$title.png") } })
                            DropdownMenuItem({ Text("Delete") }, onClick = { menu = false; flush(); vm.trash(path) { onClose() } })
                        }
                    }
                },
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.navigationBarsPadding().padding(vertical = 4.dp)) {
                    if (picked.isNotEmpty() || pickedTexts.isNotEmpty()) Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            commit(drawn.filterIndexed { i, _ -> i !in picked }, texts.filterIndexed { i, _ -> i !in pickedTexts })
                            picked = emptySet(); pickedTexts = emptySet()
                        }) { Text("Delete selected") }
                        TextButton(onClick = {
                            val copies = drawn.filterIndexed { i, _ -> i in picked }.map { it.data.moved(24f, 24f) }.map { Drawn(it, toInk(it)) }
                            val wordCopies = texts.filterIndexed { i, _ -> i in pickedTexts }.map { it.moved(24f, 24f) }
                            val base = drawn.size
                            val baseText = texts.size
                            commit(drawn + copies, texts + wordCopies)
                            picked = copies.indices.map { base + it }.toSet()
                            pickedTexts = wordCopies.indices.map { baseText + it }.toSet()
                        }) { Text("Duplicate") }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Tool.entries.forEach { t ->
                            FilterChip(tool == t, { tool = t; if (t != Tool.SELECT) { picked = emptySet(); pickedTexts = emptySet() } }, label = { Text(t.label) })
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Palette.forEach { c ->
                            Box(
                                Modifier.size(28.dp).clip(CircleShape).background(Color(c))
                                    .border(if (colour == c) 3.dp else 1.dp, if (colour == c) MaterialTheme.colorScheme.primary else Color(0x33000000), CircleShape)
                                    .clickable { colour = c }.semantics { contentDescription = "Colour ${Palette.indexOf(c) + 1}" },
                            )
                        }
                        Slider(size, { size = it }, valueRange = 2f..32f, modifier = Modifier.weight(1f).semantics { contentDescription = "Pen size" })
                    }
                }
            }
        },
    ) { padding ->
        Box(
            Modifier.padding(padding).fillMaxSize().clipToBounds().background(Paper)
                .semantics { contentDescription = "Drawing canvas with ${drawn.size} strokes" }
                // Runs before anything else sees the touch, and decides what it's for.
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull() ?: continue
                            if (event.type == PointerEventType.Scroll) {
                                val factor = if (change.scrollDelta.y < 0) 1.1f else .9f
                                val next = (zoom * factor).coerceIn(.2f, 8f)
                                panX = change.position.x - (change.position.x - panX) * (next / zoom)
                                panY = change.position.y - (change.position.y - panY) * (next / zoom)
                                zoom = next
                                change.consume()
                                continue
                            }
                            if (event.type == PointerEventType.Press && event.changes.count { it.pressed } == 1) {
                                val barrel = event.motionEvent?.let { it.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0 } == true
                                if (change.type == PointerType.Stylus || change.type == PointerType.Eraser) {
                                    if (!penSeen) { penSeen = true; penOnly = true }
                                }
                                route = when {
                                    change.type == PointerType.Eraser || barrel -> Route.ERASE
                                    change.type == PointerType.Touch && penOnly -> Route.PAN
                                    else -> when (tool) {
                                        Tool.PEN, Tool.MARKER, Tool.HIGHLIGHTER -> Route.INK
                                        Tool.ERASER -> Route.ERASE
                                        Tool.LINE, Tool.ARROW, Tool.RECTANGLE, Tool.ELLIPSE -> Route.SHAPE
                                        Tool.TEXT -> Route.TEXT
                                        Tool.SELECT -> Route.SELECT
                                        Tool.HAND -> Route.PAN
                                    }
                                }
                            }
                        }
                    }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        when (route) {
                            Route.INK -> Unit
                            Route.PAN -> {
                                while (true) {
                                    val e = awaitPointerEvent()
                                    if (e.changes.none { it.pressed }) break
                                    val gesture = e.calculateZoom()
                                    val pan = e.calculatePan()
                                    val center = e.calculateCentroid()
                                    val next = (zoom * gesture).coerceIn(.2f, 8f)
                                    if (center.x.isFinite()) {
                                        panX = center.x - (center.x - panX) * (next / zoom)
                                        panY = center.y - (center.y - panY) * (next / zoom)
                                    }
                                    panX += pan.x; panY += pan.y
                                    zoom = next
                                    e.changes.forEach { it.consume() }
                                }
                            }
                            Route.ERASE -> {
                                eraseStart = drawn to texts
                                fun erase(at: Offset) {
                                    val w = world(at)
                                    val reach = 12f / zoom
                                    val keep = drawn.filterNot { it.data.touches(w.x, w.y, reach) }
                                    if (keep.size != drawn.size) drawn = keep
                                    val words = texts.filterNot { t -> w.x in t.x..(t.x + t.text.length * t.size * .6f) && w.y in (t.y - t.size)..t.y }
                                    if (words.size != texts.size) texts = words
                                }
                                erase(down.position)
                                while (true) {
                                    val e = awaitPointerEvent()
                                    val c = e.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!c.pressed) break
                                    erase(c.position); c.consume()
                                }
                                eraseStart?.let { before ->
                                    if (before.first.size != drawn.size || before.second.size != texts.size) commit(drawn, texts, before)
                                }
                                eraseStart = null
                            }
                            Route.SHAPE -> {
                                val from = world(down.position)
                                shape = listOf(from, from)
                                while (true) {
                                    val e = awaitPointerEvent()
                                    val c = e.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!c.pressed) break
                                    shape = listOf(from, world(c.position)); c.consume()
                                }
                                val to = shape?.getOrNull(1)
                                shape = null
                                if (to != null && (to - from).getDistance() > 4f) {
                                    val made = when (tool) {
                                        Tool.ARROW -> Sketch.arrow(from.x, from.y, to.x, to.y, colour, size)
                                        Tool.RECTANGLE -> listOf(Sketch.rectangle(from.x, from.y, to.x, to.y, colour, size))
                                        Tool.ELLIPSE -> listOf(Sketch.ellipse(from.x, from.y, to.x, to.y, colour, size))
                                        else -> listOf(Sketch.line(from.x, from.y, to.x, to.y, colour, size))
                                    }
                                    commit(drawn + made.map { Drawn(it, toInk(it)) }, texts)
                                }
                            }
                            Route.TEXT -> {
                                val c = waitForRelease(down.id)
                                if (c != null) typingAt = world(c)
                            }
                            Route.SELECT -> {
                                val from = world(down.position)
                                val current = selectionBounds(drawn, texts, picked, pickedTexts)
                                val onSelection = current != null && from.x in current[0]..current[2] && from.y in current[1]..current[3]
                                if (onSelection) {
                                    var last = from
                                    val before = drawn to texts
                                    var moved = false
                                    while (true) {
                                        val e = awaitPointerEvent()
                                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!c.pressed) break
                                        val w = world(c.position)
                                        val dx = w.x - last.x; val dy = w.y - last.y
                                        last = w
                                        moved = true
                                        drawn = drawn.mapIndexed { i, d -> if (i in picked) d.data.moved(dx, dy).let { Drawn(it, null) } else d }
                                        texts = texts.mapIndexed { i, t -> if (i in pickedTexts) t.moved(dx, dy) else t }
                                        c.consume()
                                    }
                                    if (moved) commit(drawn.map { if (it.ink == null) Drawn(it.data, toInk(it.data)) else it }, texts, before)
                                } else {
                                    box = listOf(from, from)
                                    while (true) {
                                        val e = awaitPointerEvent()
                                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!c.pressed) break
                                        box = listOf(from, world(c.position)); c.consume()
                                    }
                                    val to = box?.getOrNull(1) ?: from
                                    box = null
                                    val l = minOf(from.x, to.x); val r = maxOf(from.x, to.x)
                                    val t = minOf(from.y, to.y); val b = maxOf(from.y, to.y)
                                    picked = drawn.indices.filter { drawn[it].data.inside(l, t, r, b) }.toSet()
                                    pickedTexts = texts.indices.filter { texts[it].x in l..r && texts[it].y in t..b }.toSet()
                                }
                            }
                        }
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                paperLines(paper, zoom, panX, panY)
                drawIntoCanvas { canvas ->
                    val native = canvas.nativeCanvas
                    native.save()
                    native.concat(toScreen)
                    drawn.forEach { d ->
                        val ink = d.ink
                        if (ink != null) renderer.draw(native, ink, toScreen) else drawPlain(native, d.data)
                    }
                    drawTexts(native, texts)
                    native.restore()
                }
                val highlight = Color(0xFF2962FF)
                fun screen(w: Offset) = Offset(w.x * zoom + panX, w.y * zoom + panY)
                shape?.let { (a, b) ->
                    val c = Color(colour)
                    val width = size * zoom
                    val p = screen(a); val q = screen(b)
                    when (tool) {
                        Tool.RECTANGLE -> drawRect(c, Offset(minOf(p.x, q.x), minOf(p.y, q.y)), androidx.compose.ui.geometry.Size(kotlin.math.abs(q.x - p.x), kotlin.math.abs(q.y - p.y)), style = DrawStroke(width))
                        Tool.ELLIPSE -> drawOval(c, Offset(minOf(p.x, q.x), minOf(p.y, q.y)), androidx.compose.ui.geometry.Size(kotlin.math.abs(q.x - p.x), kotlin.math.abs(q.y - p.y)), style = DrawStroke(width))
                        else -> drawLine(c, p, q, width, StrokeCap.Round)
                    }
                }
                box?.let { (a, b) ->
                    val p = screen(a); val q = screen(b)
                    drawRect(highlight.copy(alpha = .12f), Offset(minOf(p.x, q.x), minOf(p.y, q.y)), androidx.compose.ui.geometry.Size(kotlin.math.abs(q.x - p.x), kotlin.math.abs(q.y - p.y)))
                }
                selectionBounds(drawn, texts, picked, pickedTexts)?.let { b ->
                    val p = screen(Offset(b[0], b[1])); val q = screen(Offset(b[2], b[3]))
                    drawRect(highlight, p, androidx.compose.ui.geometry.Size(q.x - p.x, q.y - p.y), style = DrawStroke(2.dp.toPx()))
                }
            }
            InProgressStrokes(
                defaultBrush = null,
                nextBrush = { if (route == Route.INK) brush else null },
                pointerEventToWorldTransform = toWorld,
                onStrokesFinished = { finished ->
                    val made = finished.map { s -> Drawn(fromInk(s, inkKey, colour, inkSize), s) }
                    commit(drawn + made, texts)
                },
            )
            if (penSeen) Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape, modifier = Modifier.align(Alignment.TopCenter).padding(8.dp)) {
                Text(if (penOnly) "Pen draws, fingers move" else "Pen and fingers draw", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            }
        }
    }

    typingAt?.let { at ->
        var words by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { typingAt = null },
            title = { Text("Add text") },
            text = { OutlinedTextField(words, { words = it }, label = { Text("Text") }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(enabled = words.isNotBlank(), onClick = {
                    commit(drawn, texts + SketchText(at.x, at.y, words.trim(), colour, (size * 4f).coerceAtLeast(20f)))
                    typingAt = null
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { typingAt = null }) { Text("Cancel") } },
        )
    }
    if (renaming) {
        var name by remember { mutableStateOf(title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename drawing") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    renaming = false
                    flush()
                    vm.moveSketch(path, path.substringBeforeLast('/', ""), name, onPathChanged)
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.waitForRelease(id: androidx.compose.ui.input.pointer.PointerId): Offset? {
    while (true) {
        val e = awaitPointerEvent()
        val c = e.changes.firstOrNull { it.id == id } ?: return null
        if (!c.pressed) return c.position
    }
}

private fun selectionBounds(drawn: List<Drawn>, texts: List<SketchText>, picked: Set<Int>, pickedTexts: Set<Int>): FloatArray? {
    if (picked.isEmpty() && pickedTexts.isEmpty()) return null
    return Sketch(strokes = picked.mapNotNull { drawn.getOrNull(it)?.data }, texts = pickedTexts.mapNotNull { texts.getOrNull(it) }).bounds(8f)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.paperLines(paper: String, zoom: Float, panX: Float, panY: Float) {
    if (paper == Sketch.PLAIN) return
    val step = 48f * zoom
    if (step < 8f) return
    val startX = ((panX % step) + step) % step
    val startY = ((panY % step) + step) % step
    var y = startY
    if (paper == Sketch.DOTS) {
        while (y < size.height) {
            var x = startX
            while (x < size.width) { drawCircle(PaperLine, 1.5f, Offset(x, y)); x += step }
            y += step
        }
        return
    }
    while (y < size.height) { drawLine(PaperLine, Offset(0f, y), Offset(size.width, y), 1f); y += step }
    if (paper == Sketch.GRID) {
        var x = startX
        while (x < size.width) { drawLine(PaperLine, Offset(x, 0f), Offset(x, size.height), 1f); x += step }
    }
}

private fun drawTexts(c: android.graphics.Canvas, texts: List<SketchText>) {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    texts.forEach { t ->
        paint.color = t.colour or (0xFF shl 24)
        paint.textSize = t.size
        c.drawText(t.text, t.x, t.y, paint)
    }
}

/** Without the ink library: for a stroke being moved, and for thumbnails. */
private fun drawPlain(c: android.graphics.Canvas, s: SketchStroke) {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
        strokeWidth = s.size
        color = if (s.tool == Sketch.HIGHLIGHTER) (s.colour and 0xFFFFFF) or (0x66 shl 24) else s.colour or (0xFF shl 24)
    }
    if (s.count == 1) { c.drawPoint(s.x(0), s.y(0), paint); return }
    val path = android.graphics.Path()
    path.moveTo(s.x(0), s.y(0))
    for (i in 1 until s.count) path.lineTo(s.x(i), s.y(i))
    c.drawPath(path, paint)
}

/** A drawing, fitted to whatever space it's given. */
@Composable
fun SketchPicture(sketch: Sketch, modifier: Modifier = Modifier) {
    Canvas(modifier.clipToBounds().background(Paper)) {
        val b = sketch.bounds()
        val scale = minOf(size.width / (b[2] - b[0]), size.height / (b[3] - b[1])).coerceAtMost(2f)
        val dx = (size.width - (b[2] - b[0]) * scale) / 2f - b[0] * scale
        val dy = (size.height - (b[3] - b[1]) * scale) / 2f - b[1] * scale
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.translate(dx, dy)
            native.scale(scale, scale)
            sketch.strokes.forEach { drawPlain(native, it) }
            drawTexts(native, sketch.texts)
            native.restore()
        }
    }
}
