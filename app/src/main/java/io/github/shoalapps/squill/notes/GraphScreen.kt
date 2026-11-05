@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.shoalapps.squill.notes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Every note as a dot, every link as a line. Drag and pinch to look around; tap a dot to open it. */
@Composable
fun GraphScreen(
    library: Library, focus: String?, onClearFocus: () -> Unit,
    onLink: (String, String, (Boolean) -> Unit) -> Unit = { _, _, _ -> },
    onUnlink: (String, String) -> Unit = { _, _ -> },
    onOpen: (GraphNode) -> Unit, onSettings: () -> Unit,
) {
    val snackbars = remember { androidx.compose.material3.SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val lastLayout = remember { arrayOfNulls<GraphLayout>(1) }
    var showUnwritten by rememberSaveable { mutableStateOf(true) }
    val graph = remember(library.notes, showUnwritten, focus) {
        library.graph(showUnwritten).let { if (focus != null) it.around(focus) else it }
    }
    Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                title = { Text(focus?.substringAfterLast('/')?.removeSuffix(".md") ?: "Graph", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(showUnwritten, { showUnwritten = !showUnwritten }, label = { Text("Unwritten notes") })
                if (focus != null) FilterChip(true, onClearFocus, label = { Text("Just this note") })
            }
            if (graph.nodes.size > 1) Text("Drag one note onto another to link them", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
            if (graph.nodes.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No notes to show yet") }
            } else {
                GraphCanvas(graph, lastLayout, onOpen, onJoin = { from, to ->
                    // Dragging a note that isn't written yet links the other way round.
                    val (source, destination) = if (from.path != null) from to to else to to from
                    val path = source.path
                    if (path != null) {
                        val target = destination.path?.let { p -> destination.title.takeIf { library.resolver.resolve(it) == p } ?: p.removeSuffix(".md") } ?: destination.title
                        onLink(path, target) { changed ->
                            scope.launch {
                                snackbars.currentSnackbarData?.dismiss()
                                if (!changed) snackbars.showSnackbar("Those notes are already linked")
                                else if (snackbars.showSnackbar("Linked ${source.title} to ${destination.title}", actionLabel = "Undo") == androidx.compose.material3.SnackbarResult.ActionPerformed) onUnlink(path, target)
                            }
                        }
                    }
                }, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun GraphCanvas(graph: Graph, last: Array<GraphLayout?>, onOpen: (GraphNode) -> Unit, onJoin: (GraphNode, GraphNode) -> Unit, modifier: Modifier) {
    // Notes keep their places when the graph changes, so linking two doesn't scramble the rest.
    val layout = remember(graph) { GraphLayout(graph, from = last[0]).also { last[0] = it } }
    var frame by remember { mutableIntStateOf(0) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    // Everything stays in view until you move or zoom it yourself.
    var moved by remember { mutableStateOf(false) }
    var settle by remember { mutableIntStateOf(0) }
    var dropOn by remember { mutableIntStateOf(-1) }
    LaunchedEffect(layout, settle) {
        var moving = Float.MAX_VALUE
        while (moving > .05f || layout.held >= 0) {
            withFrameNanos { }
            // More steps per frame early on, so it opens up quickly. Big graphs get fewer.
            repeat(if (graph.nodes.size > 300) 1 else 2) { moving = layout.step() }
            frame++
        }
    }
    val c = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer(cacheSize = 256)
    val labelStyle = TextStyle(color = c.onSurface, fontSize = 12.sp)
    val mutedStyle = TextStyle(color = c.onSurfaceVariant, fontSize = 11.sp)
    Canvas(
        modifier
            .semantics { contentDescription = "Graph of ${graph.nodes.size} notes and ${graph.edges.size} links" }
            .pointerInput(layout) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val middleX = size.width / 2f
                    val middleY = size.height / 2f
                    fun worldX(x: Float) = (x - middleX - panX) / zoom
                    fun worldY(y: Float) = (y - middleY - panY) / zoom
                    val slop = viewConfiguration.touchSlop
                    val grabbed = layout.nearest(worldX(down.position.x), worldY(down.position.y), 28.dp.toPx() / zoom)
                    var dragging = false
                    var lastSpan = 0f
                    var lastX = down.position.x
                    var lastY = down.position.y
                    if (grabbed != null) { layout.held = grabbed; settle++ }
                    while (true) {
                        val e = awaitPointerEvent()
                        val live = e.changes.filter { it.pressed }
                        if (live.isEmpty()) {
                            if (grabbed != null) {
                                layout.held = -1
                                val target = dropOn
                                dropOn = -1
                                if (!dragging) onOpen(graph.nodes[grabbed])
                                else if (target >= 0) onJoin(graph.nodes[grabbed], graph.nodes[target])
                                layout.reheat(); settle++
                            }
                            break
                        }
                        if (live.size >= 2) {
                            // A second finger always means pinch, even if the first was on a note.
                            if (layout.held >= 0) { layout.held = -1; dropOn = -1; layout.reheat() }
                            val a = live[0].position; val b = live[1].position
                            val span = kotlin.math.hypot(b.x - a.x, b.y - a.y)
                            val cx = (a.x + b.x) / 2f; val cy = (a.y + b.y) / 2f
                            if (lastSpan > 0f) {
                                val next = (zoom * span / lastSpan).coerceIn(.05f, 5f)
                                panX = cx - middleX - (cx - middleX - panX) * (next / zoom) + cx - lastX
                                panY = cy - middleY - (cy - middleY - panY) * (next / zoom) + cy - lastY
                                zoom = next
                                moved = true
                            }
                            lastSpan = span; lastX = cx; lastY = cy
                        } else {
                            val p = live[0].position
                            if (!dragging && kotlin.math.hypot(p.x - down.position.x, p.y - down.position.y) > slop) dragging = true
                            if (dragging) {
                                if (layout.held >= 0) {
                                    val wx = worldX(p.x); val wy = worldY(p.y)
                                    layout.place(layout.held, wx, wy)
                                    dropOn = closestOther(layout, layout.held, wx, wy, 36.dp.toPx() / zoom)
                                    frame++
                                } else if (lastSpan == 0f) {
                                    panX += p.x - lastX; panY += p.y - lastY
                                    moved = true
                                }
                            }
                            lastSpan = 0f; lastX = p.x; lastY = p.y
                        }
                        e.changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        frame.let { } // Redraw as the layout moves.
        if (!moved && layout.held < 0) {
            val (z, x, y) = layout.fit(size.width, size.height, 56.dp.toPx())
            zoom = z; panX = x; panY = y
        }
        val middle = Offset(size.width / 2f + panX, size.height / 2f + panY)
        fun at(i: Int) = Offset(middle.x + layout.x[i] * zoom, middle.y + layout.y[i] * zoom)
        graph.edges.forEach { (a, b) -> drawLine(c.outlineVariant, at(a), at(b), strokeWidth = 1.5.dp.toPx()) }
        val labels = graph.nodes.size <= 60 || zoom > 1.2f
        val labelWidth = 130.dp.roundToPx()
        graph.nodes.forEachIndexed { i, node ->
            val p = at(i)
            if (p.x < -200f || p.y < -60f || p.x > size.width + 200f || p.y > size.height + 60f) return@forEachIndexed
            val radius = (5f + 1.6f * node.links.coerceAtMost(12)).dp.toPx() * zoom.coerceIn(.6f, 1.6f)
            if (i == dropOn) drawCircle(c.tertiary, radius + 10.dp.toPx(), p, style = Stroke(3.dp.toPx()))
            if (i == layout.held) drawCircle(c.primary.copy(alpha = .25f), radius + 14.dp.toPx(), p)
            if (node.path != null) drawCircle(c.primary, radius, p)
            else drawCircle(c.tertiary, radius, p, style = Stroke(2.dp.toPx()))
            if (labels) {
                val laid = measurer.measure(
                    node.title, if (node.path != null) labelStyle else mutedStyle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    constraints = Constraints(maxWidth = labelWidth),
                )
                drawText(laid, topLeft = Offset(p.x - laid.size.width / 2f, p.y + radius + 3.dp.toPx()))
            }
        }
    }
}

private fun closestOther(layout: GraphLayout, skip: Int, x: Float, y: Float, within: Float): Int {
    var best = -1
    var bestD = within * within
    for (i in layout.x.indices) {
        if (i == skip) continue
        val d = (layout.x[i] - x) * (layout.x[i] - x) + (layout.y[i] - y) * (layout.y[i] - y)
        if (d <= bestD) { best = i; bestD = d }
    }
    return best
}
