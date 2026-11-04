@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)

package io.github.shoalapps.squill.notes

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import java.util.Base64

private fun solid(c: Int) = Color(c.toLong() and 0xFFFFFF or 0xFF000000L)
private val Colours = listOf(0x1B1B1F, 0xFFFFFF, 0x4A4FA3, 0x0B7A75, 0x2E7D32, 0xE0A800, 0xE65100, 0xC62828, 0xAD1457)

/** One slide, drawn at whatever size it's given. [reveal] hides anything due on a later click. */
@Composable
fun SlideView(
    slide: Slide,
    modifier: Modifier = Modifier,
    reveal: Int = Int.MAX_VALUE,
    selected: Long? = null,
    onSelect: ((Long?) -> Unit)? = null,
    onEdit: ((Element) -> Unit)? = null,
    onMove: ((Long, Float, Float) -> Unit)? = null,
    onResize: ((Long, Float, Float) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
) {
    BoxWithConstraints(modifier.aspectRatio(SLIDE_W / SLIDE_H).clipToBounds().background(solid(slide.background))) {
        val unit = maxWidth.value / SLIDE_W
        val px = with(androidx.compose.ui.platform.LocalDensity.current) { maxWidth.toPx() } / SLIDE_W
        if (onSelect != null) Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onSelect(null) } })
        slide.elements.forEach { e ->
            if (e.step > reveal) return@forEach
            val interactive = onSelect != null
            val current by rememberUpdatedState(e)
            Box(
                Modifier.offset((e.x * unit).dp, (e.y * unit).dp).size((e.w * unit).dp.coerceAtLeast(if (interactive) 16.dp else 0.dp), (e.h * unit).dp.coerceAtLeast(if (interactive) 16.dp else 0.dp))
                    .then(
                        if (!interactive) Modifier else Modifier
                            .pointerInput(e.id) {
                                detectTapGestures(
                                    onTap = { onSelect?.invoke(current.id) },
                                    onDoubleTap = { onEdit?.invoke(current) },
                                )
                            }
                            .pointerInput(e.id) {
                                detectDragGestures(
                                    onDragStart = { onSelect?.invoke(current.id) },
                                    onDragEnd = { onDragEnd?.invoke() },
                                ) { change, drag ->
                                    change.consume()
                                    onMove?.invoke(current.id, drag.x / px, drag.y / px)
                                }
                            },
                    ),
            ) { ElementView(e, unit) }
            if (selected == e.id) {
                Box(
                    Modifier.offset((e.x * unit).dp, (e.y * unit).dp).size((e.w * unit).dp.coerceAtLeast(16.dp), (e.h * unit).dp.coerceAtLeast(16.dp))
                        .border(2.dp, Color(0xFF2962FF)),
                )
                // The handle is large to touch, whatever size the slide is on screen.
                Box(
                    Modifier.offset(((e.x + e.w) * unit).dp - 16.dp, ((e.y + e.h) * unit).dp - 16.dp).size(32.dp)
                        .semantics { contentDescription = "Resize" }
                        .pointerInput(e.id) {
                            detectDragGestures(onDragEnd = { onDragEnd?.invoke() }) { change, drag ->
                                change.consume()
                                onResize?.invoke(current.id, drag.x / px, drag.y / px)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.size(14.dp).clip(CircleShape).background(Color(0xFF2962FF))) }
            }
        }
    }
}

@Composable
private fun ElementView(e: Element, unit: Float) {
    when (e.kind) {
        "image" -> {
            val bitmap = remember(e.image) {
                runCatching { Base64.getDecoder().decode(e.image.orEmpty()).let { BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull()
            }
            if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        "line" -> Canvas(Modifier.fillMaxSize()) {
            drawLine(solid(e.colour), Offset.Zero, Offset(size.width, size.height), (e.size / 4f * unit).dp.toPx().coerceAtLeast(1f))
        }
        else -> {
            if (e.kind == "rect" || e.kind == "ellipse") Canvas(Modifier.fillMaxSize()) {
                val line = Stroke(2.dp.toPx() * unit.coerceAtLeast(.3f))
                if (e.kind == "rect") {
                    if (e.fill >= 0) drawRect(solid(e.fill))
                    drawRect(solid(e.colour), style = line)
                } else {
                    if (e.fill >= 0) drawOval(solid(e.fill))
                    drawOval(solid(e.colour), style = line)
                }
            } else if (e.fill >= 0) Box(Modifier.fillMaxSize().background(solid(e.fill)))
            if (e.text.isNotEmpty()) Text(
                e.text,
                color = solid(e.colour),
                fontSize = (e.size * unit).sp,
                lineHeight = (e.size * unit * 1.2f).sp,
                fontWeight = if (e.bold) FontWeight.Bold else FontWeight.Normal,
                fontStyle = if (e.italic) FontStyle.Italic else FontStyle.Normal,
                textAlign = when (e.align) { "center" -> TextAlign.Center; "right" -> TextAlign.End; else -> TextAlign.Start },
                modifier = Modifier.fillMaxWidth().padding((6f * unit).dp),
            )
        }
    }
}

/** Slides you can really edit on a phone: drag, resize, restyle, and present with step-by-step reveals. */
@Composable
fun DeckScreen(path: String, library: Library, vm: NotesViewModel, onClose: () -> Unit, onPathChanged: (String) -> Unit) {
    val context = LocalContext.current
    val start = remember(path) { library.decks.firstOrNull { it.path == path }?.deck ?: Deck() }
    var deck by remember(path) { mutableStateOf(start) }
    var version by remember(path) { mutableIntStateOf(0) }
    var savedVersion by remember(path) { mutableIntStateOf(0) }
    val undo = remember(path) { ArrayDeque<Deck>() }
    val redo = remember(path) { ArrayDeque<Deck>() }
    var dragStart by remember { mutableStateOf<Deck?>(null) }
    var index by remember(path) { mutableIntStateOf(0) }
    var selected by remember(path) { mutableStateOf<Long?>(null) }
    var editing by remember { mutableStateOf<Element?>(null) }
    var notes by remember { mutableStateOf(false) }
    var presenting by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var nextId by remember(path) { mutableLongStateOf((deck.slides.flatMap { s -> s.elements.map { it.id } + s.id }.maxOrNull() ?: 0L) + 1) }
    val title = path.substringAfterLast('/').removeSuffix(".deck")
    val slide = deck.slides[index.coerceIn(deck.slides.indices)]
    val element = slide.elements.firstOrNull { it.id == selected }

    fun flush() { if (version != savedVersion) { savedVersion = version; vm.saveDeckNow(path, deck) } }
    fun apply(next: Deck, record: Boolean = true) {
        if (record) { undo.addLast(deck); if (undo.size > 100) undo.removeFirst(); redo.clear() }
        deck = next
        version++
    }
    fun changeSlide(record: Boolean = true, change: (Slide) -> Slide) {
        apply(deck.copy(slides = deck.slides.map { if (it.id == slide.id) change(it) else it }), record)
    }
    fun changeElement(id: Long, record: Boolean = true, change: (Element) -> Element) {
        changeSlide(record) { s -> s.copy(elements = s.elements.map { if (it.id == id) change(it) else it }) }
    }
    fun add(e: Element) {
        changeSlide { it.copy(elements = it.elements + e) }
        selected = e.id
    }
    fun newId() = nextId++
    LaunchedEffect(path) {
        snapshotFlow { version }.drop(1).debounce(800).collect { v -> savedVersion = v; vm.saveDeck(path, deck) }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, path) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) flush() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); flush() }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val result = runCatching {
                val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                if (bytes.size > 12 * 1024 * 1024) error("too big")
                val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) }
                if (b.outWidth <= 0) error("not a picture")
                val w = 600f
                val h = (w * b.outHeight / b.outWidth).coerceAtMost(SLIDE_H - 40f)
                add(Element(newId(), "image", 100f, 40f, w, h, image = Base64.getEncoder().encodeToString(bytes)))
            }
            if (result.isFailure) Toast.makeText(context, "That picture couldn't be added", Toast.LENGTH_LONG).show()
        }
    }
    val exportPptx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.presentationml.presentation")) { uri ->
        if (uri != null) {
            val r = runCatching { context.contentResolver.openOutputStream(uri, "wt")!!.use { Pptx.export(deck, it) } }
            Toast.makeText(context, if (r.isSuccess) "Saved. Reveal steps stay in Squill." else "Couldn't save that file", Toast.LENGTH_LONG).show()
        }
    }

    if (presenting) {
        Presenter(deck, index, onExit = { presenting = false; index = it })
        return
    }
    BackHandler { onClose() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    IconButton(enabled = undo.isNotEmpty() && version >= 0, onClick = {
                        undo.removeLastOrNull()?.let { redo.addLast(deck); deck = it; index = index.coerceIn(it.slides.indices); selected = null; version++ }
                    }) { Icon(androidx.compose.ui.res.painterResource(io.github.shoalapps.squill.R.drawable.ic_undo), contentDescription = "Undo") }
                    IconButton(enabled = redo.isNotEmpty() && version >= 0, onClick = {
                        redo.removeLastOrNull()?.let { undo.addLast(deck); deck = it; index = index.coerceIn(it.slides.indices); selected = null; version++ }
                    }) { Icon(androidx.compose.ui.res.painterResource(io.github.shoalapps.squill.R.drawable.ic_redo), contentDescription = "Redo") }
                    IconButton(onClick = { flush(); presenting = true }) { Icon(Icons.Default.PlayArrow, contentDescription = "Present") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Speaker notes") }, onClick = { menu = false; notes = true })
                            DropdownMenuItem({ Text("Save as PowerPoint") }, onClick = { menu = false; flush(); runCatching { exportPptx.launch("$title.pptx") } })
                            DropdownMenuItem({ Text("Rename") }, onClick = { menu = false; renaming = true })
                            DropdownMenuItem({ Text("Delete") }, onClick = { menu = false; flush(); vm.trash(path) { onClose() } })
                        }
                    }
                },
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.navigationBarsPadding().padding(vertical = 4.dp)) {
                    if (element != null) ElementBar(
                        element,
                        onEditText = { editing = element },
                        onChange = { c -> changeElement(element.id, change = c) },
                        onDelete = { val id = element.id; selected = null; changeSlide { s -> s.copy(elements = s.elements.filterNot { it.id == id }) } },
                        onDuplicate = { add(element.copy(id = newId(), x = element.x + 24f, y = element.y + 24f)) },
                        onToFront = { val id = element.id; changeSlide { s -> s.copy(elements = s.elements.filterNot { it.id == id } + element) } },
                    ) else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
                        TextButton(onClick = { add(Element(newId(), "text", 200f, 280f, 880f, 120f, "New text", size = 36f)) }) { Text("Text") }
                        TextButton(onClick = { add(Element(newId(), "rect", 440f, 220f, 400f, 280f, fill = 0xE1E0F9, colour = 0x4A4FA3, size = 28f)) }) { Text("Box") }
                        TextButton(onClick = { add(Element(newId(), "ellipse", 490f, 210f, 300f, 300f, fill = 0xCDEEE9, colour = 0x0B7A75, size = 28f)) }) { Text("Circle") }
                        TextButton(onClick = { add(Element(newId(), "line", 240f, 360f, 800f, 0f, colour = 0x1B1B1F, size = 12f)) }) { Text("Line") }
                        TextButton(onClick = { runCatching { pickImage.launch(arrayOf("image/png", "image/jpeg")) } }) { Text("Picture") }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        deck.slides.forEachIndexed { i, s ->
                            Box(
                                Modifier.width(88.dp).clip(RoundedCornerShape(6.dp))
                                    .border(if (i == index) 3.dp else 1.dp, if (i == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                                    .clickable { index = i; selected = null }.semantics { contentDescription = "Slide ${i + 1}" },
                            ) { SlideView(s, Modifier.fillMaxWidth()) }
                        }
                        TextButton(onClick = {
                            val fresh = Deck.contentSlide(newId()).let { s -> s.copy(elements = s.elements.map { it.copy(id = newId()) }) }
                            val at = index + 1
                            apply(deck.copy(slides = deck.slides.take(at) + fresh + deck.slides.drop(at)))
                            index = at; selected = null
                        }) { Text("Add slide") }
                        TextButton(onClick = {
                            val copy = slide.copy(id = newId(), elements = slide.elements.map { it.copy(id = newId()) })
                            val at = index + 1
                            apply(deck.copy(slides = deck.slides.take(at) + copy + deck.slides.drop(at)))
                            index = at; selected = null
                        }) { Text("Duplicate") }
                        if (deck.slides.size > 1) {
                            if (index > 0) TextButton(onClick = {
                                val list = deck.slides.toMutableList(); val s = list.removeAt(index); list.add(index - 1, s)
                                apply(deck.copy(slides = list)); index--
                            }) { Text("Earlier") }
                            if (index < deck.slides.lastIndex) TextButton(onClick = {
                                val list = deck.slides.toMutableList(); val s = list.removeAt(index); list.add(index + 1, s)
                                apply(deck.copy(slides = list)); index++
                            }) { Text("Later") }
                            TextButton(onClick = {
                                val at = index
                                apply(deck.copy(slides = deck.slides.filterIndexed { i, _ -> i != at }))
                                index = at.coerceAtMost(deck.slides.lastIndex); selected = null
                            }) { Text("Remove slide") }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(12.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SlideView(
                slide,
                Modifier.fillMaxWidth().semantics { contentDescription = "Slide ${index + 1} of ${deck.slides.size}, ${slide.elements.size} items" },
                selected = selected,
                onSelect = { selected = it },
                onEdit = { if (it.kind != "image" && it.kind != "line") editing = it },
                onMove = { id, dx, dy ->
                    if (dragStart == null) dragStart = deck
                    changeElement(id, record = false) { it.copy(x = (it.x + dx).coerceIn(-it.w + 20f, SLIDE_W - 20f), y = (it.y + dy).coerceIn(-it.h + 20f, SLIDE_H - 20f)) }
                },
                onResize = { id, dx, dy ->
                    if (dragStart == null) dragStart = deck
                    changeElement(id, record = false) { it.copy(w = (it.w + dx).coerceAtLeast(if (it.kind == "line") 0f else 40f), h = (it.h + dy).coerceAtLeast(if (it.kind == "line") 0f else 30f)) }
                },
                onDragEnd = { dragStart?.let { undo.addLast(it); redo.clear(); version++ }; dragStart = null },
            )
            Text(
                if (slide.elements.isEmpty()) "Add something below" else "Tap to select, drag to move, double-tap to change the words",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
            )
        }
    }

    editing?.let { e ->
        var words by remember(e.id) { mutableStateOf(e.text) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Words") },
            text = { OutlinedTextField(words, { words = it }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Slide text" }) },
            confirmButton = { TextButton(onClick = { changeElement(e.id) { it.copy(text = words) }; editing = null }) { Text("Done") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
    if (notes) {
        var words by remember(slide.id) { mutableStateOf(slide.notes) }
        AlertDialog(
            onDismissRequest = { notes = false },
            title = { Text("Speaker notes") },
            text = { OutlinedTextField(words, { words = it }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { changeSlide { it.copy(notes = words) }; notes = false }) { Text("Done") } },
        )
    }
    if (renaming) {
        var name by remember { mutableStateOf(title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename presentation") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    renaming = false; flush()
                    vm.moveDeck(path, path.substringBeforeLast('/', ""), name, onPathChanged)
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ElementBar(e: Element, onEditText: () -> Unit, onChange: ((Element) -> Element) -> Unit, onDelete: () -> Unit, onDuplicate: () -> Unit, onToFront: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val hasWords = e.kind == "text" || e.kind == "rect" || e.kind == "ellipse"
            if (hasWords) {
                TextButton(onClick = onEditText) { Text("Words") }
                TextButton(onClick = { onChange { it.copy(size = (it.size - 4f).coerceAtLeast(10f)) } }) { Text("A−") }
                TextButton(onClick = { onChange { it.copy(size = (it.size + 4f).coerceAtMost(200f)) } }) { Text("A+") }
                TextButton(onClick = { onChange { it.copy(bold = !it.bold) } }) { Text("B", fontWeight = if (e.bold) FontWeight.Black else FontWeight.Normal) }
                TextButton(onClick = { onChange { it.copy(italic = !it.italic) } }) { Text("I", fontStyle = FontStyle.Italic) }
                TextButton(onClick = { onChange { it.copy(align = when (it.align) { "left" -> "center"; "center" -> "right"; else -> "left" }) } }) {
                    Text(when (e.align) { "center" -> "Centre"; "right" -> "Right"; else -> "Left" })
                }
            }
            TextButton(onClick = { onChange { it.copy(step = (it.step + 1) % 6) } }, modifier = Modifier.semantics { contentDescription = "Reveal step" }) {
                Text(if (e.step == 0) "Always shown" else "Click ${e.step}")
            }
            TextButton(onClick = onDuplicate) { Text("Duplicate") }
            TextButton(onClick = onToFront) { Text("To front") }
            TextButton(onClick = onDelete) { Text("Delete") }
        }
        if (e.kind != "image") Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Colour", style = MaterialTheme.typography.labelSmall)
            Colours.forEach { c -> Swatch(c, e.colour == c) { onChange { it.copy(colour = c) } } }
            if (e.kind != "line") {
                Text("Fill", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 8.dp))
                TextButton(onClick = { onChange { it.copy(fill = -1) } }) { Text("None") }
                Colours.forEach { c -> Swatch(c, e.fill == c) { onChange { it.copy(fill = c) } } }
            }
        }
    }
}

@Composable
private fun Swatch(colour: Int, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(26.dp).clip(CircleShape).background(solid(colour))
            .border(if (on) 3.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else Color(0x55000000), CircleShape).clickable(onClick = onClick),
    )
}

/** The slides, full screen. Tap the right side to go on, the left to go back; reveals happen in order. */
@Composable
private fun Presenter(deck: Deck, start: Int, onExit: (Int) -> Unit) {
    var index by remember { mutableIntStateOf(start.coerceIn(deck.slides.indices)) }
    var step by remember { mutableIntStateOf(0) }
    val slide = deck.slides[index]
    BackHandler { onExit(index) }
    val window = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(window) {
        window.keepScreenOn = true
        onDispose { window.keepScreenOn = false }
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .semantics { contentDescription = "Presenting slide ${index + 1} of ${deck.slides.size}, step $step" }
            .pointerInput(deck) {
                detectTapGestures { tap ->
                    if (tap.x > size.width / 3f) {
                        when {
                            step < deck.slides[index].steps -> step++
                            index < deck.slides.lastIndex -> { index++; step = 0 }
                            else -> onExit(index)
                        }
                    } else when {
                        step > 0 -> step--
                        index > 0 -> { index--; step = deck.slides[index].steps }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(slide to step, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "slide") { (s, st) ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { SlideView(s, Modifier.fillMaxWidth(), reveal = st) }
        }
        if (slide.notes.isNotBlank()) Text(
            slide.notes, color = Color(0xAAFFFFFF), style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).verticalScroll(rememberScrollState()),
        )
    }
}
