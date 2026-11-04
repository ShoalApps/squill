@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.shoalapps.squill.notes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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

/** Every note as a dot, every link as a line. Drag and pinch to look around; tap a dot to open it. */
@Composable
fun GraphScreen(library: Library, focus: String?, onClearFocus: () -> Unit, onOpen: (GraphNode) -> Unit, onSettings: () -> Unit) {
    var showUnwritten by rememberSaveable { mutableStateOf(true) }
    val graph = remember(library.notes, showUnwritten, focus) {
        library.graph(showUnwritten).let { if (focus != null) it.around(focus) else it }
    }
    Scaffold(
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
            if (graph.nodes.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No notes to show yet") }
            } else {
                GraphCanvas(graph, onOpen, Modifier.fillMaxSize())
            }
        }
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
        while (moving > .05f) {
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
            .pointerInput(graph) {
                detectTransformGestures { centroid, pan, gesture, _ ->
                    val next = (zoom * gesture).coerceIn(.2f, 5f)
                    val middleX = size.width / 2f
                    val middleY = size.height / 2f
                    // Keeps what's under your fingers under your fingers.
                    panX = centroid.x - middleX - (centroid.x - middleX - panX) * (next / zoom) + pan.x
                    panY = centroid.y - middleY - (centroid.y - middleY - panY) * (next / zoom) + pan.y
                    zoom = next
                }
            }
            .pointerInput(graph) {
                detectTapGestures { tap ->
                    val px = (tap.x - size.width / 2f - panX) / zoom
                    val py = (tap.y - size.height / 2f - panY) / zoom
                    layout.nearest(px, py, 24.dp.toPx() / zoom)?.let { onOpen(graph.nodes[it]) }
                }
            },
    ) {
        frame.let { } // Redraw as the layout moves.
        val middle = Offset(size.width / 2f + panX, size.height / 2f + panY)
        fun at(i: Int) = Offset(middle.x + layout.x[i] * zoom, middle.y + layout.y[i] * zoom)
        graph.edges.forEach { (a, b) -> drawLine(c.outlineVariant, at(a), at(b), strokeWidth = 1.5.dp.toPx()) }
        val labels = graph.nodes.size <= 60 || zoom > 1.2f
        val labelWidth = 130.dp.roundToPx()
        graph.nodes.forEachIndexed { i, node ->
            val p = at(i)
            if (p.x < -200f || p.y < -60f || p.x > size.width + 200f || p.y > size.height + 60f) return@forEachIndexed
            val radius = (5f + 1.6f * node.links.coerceAtMost(12)).dp.toPx() * zoom.coerceIn(.6f, 1.6f)
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
