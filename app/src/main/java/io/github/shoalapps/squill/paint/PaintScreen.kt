@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.shoalapps.squill.paint

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush as ComposeBrush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.shoalapps.squill.notes.Library
import io.github.shoalapps.squill.notes.NotesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.atan2
import kotlin.math.hypot

enum class PaintMode(val label: String) { SIMPLE("Simple"), ADVANCED("Advanced"), FOCUS("Focus") }

private enum class Tool(val label: String) {
    BRUSH("Brush"), PICK("Pick colour"), FILL("Fill"), GRADIENT("Gradient"), LINE("Line"), RECT("Rectangle"), ELLIPSE("Ellipse"),
    SELECT_RECT("Select box"), SELECT_ELLIPSE("Select oval"), LASSO("Lasso"), WAND("Magic wand"), TRANSFORM("Transform"), GUIDES("Guides"), TEXT("Text"), CROP("Crop"), VIEW("Move view"),
}

private enum class Sheet { GUIDES, LAYERS, BRUSH, COLOUR, FILTERS, IMAGE }

private enum class Route { STROKE, TOOL, VIEW }

private const val MODE_PREF = "paint-mode"

private class View {
    var zoom = 1f; var panX = 0f; var panY = 0f; var turn = 0f
    val matrix = Matrix()
    private val inverse = Matrix()
    private val point = FloatArray(2)
    fun update() { matrix.reset(); matrix.postScale(zoom, zoom); matrix.postRotate(turn); matrix.postTranslate(panX, panY); matrix.invert(inverse) }
    fun toCanvas(x: Float, y: Float): Offset { point[0] = x; point[1] = y; inverse.mapPoints(point); return Offset(point[0], point[1]) }
    fun toScreen(x: Float, y: Float): Offset { point[0] = x; point[1] = y; matrix.mapPoints(point); return Offset(point[0], point[1]) }
}

/** The painting studio: layers, brushes, selections and filters on a pixel canvas. */
@Composable
fun PaintScreen(path: String, vm: NotesViewModel, onClose: () -> Unit, onPathChanged: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE) }
    var mode by remember { mutableStateOf(runCatching { PaintMode.valueOf(prefs.getString(MODE_PREF, null) ?: "") }.getOrDefault(PaintMode.SIMPLE)) }
    val libraryFile = remember { BrushLibraryFile(java.io.File(context.filesDir, "brush-library.json")) }
    var library by remember { mutableStateOf(BrushLibrary()) }
    var libraryReady by remember { mutableStateOf(false) }
    val librarySnackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        library = withContext(Dispatchers.IO) { libraryFile.load() }
        libraryReady = true
    }
    var painting by remember(path) { mutableStateOf<Painting?>(null) }
    var failed by remember(path) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(path) {
        val loaded = withContext(Dispatchers.IO) { runCatching { OraFile.load(vm.vault.readBytes(path)) } }
        loaded.onSuccess { painting = it }.onFailure { failed = it.message ?: "That painting couldn't be opened" }
    }
    BackHandler(painting == null) { onClose() }
    val p = painting
    if (p == null || !libraryReady) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
            val message = failed
            if (message == null) CircularProgressIndicator()
            else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(message)
                TextButton(onClick = onClose) { Text("Back") }
            }
        }
        return
    }

    var version by remember(path) { mutableIntStateOf(0) }
    var savedVersion by remember(path) { mutableIntStateOf(0) }
    var frame by remember { mutableIntStateOf(0) }
    var bitmap by remember(path) { mutableStateOf(Bitmap.createBitmap(p.width, p.height, Bitmap.Config.ARGB_8888)) }
    var pixels by remember(path) { mutableStateOf(IntArray(p.width * p.height)) }
    val view = remember(path) { View() }
    var fitted by remember(path) { mutableStateOf(false) }
    var viewSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    fun refresh(rect: Rect = Rect(0, 0, p.width, p.height)) {
        val r = rect.clip(p.width, p.height)
        if (r.isEmpty) return
        p.render(r, pixels)
        bitmap.setPixels(pixels, r.top * p.width + r.left, p.width, r.left, r.top, r.width, r.height)
        frame++
    }
    /** After anything that could change the canvas's size. */
    fun refreshAll() {
        if (bitmap.width != p.width || bitmap.height != p.height) {
            bitmap = Bitmap.createBitmap(p.width, p.height, Bitmap.Config.ARGB_8888)
            pixels = IntArray(p.width * p.height)
        }
        refresh()
    }
    fun fit() {
        if (viewSize.width == 0) return
        view.turn = 0f
        view.zoom = minOf(viewSize.width.toFloat() / p.width, viewSize.height.toFloat() / p.height) * .96f
        view.panX = (viewSize.width - p.width * view.zoom) / 2f
        view.panY = (viewSize.height - p.height * view.zoom) / 2f
        view.update()
        frame++
    }
    LaunchedEffect(p) { refreshAll() }
    fun edited() { version++ }
    var lifted by remember { mutableStateOf<Bitmap?>(null) }
    var liftedOriginal by remember { mutableStateOf<IntArray?>(null) }
    fun flush() {
        if (version == savedVersion) return
        savedVersion = version
        // Mid-transform the layer has a hole where the lifted part was, so save it as it was before lifting.
        val original = liftedOriginal
        if (original == null) { vm.savePainting(path, p.frozen()); return }
        val a = p.active; val holed = a.pixels
        a.pixels = original
        val copy = p.frozen()
        a.pixels = holed
        vm.savePainting(path, copy)
    }
    LaunchedEffect(path) {
        // Saves shortly after you stop, with no need to press anything.
        while (true) {
            delay(2500)
            if (version != savedVersion) flush()
        }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, path) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) flush() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); flush() }
    }

    var tool by remember { mutableStateOf(Tool.BRUSH) }
    var brush by remember { mutableStateOf(library.brush()) }
    var colour by remember { mutableIntStateOf(library.colour) }
    var backColour by remember { mutableIntStateOf(0xFFFFFFFF.toInt()) }
    val recent = remember { mutableStateListOf<Int>().also { it.addAll(library.recent) } }
    var lastLibraryWrite by remember { mutableStateOf(library) }
    SideEffect {
        val next = library.used(brush, colour, recent.toList())
        if (next != library) library = next
        if (next != lastLibraryWrite) {
            lastLibraryWrite = next
            libraryFile.save(next) { error ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(context, "Brush library could not be saved: ${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    fun selectBrush(name: String) {
        library = library.used(brush)
        brush = library.brush(name)
    }
    var symmetry by remember { mutableStateOf(Symmetry()) }
    var penSeen by remember { mutableStateOf(false) }
    var penOnly by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var menu by remember { mutableStateOf(false) }
    var tolerance by remember { mutableFloatStateOf(24f) }
    var contiguous by remember { mutableStateOf(true) }
    var radial by remember { mutableStateOf(false) }
    var filled by remember { mutableStateOf(false) }
    var typingAt by remember { mutableStateOf<Offset?>(null) }
    var preview by remember { mutableStateOf<List<Offset>?>(null) }
    var selectionTint by remember { mutableStateOf<Bitmap?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var showBars by remember { mutableStateOf(true) }

    // Transform in progress: the lifted part floats above the rest until it's applied.
    var tfX by remember { mutableFloatStateOf(0f) }
    var tfY by remember { mutableFloatStateOf(0f) }
    var tfScale by remember { mutableFloatStateOf(1f) }
    var tfTurn by remember { mutableFloatStateOf(0f) }

    fun updateSelectionTint() {
        selectionTint?.recycle()
        val mask = p.selection
        selectionTint = if (mask == null) null else {
            val tint = IntArray(mask.size) { if (mask[it] != 0.toByte()) 0x442962FF else 0 }
            Bitmap.createBitmap(tint, p.width, p.height, Bitmap.Config.ARGB_8888)
        }
    }
    fun remember(c: Int) {
        recent.remove(c); recent.add(0, c)
        if (recent.size > 12) recent.removeAt(recent.lastIndex)
    }
    fun setColour(c: Int) { colour = c; remember(c) }
    fun paintingAllowed(): Boolean {
        if (p.canPaint) return true
        Toast.makeText(context, "That's a group. Pick a layer inside it to paint.", Toast.LENGTH_SHORT).show()
        return false
    }
    fun beginTransform() {
        if (!paintingAllowed()) return
        if (lifted != null) return
        val a = p.active
        val mask = p.selection
        val whole = a.pixels
        val float = IntArray(whole.size)
        val rest = whole.copyOf()
        for (i in whole.indices) if (mask == null || mask[i] != 0.toByte()) { float[i] = whole[i]; rest[i] = 0 }
        liftedOriginal = whole
        a.pixels = rest
        lifted = Bitmap.createBitmap(float, p.width, p.height, Bitmap.Config.ARGB_8888)
        tfX = 0f; tfY = 0f; tfScale = 1f; tfTurn = 0f
        refresh()
    }
    fun endTransform(apply: Boolean) {
        val original = liftedOriginal ?: return
        p.active.pixels = original
        lifted?.recycle(); lifted = null; liftedOriginal = null
        if (apply && (tfX != 0f || tfY != 0f || tfScale != 1f || tfTurn != 0f)) {
            p.transformActive(tfScale, tfScale, Math.toRadians(tfTurn.toDouble()).toFloat(), tfX, tfY)
            edited()
        }
        refresh()
    }
    fun leave() { endTransform(true); onClose() }
    BackHandler { leave() }
    fun chooseTool(t: Tool) {
        if (tool == Tool.TRANSFORM && t != Tool.TRANSFORM) endTransform(true)
        tool = t
        if (t == Tool.TRANSFORM) beginTransform()
    }
    fun undo() { endTransform(false); if (p.undo()) { edited(); updateSelectionTint(); refreshAll(); fit() } }
    fun redo() { endTransform(false); if (p.redo()) { edited(); updateSelectionTint(); refreshAll(); fit() } }
    /** Long work happens off the main thread, behind a "working" message so nothing can change meanwhile. */
    fun work(label: String, block: () -> Unit) {
        busy = label
        scope.launch {
            val r = withContext(Dispatchers.Default) { runCatching(block) }
            busy = null
            r.onFailure { Toast.makeText(context, "That didn't work: not enough memory, perhaps", Toast.LENGTH_LONG).show() }
            edited(); updateSelectionTint(); refreshAll()
        }
    }
    val title = path.substringAfterLast('/').removeSuffix(".ora")

    val exportPng = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) work("Saving picture") {
            val b = Bitmap.createBitmap(p.flattened(), p.width, p.height, Bitmap.Config.ARGB_8888)
            context.contentResolver.openOutputStream(uri, "wt")!!.use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            b.recycle()
        }
    }
    val exportJpeg = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        if (uri != null) work("Saving picture") {
            val b = Bitmap.createBitmap(p.flattened(), p.width, p.height, Bitmap.Config.ARGB_8888)
            context.contentResolver.openOutputStream(uri, "wt")!!.use { b.compress(Bitmap.CompressFormat.JPEG, 94, it) }
            b.recycle()
        }
    }
    val exportOra = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/openraster")) { uri ->
        if (uri != null) work("Saving layers") {
            context.contentResolver.openOutputStream(uri, "wt")!!.use { OraFile.save(p.frozen(), it) }
        }
    }
    val addPicture = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && p.layers.size < Painting.MAX_LAYERS) work("Adding picture") {
            val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            val picture = OraFile.fromPicture(bytes)
            p.change {
                val out = IntArray(p.width * p.height)
                for (y in 0 until minOf(picture.height, p.height)) System.arraycopy(picture.active.pixels, y * picture.width, out, y * p.width, minOf(picture.width, p.width))
                val l = Layer(p.nextId++, "Picture", out)
                p.insertLayer(l)
            }
        }
    }

    /** Lays a stroke along canvas points with the current brush, for lines and shape outlines. */
    fun strokeAlong(points: List<Offset>) {
        if (!paintingAllowed()) return
        val a = p.active
        val before = a.pixels.copyOf()
        val s = StrokePainter(a.pixels, p.width, p.height, brush.copy(stabiliser = 0f), colour, p.selection, a.alphaLock, symmetry)
        points.forEach { s.add(it.x, it.y) }
        p.recordPixels(a, s.changed, before)
        edited(); refresh(s.changed)
    }
    fun shapeOutline(a: Offset, b: Offset, ellipse: Boolean): List<Offset> = if (ellipse) (0..96).map { i ->
        val t = i / 96f * 2f * Math.PI.toFloat()
        Offset((a.x + b.x) / 2f + (b.x - a.x) / 2f * kotlin.math.cos(t), (a.y + b.y) / 2f + (b.y - a.y) / 2f * kotlin.math.sin(t))
    } else listOf(a, Offset(b.x, a.y), b, Offset(a.x, b.y), a)

    fun finishTool(t: Tool, a: Offset, b: Offset, trail: List<Offset>) {
        if (t !in listOf(Tool.PICK, Tool.SELECT_RECT, Tool.SELECT_ELLIPSE, Tool.LASSO, Tool.WAND, Tool.VIEW, Tool.GUIDES) && !paintingAllowed()) return
        val w = p.width; val h = p.height
        val tap = hypot(b.x - a.x, b.y - a.y) < 6f / view.zoom
        when (t) {
            Tool.PICK -> {
                val x = b.x.toInt(); val y = b.y.toInt()
                if (x in 0 until w && y in 0 until h) setColour(pixels[y * w + x] or (0xFF shl 24))
            }
            Tool.FILL -> {
                val x = b.x.toInt(); val y = b.y.toInt()
                if (x in 0 until w && y in 0 until h) {
                    val l = p.active
                    val before = l.pixels.copyOf()
                    val mask = Raster.floodMask(l.pixels, w, h, x, y, tolerance.toInt(), contiguous)
                    Raster.fill(l.pixels, mask, colour, Blend.NORMAL, 1f, p.selection, l.alphaLock)
                    p.recordPixels(l, Raster.bounds(mask, w, h) ?: Rect.EMPTY, before)
                    edited(); refresh()
                }
            }
            Tool.GRADIENT -> if (!tap) {
                val l = p.active
                val before = l.pixels.copyOf()
                Raster.gradient(l.pixels, w, h, a.x, a.y, b.x, b.y, colour, backColour, radial, Blend.NORMAL, 1f, p.selection, l.alphaLock)
                p.recordPixels(l, Rect(0, 0, w, h), before)
                edited(); refresh()
            }
            Tool.LINE -> if (!tap) strokeAlong(listOf(a, b))
            Tool.RECT, Tool.ELLIPSE -> if (!tap) {
                if (filled) {
                    val l = p.active
                    val before = l.pixels.copyOf()
                    val mask = if (t == Tool.RECT) Raster.rectMask(a.x.toInt(), a.y.toInt(), b.x.toInt(), b.y.toInt(), w, h) else Raster.ellipseMask(a.x.toInt(), a.y.toInt(), b.x.toInt(), b.y.toInt(), w, h)
                    Raster.fill(l.pixels, mask, colour, Blend.NORMAL, brush.opacity, p.selection, l.alphaLock)
                    p.recordPixels(l, Raster.bounds(mask, w, h) ?: Rect.EMPTY, before)
                    edited(); refresh()
                } else strokeAlong(shapeOutline(a, b, t == Tool.ELLIPSE))
            }
            Tool.SELECT_RECT, Tool.SELECT_ELLIPSE, Tool.LASSO, Tool.WAND -> {
                p.change {
                    p.selection = when {
                        t == Tool.WAND -> {
                            val x = b.x.toInt(); val y = b.y.toInt()
                            if (x in 0 until w && y in 0 until h) Raster.floodMask(p.active.pixels, w, h, x, y, tolerance.toInt(), contiguous) else null
                        }
                        tap && t != Tool.LASSO -> null
                        t == Tool.SELECT_RECT -> Raster.rectMask(a.x.toInt(), a.y.toInt(), b.x.toInt(), b.y.toInt(), w, h)
                        t == Tool.SELECT_ELLIPSE -> Raster.ellipseMask(a.x.toInt(), a.y.toInt(), b.x.toInt(), b.y.toInt(), w, h)
                        trail.size < 3 -> null
                        else -> Raster.polygonMask(FloatArray(trail.size) { trail[it].x }, FloatArray(trail.size) { trail[it].y }, w, h)
                    }
                }
                updateSelectionTint(); edited(); frame++
            }
            Tool.CROP -> if (!tap) {
                val r = Rect(minOf(a.x, b.x).toInt(), minOf(a.y, b.y).toInt(), maxOf(a.x, b.x).toInt(), maxOf(a.y, b.y).toInt()).clip(w, h)
                if (r.width > 1 && r.height > 1) {
                    p.resizeCanvas(r.width, r.height, -r.left, -r.top)
                    updateSelectionTint(); edited(); refreshAll(); fit()
                }
            }
            Tool.TEXT -> typingAt = b
            else -> Unit
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(librarySnackbar) },
        topBar = {
            if (mode != PaintMode.FOCUS) TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = { leave() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    val tick = version + frame
                    IconButton(enabled = p.canUndo && tick >= 0, onClick = ::undo) { Icon(androidx.compose.ui.res.painterResource(io.github.shoalapps.squill.R.drawable.ic_undo), contentDescription = "Undo") }
                    IconButton(enabled = p.canRedo && tick >= 0, onClick = ::redo) { Icon(androidx.compose.ui.res.painterResource(io.github.shoalapps.squill.R.drawable.ic_redo), contentDescription = "Redo") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                        DropdownMenu(menu, { menu = false }) {
                            PaintMode.entries.forEach { m ->
                                DropdownMenuItem({ Text(if (m == mode) "${m.label} mode ✓" else "${m.label} mode") }, onClick = {
                                    menu = false; mode = m; prefs.edit().putString(MODE_PREF, m.name).apply()
                                    if (m == PaintMode.SIMPLE) chooseTool(Tool.BRUSH)
                                })
                            }
                            DropdownMenuItem({ Text(if (penOnly) "Fingers paint too" else "Only the pen paints") }, onClick = { menu = false; penOnly = !penOnly })
                            DropdownMenuItem({ Text("Fit on screen") }, onClick = { menu = false; fit() })
                            DropdownMenuItem({ Text("Save as PNG") }, onClick = { menu = false; runCatching { exportPng.launch("$title.png") } })
                            DropdownMenuItem({ Text("Rename") }, onClick = { menu = false; renaming = true })
                            DropdownMenuItem({ Text("Delete") }, onClick = { menu = false; vm.trash(path) { savedVersion = version; onClose() } })
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (mode != PaintMode.FOCUS) Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.navigationBarsPadding().padding(vertical = 4.dp)) {
                    if (tool == Tool.TRANSFORM) TransformBar(tfScale, tfTurn, { tfScale = it; frame++ }, { tfTurn = it; frame++ }, { endTransform(true); tool = Tool.BRUSH }, { endTransform(false); tool = Tool.BRUSH })
                    if (selectionTint != null && mode == PaintMode.ADVANCED) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
                        TextButton(onClick = { p.change { p.selection = null }; updateSelectionTint(); edited(); frame++ }) { Text("Deselect") }
                        TextButton(onClick = { p.invertSelection(); updateSelectionTint(); edited(); frame++ }) { Text("Invert") }
                        TextButton(onClick = { if (paintingAllowed()) work("Copying") { p.copySelectionToLayer() } }) { Text("Copy to layer") }
                        TextButton(onClick = { if (paintingAllowed()) work("Cutting") { p.copySelectionToLayer(cut = true) } }) { Text("Cut to layer") }
                        TextButton(onClick = { if (paintingAllowed()) { p.clearActive(); edited(); refresh() } }) { Text("Clear") }
                    }
                    if (mode == PaintMode.ADVANCED) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Tool.entries.forEach { t -> FilterChip(tool == t, { chooseTool(t) }, label = { Text(t.label) }, modifier = Modifier.semantics { contentDescription = "${t.label} tool" }) }
                        }
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { endTransform(true); sheet = Sheet.LAYERS }) { Text("Layers") }
                            TextButton(onClick = { endTransform(true); sheet = Sheet.GUIDES }, modifier = Modifier.semantics { contentDescription = "Open guides" }) { Text("Guides") }
                            TextButton(onClick = { sheet = Sheet.BRUSH }, modifier = Modifier.semantics { contentDescription = "Open brush settings" }) { Text("Brush") }
                            TextButton(onClick = { sheet = Sheet.FILTERS }) { Text("Filters") }
                            TextButton(onClick = { sheet = Sheet.IMAGE }) { Text("Image") }
                            when (tool) {
                                Tool.FILL, Tool.WAND -> {
                                    Text("Tolerance", style = MaterialTheme.typography.labelSmall)
                                    Slider(tolerance, { tolerance = it }, valueRange = 0f..128f, modifier = Modifier.width(120.dp))
                                    FilterChip(contiguous, { contiguous = !contiguous }, label = { Text("Joined only") })
                                }
                                Tool.GRADIENT -> FilterChip(radial, { radial = !radial }, label = { Text("Radial") })
                                Tool.RECT, Tool.ELLIPSE -> FilterChip(filled, { filled = !filled }, label = { Text("Filled") })
                                else -> Unit
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val names = if (mode == PaintMode.SIMPLE) BrushPresets.simple else library.orderedBrushes.map { it.name }
                        names.forEach { n ->
                            FilterChip(brush.name == n && tool == Tool.BRUSH, {
                                selectBrush(n)
                                chooseTool(Tool.BRUSH)
                            }, label = { Text(n) })
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(Color(colour)).border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                .clickable { sheet = Sheet.COLOUR }.semantics { contentDescription = "Choose colour" },
                        )
                        if (mode == PaintMode.ADVANCED) Box(
                            Modifier.size(24.dp).clip(CircleShape).background(Color(backColour)).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                .clickable { val c = colour; colour = backColour; backColour = c }.semantics { contentDescription = "Swap colours" },
                        )
                        Text("${brush.size.toInt()}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(26.dp))
                        Slider(
                            kotlin.math.sqrt(brush.size), { v -> brush = brush.copy(size = (v * v).coerceIn(1f, 600f)) }, valueRange = 1f..24.5f,
                            modifier = Modifier.weight(1f).semantics { contentDescription = "Brush size" },
                        )
                        if (mode == PaintMode.SIMPLE) TextButton(onClick = { endTransform(true); sheet = Sheet.LAYERS }) { Text("Layers") }
                    }
                }
            }
        },
    ) { padding ->
        Box(
            Modifier.padding(padding).fillMaxSize().clipToBounds().background(Color(0xFF3A3A3F))
                .semantics { contentDescription = "Painting canvas, ${p.width} by ${p.height}, ${p.layers.size} layers, version $version" }
                .onSized { size -> viewSize = size; if (!fitted && size.width > 0) { fitted = true; fit() } },
        ) {
            var route by remember { mutableStateOf(Route.STROKE) }
            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(p) {
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent(PointerEventPass.Initial)
                                val c = e.changes.firstOrNull() ?: continue
                                if (e.type == PointerEventType.Scroll) {
                                    val factor = if (c.scrollDelta.y < 0) 1.12f else .89f
                                    val before = view.toCanvas(c.position.x, c.position.y)
                                    view.zoom = (view.zoom * factor).coerceIn(.05f, 32f)
                                    view.update()
                                    val after = view.toScreen(before.x, before.y)
                                    view.panX += c.position.x - after.x; view.panY += c.position.y - after.y
                                    view.update(); frame++
                                    c.consume()
                                }
                            }
                        }
                    }
                    .pointerInput(p) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val first = currentEvent
                            val isPen = down.type == PointerType.Stylus || down.type == PointerType.Eraser
                            if (isPen && !penSeen) { penSeen = true; penOnly = true }
                            val eraserEnd = down.type == PointerType.Eraser || first.motionEvent?.let { it.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0 } == true
                            route = when {
                                tool == Tool.VIEW || (down.type == PointerType.Touch && penOnly) -> Route.VIEW
                                eraserEnd || tool == Tool.BRUSH -> Route.STROKE
                                else -> Route.TOOL
                            }

                            suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.panLoop() {
                                var lastSpan = 0f; var lastAngle = 0f; var lastCx = Float.NaN; var lastCy = Float.NaN
                                while (true) {
                                    val e = awaitPointerEvent()
                                    val live = e.changes.filter { it.pressed }
                                    if (live.isEmpty()) break
                                    val cx = live.map { it.position.x }.average().toFloat()
                                    val cy = live.map { it.position.y }.average().toFloat()
                                    if (!lastCx.isNaN()) { view.panX += cx - lastCx; view.panY += cy - lastCy }
                                    lastCx = cx; lastCy = cy
                                    if (live.size >= 2) {
                                        val a = live[0].position; val b = live[1].position
                                        val span = hypot(b.x - a.x, b.y - a.y)
                                        val angle = Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat()
                                        if (lastSpan > 0f) {
                                            val anchor = view.toCanvas(cx, cy)
                                            view.zoom = (view.zoom * span / lastSpan).coerceIn(.05f, 32f)
                                            if (mode != PaintMode.SIMPLE) view.turn += angle - lastAngle
                                            view.update()
                                            val moved = view.toScreen(anchor.x, anchor.y)
                                            view.panX += cx - moved.x; view.panY += cy - moved.y
                                        }
                                        lastSpan = span; lastAngle = angle
                                    } else lastSpan = 0f
                                    view.update(); frame++
                                    e.changes.forEach { it.consume() }
                                }
                            }

                            if (route == Route.STROKE && !paintingAllowed()) return@awaitEachGesture
                            when (route) {
                                Route.VIEW -> panLoop()
                                Route.STROKE -> {
                                    val a = p.active
                                    val use = if (eraserEnd) BrushPresets.all.first { it.name == "Eraser" }.copy(size = brush.size) else brush
                                    val painter = StrokePainter(a.pixels, p.width, p.height, use, colour, p.selection, a.alphaLock, symmetry, seed = version + 11)
                                    // Only where they can be seen and switched off.
                                    val snap = if (mode == PaintMode.ADVANCED && p.assistantsOn && p.assistants.isNotEmpty()) AssistantSnap(p.assistants, 14.dp.toPx() / view.zoom) else null
                                    fun add(x: Float, y: Float, pressure: Float, tilt: Float = 0f, orientation: Float = 0f): Rect {
                                        if (snap == null) return painter.add(x, y, pressure, tilt, orientation)
                                        val points = snap.feed(x, y)
                                        var area = Rect.EMPTY
                                        for (i in points.indices step 2) area = area.union(painter.add(points[i], points[i + 1], pressure, tilt, orientation))
                                        return area
                                    }
                                    val start = view.toCanvas(down.position.x, down.position.y)
                                    refresh(add(start.x, start.y, pressureOf(down.pressure, down.type)))
                                    var last = start
                                    var cancelled = false
                                    while (true) {
                                        val e = awaitPointerEvent()
                                        if (e.changes.count { it.pressed } >= 2 && down.type == PointerType.Touch) {
                                            // A second finger means pinch, not paint: take the stroke back.
                                            System.arraycopy(painter.original, 0, a.pixels, 0, a.pixels.size)
                                            refresh(painter.changed)
                                            cancelled = true
                                            panLoop()
                                            break
                                        }
                                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!c.pressed) break
                                        val me = e.motionEvent
                                        var area = Rect.EMPTY
                                        if (me != null && me.pointerCount == 1) {
                                            // The hardware's in-between samples, for smooth lines and real pressure.
                                            val shiftX = c.position.x - me.x
                                            val shiftY = c.position.y - me.y
                                            for (h in 0 until me.historySize) {
                                                val q = view.toCanvas(me.getHistoricalX(h) + shiftX, me.getHistoricalY(h) + shiftY)
                                                area = area.union(add(q.x, q.y, pressureOf(me.getHistoricalPressure(h), c.type), me.getHistoricalAxisValue(MotionEvent.AXIS_TILT, h), me.getHistoricalOrientation(h)))
                                            }
                                            val q = view.toCanvas(me.x + shiftX, me.y + shiftY)
                                            area = area.union(add(q.x, q.y, pressureOf(me.pressure, c.type), me.getAxisValue(MotionEvent.AXIS_TILT), me.orientation))
                                            last = q
                                        } else {
                                            val q = view.toCanvas(c.position.x, c.position.y)
                                            area = add(q.x, q.y, pressureOf(c.pressure, c.type))
                                            last = q
                                        }
                                        refresh(area)
                                        c.consume()
                                    }
                                    if (!cancelled) {
                                        val end = snap?.end(last.x, last.y) ?: (last.x to last.y)
                                        refresh(painter.finish(end.first, end.second))
                                        p.recordPixels(a, painter.changed, painter.original)
                                        edited()
                                        if (use.kind == BrushKind.PAINT) remember(colour)
                                    }
                                }
                                Route.TOOL -> {
                                    val activeTool = tool
                                    val a = view.toCanvas(down.position.x, down.position.y)
                                    if (activeTool == Tool.GUIDES) {
                                        val handle = if (p.assistantsOn) p.assistants.flatMapIndexed { index, guide ->
                                            assistantHandles(guide).mapIndexed { i, point -> Triple(index, i, view.toScreen(point.x, point.y)) }
                                        }.minByOrNull { (_, _, point) -> (point - down.position).getDistance() }
                                            ?.takeIf { (_, _, point) -> (point - down.position).getDistance() <= 32.dp.toPx() } else null
                                        if (handle != null) {
                                            val limit = 20f * maxOf(p.width, p.height)
                                            var moved = false
                                            while (true) {
                                                val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                                val q = view.toCanvas(c.position.x, c.position.y)
                                                p.assistants = p.assistants.mapIndexed { index, guide ->
                                                    if (index == handle.first) guide.movedHandle(handle.second, q.x.coerceIn(-limit, limit), q.y.coerceIn(-limit, limit)) else guide
                                                }
                                                moved = true; frame++; c.consume()
                                                if (!c.pressed) break
                                            }
                                            if (moved) edited()
                                        }
                                    } else if (activeTool == Tool.TRANSFORM) {
                                        if (!paintingAllowed()) return@awaitEachGesture
                                        var lastScreen = a
                                        while (true) {
                                            val e = awaitPointerEvent()
                                            val c = e.changes.firstOrNull { it.id == down.id } ?: break
                                            if (!c.pressed) break
                                            val q = view.toCanvas(c.position.x, c.position.y)
                                            tfX += q.x - lastScreen.x; tfY += q.y - lastScreen.y
                                            lastScreen = q; frame++; c.consume()
                                        }
                                    } else {
                                        val trail = mutableListOf(a)
                                        var b = a
                                        preview = listOf(a, a)
                                        while (true) {
                                            val e = awaitPointerEvent()
                                            val c = e.changes.firstOrNull { it.id == down.id } ?: break
                                            if (!c.pressed) { b = view.toCanvas(c.position.x, c.position.y); break }
                                            b = view.toCanvas(c.position.x, c.position.y)
                                            if (activeTool == Tool.LASSO) { trail += b; preview = trail.toList() } else preview = listOf(a, b)
                                            c.consume()
                                        }
                                        preview = null
                                        finishTool(activeTool, a, b, trail)
                                    }
                                }
                            }
                        }
                    }
                    .then(if (mode == PaintMode.FOCUS) Modifier else Modifier),
            ) {
                frame.let { }
                view.update()
                drawIntoCanvas { canvas ->
                    val native = canvas.nativeCanvas
                    val paint = Paint().apply { isFilterBitmap = view.zoom < 2f }
                    native.drawBitmap(bitmap, view.matrix, paint)
                    native.save()
                    native.concat(view.matrix)
                    lifted?.let { l ->
                        native.save()
                        native.translate(p.width / 2f + tfX, p.height / 2f + tfY)
                        native.rotate(tfTurn)
                        native.scale(tfScale, tfScale)
                        native.translate(-p.width / 2f, -p.height / 2f)
                        native.drawBitmap(l, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                        native.restore()
                    }
                    selectionTint?.let { native.drawBitmap(it, 0f, 0f, null) }
                    native.restore()
                }
                preview?.let { pts ->
                    val colourLine = Color(0xFF2962FF)
                    fun s(o: Offset) = view.toScreen(o.x, o.y)
                    val t = tool
                    when {
                        t == Tool.LASSO -> pts.zipWithNext().forEach { (a, b) -> drawLine(colourLine, s(a), s(b), 2.dp.toPx()) }
                        t == Tool.LINE || t == Tool.GRADIENT -> drawLine(colourLine, s(pts[0]), s(pts[1]), 2.dp.toPx())
                        t == Tool.ELLIPSE || t == Tool.SELECT_ELLIPSE -> shapeOutline(pts[0], pts[1], true).zipWithNext().forEach { (a, b) -> drawLine(colourLine, s(a), s(b), 2.dp.toPx()) }
                        t == Tool.RECT || t == Tool.SELECT_RECT || t == Tool.CROP -> shapeOutline(pts[0], pts[1], false).zipWithNext().forEach { (a, b) -> drawLine(colourLine, s(a), s(b), 2.dp.toPx()) }
                    }
                }
                if (p.assistantsOn && mode == PaintMode.ADVANCED) {
                    val length = 50f * maxOf(p.width, p.height)
                    p.assistants.forEach { guide ->
                        assistantLines(guide, length).forEach { (a, b) ->
                            drawLine(Color(0x882962FF), view.toScreen(a.x, a.y), view.toScreen(b.x, b.y), 1.dp.toPx())
                        }
                        if (tool == Tool.GUIDES) assistantHandles(guide).forEach { h ->
                            drawCircle(Color(0x882962FF), 8.dp.toPx(), view.toScreen(h.x, h.y))
                        }
                    }
                }
                if (symmetry.mirrorX) drawLine(Color(0x882962FF), view.toScreen(p.width / 2f, 0f), view.toScreen(p.width / 2f, p.height.toFloat()), 1.dp.toPx())
                if (symmetry.mirrorY) drawLine(Color(0x882962FF), view.toScreen(0f, p.height / 2f), view.toScreen(p.width.toFloat(), p.height / 2f), 1.dp.toPx())
                drawCircle(Color.Transparent, 0f, style = DrawStroke(0f))
            }
            if (mode == PaintMode.FOCUS) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
                    TextButton(onClick = { mode = PaintMode.ADVANCED; prefs.edit().putString(MODE_PREF, mode.name).apply() }) { Text("Show tools") }
                }
            } else if (penSeen) Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape, modifier = Modifier.align(Alignment.TopCenter).padding(8.dp)) {
                Text(if (penOnly) "Pen paints, fingers move" else "Pen and fingers paint", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            }
            busy?.let { label ->
                Box(Modifier.fillMaxSize().background(Color(0x66000000)).pointerInput(Unit) { detectTapGestures { } }, contentAlignment = Alignment.Center) {
                    Surface(shape = MaterialTheme.shapes.medium) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.width(12.dp)); Text(label)
                        }
                    }
                }
            }
        }
    }

    sheet?.let { which ->
        ModalBottomSheet(onDismissRequest = { sheet = null }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                when (which) {
                    Sheet.GUIDES -> {
                        // Painting is mutable; observe edits and switch changes while the sheet is open.
                        (version + frame).let { }
                        Text("Guides", style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(p.assistantsOn, { p.assistantsOn = it; frame++ }, modifier = Modifier.semantics { contentDescription = "Use guides" })
                            Text("Use guides")
                        }
                        FlowRow {
                            listOf("Add ruler" to 0, "Add parallel" to -1, "Add 1-point" to 1, "Add 2-point" to 2, "Add 3-point" to 3).forEach { (label, kind) ->
                                TextButton(onClick = {
                                    p.assistants = p.assistants + defaultAssistant(kind, p.width, p.height)
                                    chooseTool(Tool.GUIDES); sheet = null; edited(); frame++
                                }, enabled = p.assistants.size < MAX_ASSISTANTS) { Text(label) }
                            }
                        }
                        p.assistants.forEachIndexed { index, guide ->
                            val name = assistantName(guide)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(name, Modifier.weight(1f))
                                TextButton(onClick = { p.assistants = p.assistants.filterIndexed { i, _ -> i != index }; edited(); frame++ },
                                    modifier = Modifier.semantics { contentDescription = "Delete $name ${index + 1}" }) { Text("Delete") }
                            }
                        }
                        Text("Drag the handles with the Guides tool. Brush strokes follow the guides.")
                    }
                    Sheet.COLOUR -> ColourSheet(colour, recent, { colour = it }, { setColour(it); sheet = null }, library, { library = it })
                    Sheet.BRUSH -> {
                        BrushLibraryControls(brush, library, { library = it; if (it.lastBrush != brush.name) brush = brush.copy(name = it.lastBrush) }, onDelete = {
                            val deleted = brush
                            val definition = library.customs.first { it.name == deleted.name }
                            val wasFavourite = deleted.name in library.favourites
                            library = library.delete(deleted.name); brush = library.brush(); sheet = null
                            scope.launch {
                                if (librarySnackbar.showSnackbar("Deleted ${deleted.name}", "Undo") == SnackbarResult.ActionPerformed) {
                                    if (library.customs.size < 100) {
                                        val restored = library.saveNew(definition, deleted.name)
                                        library = (if (wasFavourite) restored.toggleFavourite(restored.lastBrush) else restored)
                                            .used(deleted.copy(name = restored.lastBrush))
                                        brush = deleted.copy(name = restored.lastBrush)
                                    } else Toast.makeText(context, "Brush library is full", Toast.LENGTH_SHORT).show()
                                }
                            }
                        })
                        BrushSheet(brush, { brush = it }, symmetry, { symmetry = it })
                    }
                    Sheet.LAYERS -> LayerSheet(p, version + frame, advanced = mode != PaintMode.SIMPLE, onChanged = { edited(); refresh() })
                    Sheet.FILTERS -> FilterSheet { f, amount ->
                        sheet = null
                        if (paintingAllowed()) work(f.label) { p.filterActive(f, amount) }
                    }
                    Sheet.IMAGE -> ImageSheet(
                        p,
                        onDo = { label, block -> sheet = null; work(label) { block() }; scope.launch { delay(50); fit() } },
                        onExport = { kind ->
                            sheet = null
                            runCatching {
                                when (kind) {
                                    "png" -> exportPng.launch("$title.png")
                                    "jpg" -> exportJpeg.launch("$title.jpg")
                                    "ora" -> exportOra.launch("$title.ora")
                                    else -> addPicture.launch(arrayOf("image/*"))
                                }
                            }
                        },
                    )
                }
            }
        }
    }
    typingAt?.let { at ->
        var words by remember { mutableStateOf("") }
        var size by remember { mutableFloatStateOf(64f) }
        AlertDialog(
            onDismissRequest = { typingAt = null },
            title = { Text("Add text") },
            text = {
                Column {
                    OutlinedTextField(words, { words = it }, label = { Text("Text") }, modifier = Modifier.fillMaxWidth())
                    Text("Size ${size.toInt()}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                    Slider(size, { size = it }, valueRange = 12f..400f)
                }
            },
            confirmButton = {
                TextButton(enabled = words.isNotBlank() && p.layers.size < Painting.MAX_LAYERS, onClick = {
                    val text = words; val fontSize = size; val ink = colour
                    typingAt = null
                    if (!paintingAllowed()) return@TextButton
                    p.change {
                        val bmp = Bitmap.createBitmap(p.width, p.height, Bitmap.Config.ARGB_8888)
                        val c = android.graphics.Canvas(bmp)
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = fontSize }
                        text.split('\n').forEachIndexed { i, line -> c.drawText(line, at.x, at.y + i * fontSize * 1.2f, paint) }
                        val out = IntArray(p.width * p.height)
                        bmp.getPixels(out, 0, p.width, 0, 0, p.width, p.height)
                        bmp.recycle()
                        // Android hands these back premultiplied, and layers hold plain colour.
                        for (i in out.indices) { val v = out[i]; val al = alphaOf(v); if (al in 1..254) out[i] = (ink and 0xFFFFFF) or (al shl 24) }
                        val l = Layer(p.nextId++, text.take(20), out)
                        p.insertLayer(l)
                    }
                    edited(); refresh()
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { typingAt = null }) { Text("Cancel") } },
        )
    }
    if (renaming) {
        var name by remember { mutableStateOf(title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename painting") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    renaming = false
                    flush()
                    vm.moveFile(path, path.substringBeforeLast('/', ""), name, onPathChanged)
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
}

private fun pressureOf(raw: Float, type: PointerType) = if (type == PointerType.Touch || type == PointerType.Mouse) 1f else raw.coerceIn(0f, 1f)

private fun Modifier.onSized(block: (androidx.compose.ui.unit.IntSize) -> Unit) = onSizeChanged(block)

@Composable
private fun TransformBar(scale: Float, turn: Float, onScale: (Float) -> Unit, onTurn: (Float) -> Unit, onApply: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Size ${(scale * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(92.dp))
            Slider(scale, onScale, valueRange = .1f..4f, modifier = Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Turn ${turn.toInt()}°", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(92.dp))
            Slider(turn, onTurn, valueRange = -180f..180f, modifier = Modifier.weight(1f))
        }
        Row {
            Text("Drag on the picture to move it", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f).align(Alignment.CenterVertically))
            TextButton(onClick = onCancel) { Text("Cancel") }
            TextButton(onClick = onApply) { Text("Apply") }
        }
    }
}

@Composable
private fun ColourSheet(colour: Int, recent: List<Int>, onChange: (Int) -> Unit, onDone: (Int) -> Unit, library: BrushLibrary, onLibrary: (BrushLibrary) -> Unit) {
    val start = remember { rgbToHsv(colour) }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var sat by remember { mutableFloatStateOf(start[1]) }
    var value by remember { mutableFloatStateOf(start[2]) }
    var hex by remember { mutableStateOf("%06X".format(colour and 0xFFFFFF)) }
    fun pushed() { val c = hsvToRgb(hue, sat, value); hex = "%06X".format(c and 0xFFFFFF); onChange(c) }
    Text("Colour", style = MaterialTheme.typography.titleMedium)
    Canvas(
        Modifier.fillMaxWidth().aspectRatio(1.6f).padding(vertical = 8.dp).semantics { contentDescription = "Saturation and brightness" }
            .pointerInput(Unit) {
                val pick = { o: Offset -> sat = (o.x / size.width).coerceIn(0f, 1f); value = 1f - (o.y / size.height).coerceIn(0f, 1f); pushed() }
                detectTapGestures { pick(it) }
            }
            .pointerInput(Unit) {
                val pick = { o: Offset -> sat = (o.x / size.width).coerceIn(0f, 1f); value = 1f - (o.y / size.height).coerceIn(0f, 1f); pushed() }
                detectDragGestures(onDragStart = { pick(it) }) { c, _ -> c.consume(); pick(c.position) }
            },
    ) {
        drawRect(ComposeBrush.horizontalGradient(listOf(Color.White, Color(hsvToRgb(hue, 1f, 1f)))))
        drawRect(ComposeBrush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        drawCircle(Color.White, 10.dp.toPx(), Offset(sat * size.width, (1f - value) * size.height), style = DrawStroke(2.dp.toPx()))
    }
    Text("Hue", style = MaterialTheme.typography.labelMedium)
    Slider(hue, { hue = it; pushed() }, valueRange = 0f..360f)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            hex,
            { v ->
                val clean = v.removePrefix("#").take(6).uppercase().filter { it in "0123456789ABCDEF" }
                hex = clean
                if (clean.length == 6) { val c = clean.toInt(16) or (0xFF shl 24); val h = rgbToHsv(c); hue = h[0]; sat = h[1]; value = h[2]; onChange(c) }
            },
            label = { Text("Hex") }, singleLine = true, modifier = Modifier.width(140.dp),
        )
        Box(Modifier.size(48.dp).clip(CircleShape).background(Color(hsvToRgb(hue, sat, value))))
        TextButton(onClick = { onDone(hsvToRgb(hue, sat, value)) }) { Text("Done") }
    }
    Text("Palette", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
    PaletteControls(library, onLibrary, hsvToRgb(hue, sat, value), onDone)
    if (recent.isNotEmpty()) {
        Text("Recent", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            recent.forEach { c -> SwatchDot(c) { onDone(c) } }
        }
    }
}

@Composable
private fun LibraryNameDialog(title: String, initial: String = "", onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
    }, confirmButton = {
        TextButton(onClick = { onSave(name.trim()); onDismiss() }, enabled = name.isNotBlank()) { Text("Save") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun BrushLibraryControls(b: BrushSettings, library: BrushLibrary, onLibrary: (BrushLibrary) -> Unit, onDelete: () -> Unit) {
    var naming by remember { mutableStateOf<String?>(null) }
    val custom = library.customs.any { it.name == b.name }
    FlowRow {
        TextButton(onClick = { onLibrary(library.used(b).toggleFavourite(b.name)) },
            modifier = Modifier.semantics { contentDescription = "Favourite brush" }) {
            Text(if (b.name in library.favourites) "★" else "☆")
        }
        TextButton(onClick = { naming = "Save as new brush" }, enabled = library.customs.size < 100) { Text("Save as new brush") }
        if (custom) {
            TextButton(onClick = { onLibrary(library.update(b).used(b)) }) { Text("Update") }
            TextButton(onClick = { naming = "Rename brush" }) { Text("Rename") }
            TextButton(onClick = onDelete) { Text("Delete") }
        }
    }
    naming?.let { title ->
        LibraryNameDialog(title, if (title == "Rename brush") b.name else "", { naming = null }) { name ->
            onLibrary(if (title == "Rename brush") library.used(b).rename(b.name, name) else library.used(b).saveNew(b, name))
        }
    }
}

@Composable
private fun PaletteControls(library: BrushLibrary, onLibrary: (BrushLibrary) -> Unit, current: Int, onPick: (Int) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf<String?>(null) }
    val palette = library.palettes.firstOrNull { it.name == library.palette } ?: library.palettes.first()
    FlowRow {
        Box {
            TextButton(onClick = { menu = true }) { Text(palette.name) }
            DropdownMenu(menu, { menu = false }) {
                library.palettes.forEach { p -> DropdownMenuItem(text = { Text(p.name) }, onClick = { onLibrary(library.copy(palette = p.name)); menu = false }) }
            }
        }
        TextButton(onClick = { onLibrary(library.addColour(current)) }, enabled = palette.colours.size < 64 && current !in palette.colours) { Text("Add current colour") }
        TextButton(onClick = { naming = "New palette" }, enabled = library.palettes.size < 10) { Text("New palette") }
        TextButton(onClick = { naming = "Rename palette" }) { Text("Rename palette") }
        TextButton(onClick = { onLibrary(library.deletePalette()) }) { Text("Delete palette") }
        TextButton(onClick = { onLibrary(library.restorePalette()) }, enabled = library.palettes.size < 10 || library.palettes.any { it.name == BrushLibrary.defaultPalette.name }) { Text("Restore default palette") }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        palette.colours.forEach { c ->
            Box(Modifier.size(36.dp).clip(CircleShape).background(Color(c)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                .semantics { contentDescription = "Palette colour %08X; hold to remove".format(c) }
                .pointerInput(c, library) { detectTapGestures(onLongPress = { onLibrary(library.removeColour(c)) }, onTap = { onPick(c) }) })
        }
    }
    naming?.let { title -> LibraryNameDialog(title, if (title == "Rename palette") palette.name else "", { naming = null }) { name ->
        onLibrary(if (title == "New palette") library.newPalette(name) else library.renamePalette(name))
    } }
}

@Composable
private fun SwatchDot(c: Int, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).clip(CircleShape).background(Color(c)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape).clickable(onClick = onClick))
}

@Composable
private fun Labelled(label: String, value: Float, range: ClosedFloatingPointRange<Float>, shown: String, onFinished: () -> Unit = {}, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(96.dp))
        Slider(value, onChange, valueRange = range, onValueChangeFinished = onFinished, modifier = Modifier.weight(1f))
        Text(shown, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(44.dp))
    }
}

private fun pct(v: Float) = "${(v * 100).toInt()}%"

@Composable
private fun BrushSheet(b: BrushSettings, onChange: (BrushSettings) -> Unit, symmetry: Symmetry, onSymmetry: (Symmetry) -> Unit) {
    Text("Brush: ${b.name}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
    Labelled("Opacity", b.opacity, 0f..1f, pct(b.opacity)) { onChange(b.copy(opacity = it)) }
    Labelled("Flow", b.flow, .01f..1f, pct(b.flow)) { onChange(b.copy(flow = it)) }
    Labelled("Hardness", b.hardness, 0f..1f, pct(b.hardness)) { onChange(b.copy(hardness = it)) }
    Labelled("Spacing", b.spacing, .02f..1.5f, pct(b.spacing)) { onChange(b.copy(spacing = it)) }
    Labelled("Roundness", b.roundness, .05f..1f, pct(b.roundness)) { onChange(b.copy(roundness = it)) }
    Labelled("Angle", b.angle, 0f..180f, "${b.angle.toInt()}°") { onChange(b.copy(angle = it)) }
    Labelled("Scatter", b.scatter, 0f..3f, pct(b.scatter)) { onChange(b.copy(scatter = it)) }
    Labelled("Size jitter", b.sizeJitter, 0f..1f, pct(b.sizeJitter)) { onChange(b.copy(sizeJitter = it)) }
    Labelled("Opacity jitter", b.opacityJitter, 0f..1f, pct(b.opacityJitter)) { onChange(b.copy(opacityJitter = it)) }
    Labelled("Stabiliser", b.stabiliser, 0f..0.97f, pct(b.stabiliser)) { onChange(b.copy(stabiliser = it)) }
    if (b.kind == BrushKind.SMUDGE || b.kind == BrushKind.BLUR) Labelled("Strength", b.strength, 0f..1f, pct(b.strength)) { onChange(b.copy(strength = it)) }
    Text("Pen pressure changes", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(b.pressureSize, { onChange(b.copy(pressureSize = !b.pressureSize)) }, label = { Text("Size") })
        FilterChip(b.pressureOpacity, { onChange(b.copy(pressureOpacity = !b.pressureOpacity, pressureFlow = !b.pressureOpacity)) }, label = { Text("Opacity") })
        FilterChip(b.tiltAngle, { onChange(b.copy(tiltAngle = !b.tiltAngle)) }, label = { Text("Tilt turns tip") })
    }
    Text("Tip", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TipShape.entries.forEach { t -> FilterChip(b.tip == t, { onChange(b.copy(tip = t)) }, label = { Text(t.name.lowercase().replaceFirstChar { it.uppercase() }) }) }
        FilterChip(b.aliased, { onChange(b.copy(aliased = !b.aliased)) }, label = { Text("Pixel edges") })
    }
    Text("Paints with blend mode", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
    BlendPicker(b.blend) { onChange(b.copy(blend = it)) }
    Text("Symmetry", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(symmetry.mirrorX, { onSymmetry(symmetry.copy(mirrorX = !symmetry.mirrorX)) }, label = { Text("Mirror left-right") })
        FilterChip(symmetry.mirrorY, { onSymmetry(symmetry.copy(mirrorY = !symmetry.mirrorY)) }, label = { Text("Mirror up-down") })
    }
    Labelled("Radial", symmetry.radial.toFloat(), 1f..16f, "${symmetry.radial}") { onSymmetry(symmetry.copy(radial = it.toInt())) }
}

@Composable
private fun BlendPicker(current: Blend, onPick: (Blend) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Blend.entries.forEach { m -> FilterChip(current == m, { onPick(m) }, label = { Text(m.label) }) }
    }
}

@Composable
private fun LayerSheet(p: Painting, tick: Int, advanced: Boolean, onChanged: () -> Unit) {
    var local by remember { mutableIntStateOf(0) }
    fun done() { local++; onChanged() }
    val renderTick = tick + local
    var renaming by remember { mutableStateOf<Node?>(null) }
    val collapsed = remember { androidx.compose.runtime.mutableStateMapOf<Long, Boolean>() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Layers", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (renderTick >= 0) TextButton(enabled = p.layers.size < Painting.MAX_LAYERS, onClick = { p.newLayer(); done() }) { Text("Add layer") }
        if (advanced) TextButton(enabled = p.nodes.count { it is Group } < Painting.MAX_LAYERS, onClick = { p.newGroup(); done() }) { Text("Add group") }
    }
    // The tree is plain mutable data, so the tick is what makes these rows redraw.
    @Composable fun rows(group: Group, depth: Int, tick: Int) {
        group.children.asReversed().forEach { n ->
            val isActive = n.id == p.activeId
            Row(
                Modifier.fillMaxWidth().padding(start = (depth * 16).dp).clip(MaterialTheme.shapes.small).background(if (isActive) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .clickable { p.activeId = n.id; done() }.padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (n is Group) {
                    TextButton(onClick = { collapsed[n.id] = collapsed[n.id] != true }, modifier = Modifier.semantics { contentDescription = "${if (collapsed[n.id] == true) "Expand" else "Collapse"} ${n.name}" }) { Text(if (collapsed[n.id] == true) "+" else "−") }
                    Icon(androidx.compose.ui.res.painterResource(io.github.shoalapps.squill.R.drawable.ic_folder), contentDescription = null, modifier = Modifier.size(20.dp))
                }
                Switch(n.visible, { p.change { n.visible = it }; done() }, modifier = Modifier.semantics { contentDescription = "Show ${n.name}" })
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(n.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (n is Group) Text(if (n.children.size == 1) "1 item" else "${n.children.size} items", style = MaterialTheme.typography.labelSmall)
                    if (advanced) Text(
                        "${n.blend.label}, ${(n.opacity * 100).toInt()}%" + (if (n is Layer && n.alphaLock) ", locked" else "") + (if (n is Group && n.passThrough) ", pass through" else "") + (if (n.clipped) ", clipped" else ""),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isActive) TextButton(onClick = { renaming = n }) { Text("Rename") }
            }
            if (n is Group && collapsed[n.id] != true) rows(n, depth + 1, tick)
        }
    }
    rows(p.root, 0, renderTick)
    val a = p.activeNode
    // One undo step for the whole drag: one per tick would push out older history.
    var opacityBefore by remember { mutableStateOf<Float?>(null) }
    Labelled("Opacity", a.opacity, 0f..1f, pct(a.opacity), onFinished = {
        val from = opacityBefore; val to = a.opacity
        opacityBefore = null
        if (from != null && from != to) { a.opacity = from; p.change { a.opacity = to }; done() }
    }) { if (opacityBefore == null) opacityBefore = a.opacity; a.opacity = it; done() }
    if (advanced) {
        Text("Blend mode", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
        BlendPicker(a.blend) { blend -> p.change { a.blend = blend; if (blend != Blend.NORMAL && a is Group) a.passThrough = false }; done() }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (a is Layer) FilterChip(a.alphaLock, { p.change { a.alphaLock = !a.alphaLock }; done() }, label = { Text("Lock transparency") })
            if (a is Group) FilterChip(a.passThrough, { p.change { a.passThrough = !a.passThrough; if (a.passThrough) a.blend = Blend.NORMAL }; done() }, enabled = !a.clipped, label = { Text("Pass through") })
            FilterChip(a.clipped, { p.change { a.clipped = !a.clipped }; done() }, label = { Text("Clip to layer below") })
        }
    }
    FlowRow(Modifier.padding(top = 8.dp)) {
        TextButton(onClick = { p.moveActive(1); done() }) { Text("Move up") }
        TextButton(onClick = { p.moveActive(-1); done() }) { Text("Move down") }
        TextButton(enabled = p.canDuplicateActive, onClick = { p.duplicateActive(); done() }) { Text("Duplicate") }
        if (a is Group) {
            TextButton(onClick = { p.ungroup(); done() }) { Text("Ungroup") }
            TextButton(onClick = { p.mergeGroup(); done() }) { Text("Merge group") }
        } else {
            if (advanced) TextButton(onClick = { p.groupActive(); done() }) { Text("Group this") }
            TextButton(enabled = p.canMergeDown, onClick = { p.mergeDown(); done() }) { Text("Merge down") }
            TextButton(onClick = { p.clearActive(); done() }) { Text("Clear") }
        }
        TextButton(enabled = p.canDeleteActive, onClick = { p.deleteActive(); done() }) { Text("Delete") }
    }
    renaming?.let { l ->
        var name by remember(l.id) { mutableStateOf(l.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Name") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { p.change { l.name = name.trim() }; renaming = null; done() }) { Text("OK") } },
        )
    }
}

@Composable
private fun FilterSheet(onApply: (Filter, Float) -> Unit) {
    var picked by remember { mutableStateOf<Filter?>(null) }
    var amount by remember { mutableFloatStateOf(.5f) }
    Text("Filters", style = MaterialTheme.typography.titleMedium)
    Text("These change the current layer, or just what's selected. Undo takes them back.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Filter.entries.forEach { f ->
            FilterChip(picked == f, { picked = f; amount = if (f in TWO_WAY) 0f else .5f }, label = { Text(f.label) })
        }
    }
    picked?.let { f ->
        if (f.hasAmount) {
            val range = if (f in TWO_WAY) -1f..1f else 0f..1f
            Labelled("Amount", amount, range, "${(amount * 100).toInt()}") { amount = it }
        }
        TextButton(onClick = { onApply(f, amount) }) { Text("Apply ${f.label.lowercase()}") }
    }
}

private val TWO_WAY = setOf(Filter.BRIGHTNESS, Filter.CONTRAST, Filter.HUE, Filter.SATURATION, Filter.WARMTH)

@Composable
private fun ImageSheet(p: Painting, onDo: (String, () -> Unit) -> Unit, onExport: (String) -> Unit) {
    Text("Image", style = MaterialTheme.typography.titleMedium)
    Text("${p.width} × ${p.height} pixels", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(Modifier.padding(top = 8.dp)) {
        TextButton(onClick = { onDo("Flipping") { p.flip(true, true) } }) { Text("Flip left-right") }
        TextButton(onClick = { onDo("Flipping") { p.flip(false, true) } }) { Text("Flip up-down") }
        TextButton(onClick = { onDo("Turning") { p.rotate90(true) } }) { Text("Turn right") }
        TextButton(onClick = { onDo("Turning") { p.rotate90(false) } }) { Text("Turn left") }
        TextButton(onClick = { onDo("Flattening") { p.flatten() } }) { Text("Flatten layers") }
    }
    Text("Canvas size", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
    FlowRow {
        listOf(1080 to 1080, 1920 to 1080, 1080 to 1920, 2048 to 1536, 3000 to 2000, 4096 to 4096).forEach { (w, h) ->
            TextButton(onClick = { onDo("Resizing") { p.resizeCanvas(w, h, (w - p.width) / 2, (h - p.height) / 2) } }) { Text("$w × $h") }
        }
    }
    Text("Files", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
    FlowRow {
        TextButton(onClick = { onExport("add") }) { Text("Add a picture as a layer") }
        TextButton(onClick = { onExport("png") }) { Text("Save as PNG") }
        TextButton(onClick = { onExport("jpg") }) { Text("Save as JPEG") }
        TextButton(onClick = { onExport("ora") }) { Text("Save for Krita (.ora)") }
    }
    Spacer(Modifier.height(8.dp))
}
