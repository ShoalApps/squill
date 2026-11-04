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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
    SELECT_RECT("Select box"), SELECT_ELLIPSE("Select oval"), LASSO("Lasso"), WAND("Magic wand"), TRANSFORM("Transform"), TEXT("Text"), CROP("Crop"), VIEW("Move view"),
}

private enum class Sheet { LAYERS, BRUSH, COLOUR, FILTERS, IMAGE }

private enum class Route { STROKE, TOOL, VIEW }

private const val MODE_PREF = "paint-mode"
private val Swatches = listOf(0xFF000000, 0xFFFFFFFF, 0xFF5F5F66, 0xFFC62828, 0xFFE65100, 0xFFF9A825, 0xFF2E7D32, 0xFF0B7A75, 0xFF1565C0, 0xFF4A4FA3, 0xFF6A1B9A, 0xFFAD1457, 0xFF6D4C41, 0xFFFFCC80).map { it.toInt() }

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
    var painting by remember(path) { mutableStateOf<Painting?>(null) }
    var failed by remember(path) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(path) {
        val loaded = withContext(Dispatchers.IO) { runCatching { OraFile.load(vm.vault.readBytes(path)) } }
        loaded.onSuccess { painting = it }.onFailure { failed = it.message ?: "That painting couldn't be opened" }
    }
    BackHandler(painting == null) { onClose() }
    val p = painting
    if (p == null) {
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
    fun flush() { if (version != savedVersion) { savedVersion = version; vm.savePainting(path, p.frozen()) } }
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
    BackHandler { onClose() }

    var tool by remember { mutableStateOf(Tool.BRUSH) }
    var brush by remember { mutableStateOf(BrushPresets.all.first()) }
    var colour by remember { mutableIntStateOf(0xFF000000.toInt()) }
    var backColour by remember { mutableIntStateOf(0xFFFFFFFF.toInt()) }
    val recent = remember { mutableStateListOf<Int>() }
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
    var lifted by remember { mutableStateOf<Bitmap?>(null) }
    var liftedOriginal by remember { mutableStateOf<IntArray?>(null) }
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
    fun beginTransform() {
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
        if (uri != null) work("Adding picture") {
            val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            val picture = OraFile.fromPicture(bytes)
            p.change {
                val out = IntArray(p.width * p.height)
                for (y in 0 until minOf(picture.height, p.height)) System.arraycopy(picture.active.pixels, y * picture.width, out, y * p.width, minOf(picture.width, p.width))
                val l = Layer(p.nextId++, "Picture", out)
                p.layers.add(p.layers.indexOf(p.active) + 1, l)
                p.activeId = l.id
            }
        }
    }

    /** Lays a stroke along canvas points with the current brush, for lines and shape outlines. */
    fun strokeAlong(points: List<Offset>) {
        val a = p.active
        val before = a.pixels.copyOf()
        val under = p.layers.getOrNull(p.layers.indexOf(a) - 1)?.takeIf { a.clipped }?.pixels
        val s = StrokePainter(a.pixels, p.width, p.height, brush.copy(stabiliser = 0f), colour, p.selection, a.alphaLock, symmetry, under)
        points.forEach { s.add(it.x, it.y) }
        p.recordPixels(a, s.changed, before)
        edited(); refresh(s.changed)
    }
    fun shapeOutline(a: Offset, b: Offset, ellipse: Boolean): List<Offset> = if (ellipse) (0..96).map { i ->
        val t = i / 96f * 2f * Math.PI.toFloat()
        Offset((a.x + b.x) / 2f + (b.x - a.x) / 2f * kotlin.math.cos(t), (a.y + b.y) / 2f + (b.y - a.y) / 2f * kotlin.math.sin(t))
    } else listOf(a, Offset(b.x, a.y), b, Offset(a.x, b.y), a)

    fun finishTool(t: Tool, a: Offset, b: Offset, trail: List<Offset>) {
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
        topBar = {
            if (mode != PaintMode.FOCUS) TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
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
                        TextButton(onClick = { work("Copying") { p.copySelectionToLayer() } }) { Text("Copy to layer") }
                        TextButton(onClick = { work("Cutting") { p.copySelectionToLayer(cut = true) } }) { Text("Cut to layer") }
                        TextButton(onClick = { p.clearActive(); edited(); refresh() }) { Text("Clear") }
                    }
                    if (mode == PaintMode.ADVANCED) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Tool.entries.forEach { t -> FilterChip(tool == t, { chooseTool(t) }, label = { Text(t.label) }) }
                        }
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { sheet = Sheet.LAYERS }) { Text("Layers") }
                            TextButton(onClick = { sheet = Sheet.BRUSH }) { Text("Brush") }
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
                        val names = if (mode == PaintMode.SIMPLE) BrushPresets.simple else BrushPresets.all.map { it.name }
                        names.forEach { n ->
                            FilterChip(brush.name == n && tool == Tool.BRUSH, {
                                brush = BrushPresets.all.first { it.name == n }
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
                        if (mode == PaintMode.SIMPLE) TextButton(onClick = { sheet = Sheet.LAYERS }) { Text("Layers") }
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

                            when (route) {
                                Route.VIEW -> panLoop()
                                Route.STROKE -> {
                                    val a = p.active
                                    val use = if (eraserEnd) BrushPresets.all.first { it.name == "Eraser" }.copy(size = brush.size) else brush
                                    val under = p.layers.getOrNull(p.layers.indexOf(a) - 1)?.takeIf { a.clipped }?.pixels
                                    val painter = StrokePainter(a.pixels, p.width, p.height, use, colour, p.selection, a.alphaLock, symmetry, under, seed = version + 11)
                                    val start = view.toCanvas(down.position.x, down.position.y)
                                    refresh(painter.add(start.x, start.y, pressureOf(down.pressure, down.type)))
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
                                                area = area.union(painter.add(q.x, q.y, pressureOf(me.getHistoricalPressure(h), c.type), me.getHistoricalAxisValue(MotionEvent.AXIS_TILT, h), me.getHistoricalOrientation(h)))
                                            }
                                            val q = view.toCanvas(me.x + shiftX, me.y + shiftY)
                                            area = area.union(painter.add(q.x, q.y, pressureOf(me.pressure, c.type), me.getAxisValue(MotionEvent.AXIS_TILT), me.orientation))
                                            last = q
                                        } else {
                                            val q = view.toCanvas(c.position.x, c.position.y)
                                            area = painter.add(q.x, q.y, pressureOf(c.pressure, c.type))
                                            last = q
                                        }
                                        refresh(area)
                                        c.consume()
                                    }
                                    if (!cancelled) {
                                        refresh(painter.finish(last.x, last.y))
                                        p.recordPixels(a, painter.changed, painter.original)
                                        edited()
                                        if (use.kind == BrushKind.PAINT) remember(colour)
                                    }
                                }
                                Route.TOOL -> {
                                    val activeTool = tool
                                    val a = view.toCanvas(down.position.x, down.position.y)
                                    if (activeTool == Tool.TRANSFORM) {
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
                    Sheet.COLOUR -> ColourSheet(colour, recent, { colour = it }, { setColour(it); sheet = null })
                    Sheet.BRUSH -> BrushSheet(brush, { brush = it }, symmetry, { symmetry = it })
                    Sheet.LAYERS -> LayerSheet(p, version + frame, advanced = mode != PaintMode.SIMPLE, onChanged = { edited(); refresh() })
                    Sheet.FILTERS -> FilterSheet { f, amount ->
                        sheet = null
                        work(f.label) { p.filterActive(f, amount) }
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
                TextButton(enabled = words.isNotBlank(), onClick = {
                    val text = words; val fontSize = size; val ink = colour
                    typingAt = null
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
                        p.layers.add(p.layers.indexOf(p.active) + 1, l)
                        p.activeId = l.id
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
private fun ColourSheet(colour: Int, recent: List<Int>, onChange: (Int) -> Unit, onDone: (Int) -> Unit) {
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
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Swatches.forEach { c -> SwatchDot(c) { onDone(c) } }
    }
    if (recent.isNotEmpty()) {
        Text("Recent", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            recent.forEach { c -> SwatchDot(c) { onDone(c) } }
        }
    }
}

@Composable
private fun SwatchDot(c: Int, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).clip(CircleShape).background(Color(c)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape).clickable(onClick = onClick))
}

@Composable
private fun Labelled(label: String, value: Float, range: ClosedFloatingPointRange<Float>, shown: String, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(96.dp))
        Slider(value, onChange, valueRange = range, modifier = Modifier.weight(1f))
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
    var renaming by remember { mutableStateOf<Layer?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Layers", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (renderTick >= 0) TextButton(enabled = p.layers.size < Painting.MAX_LAYERS, onClick = { p.change { p.newLayer() }; done() }) { Text("Add layer") }
    }
    p.layers.asReversed().forEach { l ->
        val isActive = l.id == p.activeId
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(if (isActive) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .clickable { p.activeId = l.id; done() }.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(l.visible, { p.change { l.visible = it }; done() }, modifier = Modifier.semantics { contentDescription = "Show ${l.name}" })
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(l.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (advanced) Text(
                    "${l.blend.label}, ${(l.opacity * 100).toInt()}%" + (if (l.alphaLock) ", locked" else "") + (if (l.clipped) ", clipped" else ""),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isActive) TextButton(onClick = { renaming = l }) { Text("Rename") }
        }
    }
    val a = p.active
    Labelled("Opacity", a.opacity, 0f..1f, pct(a.opacity)) { a.opacity = it; done() }
    if (advanced) {
        Text("Blend mode", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
        BlendPicker(a.blend) { p.change { a.blend = it }; done() }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(a.alphaLock, { p.change { a.alphaLock = !a.alphaLock }; done() }, label = { Text("Lock transparency") })
            FilterChip(a.clipped, { p.change { a.clipped = !a.clipped }; done() }, label = { Text("Clip to layer below") })
        }
    }
    FlowRow(Modifier.padding(top = 8.dp)) {
        TextButton(onClick = { p.moveActive(1); done() }) { Text("Move up") }
        TextButton(onClick = { p.moveActive(-1); done() }) { Text("Move down") }
        TextButton(enabled = p.layers.size < Painting.MAX_LAYERS, onClick = { p.duplicateActive(); done() }) { Text("Duplicate") }
        TextButton(onClick = { p.mergeDown(); done() }) { Text("Merge down") }
        TextButton(onClick = { p.clearActive(); done() }) { Text("Clear") }
        TextButton(enabled = p.layers.size > 1, onClick = { p.deleteActive(); done() }) { Text("Delete") }
    }
    renaming?.let { l ->
        var name by remember(l.id) { mutableStateOf(l.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Layer name") },
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
