package io.github.shoalapps.squill.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import io.github.shoalapps.squill.paint.*
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import javax.swing.SwingUtilities
import kotlin.math.pow

/** All mutable engine state belongs to this worker, including history and in-flight strokes. */
internal class PaintingSession(initial: PaintingCopy, private val publish: (PaintingCopy, ImageBitmap, Boolean, Boolean, Long) -> Unit) {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "Painting").apply { isDaemon = true } }
    private val closed = java.util.concurrent.atomic.AtomicBoolean()
    private val queued = java.util.concurrent.atomic.AtomicInteger()
    private var document = initial
    private val p get() = document.painting
    private var stroke: StrokePainter? = null
    private var pending = Rect.EMPTY
    private var lastFrame = 0L
    private var revision = 0L
    private var snapshot = initial
    private var rendered = IntArray(0)
    init { submit { document = initial.frozen(); rendered = IntArray(p.width * p.height); refresh() } }
    // Pixel writes always detach first. Published arrays can therefore be shared with saves.
    private fun snapshot() = PaintingCopy(p.snapshot(false), document.extras, document.readOnlyReason, document.originalBytes, document.sources, document.preview)
    fun submit(block: PaintingSession.() -> Unit) {
        if (closed.get()) return
        queued.incrementAndGet()
        worker.execute { try { block() } finally { queued.decrementAndGet() } }
    }
    private fun refresh(rect: Rect = Rect(0, 0, p.width, p.height), snapshot: Boolean = true) {
        val preview = document.preview
        val image = if (document.readOnlyReason != null && preview != null) {
            org.jetbrains.skia.Image.makeFromEncoded(preview).use { it.toComposeImageBitmap() }
        } else {
            p.render(rect, rendered)
            OraFile.image(rendered, p.width, p.height).use { it.toComposeImageBitmap() }
        }
        if (snapshot) this.snapshot = snapshot()
        val copy = this.snapshot
        val undo = p.canUndo; val redo = p.canRedo; val version = revision
        SwingUtilities.invokeLater { publish(copy, image, undo, redo, version) }
    }
    fun replace(copy: PaintingCopy) { document = copy.frozen(); rendered = IntArray(p.width * p.height); refresh() }
    fun select(id: Long) { if (stroke == null && p.find(id) != null) { p.activeId = id; refresh() } }
    fun edit(block: Painting.() -> Unit) { if (stroke != null || document.readOnlyReason != null) return; p.block(); revision++; refresh() }
    fun history(redo: Boolean) = edit {
        // Whole history steps retain arrays; detach before a pixel undo can write to them.
        layers.forEach { it.pixels = it.pixels.copyOf() }
        if (redo) redo() else undo()
    }
    fun begin(x: Float, y: Float, pressure: Float, brush: BrushSettings, colour: Int) {
        if (document.readOnlyReason != null || !p.canPaint) return
        val a = p.active
        a.pixels = a.pixels.copyOf()
        val base = p.clipAlphaFor(a)
        stroke = StrokePainter(a.pixels, p.width, p.height, brush, colour, p.selection, a.alphaLock, clipAlpha = base)
        point(x, y, pressure)
    }
    fun point(x: Float, y: Float, pressure: Float) { stroke?.let {
        pending = pending.union(it.add(x, y, pressure))
        val now = System.nanoTime()
        if (queued.get() <= 1 && now - lastFrame >= 16_000_000L) {
            refresh(pending, false); pending = Rect.EMPTY; lastFrame = now
        }
    } }
    fun end(x: Float, y: Float, pressure: Float) { stroke?.let {
        it.finish(x, y, pressure); p.recordPixels(p.active, it.changed, it.original); stroke = null
        revision++
        pending = Rect.EMPTY; refresh(it.changed)
    } ?: run { revision++; refresh() } } // The press counted as an edit, so a refused stroke must still settle it or autosave waits forever.
    fun leave(save: (PaintingCopy) -> Unit, dirty: Boolean, enqueue: ((() -> PaintingCopy?) -> Unit)? = null) {
        if (!closed.compareAndSet(false, true)) return
        val finished = java.util.concurrent.CompletableFuture<PaintingCopy?>()
        worker.execute {
            try {
                stroke?.let { p.recordPixels(p.active, it.changed, it.original); stroke = null; revision++ }
                val copy = if (dirty && document.readOnlyReason == null) snapshot() else null
                finished.complete(copy)
                if (enqueue == null && copy != null) save(copy)
            } catch (e: Exception) { finished.completeExceptionally(e) }
        }
        // Queue the disk write now, before Model drains its work during window close.
        enqueue?.invoke { finished.get() }
        worker.shutdown()
    }
}

/** Save comparisons use the file's contents, since layer IDs and history are local to an editor. */
internal fun PaintingCopy.signature(): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    fun bytes(value: ByteArray) {
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(value.size).array()); digest.update(value)
    }
    fun text(s: String) { bytes(s.toByteArray()) }
    text(readOnlyReason ?: "")
    if (readOnlyReason != null) originalBytes?.let(::bytes)
    text("${painting.width},${painting.height},${painting.background}")
    fun node(n: Node) {
        text(if (n is Group) "group" else "layer")
        text(n.name); text("${n.visible},${n.opacity},${n.blend},${n.clipped}")
        if (n is Group) { text("${n.passThrough},${n.children.size}"); n.children.forEach(::node); text("end"); return }
        val l = n as Layer
        text("${l.alphaLock}")
        val buffer = java.nio.ByteBuffer.allocate(16384)
        l.pixels.forEach { pixel ->
            if (buffer.remaining() < 4) { digest.update(buffer.array(), 0, buffer.position()); buffer.clear() }
            buffer.putInt(pixel)
        }
        digest.update(buffer.array(), 0, buffer.position())
    }
    painting.root.children.forEach(::node)
    extras.toSortedMap().forEach { (name, value) -> text(name); bytes(value) }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

@Composable
fun PaintingEditor(document: PaintingCopy, onSave: (PaintingCopy) -> Unit, modifier: Modifier = Modifier,
                   saveFailures: Int = 0, onConflict: (PaintingCopy) -> Unit = {},
                   onLeave: ((() -> PaintingCopy?) -> Unit)? = null) {
    var copy by remember { mutableStateOf(document) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var revision by remember { mutableLongStateOf(0) }
    var saved by remember { mutableLongStateOf(0) }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var requested by remember { mutableLongStateOf(0) }
    val save by rememberUpdatedState(onSave)
    val conflict by rememberUpdatedState(onConflict)
    val leave by rememberUpdatedState(onLeave)
    val sent = remember { mutableListOf<String>() }
    var seen by remember { mutableStateOf(document) }
    val session = remember { PaintingSession(document) { next, bitmap, undo, redo, version ->
        copy = next; image = bitmap; canUndo = undo; canRedo = redo; revision = version
    } }
    LaunchedEffect(document) {
        val signature = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { document.signature() }
        if (seen === document && sent.isEmpty()) sent += signature
        else if (seen !== document) {
            seen = document
            if (signature !in sent) {
                sent += signature
                if (requested != saved || dragging) conflict(document)
                else session.submit { replace(document) }
            }
        }
    }
    var failuresSeen by remember { mutableIntStateOf(saveFailures) }
    LaunchedEffect(saveFailures) { if (failuresSeen != saveFailures) { failuresSeen = saveFailures; saved = -1 } }
    LaunchedEffect(revision, requested, dragging) {
        if (dragging || revision != requested || revision == saved || copy.readOnlyReason != null) return@LaunchedEffect
        delay(SAVE_AFTER_MS)
        val snapshot = copy
        sent += kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { snapshot.signature() }
        if (sent.size > 8) sent.removeAt(0)
        saved = revision; save(snapshot)
    }
    DisposableEffect(Unit) { onDispose { session.leave({ save(it) }, requested != saved || dragging, leave) } }
    fun edit(block: Painting.() -> Unit) { requested++; session.submit { this.edit(block) } }
    fun history(redo: Boolean) { requested++; session.submit { this.history(redo) } }
    var brush by remember { mutableStateOf(BrushPresets.all.first()) }
    var brushMenu by remember { mutableStateOf(false) }
    var blendMenu by remember { mutableStateOf(false) }
    var colour by remember { mutableStateOf("#1B1B1F") }
    var brushSize by remember { mutableFloatStateOf(10f) }
    var opacity by remember { mutableFloatStateOf(1f) }
    var eraser by remember { mutableStateOf(false) }
    var hand by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var fitted by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val readOnly = copy.readOnlyReason != null
    val active = copy.painting.activeNode
    fun fit() {
        if (viewport.width <= 0 || viewport.height <= 0) return
        zoom = minOf(viewport.width.toFloat() / copy.painting.width, viewport.height.toFloat() / copy.painting.height) * .95f
        pan = Offset((viewport.width - copy.painting.width * zoom) / 2, (viewport.height - copy.painting.height * zoom) / 2); fitted = true
    }
    Column(modifier.onPreviewKeyEvent {
        if (!readOnly && !dragging && it.type == KeyEventType.KeyDown && it.isCtrlPressed && it.key == Key.Z) {
            history(it.isShiftPressed); true
        } else false
    }) {
        copy.readOnlyReason?.let { Text(it, Modifier.padding(8.dp)) }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box {
                TextButton({ brushMenu = true }, enabled = !readOnly) { Text(brush.name) }
                DropdownMenu(brushMenu, { brushMenu = false }) { BrushPresets.all.forEach { preset ->
                    DropdownMenuItem({ Text(preset.name) }, { brush = preset; brushSize = preset.size; opacity = preset.opacity; eraser = false; brushMenu = false })
                } }
            }
            FilterChip(eraser, { eraser = !eraser }, { Text("Eraser") }, enabled = !readOnly)
            FilterChip(hand, { hand = !hand }, { Text("Hand") })
            TextButton({ history(false) }, enabled = canUndo && !dragging && !readOnly) { Text("Undo") }
            TextButton({ history(true) }, enabled = canRedo && !dragging && !readOnly) { Text("Redo") }
            TextButton(::fit) { Text("Fit") }
            OutlinedTextField(colour, { colour = it }, label = { Text("Colour #RRGGBB") }, singleLine = true, modifier = Modifier.width(170.dp), enabled = !readOnly)
            Column(Modifier.width(150.dp)) { Text("Size ${brushSize.toInt()} px"); Slider(brushSize, { brushSize = it }, modifier = Modifier.semantics { contentDescription = "Brush size" }, valueRange = 1f..300f, enabled = !readOnly) }
            Column(Modifier.width(150.dp)) { Text("Brush opacity ${(opacity * 100).toInt()}%"); Slider(opacity, { opacity = it }, modifier = Modifier.semantics { contentDescription = "Brush opacity" }, enabled = !readOnly) }
        }
        Text("Scroll to zoom. Alt-drag or use Hand to move.", Modifier.padding(horizontal = 8.dp))
        Row(Modifier.weight(1f)) {
            Canvas(Modifier.weight(1f).fillMaxHeight().clipToBounds().background(Color(0xFFCCCCCC))
                .onSizeChanged { viewport = it; if (!fitted) fit() }.focusRequester(focus).focusable()
                .semantics { contentDescription = "Painting canvas" }
                .pointerInput(readOnly) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(); val down = event.changes.firstOrNull() ?: continue
                            if (event.type == PointerEventType.Scroll) {
                                val next = (zoom * 1.1f.pow(-down.scrollDelta.y)).coerceIn(.01f, 32f)
                                pan = down.position - (down.position - pan) * (next / zoom); zoom = next; down.consume(); continue
                            }
                            if (event.type != PointerEventType.Press) continue
                            focus.requestFocus()
                            val move = hand || event.keyboardModifiers.isAltPressed
                            val paint = !move && !readOnly && copy.painting.canPaint
                            fun pressure(c: PointerInputChange) = if (c.type == PointerType.Stylus || c.type == PointerType.Eraser) c.pressure.coerceIn(0f, 1f) else 1f
                            fun world(c: PointerInputChange) = (c.position - pan) / zoom
                            var last = down
                            if (paint) {
                                dragging = true; requested++
                                val settings = brush.copy(size = brushSize, opacity = opacity, kind = if (eraser || down.type == PointerType.Eraser) BrushKind.ERASE else brush.kind)
                                val ink = colour.removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)?.toInt()?.or(0xFF000000.toInt()) ?: 0xFF000000.toInt()
                                val at = world(down); val force = pressure(down)
                                session.submit { begin(at.x, at.y, force, settings, ink) }
                            }
                            down.consume()
                            try {
                                while (true) {
                                    val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    if (move) pan += c.position - last.position
                                    else if (paint && c.position != last.position) { val at = world(c); val force = pressure(c); session.submit { point(at.x, at.y, force) } }
                                    last = c; c.consume(); if (!c.pressed) break
                                }
                            } finally {
                                if (paint) { val at = world(last); val force = pressure(last); session.submit { end(at.x, at.y, force) }; dragging = false }
                            }
                        }
                    }
                }) {
                image?.let { bitmap -> withTransform({ translate(pan.x, pan.y); scale(zoom, zoom, Offset.Zero) }) { drawImage(bitmap) } }
            }
            Column(Modifier.width(230.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(8.dp)) {
                Text("Layers")
                Row {
                    TextButton({ edit { newLayer() } }, enabled = !readOnly && !dragging && copy.painting.layers.size < Painting.MAX_LAYERS) { Text("Add") }
                    TextButton({ edit { deleteActive() } }, enabled = !readOnly && !dragging && copy.painting.canDeleteActive) { Text("Delete") }
                }
                val collapsed = remember { mutableStateMapOf<Long, Boolean>() }
                @Composable fun rows(group: Group, depth: Int) {
                    group.children.asReversed().forEach { node ->
                        Row(Modifier.padding(start = (depth * 16).dp)) {
                            if (node is Group) TextButton({ collapsed[node.id] = collapsed[node.id] != true }, modifier = Modifier.semantics { contentDescription = "${if (collapsed[node.id] == true) "Expand" else "Collapse"} ${node.name}" }) { Text(if (collapsed[node.id] == true) "+" else "−") }
                            FilterChip(node.id == active.id, { session.submit { select(node.id) } }, { Text(if (node.visible) node.name else "${node.name} (hidden)") }, modifier = Modifier.semantics { contentDescription = "Select ${if (node is Group) "group" else "layer"} ${node.name}" })
                        }
                        if (node is Group && collapsed[node.id] != true) rows(node, depth + 1)
                    }
                }
                rows(copy.painting.root, 0)
                Row {
                    TextButton({ edit { newGroup() } }, enabled = !readOnly && !dragging && copy.painting.nodes.count { it is Group } < Painting.MAX_LAYERS) { Text("Add group") }
                    if (active is Layer) TextButton({ edit { groupActive() } }, enabled = !readOnly && !dragging) { Text("Group this") }
                }
                if (active is Group) {
                    Text("That's a group. Pick a layer inside it to paint.")
                    Row {
                        TextButton({ edit { ungroup() } }, enabled = !readOnly && !dragging) { Text("Ungroup") }
                        TextButton({ edit { mergeGroup() } }, enabled = !readOnly && !dragging) { Text("Merge group") }
                    }
                    Row {
                        Checkbox(active.passThrough, { value -> edit { change { (activeNode as? Group)?.let { it.passThrough = value; if (value) it.blend = Blend.NORMAL } } } }, enabled = !readOnly && !dragging && !active.clipped)
                        Text("Pass through")
                    }
                }
                Row {
                    TextButton({ edit { duplicateActive() } }, enabled = !readOnly && !dragging && copy.painting.canDuplicateActive) { Text("Duplicate") }
                    TextButton({ edit { mergeDown() } }, enabled = !readOnly && !dragging && copy.painting.canMergeDown) { Text("Merge down") }
                }
                key(active.id, active.name) {
                    var name by remember { mutableStateOf(active.name) }
                    OutlinedTextField(name, { name = it }, label = { Text("Layer name") }, enabled = !readOnly && !dragging)
                    TextButton({ val value = name; edit { change { this.activeNode.name = value } } }, enabled = !readOnly && !dragging && name != active.name) { Text("Rename") }
                }
                Row {
                    TextButton({ edit { moveActive(1) } }, enabled = !readOnly && !dragging) { Text("Up") }
                    TextButton({ edit { moveActive(-1) } }, enabled = !readOnly && !dragging) { Text("Down") }
                }
                Row { Checkbox(active.visible, { value -> edit { change { this.activeNode.visible = value } } }, modifier = Modifier.semantics { contentDescription = "Layer visible" }, enabled = !readOnly && !dragging); Text("Visible") }
                var layerOpacity by remember(active.id, active.opacity) { mutableFloatStateOf(active.opacity) }
                Text("Layer opacity ${(layerOpacity * 100).toInt()}%")
                Slider(layerOpacity, { layerOpacity = it }, onValueChangeFinished = {
                    if (layerOpacity != active.opacity) { val value = layerOpacity; edit { change { this.activeNode.opacity = value } } }
                }, modifier = Modifier.semantics { contentDescription = "Layer opacity" }, enabled = !readOnly && !dragging)
                Box {
                    TextButton({ blendMenu = true }, enabled = !readOnly && !dragging) { Text("Blend: ${active.blend.label}") }
                    DropdownMenu(blendMenu, { blendMenu = false }) { Blend.entries.forEach { blend ->
                        DropdownMenuItem({ Text(blend.label) }, { edit { change { this.activeNode.blend = blend; if (blend != Blend.NORMAL) (activeNode as? Group)?.passThrough = false } }; blendMenu = false })
                    } }
                }
            }
        }
    }
}
