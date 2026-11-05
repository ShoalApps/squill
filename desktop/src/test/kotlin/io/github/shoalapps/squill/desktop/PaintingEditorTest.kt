package io.github.shoalapps.squill.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import io.github.shoalapps.squill.paint.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalTestApi::class)
class PaintingEditorTest {
    @Test fun rulerConstrainsStrokesAndUnlockedTapsStayAtTheirStart() {
        val p = Painting.blank(64, 64)
        p.assistants = listOf(Ruler(0f, 32f, 64f, 32f))
        val session = PaintingSession(PaintingCopy(p)) { _, _, _, _, _ -> }
        val brush = BrushPresets.all.first { it.name == "Pixel" }.copy(size = 3f)
        session.submit {
            begin(10f, 10f, 1f, brush, -1, 5f)
            point(50f, 50f, 1f); end(50f, 50f, 1f)
            begin(20f, 10f, 1f, brush, -1, 5f)
            point(22f, 11f, 1f); end(22f, 11f, 1f)
        }
        val saved = AtomicReference<PaintingCopy?>()
        val done = java.util.concurrent.CountDownLatch(1)
        session.leave({ saved.set(it); done.countDown() }, true)
        assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS))
        val pixels = saved.get()!!.painting.active.pixels
        assertTrue(pixels[32 * 64 + 30] != 0)
        assertTrue(pixels[10 * 64 + 20] != 0)
        assertTrue(pixels.indices.filter { pixels[it] != 0 }.all { it / 64 in 30..33 || (it / 64 in 8..11 && it % 64 in 18..21) })
    }

    @Test fun leavingBeforeDirectionLockKeepsTheTap() {
        val p = Painting.blank(64, 64).also { it.assistants = listOf(Ruler(0f, 32f, 64f, 32f)) }
        val session = PaintingSession(PaintingCopy(p)) { _, _, _, _, _ -> }
        val brush = BrushPresets.all.first { it.name == "Pixel" }.copy(size = 3f)
        session.submit { begin(10f, 10f, 1f, brush, -1) }
        val saved = AtomicReference<PaintingCopy?>()
        val done = java.util.concurrent.CountDownLatch(1)
        session.leave({ saved.set(it); done.countDown() }, true)
        assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(saved.get()!!.painting.active.pixels[10 * 64 + 10] != 0)
        assertEquals(0, saved.get()!!.painting.active.pixels[32 * 64 + 10])
    }

    @Test fun paintLayersEraseUndoAndRedo() = runDesktopComposeUiTest(1200, 900) {
        val saved = AtomicReference<PaintingCopy?>()
        setContent { MaterialTheme { PaintingEditor(PaintingCopy(Painting.blank(64, 64)), saved::set) } }
        fun settle() { Thread.sleep(200); waitForIdle() }
        fun savePause(): PaintingCopy {
            settle(); mainClock.advanceTimeBy(SAVE_AFTER_MS + 100); waitForIdle()
            waitUntil(timeoutMillis = 5000) { saved.get() != null }; return saved.get()!!
        }
        settle()
        onNodeWithText("Colour #RRGGBB").performTextReplacement("#2F6A91")
        onNodeWithContentDescription("Brush size").performSemanticsAction(SemanticsActions.SetProgress) { it(20f) }
        onNodeWithContentDescription("Brush opacity").performSemanticsAction(SemanticsActions.SetProgress) { it(.8f) }
        onNodeWithContentDescription("Painting canvas").performMouseInput {
            moveTo(Offset(450f, 350f)); press(); moveTo(Offset(500f, 350f), 100); release()
        }
        val painted = savePause().painting.active.pixels.copyOf()
        assertTrue(painted.any { it ushr 24 > 0 && (it and 0xFFFFFF) == 0x2F6A91 })
        onNodeWithText("Eraser", useUnmergedTree = true).performClick()
        onNodeWithContentDescription("Painting canvas").performMouseInput {
            moveTo(Offset(450f, 350f)); press(); moveTo(Offset(500f, 350f), 100); release()
        }
        val erased = savePause().painting.active.pixels.copyOf()
        assertFalse(painted.contentEquals(erased))
        onNodeWithContentDescription("Painting canvas").performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.Z); keyUp(Key.CtrlLeft) }
        assertArrayEquals(painted, savePause().painting.active.pixels)
        onNodeWithContentDescription("Painting canvas").performKeyInput { keyDown(Key.CtrlLeft); keyDown(Key.ShiftLeft); pressKey(Key.Z); keyUp(Key.ShiftLeft); keyUp(Key.CtrlLeft) }
        assertArrayEquals(erased, savePause().painting.active.pixels)
        onNodeWithText("Add").performClick(); settle()
        onNodeWithContentDescription("Select layer Layer 2").assertExists()
        onNodeWithText("Layer name").performTextReplacement("Clouds")
        onNodeWithText("Rename").performClick(); settle()
        onNodeWithContentDescription("Select layer Clouds").assertExists()
        onNodeWithText("Down").performClick()
        onNodeWithContentDescription("Layer visible").performClick(); settle()
        onNodeWithContentDescription("Layer opacity").performSemanticsAction(SemanticsActions.SetProgress) { it(.4f) }; settle()
        onNodeWithText("Blend: Normal").performClick()
        onNodeWithText("Multiply").performClick()
        val layers = savePause().painting.layers
        assertEquals("Clouds", layers.first().name)
        assertFalse(layers.first().visible)
        assertEquals(.4f, layers.first().opacity, .001f)
        assertEquals(Blend.MULTIPLY, layers.first().blend)
        onNodeWithText("Delete").performClick()
        assertEquals(1, savePause().painting.layers.size)
    }

    @Test fun leavingQueuesACompleteStrokeAndUsesLatestSaveCallback() = runDesktopComposeUiTest(1200, 900) {
        var open by mutableStateOf(true)
        var latest by mutableStateOf(false)
        val saved = AtomicReference<PaintingCopy?>()
        val initial = PaintingCopy(Painting.blank(64, 64))
        setContent { MaterialTheme { if (open) PaintingEditor(initial, if (latest) saved::set else ({})) } }
        latest = true; waitForIdle()
        mainClock.autoAdvance = false
        onNodeWithContentDescription("Painting canvas").performMouseInput {
            moveTo(Offset(450f, 350f)); press(); release()
        }
        open = false; mainClock.advanceTimeByFrame(); waitForIdle()
        waitUntil(timeoutMillis = 5000) { saved.get() != null }
        assertTrue(saved.get()!!.painting.active.pixels.any { it != 0 })
    }

    @Test fun phoneUpdateWhileDirtyIsKeptAndSaveFailureRetriesOnLeaving() = runDesktopComposeUiTest(1200, 900) {
        var incoming by mutableStateOf(PaintingCopy(Painting.blank(64, 64)))
        var failures by mutableIntStateOf(0)
        var open by mutableStateOf(true)
        val kept = AtomicReference<PaintingCopy?>()
        val saved = AtomicReference<PaintingCopy?>()
        val saves = java.util.concurrent.atomic.AtomicInteger()
        setContent { MaterialTheme { if (open) PaintingEditor(incoming, { saved.set(it); saves.incrementAndGet() }, saveFailures = failures, onConflict = kept::set) } }
        mainClock.autoAdvance = false
        onNodeWithContentDescription("Painting canvas").performMouseInput { moveTo(Offset(450f, 350f)); press(); release() }
        val phone = PaintingCopy(Painting.blank(64, 64).also { it.active.name = "Phone" })
        incoming = phone
        mainClock.advanceTimeByFrame(); waitForIdle()
        waitUntil(timeoutMillis = 5000) { kept.get() != null }; assertSame(phone, kept.get())
        Thread.sleep(200); mainClock.advanceTimeByFrame()
        mainClock.advanceTimeBy(SAVE_AFTER_MS + 100); waitForIdle()
        waitUntil(timeoutMillis = 5000) { saves.get() >= 1 }
        assertEquals("Layer 1", saved.get()!!.painting.active.name)
        failures++; mainClock.advanceTimeByFrame(); waitForIdle()
        val before = saves.get(); open = false; mainClock.advanceTimeByFrame(); waitForIdle()
        waitUntil(timeoutMillis = 5000) { saves.get() > before }
    }

    @Test fun handZoomAndReadOnlyLeavePixelsAlone() = runDesktopComposeUiTest(1200, 900) {
        val saved = AtomicReference<PaintingCopy?>()
        val copy = PaintingCopy(Painting.blank(64, 64), readOnlyReason = "Read only: unknown stack attribute")
        setContent { MaterialTheme { PaintingEditor(copy, saved::set) } }
        onNodeWithText(copy.readOnlyReason!!).assertExists()
        onNodeWithText("Add").assertIsNotEnabled()
        onNodeWithContentDescription("Painting canvas").performMouseInput { moveTo(Offset(450f, 350f)); press(); moveTo(Offset(500f, 400f), 100); release(); scroll(-2f) }
        onNodeWithText("Fit").performClick()
        mainClock.advanceTimeBy(SAVE_AFTER_MS + 100); waitForIdle()
        assertNull(saved.get())
    }
    @Test fun cleanPhoneUpdateIsAdoptedWithoutSavingItAgain() = runDesktopComposeUiTest(1200, 900) {
        var incoming by mutableStateOf(PaintingCopy(Painting.blank(64, 64)))
        val saved = AtomicReference<PaintingCopy?>()
        setContent { MaterialTheme { PaintingEditor(incoming, saved::set) } }
        incoming = PaintingCopy(Painting.blank(64, 64).also { it.active.name = "Phone layer" })
        waitUntil(timeoutMillis = 5000) {
            onAllNodesWithContentDescription("Select layer Phone layer").fetchSemanticsNodes().size == 1
        }
        mainClock.advanceTimeBy(SAVE_AFTER_MS + 100); waitForIdle()
        assertNull(saved.get())
        onNodeWithText("Hand").performClick()
        onNodeWithContentDescription("Painting canvas").performMouseInput {
            moveTo(Offset(450f, 350f)); press(); moveTo(Offset(500f, 400f), 100); release(); scroll(-2f)
        }
        onNodeWithText("Fit").performClick()
        mainClock.advanceTimeBy(SAVE_AFTER_MS + 100); waitForIdle()
        assertNull(saved.get())
    }

    @Test fun queuedLargeStrokeIsSavedBeforeModelFinishesAndDoesNotBlockTheWindow() {
        val folder = java.nio.file.Files.createTempDirectory("painting-editor-test").toFile()
        val model = Model(folder, hosting = false)
        model.start()
        val release = java.util.concurrent.CountDownLatch(1)
        try {
            val original = PaintingCopy(Painting.blank(1024, 1024))
            val path = model.vault.createFile("", "Large", "ora") { OraFile.save(original, it) }
            val session = PaintingSession(original) { _, _, _, _, _ -> }
            val waiting = java.util.concurrent.CountDownLatch(1)
            session.submit { assertFalse(javax.swing.SwingUtilities.isEventDispatchThread()); waiting.countDown(); release.await() }
            assertTrue(waiting.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val brush = BrushPresets.all.first { it.name == "Pixel" }.copy(size = 10f)
            session.submit { begin(100f, 100f, 1f, brush, 0xFF123456.toInt()); point(900f, 100f, 1f); end(900f, 100f, 1f) }
            // Leaving queues a future snapshot into Model immediately, even while the stroke waits.
            javax.swing.SwingUtilities.invokeAndWait {
                session.leave({}, true) { snapshot -> model.savePaintingOnLeave(path, snapshot) }
            }
            val finished = java.util.concurrent.CountDownLatch(1)
            model.afterSaves { finished.countDown() }
            // The painting and disk workers are waiting, but the UI event thread can still run.
            javax.swing.SwingUtilities.invokeAndWait { assertEquals(1L, finished.count) }
            release.countDown()
            assertTrue(finished.await(15, java.util.concurrent.TimeUnit.SECONDS))
            val loaded = OraFile.load(model.vault.readBytes(path))
            assertEquals(0xFF123456.toInt(), loaded.painting.active.pixels[100 * 1024 + 500])
        } finally { release.countDown(); model.stop(); folder.deleteRecursively() }
    }

    @Test fun mainOpensTheEditorAndLeavingSavesThroughModel() = runDesktopComposeUiTest(1200, 900) {
        val folder = java.nio.file.Files.createTempDirectory("painting-main-test").toFile()
        val model = Model(folder, hosting = false)
        model.start()
        var showing by mutableStateOf(true)
        try {
            val path = model.vault.createFile("", "Painting", "ora") { OraFile.save(PaintingCopy(Painting.blank(64, 64)), it) }
            model.reloadNow()
            setContent { MaterialTheme { if (showing) App(model, initialOpen = path) } }
            waitUntil(timeoutMillis = 5000) { onAllNodesWithContentDescription("Painting canvas").fetchSemanticsNodes().size == 1 }
            mainClock.autoAdvance = false
            onNodeWithContentDescription("Painting canvas").performMouseInput { moveTo(Offset(350f, 350f)); press(); release() }
            showing = false; mainClock.advanceTimeByFrame(); waitForIdle()
            val finished = java.util.concurrent.CountDownLatch(1)
            model.afterSaves { finished.countDown() }
            waitUntil(timeoutMillis = 10000) { finished.count == 0L }
            assertTrue(OraFile.load(model.vault.readBytes(path)).painting.active.pixels.any { it ushr 24 > 0 })
        } finally { model.stop(); folder.deleteRecursively() }
    }

    @Test fun metadataHistoryAndPublishedPixelsSurviveLaterStrokes() {
        val frames = java.util.concurrent.ConcurrentLinkedQueue<Pair<Long, PaintingCopy>>()
        val initial = PaintingCopy(Painting.blank(32, 32))
        val session = PaintingSession(initial) { copy, _, _, _, version -> frames += version to copy }
        val brush = BrushPresets.all.first { it.name == "Pixel" }.copy(size = 5f)
        session.submit { begin(10f, 10f, 1f, brush, 0xFF123456.toInt()); end(10f, 10f, 1f) }
        session.submit { edit { change { active.name = "Renamed" } } }
        session.submit { begin(10f, 10f, 1f, brush.copy(kind = BrushKind.ERASE), 0); end(10f, 10f, 1f) }
        session.submit { history(false); history(false); history(false) }
        val saved = AtomicReference<PaintingCopy?>()
        val done = java.util.concurrent.CountDownLatch(1)
        session.leave({ saved.set(it); done.countDown() }, true)
        assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS))
        javax.swing.SwingUtilities.invokeAndWait { }
        assertEquals("Layer 1", saved.get()!!.painting.active.name)
        assertTrue(saved.get()!!.painting.active.pixels.all { it == 0 })
        val painted = frames.first { it.first == 1L }.second
        assertEquals(0xFF123456.toInt(), painted.painting.active.pixels[10 * 32 + 10])
        assertEquals("Layer 1", painted.painting.active.name)
        val undoErase = frames.first { it.first == 4L }.second
        assertEquals("Renamed", undoErase.painting.active.name)
        assertArrayEquals(painted.painting.active.pixels, undoErase.painting.active.pixels)
    }

    @Test fun groupsAcceptMovedLayersAndBlockPainting() = runDesktopComposeUiTest(1200, 900) {
        val saved = AtomicReference<PaintingCopy?>()
        setContent { MaterialTheme { PaintingEditor(PaintingCopy(Painting.blank(64, 64)), saved::set) } }
        fun settle() { Thread.sleep(200); mainClock.advanceTimeByFrame(); waitForIdle() }
        onNodeWithText("Add group").performClick(); settle()
        onNodeWithContentDescription("Select group Group 1").assertExists()
        onNodeWithContentDescription("Select layer Layer 1").performClick(); settle()
        onNodeWithText("Up").performScrollTo().performClick(); settle()
        onNodeWithContentDescription("Select group Group 1").performScrollTo().performClick(); settle()
        onNodeWithText("That's a group. Pick a layer inside it to paint.").assertExists()
        onNodeWithContentDescription("Painting canvas").performMouseInput { moveTo(Offset(450f, 350f)); press(); release() }
        settle(); mainClock.advanceTimeBy(SAVE_AFTER_MS + 100); waitForIdle()
        waitUntil(timeoutMillis = 5000) { saved.get() != null }
        val p = saved.get()!!.painting
        assertTrue(p.activeNode is io.github.shoalapps.squill.paint.Group)
        assertEquals(1, (p.activeNode as io.github.shoalapps.squill.paint.Group).children.size)
        assertTrue(p.layers.single().pixels.all { it == 0 })
    }

}
