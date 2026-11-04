package io.github.shoalapps.squill.paint

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val RED = 0xFFFF0000.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()
private const val BLACK = 0xFF000000.toInt()

class BlendTest {
    @Test fun everyModeStaysInRangeAndHasAnOraName() {
        assertEquals(Blend.entries.size, Blend.entries.map { it.ora }.toSet().size)
        for (m in Blend.entries) for (a in listOf(0x80102030.toInt(), WHITE, BLACK, 0)) for (b in listOf(RED, 0x40FF8040, WHITE)) {
            val out = composite(a, b, m, .7f)
            assertTrue(m.name, alphaOf(out) in 0..255)
            assertEquals(m.name, m, Blend.fromOra(m.ora))
        }
    }

    @Test fun knownResults() {
        assertEquals(RED, composite(WHITE, RED, Blend.NORMAL))
        assertEquals(RED, composite(WHITE, RED, Blend.MULTIPLY))
        assertEquals(BLACK, composite(BLACK, RED, Blend.MULTIPLY))
        assertEquals(WHITE, composite(WHITE, RED, Blend.SCREEN))
        assertEquals(0xFF00FFFF.toInt(), composite(WHITE, RED, Blend.DIFFERENCE))
        assertEquals(WHITE, composite(WHITE, BLACK, Blend.LIGHTEN))
        assertEquals(BLACK, composite(WHITE, BLACK, Blend.DARKEN))
    }

    @Test fun halfOpacityMixes() {
        val out = composite(WHITE, BLACK, Blend.NORMAL, .5f)
        assertEquals(255, alphaOf(out))
        assertEquals(128f, redOf(out).toFloat(), 1f)
    }

    @Test fun paintingOnNothingKeepsThePaintsColour() {
        val out = composite(0, RED, Blend.MULTIPLY, .5f)
        assertEquals(128f, alphaOf(out).toFloat(), 1f)
        assertEquals(255, redOf(out))
    }

    @Test fun colourConversionsRoundTrip() {
        for (c in listOf(RED, WHITE, BLACK, 0xFF3366CC.toInt(), 0xFF80FF40.toInt())) {
            val hsv = rgbToHsv(c)
            assertEquals(c, hsvToRgb(hsv[0], hsv[1], hsv[2]))
        }
    }
}

class RasterTest {
    @Test fun aFillStopsAtEdgesAndCanIgnoreThem() {
        // Two white areas split by a black wall.
        val w = 5; val h = 3
        val p = IntArray(w * h) { if (it % w == 2) BLACK else WHITE }
        val mask = Raster.floodMask(p, w, h, 0, 0, 10)
        assertEquals(6, mask.count { it != 0.toByte() })
        assertEquals(12, Raster.floodMask(p, w, h, 0, 0, 10, contiguous = false).count { it != 0.toByte() })
        assertEquals(3, Raster.floodMask(p, w, h, 2, 1, 0).count { it != 0.toByte() })
        assertEquals(0, Raster.floodMask(p, w, h, 9, 9, 0).count { it != 0.toByte() })
    }

    @Test fun aHugeFillDoesNotBlowTheStack() {
        val w = 1500; val h = 1500
        val p = IntArray(w * h)
        assertEquals(w * h, Raster.floodMask(p, w, h, 700, 700, 0).count { it != 0.toByte() })
    }

    @Test fun aMazeIsFilledCompletely() {
        val w = 41; val h = 41
        val p = IntArray(w * h) { i -> val x = i % w; val y = i / w; if (y % 2 == 1 && x != (if (y % 4 == 1) 1 else w - 2)) BLACK else WHITE }
        val open = p.count { it == WHITE }
        assertEquals(open, Raster.floodMask(p, w, h, 0, 0, 0).count { it != 0.toByte() })
    }

    @Test fun shapesMakeMasks() {
        assertEquals(25, Raster.rectMask(2, 2, 7, 7, 10, 10).count { it != 0.toByte() })
        assertEquals(100, Raster.rectMask(-5, -5, 50, 50, 10, 10).count { it != 0.toByte() })
        val e = Raster.ellipseMask(0, 0, 100, 100, 100, 100).count { it != 0.toByte() }
        assertEquals(Math.PI * 2500, e.toDouble(), 150.0)
        val tri = Raster.polygonMask(floatArrayOf(0f, 100f, 0f), floatArrayOf(0f, 0f, 100f), 100, 100).count { it != 0.toByte() }
        assertEquals(5000.0, tri.toDouble(), 150.0)
        assertEquals(Rect(2, 2, 7, 7), Raster.bounds(Raster.rectMask(2, 2, 7, 7, 10, 10), 10, 10))
    }

    @Test fun gradientsRunEndToEnd() {
        val p = IntArray(100)
        Raster.gradient(p, 100, 1, 0f, 0f, 99f, 0f, BLACK, WHITE, false, Blend.NORMAL, 1f, null, false)
        assertEquals(0, redOf(p[0]))
        assertEquals(255, redOf(p[99]))
        assertEquals(128f, redOf(p[50]).toFloat(), 3f)
    }

    @Test fun transformsMoveTurnAndScale() {
        val w = 11; val h = 11
        val p = IntArray(w * h); p[5 * w + 8] = RED // Right of centre.
        val moved = Raster.transform(p, w, h, 1f, 1f, 0f, -3f, 0f, smooth = false)
        assertEquals(RED, moved[5 * w + 5])
        val turned = Raster.transform(p, w, h, 1f, 1f, (Math.PI / 2).toFloat(), 0f, 0f, smooth = false)
        assertEquals(RED, turned[8 * w + 5])
        val same = Raster.transform(p, w, h, 1f, 1f, 0f, 0f, 0f, smooth = true)
        assertEquals(RED, same[5 * w + 8])
    }

    @Test fun everyFilterRunsAndKeepsSize() {
        val w = 40; val h = 30
        val p = IntArray(w * h) { argb(if (it % 7 == 0) 0 else 255, it % 256, (it * 3) % 256, (it * 7) % 256) }
        for (f in Filter.entries) {
            val out = Filters.apply(f, p, w, h, .5f)
            assertEquals(f.name, p.size, out.size)
            assertFalse(f.name, out.contentEquals(p))
        }
    }

    @Test fun particularFilters() {
        assertEquals(0xFF00FFFF.toInt(), Filters.apply(Filter.INVERT, intArrayOf(RED), 1, 1, 0f)[0])
        val grey = Filters.apply(Filter.DESATURATE, intArrayOf(RED), 1, 1, 0f)[0]
        assertEquals(redOf(grey), blueOf(grey))
        // A blurred edge stays the colour it was, rather than going dark against see-through.
        val p = IntArray(400); for (i in 0 until 200) p[i] = RED
        val soft = Filters.blur(p, 20, 20, 3)
        val edge = soft.first { alphaOf(it) in 1..254 }
        assertEquals(255, redOf(edge))
    }
}

class BrushTest {
    private val size = 200
    private fun fresh() = IntArray(size * size)
    private fun stroke(p: IntArray, b: BrushSettings, colour: Int = RED, symmetry: Symmetry = Symmetry(), selection: ByteArray? = null, lock: Boolean = false): StrokePainter {
        val s = StrokePainter(p, size, size, b.copy(stabiliser = 0f), colour, selection, lock, symmetry)
        s.add(20f, 100f); s.add(180f, 100f); s.finish(180f, 100f)
        return s
    }
    private fun painted(p: IntArray) = p.count { alphaOf(it) > 0 }

    @Test fun aStrokeIsAContinuousLine() {
        val p = fresh()
        val s = stroke(p, BrushSettings("t", size = 10f, hardness = 1f, spacing = .1f))
        for (x in 25..175) assertTrue("gap at $x", alphaOf(p[100 * size + x]) > 200)
        assertEquals(0, alphaOf(p[60 * size + 100]))
        assertTrue(s.changed.left <= 20 && s.changed.right >= 180)
    }

    @Test fun everyPresetPaints() {
        for (b in BrushPresets.all) {
            val p = IntArray(size * size) { if (b.kind == BrushKind.PAINT) 0 else if (it % size < 100) WHITE else RED }
            val before = p.copyOf()
            stroke(p, b)
            assertFalse(b.name, p.contentEquals(before))
        }
        BrushPresets.simple.forEach { n -> assertTrue(n, BrushPresets.all.any { it.name == n }) }
    }

    @Test fun opacityIsACeilingWithinAStroke() {
        val p = fresh()
        val s = StrokePainter(p, size, size, BrushSettings("t", size = 20f, opacity = .5f, hardness = 1f, stabiliser = 0f), RED)
        repeat(4) { s.add(50f, 100f); s.add(150f, 100f) }
        assertEquals(128f, alphaOf(p[100 * size + 100]).toFloat(), 3f)
    }

    @Test fun lowFlowBuildsUp() {
        val p = fresh()
        val s = StrokePainter(p, size, size, BrushSettings("t", size = 20f, flow = .1f, hardness = 1f, spacing = .2f, stabiliser = 0f), RED)
        s.add(100f, 100f)
        val once = alphaOf(p[100 * size + 100])
        repeat(10) { s.add(100.5f + it, 100f); s.add(100f, 100f) }
        assertTrue(alphaOf(p[100 * size + 100]) > once)
    }

    @Test fun pressureChangesWidth() {
        fun width(pressure: Float): Int {
            val p = fresh()
            val s = StrokePainter(p, size, size, BrushSettings("t", size = 40f, hardness = 1f, stabiliser = 0f), RED)
            s.add(20f, 100f, pressure); s.add(180f, 100f, pressure)
            return (0 until size).count { alphaOf(p[it * size + 100]) > 128 }
        }
        assertTrue(width(1f) > width(.2f) * 2)
    }

    @Test fun anEraserRemoves() {
        val p = IntArray(size * size) { RED }
        stroke(p, BrushSettings("t", kind = BrushKind.ERASE, size = 20f, hardness = 1f))
        assertEquals(0, alphaOf(p[100 * size + 100]))
        assertEquals(255, alphaOf(p[10 * size + 100]))
    }

    @Test fun selectionsContainPaint() {
        val p = fresh()
        val mask = Raster.rectMask(50, 0, 100, size, size, size)
        stroke(p, BrushSettings("t", size = 10f, hardness = 1f), selection = mask)
        assertTrue(alphaOf(p[100 * size + 70]) > 0)
        assertEquals(0, alphaOf(p[100 * size + 120]))
    }

    @Test fun alphaLockOnlyRecolours() {
        val p = fresh()
        for (x in 0 until 100) p[100 * size + x] = 0xFF0000FF.toInt()
        stroke(p, BrushSettings("t", size = 10f, hardness = 1f), lock = true)
        assertEquals(RED, p[100 * size + 50])
        assertEquals(0, alphaOf(p[100 * size + 150]))
    }

    @Test fun symmetryRepeatsDabs() {
        val p = fresh()
        val s = StrokePainter(p, size, size, BrushSettings("t", size = 6f, hardness = 1f, stabiliser = 0f), RED, symmetry = Symmetry(mirrorX = true, mirrorY = true))
        s.add(30f, 40f)
        for ((x, y) in listOf(30 to 40, 170 to 40, 30 to 160, 170 to 160)) assertTrue("$x,$y", alphaOf(p[y * size + x]) > 0)
        val q = fresh()
        StrokePainter(q, size, size, BrushSettings("t", size = 6f, hardness = 1f, stabiliser = 0f), RED, symmetry = Symmetry(radial = 4)).add(150f, 100f)
        for ((x, y) in listOf(150 to 100, 100 to 150, 50 to 100, 100 to 50)) assertTrue("$x,$y", alphaOf(q[y * size + x]) > 0)
    }

    @Test fun smudgeCarriesColourAlong() {
        val p = IntArray(size * size) { if (it % size < 100) RED else WHITE }
        val s = StrokePainter(p, size, size, BrushSettings("t", kind = BrushKind.SMUDGE, size = 20f, spacing = .1f, hardness = 1f, stabiliser = 0f, strength = .9f), 0)
        s.add(90f, 100f); s.add(130f, 100f)
        val c = p[100 * size + 110]
        assertTrue("green ${greenOf(c)}", greenOf(c) < 250)
    }

    @Test fun theStabiliserSmooths() {
        fun wobble(stabiliser: Float): Int {
            val p = fresh()
            val s = StrokePainter(p, size, size, BrushSettings("t", size = 2f, hardness = 1f, stabiliser = stabiliser), RED)
            for (i in 0..80) s.add(20f + i * 2f, 100f + if (i % 2 == 0) 20f else -20f)
            return painted(p)
        }
        assertTrue(wobble(.9f) < wobble(0f))
    }

    @Test fun strokesAtTheEdgeAndOffCanvasAreSafe() {
        val p = fresh()
        val s = StrokePainter(p, size, size, BrushSettings("t", size = 40f, stabiliser = 0f), RED)
        s.add(-500f, -500f); s.add(5f, 5f); s.add(9000f, 9000f); s.finish(-9f, 9000f)
    }
}

class PaintingTest {
    private fun solid(l: Layer, c: Int) { l.pixels.fill(c) }

    @Test fun layersCompositeInOrderWithTheirSettings() {
        val d = Painting.blank(2, 1)
        solid(d.layers[0], RED)
        assertEquals(RED, d.flattened()[0])
        val top = d.newLayer()
        solid(top, WHITE)
        assertEquals(WHITE, d.flattened()[0])
        top.opacity = .5f
        assertEquals(128f, greenOf(d.flattened()[0]).toFloat(), 2f)
        top.opacity = 1f; top.blend = Blend.MULTIPLY
        assertEquals(RED, d.flattened()[0])
        top.visible = false
        assertEquals(RED, d.flattened()[0])
    }

    @Test fun aClippedLayerStaysInsideTheOneBelow() {
        val d = Painting.blank(2, 1)
        d.layers[0].pixels[0] = RED // Only the left pixel.
        val top = d.newLayer()
        solid(top, BLACK)
        top.clipped = true
        val out = d.flattened()
        assertEquals(BLACK, out[0])
        assertEquals(WHITE, out[1])
    }

    @Test fun undoAndRedoPixels() {
        val d = Painting.blank(10, 10)
        val before = d.active.pixels.copyOf()
        d.active.pixels[55] = RED
        d.recordPixels(d.active, Rect(5, 5, 6, 6), before)
        assertTrue(d.undo())
        assertEquals(0, d.active.pixels[55])
        assertTrue(d.redo())
        assertEquals(RED, d.active.pixels[55])
        assertTrue(d.undo())
        assertFalse(d.undo())
    }

    @Test fun undoAndRedoStructure() {
        val d = Painting.blank(4, 4)
        d.newLayer("Second")
        val a = d.layers.size
        d.duplicateActive()
        assertEquals(a + 1, d.layers.size)
        d.undo()
        assertEquals(a, d.layers.size)
        d.redo()
        assertEquals(a + 1, d.layers.size)
        d.deleteActive()
        assertEquals(a, d.layers.size)
        d.undo()
        assertEquals(a + 1, d.layers.size)
    }

    @Test fun theLastLayerCannotBeDeleted() {
        val d = Painting.blank(2, 2)
        d.deleteActive()
        assertEquals(1, d.layers.size)
    }

    @Test fun mergingKeepsHowItLooked() {
        val d = Painting.blank(1, 1)
        solid(d.layers[0], RED)
        val top = d.newLayer()
        solid(top, WHITE)
        top.opacity = .5f
        val look = d.flattened()[0]
        d.mergeDown()
        assertEquals(1, d.layers.size)
        assertEquals(look, d.flattened()[0])
        d.undo()
        assertEquals(2, d.layers.size)
        assertEquals(look, d.flattened()[0])
    }

    @Test fun flattenRotateFlipAndResize() {
        val d = Painting.blank(3, 2)
        d.active.pixels[0] = RED
        d.flip(horizontal = true, allLayers = true)
        assertEquals(RED, d.active.pixels[2])
        d.undo()
        assertEquals(RED, d.active.pixels[0])
        d.rotate90(clockwise = true)
        assertEquals(2, d.width); assertEquals(3, d.height)
        assertEquals(RED, d.active.pixels[1]) // Top-left goes to top-right.
        d.undo()
        assertEquals(3, d.width)
        d.resizeCanvas(5, 5, 1, 1)
        assertEquals(RED, d.active.pixels[1 * 5 + 1])
        d.undo()
        d.resizeCanvas(1, 1)
        assertEquals(RED, d.active.pixels[0])
        d.undo()
        d.newLayer(); d.flatten()
        assertEquals(1, d.layers.size)
    }

    @Test fun selectionsLimitCopyClearAndFilters() {
        val d = Painting.blank(4, 1)
        solid(d.active, RED)
        d.selection = Raster.rectMask(0, 0, 2, 1, 4, 1)
        d.clearActive()
        assertEquals(0, d.active.pixels[0]); assertEquals(RED, d.active.pixels[3])
        d.undo()
        assertEquals(RED, d.active.pixels[0])
        d.filterActive(Filter.INVERT, 0f)
        assertNotEquals(RED, d.active.pixels[0]); assertEquals(RED, d.active.pixels[3])
        d.undo()
        d.copySelectionToLayer(cut = true)
        assertEquals(2, d.layers.size)
        assertEquals(RED, d.active.pixels[0]); assertEquals(0, d.active.pixels[3])
        assertEquals(0, d.layers[0].pixels[0])
        d.undo()
        d.invertSelection()
        assertEquals(0.toByte(), d.selection!![0]); assertTrue(d.selection!![3] != 0.toByte())
    }

    @Test fun undoIsBoundedByMemory() {
        val d = Painting.blank(1000, 1000)
        repeat(30) { d.filterActive(Filter.INVERT, 0f) }
        var count = 0
        while (d.undo()) count++
        assertTrue("kept $count", count in 1..((Painting.LIMIT / 4_000_000).toInt() + 1))
    }

    @Test fun oraStackRoundTrips() {
        val entries = listOf(
            OraStack.Entry("Top <one> & \"two\"", "data/layer1.png", .5f, false, Blend.MULTIPLY, true, true),
            OraStack.Entry("Bottom", "data/layer0.png", 1f, true, Blend.GRAIN_MERGE, false, false),
        )
        val back = OraStack.parse(OraStack.write(800, 600, entries).toByteArray())
        assertEquals(800, back.width); assertEquals(600, back.height)
        assertEquals(entries, back.entries)
        assertArrayEquals(intArrayOf(0, 0), back.offsets.map { it.first }.toIntArray())
    }
}
