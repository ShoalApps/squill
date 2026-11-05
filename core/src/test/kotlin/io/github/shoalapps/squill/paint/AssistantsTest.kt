package io.github.shoalapps.squill.paint

import org.junit.Assert.*
import org.junit.Test

class AssistantsTest {
    private fun points(expected: FloatArray, actual: FloatArray) = assertArrayEquals(expected, actual, .001f)

    @Test fun rulerProjectsOntoItsEdgeEvenWhenStartingFarAway() {
        val s = AssistantSnap(listOf(Ruler(0f, 20f, 100f, 20f)), 10f)
        assertTrue(s.feed(5f, 900f).isEmpty())
        points(floatArrayOf(5f, 20f, 25f, 20f), s.feed(25f, 910f))
        assertTrue(s.locked)
        points(floatArrayOf(35f, 20f), s.feed(35f, 800f))
        assertEquals(40f to 20f, s.end(40f, 1000f))
    }

    @Test fun parallelRunsThroughTheStart() {
        val s = AssistantSnap(listOf(Parallel(0f, 0f, 10f, 0f)), 10f)
        s.feed(20f, 50f)
        points(floatArrayOf(20f, 50f, 40f, 50f), s.feed(40f, 70f))
    }

    @Test fun perspectiveRunsThroughTheStart() {
        val s = AssistantSnap(listOf(Perspective(listOf(Point(0f, 0f)))), 10f)
        s.feed(20f, 20f)
        points(floatArrayOf(20f, 20f, 40f, 40f), s.feed(50f, 30f))
    }

    @Test fun twoPointsChooseByDirectionIgnoringItsSign() {
        val guide = Perspective(listOf(Point(0f, 50f), Point(50f, 0f)))
        for (sign in listOf(-1, 1)) {
            val horizontal = AssistantSnap(listOf(guide), 10f)
            horizontal.feed(50f, 50f)
            points(floatArrayOf(50f, 50f, 50f + sign * 20, 50f), horizontal.feed(50f + sign * 20, 52f))
            val vertical = AssistantSnap(listOf(guide), 10f)
            vertical.feed(50f, 50f)
            points(floatArrayOf(50f, 50f, 50f, 50f + sign * 20), vertical.feed(52f, 50f + sign * 20))
        }
    }

    @Test fun allGuidesCompeteAndTheChoiceStaysLocked() {
        val s = AssistantSnap(listOf(Ruler(0f, 10f, 100f, 10f), Parallel(0f, 0f, 0f, 100f)), 10f)
        s.feed(40f, 40f)
        points(floatArrayOf(40f, 40f, 40f, 60f), s.feed(41f, 60f))
        points(floatArrayOf(40f, 60f), s.feed(100f, 60f))
    }

    @Test fun thresholdDelaysPaintingAndReturnsStartFirst() {
        val s = AssistantSnap(listOf(Ruler(0f, 0f, 100f, 0f)), 10f)
        assertTrue(s.feed(5f, 20f).isEmpty())
        assertTrue(s.feed(14f, 20f).isEmpty()); assertFalse(s.locked)
        points(floatArrayOf(5f, 0f, 15f, 0f), s.feed(15f, 20f))
    }

    @Test fun unlockedTapFinishesAtRawStart() {
        val s = AssistantSnap(listOf(Ruler(0f, 0f, 100f, 0f)), 10f)
        s.feed(5f, 20f); s.feed(8f, 21f)
        assertEquals(5f to 20f, s.end(8f, 21f))
    }

    @Test fun startOnOrNearVanishingPointUsesTravelDirection() {
        for (offset in listOf(0f, .5f)) {
            val s = AssistantSnap(listOf(Perspective(listOf(Point(10f, 10f)))), 10f)
            s.feed(10f + offset, 10f)
            val result = s.feed(40f + offset, 50f)
            assertTrue(s.locked); assertTrue(result.all { it.isFinite() })
            // Both returned projections are on a line through the vanishing point.
            for (i in result.indices step 2) assertEquals((result[i] - 10f) * 40, (result[i + 1] - 10f) * 30, .01f)
        }
    }

    @Test fun degenerateGuidesAndNoGuidesPassThroughImmediately() {
        for (guides in listOf(emptyList(), listOf(Ruler(0f, 0f, .5f, 0f), Parallel(1f, 1f, 1f, 1f)))) {
            val s = AssistantSnap(guides, 10f)
            points(floatArrayOf(4f, 8f), s.feed(4f, 8f)); assertTrue(s.locked)
            points(floatArrayOf(5f, 9f), s.feed(5f, 9f))
            assertEquals(6f to 10f, s.end(6f, 10f))
        }
    }

    @Test fun hugeAndNonFiniteInputsNeverProduceNonFiniteOutput() {
        val big = Float.MAX_VALUE
        for (guides in listOf(emptyList(), listOf(Ruler(-big, big, big, -big)), listOf(Parallel(-big, -big, big, big)), listOf(Perspective(listOf(Point(big, big)))))) {
            val s = AssistantSnap(guides, 10f)
            for ((x, y) in listOf(-big to big, big to big, big to -big, Float.NaN to Float.POSITIVE_INFINITY)) {
                assertTrue(s.feed(x, y).all { it.isFinite() })
                val end = s.end(x, y); assertTrue(end.first.isFinite() && end.second.isFinite())
            }
        }
    }

    @Test fun fileRoundTripAndCap() {
        val guides = listOf(Ruler(-12f, 3.5f, 50f, 3.5f), Parallel(1f, 2f, 3f, 4f)) + (1..3).map { defaultAssistant(it, 100, 80) }
        assertEquals(guides, AssistantFile.parse(AssistantFile.write(guides)))
        assertEquals(16, AssistantFile.parse(AssistantFile.write(List(30) { guides.first() })).size)
    }

    @Test fun malformedLinesAreIgnoredAndWrongHeaderIsEmpty() {
        assertTrue(AssistantFile.parse("wrong\nruler 1 2 3 4").isEmpty())
        val text = """squill-assistants 1
            ruler 1 2 3
            ruler NaN 2 3 4
            parallel 0 Infinity 2 3
            ruler 1000001 0 2 3
            perspective 1 2 3
            perspective
            perspective 1 2 3 4 5 6 7 8
            unknown 1 2 3 4
            ruler nope 0 1 2
            parallel -1000000 0 1000000 0
            perspective 2 3
        """.trimIndent()
        assertEquals(listOf(Parallel(-1000000f, 0f, 1000000f, 0f), Perspective(listOf(Point(2f, 3f)))), AssistantFile.parse(text))
    }

    @Test fun transformsFollowPixelsAndUndoRestoresGuides() {
        fun fresh() = Painting.blank(12, 8).also {
            it.active.pixels[2 * 12 + 3] = -1
            it.assistants = listOf(Ruler(3.5f, 2.5f, 7f, 2f), Parallel(3.5f, 2.5f, 3f, 6f), Perspective(listOf(Point(3.5f, 2.5f))))
        }
        fun follows(p: Painting) {
            val at = p.active.pixels.indexOf(-1)
            assertTrue(at >= 0)
            p.assistants.forEach {
                val h = assistantHandles(it).first()
                assertEquals((at % p.width).toFloat(), h.x, 1f)
                assertEquals((at / p.width).toFloat(), h.y, 1f)
            }
        }
        for (clockwise in listOf(false, true)) {
            val p = fresh(); val original = p.assistants
            p.rotate90(clockwise); follows(p)
            p.undo(); assertEquals(original, p.assistants)
            p.redo(); p.rotate90(clockwise); follows(p)
        }
        for (horizontal in listOf(false, true)) { val p = fresh(); p.flip(horizontal, true); follows(p) }
        val p = fresh(); p.resizeCanvas(20, 15, 4, 3); follows(p)
        val guides = p.assistants
        p.flip(true, false); assertEquals(guides, p.assistants)
        p.flatten(); assertEquals(guides, p.assistants)
    }

    @Test fun snapshotsCopyGuidesAndSwitchButHistoryDoesNotRestoreSwitch() {
        val p = Painting.blank(12, 8); p.assistants = listOf(defaultAssistant(3, 12, 8)); p.assistantsOn = false
        val snapshot = p.snapshot(true)
        assertEquals(p.assistants, snapshot.assistants); assertFalse(snapshot.assistantsOn)
        p.rotate90(true); p.assistantsOn = true; p.undo()
        assertEquals(snapshot.assistants, p.assistants); assertTrue(p.assistantsOn)
        p.assistants = emptyList(); assertEquals(1, snapshot.assistants.size)
    }

    @Test fun undoingALayerChangeKeepsAGuideAddedSince() {
        val p = Painting.blank(100, 80)
        p.newLayer()
        val guide = Ruler(1f, 2f, 3f, 4f)
        p.assistants = listOf(guide)
        p.undo()
        assertEquals(listOf<Assistant>(guide), p.assistants)
        p.redo()
        assertEquals(listOf<Assistant>(guide), p.assistants)
    }

    @Test fun redoingARotationMovesGuidesAgain() {
        val p = Painting.blank(100, 80)
        p.assistants = listOf(Ruler(10f, 20f, 30f, 40f))
        p.rotate90(true)
        val turned = p.assistants
        p.undo()
        assertEquals(listOf<Assistant>(Ruler(10f, 20f, 30f, 40f)), p.assistants)
        p.redo()
        assertEquals(turned, p.assistants)
    }
}
