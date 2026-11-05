package io.github.shoalapps.squill.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.github.shoalapps.squill.notes.Sketch
import io.github.shoalapps.squill.notes.SketchText
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class SketchEditorTest {
    private val start = Sketch(Sketch.GRID, texts = listOf(SketchText(20f, 30f, "Keep", 42)))

    @Test fun drawMoveDeleteEraseUndoAndRedo() = runDesktopComposeUiTest(1200, 900) {
        var saved: Sketch? = null
        setContent { MaterialTheme { SketchEditor(start, { saved = it }) } }
        onNodeWithText("Blue").performClick()
        onNodeWithText("12 px").performClick()
        onNodeWithContentDescription("Drawing canvas with 0 strokes").performMouseInput {
            moveTo(Offset(300f, 300f)); press(); moveTo(Offset(500f, 300f), 100); release()
        }
        mainClock.advanceTimeBy(SAVE_AFTER_MS + 100)
        waitForIdle()
        val drawn = saved!!
        assertEquals(1, drawn.strokes.size)
        assertEquals(0xFF4A4FA3.toInt(), drawn.strokes.single().colour)
        assertEquals(12f, drawn.strokes.single().size)
        assertEquals(start.paper, drawn.paper)
        assertEquals(start.texts, drawn.texts)
        assertEquals(-1f, drawn.strokes.single().p(0))
        assertEquals(drawn, Sketch.parse(drawn.toSvg()))

        onNodeWithText("Select", useUnmergedTree = true).performClick()
        onNodeWithContentDescription("Drawing canvas with 1 strokes").performMouseInput {
            moveTo(Offset(400f, 300f)); press(); moveTo(Offset(450f, 350f), 100); release()
        }
        mainClock.advanceTimeBy(SAVE_AFTER_MS + 100)
        waitForIdle()
        assertTrue(saved!!.strokes.single().x(0) > drawn.strokes.single().x(0))
        onNodeWithText("Delete").performClick()
        onNodeWithContentDescription("Drawing canvas with 0 strokes").assertExists()
        onNodeWithText("Undo").performClick()
        onNodeWithContentDescription("Drawing canvas with 1 strokes").assertExists()
        onNodeWithText("Redo").performClick()
        onNodeWithContentDescription("Drawing canvas with 0 strokes").assertExists()
        onNodeWithText("Undo").performClick()
        onNodeWithText("Eraser").performClick()
        onNodeWithContentDescription("Drawing canvas with 1 strokes").performMouseInput {
            moveTo(Offset(450f, 350f)); press(); release()
        }
        onNodeWithContentDescription("Drawing canvas with 0 strokes").assertExists()
        // Clicking the canvas gives it keyboard focus.
        onNodeWithContentDescription("Drawing canvas with 0 strokes").performKeyInput {
            keyDown(Key.CtrlLeft); pressKey(Key.Z); keyUp(Key.CtrlLeft)
        }
        onNodeWithContentDescription("Drawing canvas with 1 strokes").assertExists()
        onNodeWithContentDescription("Drawing canvas with 1 strokes").performKeyInput {
            keyDown(Key.CtrlLeft); keyDown(Key.ShiftLeft); pressKey(Key.Z); keyUp(Key.ShiftLeft); keyUp(Key.CtrlLeft)
        }
        onNodeWithContentDescription("Drawing canvas with 0 strokes").assertExists()
    }

    @Test fun leavingImmediatelySavesAndKeepsTheLatestCallback() = runDesktopComposeUiTest(1200, 900) {
        var open by mutableStateOf(true)
        var callback by mutableStateOf(false)
        var saved: Sketch? = null
        setContent { MaterialTheme { if (open) SketchEditor(Sketch(), if (callback) ({ saved = it }) else ({})) } }
        mainClock.autoAdvance = false
        callback = true
        mainClock.advanceTimeByFrame()
        onNodeWithContentDescription("Drawing canvas with 0 strokes").performMouseInput {
            moveTo(Offset(300f, 300f)); press(); release()
        }
        open = false
        mainClock.advanceTimeByFrame()
        waitForIdle()
        assertEquals(1, saved!!.strokes.size)
    }

    @Test fun phoneChangesAreTakenUpOnlyWhenClean() = runDesktopComposeUiTest(1200, 900) {
        var sketch by mutableStateOf(Sketch())
        var saved: Sketch? = null
        setContent { MaterialTheme { SketchEditor(sketch, { saved = it }) } }
        val phone = Sketch(strokes = listOf(Sketch.line(0f, 0f, 100f, 0f, 42, 6f)))
        sketch = phone
        waitForIdle()
        onNodeWithContentDescription("Drawing canvas with 1 strokes").assertExists()
        mainClock.autoAdvance = false
        onNodeWithContentDescription("Drawing canvas with 1 strokes").performMouseInput {
            moveTo(Offset(300f, 300f)); press(); release()
        }
        sketch = Sketch()
        mainClock.advanceTimeByFrame()
        onNodeWithContentDescription("Drawing canvas with 2 strokes").assertExists()
        mainClock.advanceTimeBy(SAVE_AFTER_MS + 100)
        waitForIdle()
        assertEquals(phone.strokes.first(), saved!!.strokes.first())
        assertEquals(2, saved!!.strokes.size)
    }

    @Test fun handAndZoomLeaveTheDrawingAlone() = runDesktopComposeUiTest(1200, 900) {
        var saved: Sketch? = null
        val drawing = start.copy(strokes = listOf(Sketch.line(0f, 0f, 100f, 100f, 42, 6f)))
        setContent { MaterialTheme { SketchEditor(drawing, { saved = it }) } }
        onNodeWithText("Hand").performClick()
        onNodeWithContentDescription("Drawing canvas with 1 strokes").performMouseInput {
            moveTo(Offset(300f, 300f)); press(); moveTo(Offset(500f, 400f), 100); release()
            scroll(-2f)
        }
        onNodeWithText("Fit").performClick()
        mainClock.advanceTimeBy(SAVE_AFTER_MS + 100)
        assertNull(saved)
    }

    @Test fun drawingAfterPanAndZoomUsesPaperCoordinates() = runDesktopComposeUiTest(1200, 900) {
        var saved: Sketch? = null
        setContent { MaterialTheme { SketchEditor(Sketch(), { saved = it }) } }
        onNodeWithContentDescription("Drawing canvas with 0 strokes").performMouseInput {
            moveTo(Offset(300f, 300f)); press(); release()
        }
        onNodeWithText("Hand").performClick()
        onNodeWithContentDescription("Drawing canvas with 1 strokes").performMouseInput {
            moveTo(Offset(300f, 300f)); press(); moveTo(Offset(500f, 400f), 100); release()
            scroll(-2f)
        }
        onNodeWithText("Pen").performClick()
        onNodeWithContentDescription("Drawing canvas with 1 strokes").performMouseInput {
            moveTo(Offset(500f, 400f)); press(); release()
        }
        mainClock.advanceTimeBy(SAVE_AFTER_MS + 100)
        waitForIdle()
        val strokes = saved!!.strokes
        assertEquals(2, strokes.size)
        assertEquals(strokes[0].x(0), strokes[1].x(0), .01f)
        assertEquals(strokes[0].y(0), strokes[1].y(0), .01f)
    }

}
