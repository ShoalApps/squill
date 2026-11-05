package io.github.shoalapps.squill.notes

import org.junit.Assert.*
import org.junit.Test

class SketchEditingTest {
    private val stroke = SketchStroke(Sketch.PEN, 0xFF123456.toInt(), 4f, listOf(0f, 0f, 0f, -1f, 100f, 0f, 12f, .8f))
    private val sketch = Sketch(Sketch.GRID, listOf(stroke, stroke.copy(colour = 42)), listOf(SketchText(2f, 3f, "Keep", 42)))

    @Test fun picksTopmostAndIncludesWidthAndReach() {
        assertEquals(1, sketch.strokeAt(50f, 4f, 2f))
        assertNull(sketch.strokeAt(50f, 5f, 2f))
        assertNull(Sketch().strokeAt(0f, 0f, 10f))
        val dot = sketch.copy(strokes = listOf(stroke.copy(points = stroke.points.take(4))))
        assertEquals(0, dot.strokeAt(0f, 1f, 0f))
        assertNull(dot.strokeAt(10f, 0f, 0f))
    }

    @Test fun movingPreservesPressureTimeAndOtherContent() {
        val moved = sketch.moveStroke(0, -10f, 20f)
        assertEquals(-10f, moved.strokes[0].x(0))
        assertEquals(20f, moved.strokes[0].y(1))
        assertEquals(stroke.t(1), moved.strokes[0].t(1))
        assertEquals(stroke.p(1), moved.strokes[0].p(1))
        assertEquals(stroke.tool, moved.strokes[0].tool)
        assertEquals(stroke.colour, moved.strokes[0].colour)
        assertEquals(stroke.size, moved.strokes[0].size)
        assertEquals(sketch.strokes[1], moved.strokes[1])
        assertEquals(sketch.texts, moved.texts)
        assertEquals(sketch.paper, moved.paper)
        assertEquals(moved, Sketch.parse(moved.toSvg()))
        assertEquals(sketch, moved.moveStroke(0, 10f, -20f))
    }

    @Test fun eraseSweepsAcrossSegmentsAndKeepsOtherContent() {
        val erased = sketch.eraseStrokes(50f, -100f, 50f, 100f, 0f)
        assertTrue(erased.strokes.isEmpty())
        assertEquals(sketch.paper, erased.paper)
        assertEquals(sketch.texts, erased.texts)
        assertEquals(sketch, sketch.eraseStrokes(0f, 20f, 100f, 20f, 2f))
        assertTrue(sketch.eraseStrokes(50f, 4f, 50f, 4f, 2f).strokes.isEmpty())
        assertTrue(sketch.eraseStrokes(100f, 0f, 200f, 0f, 0f).strokes.isEmpty())
    }

    @Test fun deletionOnlyRemovesChosenStroke() {
        assertEquals(listOf(sketch.strokes[1]), sketch.deleteStroke(0).strokes)
        assertEquals(sketch, sketch.deleteStroke(-1))
        assertEquals(sketch.texts, sketch.deleteStroke(0).texts)
        assertEquals(listOf(stroke), sketch.strokes.take(1))
    }
}
