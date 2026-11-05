package io.github.shoalapps.squill.paint

import org.junit.Assert.*
import org.junit.Test

class GroupsTest {
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private fun layer(p: Painting, name: String, colour: Int) = Layer(p.nextId++, name, IntArray(p.width * p.height) { colour })
    private fun group(p: Painting, name: String, vararg children: Node) = Group(p.nextId++, name, children = children.toMutableList())
    private fun select(p: Painting, n: Node) { p.activeId = n.id }

    @Test fun flatRenderingMatchesTheOriginalPixelFormulaAndPartialRendersStayCanvasIndexed() {
        val p = Painting(4, 3, 0x40203040)
        val a = layer(p, "a", 0x80906030.toInt())
        val b = layer(p, "b", 0xC0A0B0C0.toInt()).also { it.opacity = .6f; it.blend = Blend.MULTIPLY; it.clipped = true }
        val hidden = layer(p, "hidden", red).also { it.visible = false }
        val c = layer(p, "c", blue).also { it.clipped = true; it.opacity = .3f }
        p.root.children.addAll(listOf(a, b, hidden, c))
        val clippedB = (b.pixels[0] and 0xFFFFFF) or ((192 * 128 / 255) shl 24)
        val expected = composite(composite(composite(p.background, a.pixels[0], Blend.NORMAL), clippedB, Blend.MULTIPLY, .6f), blue, Blend.NORMAL, .3f)
        assertArrayEquals(IntArray(12) { expected }, p.flattened())
        val out = IntArray(12) { 123 }
        p.render(Rect(1, 1, 3, 3), out)
        for (y in 0..2) for (x in 0..3) assertEquals(if (y >= 1 && x in 1..2) expected else 123, out[y * 4 + x])
    }

    @Test fun isolatedOpacityAppliesOnceToOverlappingChildren() {
        val p = Painting(1, 1)
        val a = layer(p, "a", red); val b = layer(p, "b", blue)
        val g = group(p, "g", a, b).also { it.opacity = .5f }
        p.root.children.add(g)
        assertEquals(composite(white, blue, Blend.NORMAL, .5f), p.flattened()[0])
        val look = p.flattened()[0]
        g.opacity = 1f; a.opacity = .5f; b.opacity = .5f
        assertNotEquals(look, p.flattened()[0])
    }

    @Test fun isolatedMultiplyDoesNotUseBackdropButPassThroughDoes() {
        val p = Painting(1, 1)
        val base = layer(p, "base", red)
        val child = layer(p, "child", blue).also { it.blend = Blend.MULTIPLY }
        val g = group(p, "g", child)
        p.root.children.addAll(listOf(base, g))
        assertEquals(blue, p.flattened()[0])
        g.passThrough = true
        assertEquals(0xFF000000.toInt(), p.flattened()[0])
        g.opacity = .5f
        assertEquals(0xFF800000.toInt(), p.flattened()[0])
    }

    @Test fun passThroughOpacityInterpolatesPremultipliedColour() {
        val p = Painting(1, 1, 0x0000FF00)
        val g = group(p, "g", layer(p, "red", red)).also { it.passThrough = true; it.opacity = .5f }
        p.root.children.add(g)
        assertEquals(0x80FF0000.toInt(), p.flattened()[0])
        (g.children[0] as Layer).pixels[0] = 0
        assertEquals(p.background, p.flattened()[0])
    }

    @Test fun clippingUsesGroupAlphaBeforeOpacityEvenWhenHiddenAndClippedGroupsAreIsolated() {
        val p = Painting(2, 1, 0)
        val a = layer(p, "a", 0).also { it.pixels[0] = 0x80FF0000.toInt() }
        val base = group(p, "base", a).also { it.opacity = .1f }
        val child = layer(p, "child", blue)
        val top = group(p, "top", child).also { it.clipped = true; it.passThrough = true }
        p.root.children.addAll(listOf(base, top))
        assertEquals(0, p.flattened()[1])
        base.visible = false
        assertArrayEquals(intArrayOf(0x800000FF.toInt(), 0), p.flattened())
        val clipped = layer(p, "clipped", red).also { it.clipped = true }
        p.root.children.add(clipped)
        base.children.clear()
        assertArrayEquals(intArrayOf(0, 0), p.flattened())
    }

    @Test fun emptyAndHiddenGroupsAreHarmlessAndClipBasesStayWithinTheirParent() {
        val p = Painting.blank(1, 1)
        p.active.pixels[0] = red
        val child = layer(p, "clipped without base", blue).also { it.clipped = true }
        val g = group(p, "g", child)
        p.root.children.addAll(listOf(group(p, "empty"), g))
        assertEquals(blue, p.flattened()[0])
        child.visible = false
        assertEquals(red, p.flattened()[0])
    }

    @Test fun movesSwapEnterAndLeaveBothDirectionsAndRootEdgesDoNotRecordUndo() {
        val p = Painting.blank(1, 1); val a = p.active
        p.moveActive(-1); p.moveActive(1); assertFalse(p.canUndo)
        val b = p.newLayer("b"); val c = p.newLayer("c")
        select(p, b); p.moveActive(1); assertEquals(listOf(a, c, b), p.root.children)
        p.moveActive(-1); assertEquals(listOf(a, b, c), p.root.children)
        val g = group(p, "g"); p.root.children.add(2, g)
        p.moveActive(1); assertEquals(listOf(b), g.children); assertSame(g, p.parentOf(b))
        p.moveActive(1); assertEquals(listOf(a, g, b, c), p.root.children)
        p.moveActive(-1); assertEquals(listOf(b), g.children)
        p.moveActive(-1); assertEquals(listOf(a, b, g, c), p.root.children)
        select(p, c); p.moveActive(-1); assertEquals(listOf(c), g.children)
        p.moveActive(1); assertSame(p.root, p.parentOf(c))
        assertEquals(listOf(a, b, g, c), p.root.children)
    }

    @Test fun subtreeDepthCapsEnteringAndWrappingAndCreationFallsBackToSibling() {
        val p = Painting.blank(1, 1); val a = p.active
        var parent = p.root
        repeat(Painting.MAX_GROUP_DEPTH - 1) { val g = group(p, "g$it"); parent.children.add(g); parent = g }
        val atCap = group(p, "cap"); parent.children.add(atCap)
        parent.children.add(0, a); p.root.children.remove(a)
        select(p, a); p.moveActive(1)
        assertSame(parent, p.parentOf(a)) // Cannot enter the group at depth eight; swaps instead.
        assertEquals(listOf(atCap, a), parent.children)
        select(p, atCap); val l = p.newLayer("sibling")
        assertSame(parent, p.parentOf(l))
        select(p, a); val before = p.root.children.toList(); p.groupActive()
        assertSame(a, p.activeNode); assertEquals(before, p.root.children)
        select(p, p.root.children.first()); p.groupActive()
        assertSame(p.root, p.parentOf(p.activeNode))
    }

    @Test fun placementUngroupAndMergeKeepOrderAndLayerSettings() {
        val p = Painting.blank(1, 1); val a = p.active; a.pixels[0] = red
        p.newGroup("empty"); val g = p.activeNode as Group
        val b = p.newLayer("b"); assertSame(g, p.parentOf(b))
        select(p, g); val c = p.newLayer("c"); assertEquals(listOf(b, c), g.children)
        select(p, g); p.ungroup(); assertEquals(listOf(a, b, c), p.root.children); assertSame(c, p.activeNode)
        p.undo(); assertSame(g, p.activeNode); assertEquals(listOf(b, c), g.children)
        b.pixels[0] = red; c.pixels[0] = blue; g.opacity = .4f; g.blend = Blend.SCREEN; g.clipped = true
        val look = p.flattened(); val oldId = g.id
        p.mergeGroup(); assertTrue(p.activeNode is Layer); assertNotEquals(oldId, p.activeId)
        assertEquals("empty", p.active.name); assertEquals(.4f, p.active.opacity, 0f); assertTrue(p.active.clipped)
        assertEquals(Blend.SCREEN, p.active.blend); assertArrayEquals(look, p.flattened())
        p.undo(); g.passThrough = true; p.mergeGroup(); assertEquals(Blend.NORMAL, p.active.blend)
    }

    @Test fun deletingNeverRemovesTheLastPixelAndEmptyUngroupSelectsBelowOrParent() {
        val p = Painting.blank(1, 1); val a = p.active
        p.groupActive(); val g = p.activeNode as Group
        assertFalse(p.canDeleteActive); p.deleteActive(); assertSame(g, p.activeNode)
        p.newGroup("empty"); val empty = p.activeNode
        p.ungroup(); assertSame(a, p.activeNode)
        select(p, g); p.newGroup("empty again"); p.moveActive(-1); p.ungroup(); assertSame(g, p.activeNode)
        assertNull(p.find(empty.id))
        select(p, g); p.duplicateActive(); val copy = p.activeNode
        assertTrue(p.canDeleteActive); p.deleteActive(); assertSame(g, p.activeNode); assertNull(p.find(copy.id))
    }

    @Test fun duplicateIsDeepAndCapsCountPixelsAndGroups() {
        val p = Painting.blank(1, 1); p.active.pixels[0] = red
        p.groupActive(); val g = p.activeNode as Group
        p.newGroup("nested"); p.newLayer("inside"); select(p, g)
        val ids = p.nodes.map { it.id }.toSet(); val originalLayers = p.layers
        p.duplicateActive(); val copy = p.activeNode as Group
        assertEquals(g.name + " copy", copy.name); assertEquals(4, p.layers.size)
        val newNodes = p.nodes.filter { it.id !in ids }; assertEquals(4, newNodes.size)
        originalLayers.zip(p.layers.drop(2)).forEach { (a, b) -> assertNotSame(a.pixels, b.pixels); assertArrayEquals(a.pixels, b.pixels) }
        while (p.layers.size < Painting.MAX_LAYERS) p.newLayer()
        select(p, g); assertFalse(p.canDuplicateActive); val count = p.nodes.size; p.duplicateActive(); assertEquals(count, p.nodes.size)
    }

    @Test fun undoRestoresIdentitiesStructureFieldsAndThenPixelUndoStillWorks() {
        val p = Painting.blank(1, 1); val a = p.active
        val before = a.pixels.copyOf(); a.pixels[0] = red; p.recordPixels(a, Rect(0, 0, 1, 1), before)
        p.groupActive(); val g = p.activeNode as Group
        p.change { g.name = "renamed"; g.opacity = .2f; g.passThrough = true }
        p.duplicateActive(); p.deleteActive(); p.ungroup()
        repeat(5) { assertTrue(p.undo()) }
        assertSame(a, p.activeNode); assertSame(a, p.root.children.single()); assertEquals(red, a.pixels[0])
        assertTrue(p.undo()); assertEquals(0, a.pixels[0]); assertFalse(p.canUndo)
        repeat(6) { assertTrue(p.redo()) }; assertEquals(red, a.pixels[0]); assertSame(a, p.root.children.single())
    }

    @Test fun activeGroupPixelOperationsAreNoOpsAndSnapshotPreservesTreeWithoutHistory() {
        val p = Painting.blank(2, 1); p.active.pixels[0] = red; p.groupActive()
        val g = p.activeNode as Group; val l = p.active
        val shared = p.snapshot(false); val frozen = p.snapshot(true)
        assertSame(l.pixels, shared.active.pixels); assertNotSame(l.pixels, frozen.active.pixels)
        assertTrue(shared.activeNode is Group); assertEquals(p.nextId, shared.nextId); assertFalse(shared.canUndo); assertNull(shared.selection)
        val clean = p.snapshot(true)
        clean.clearActive(); clean.filterActive(Filter.INVERT, 1f); clean.transformActive(1f, 1f, 0f, 1f, 0f); clean.copySelectionToLayer(); clean.flip(true, false)
        assertFalse(clean.canUndo); assertArrayEquals(l.pixels, clean.active.pixels); assertEquals(g.id, clean.activeId)
    }

    @Test fun populatedGroupsReceiveBottomOnUpAndTopOnDownAndSubtreesRespectDepth() {
        val p = Painting.blank(1, 1); val a = p.active
        val bottom = layer(p, "bottom", red); val top = layer(p, "top", blue)
        val g = group(p, "g", bottom, top); p.root.children.add(g)
        p.moveActive(1); assertEquals(listOf(a, bottom, top), g.children)
        repeat(3) { p.moveActive(1) }; p.moveActive(-1); assertEquals(listOf(bottom, top, a), g.children)
        p.moveActive(1)
        val subtree = group(p, "subtree", group(p, "inner", layer(p, "leaf", red)))
        p.root.children.add(subtree)
        var parent = p.root
        repeat(Painting.MAX_GROUP_DEPTH - 3) { val n = group(p, "depth$it"); parent.children.add(n); parent = n }
        val target = group(p, "target"); parent.children.add(target)
        p.root.children.remove(subtree); parent.children.add(0, subtree)
        select(p, subtree); p.moveActive(1)
        assertEquals(listOf(target, subtree), parent.children); assertTrue(target.children.isEmpty())
        p.moveActive(-1); assertEquals(listOf(subtree, target), parent.children)
    }

    @Test fun siblingMergeAndRefusedOperationsDoNotRecordHistoryAndGroupCreationIsCapped() {
        val p = Painting.blank(1, 1); val a = p.active
        p.groupActive(); val g = p.activeNode
        p.newLayer("top")
        select(p, a); assertFalse(p.canMergeDown)
        val clean = p.snapshot(true); clean.mergeDown(); select(clean, clean.parentOf(clean.activeNode)!!); clean.deleteActive()
        assertFalse(clean.canUndo); assertEquals(2, clean.layers.size)
        select(p, g); assertFalse(p.canMergeDown)
        val groups = Painting.blank(1, 1)
        repeat(Painting.MAX_LAYERS) { groups.newGroup() }
        val capped = groups.snapshot(true); capped.newGroup(); capped.groupActive(); capped.duplicateActive()
        assertFalse(capped.canUndo); assertEquals(Painting.MAX_LAYERS, capped.nodes.count { it is Group })
    }

    @Test fun nestedPartialRenderAndWholeCanvasOperationsVisitEveryPixelLayer() {
        val p = Painting(3, 2)
        val a = layer(p, "a", red); val b = layer(p, "b", blue)
        a.pixels[0] = white; b.pixels[0] = 0
        val inner = group(p, "inner", b).also { it.passThrough = true; it.opacity = .5f }
        val outer = group(p, "outer", a, inner).also { it.opacity = .6f }
        p.root.children.add(outer); select(p, outer)
        val expected = p.flattened(); val partial = IntArray(6)
        p.render(Rect(1, 1, 3, 2), partial); assertEquals(expected[4], partial[4]); assertEquals(expected[5], partial[5]); assertEquals(0, partial[0])
        p.flip(true, true); assertEquals(white, a.pixels[2]); assertEquals(0, b.pixels[2]); p.undo()
        p.rotate90(true); assertEquals(2, p.width); assertEquals(white, a.pixels[1]); assertEquals(0, b.pixels[1]); p.undo()
        p.resizeCanvas(4, 3, 1, 1); assertEquals(white, a.pixels[5]); assertEquals(0, b.pixels[5]); p.undo()
        p.flatten(); assertTrue(p.root.children.single() is Layer); assertArrayEquals(expected, p.active.pixels)
    }

    @Test fun xmlRoundTripKeepsNestedEmptyClippedPassThroughAndEscapedNames() {
        val entry = OraStack.Entry("Layer <&\"", "data/a.png", .5f, false, Blend.MULTIPLY, true, true, 3, -2)
        val empty = OraStack.GroupEntry("Empty", 1f, true, Blend.NORMAL, false, false, emptyList())
        val group = OraStack.GroupEntry("Group <&\"", .7f, false, Blend.NORMAL, true, true, listOf(entry, empty))
        val parsed = OraStack.parse(OraStack.write(4, 3, listOf(group)).toByteArray())
        assertEquals(listOf(group), parsed.items); assertEquals(listOf(entry), parsed.entries); assertEquals(listOf(3 to -2), parsed.offsets)
        val p = OraStack.toPainting(parsed) { IntArray(12) { red } }
        assertEquals(listOf("Empty", entry.name), (p.root.children.single() as Group).children.map { it.name })
        assertTrue(p.activeNode is Layer)
    }

    @Test fun stackOffsetsAreIgnoredAndNonNormalAutoIsIsolated() {
        val xml = """<image w="2" h="1"><stack x="99"><stack name="g" x="4" y="5" isolation="auto" composite-op="svg:multiply"><layer name="l" src="a" x="1" y="-2"/></stack></stack></image>"""
        val parsed = OraStack.parse(xml.toByteArray()); val g = parsed.items.single() as OraStack.GroupEntry
        assertFalse(g.passThrough); assertEquals(listOf(1 to -2), parsed.offsets)
        val p = OraStack.toPainting(parsed) { intArrayOf(red, 0) }; assertArrayEquals(intArrayOf(red, 0), p.active.pixels)
    }

    @Test fun tooDeepXmlFailsBeforeBuildingARecursiveTree() {
        fun xml(depth: Int) = "<image w=\"1\" h=\"1\"><stack>" + "<stack>".repeat(depth) + "<layer src=\"a\"/>" + "</stack>".repeat(depth) + "</stack></image>"
        OraStack.parse(xml(Painting.MAX_GROUP_DEPTH).toByteArray())
        val failure = runCatching { OraStack.parse(xml(10000).toByteArray()) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals("That painting's groups are nested too deeply to open", failure?.message)
    }

    @Test fun aStrokeOnAClippedLayerIsOnlyClippedOnce() {
        val p = Painting(40, 40, 0)
        val base = Layer(p.nextId++, "base", IntArray(40 * 40) { 0x80FF0000.toInt() }, visible = false)
        val clipped = Layer(p.nextId++, "clipped", IntArray(40 * 40), clipped = true)
        p.root.children.addAll(listOf(base, clipped))
        val brush = BrushSettings("hard", size = 12f, hardness = 1f, pressureSize = false, stabiliser = 0f)
        StrokePainter(clipped.pixels, 40, 40, brush, 0xFF0000FF.toInt()).apply { add(20f, 20f); finish(20f, 20f) }
        val free = IntArray(40 * 40)
        StrokePainter(free, 40, 40, brush, 0xFF0000FF.toInt()).apply { add(20f, 20f); finish(20f, 20f) }
        // The layer holds the stroke as painted, and what's seen is clipped exactly once, by the hidden base's half alpha.
        assertArrayEquals(free, clipped.pixels)
        val middle = 20 * 40 + 20
        assertTrue(alphaOf(free[middle]) > 0)
        assertEquals(alphaOf(free[middle]) * 128 / 255, alphaOf(p.flattened()[middle]))
    }

    @Test fun mergingDownKeepsTheLook() {
        val p = Painting.blank(1, 1)
        val under = p.active
        under.pixels[0] = red; under.opacity = .5f
        val top = Layer(p.nextId++, "top", intArrayOf(blue))
        p.root.children.add(top); p.activeId = top.id
        val before = p.flattened()[0]
        p.mergeDown()
        assertEquals(1, p.layers.size)
        assertEquals(1f, p.layers[0].opacity)
        assertEquals(before, p.flattened()[0])
        p.undo()
        assertEquals(.5f, under.opacity)
    }

    @Test fun hiddenLayersDoNotMergeDown() {
        val p = Painting.blank(1, 1)
        val top = Layer(p.nextId++, "top", intArrayOf(blue))
        p.root.children.add(top); p.activeId = top.id
        assertTrue(p.canMergeDown)
        top.visible = false; assertFalse(p.canMergeDown)
        top.visible = true; p.layers[0].visible = false; assertFalse(p.canMergeDown)
    }
}
