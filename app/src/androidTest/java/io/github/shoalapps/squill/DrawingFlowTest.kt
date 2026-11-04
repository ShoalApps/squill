package io.github.shoalapps.squill

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shoalapps.squill.notes.NotesViewModel
import io.github.shoalapps.squill.notes.Sketch
import io.github.shoalapps.squill.notes.Vault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class DrawingFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val root = File(context.filesDir, "vault")
    private val vault = Vault(root)
    private val vm get() = ViewModelProvider(rule.activity)[NotesViewModel::class.java]

    @Before fun clean() {
        root.listFiles()?.filter { it.name != ".seeded" }?.forEach { it.deleteRecursively() }
        rule.runOnUiThread { vm.refresh() }
        rule.waitForIdle()
        Thread.sleep(300)
    }

    private fun waitFor(check: () -> Boolean) = rule.waitUntil(8000, check)
    private fun saved(path: String = "Drawing.svg") = Sketch.parse(File(root, path).readText())
    private fun open() {
        rule.onNodeWithContentDescription("New checklist, drawing or presentation").performClick()
        rule.onNodeWithText("New drawing").performClick()
        rule.waitUntilExactlyOneExists(hasContentDescription("Drawing canvas with 0 strokes"), 8000)
    }
    private fun canvas() = rule.onNode(androidx.compose.ui.test.hasContentDescription("Drawing canvas", substring = true))
    private fun stroke(y: Float) = canvas().performTouchInput { swipe(Offset(100f, y), Offset(500f, y + 40f), 300) }
    private fun back() { rule.onNodeWithContentDescription("Back").performClick(); rule.waitForIdle() }

    @Test fun aStrokeIsDrawnAndSaved() {
        open()
        stroke(300f)
        waitFor { saved()?.strokes?.size == 1 }
        assertTrue(saved()!!.strokes.single().count > 1)
    }

    @Test fun undoAndRedoWork() {
        open()
        stroke(300f)
        rule.waitUntilExactlyOneExists(hasContentDescription("Drawing canvas with 1 strokes"), 8000)
        rule.waitUntilExactlyOneExists(hasContentDescription("Undo") and androidx.compose.ui.test.isEnabled(), 8000)
        rule.onNodeWithContentDescription("Undo").performClick()
        rule.waitForIdle()
        rule.waitUntilExactlyOneExists(hasContentDescription("Drawing canvas with 0 strokes"), 8000)
        rule.onNodeWithContentDescription("Redo").performClick()
        rule.waitUntilExactlyOneExists(hasContentDescription("Drawing canvas with 1 strokes"), 8000)
    }

    @Test fun theEraserTakesAStrokeAway() {
        open()
        stroke(300f)
        rule.waitUntilExactlyOneExists(hasContentDescription("Drawing canvas with 1 strokes"), 8000)
        rule.onNodeWithText("Eraser").performScrollTo().performClick()
        canvas().performTouchInput { swipe(Offset(300f, 200f), Offset(300f, 500f), 300) }
        rule.waitUntilExactlyOneExists(hasContentDescription("Drawing canvas with 0 strokes"), 8000)
        waitFor { saved()?.strokes?.isEmpty() == true }
    }

    @Test fun shapesAreDrawn() {
        open()
        rule.onNodeWithText("Box").performScrollTo().performClick()
        stroke(300f)
        rule.waitUntilExactlyOneExists(hasContentDescription("Drawing canvas with 1 strokes"), 8000)
        rule.onNodeWithText("Arrow").performScrollTo().performClick()
        stroke(500f)
        rule.waitUntilExactlyOneExists(hasContentDescription("Drawing canvas with 4 strokes"), 8000)
    }

    @Test fun textIsPlacedAndSaved() {
        open()
        rule.onNodeWithText("Text").performScrollTo().performClick()
        canvas().performTouchInput { click(Offset(200f, 300f)) }
        rule.waitUntilExactlyOneExists(hasText("Add text"), 8000)
        rule.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("Boats")
        rule.onNodeWithText("Add").performClick()
        waitFor { saved()?.texts?.singleOrNull()?.text == "Boats" }
    }

    @Test fun leavingStraightAwayStillSaves() {
        open()
        stroke(300f)
        rule.waitUntilExactlyOneExists(hasContentDescription("Drawing canvas with 1 strokes"), 8000)
        back()
        assertEquals(1, saved()!!.strokes.size)
    }

    @Test fun aDrawingComesBackWhenReopened() {
        vault.createSketch("", "Saved", Sketch(strokes = listOf(Sketch.line(0f, 0f, 400f, 400f, 0, 6f))))
        rule.runOnUiThread { vm.refresh() }
        rule.waitUntilExactlyOneExists(hasText("Saved"), 8000)
        rule.onNodeWithText("Saved").performClick()
        rule.waitUntilExactlyOneExists(hasContentDescription("Drawing canvas with 1 strokes"), 8000)
    }

    @Test fun aNoteShowsItsDrawing() {
        vault.createSketch("", "Boat", Sketch(strokes = listOf(Sketch.line(0f, 0f, 400f, 400f, 0, 6f))))
        vault.create("", "Voyage", "Look:\n![[Boat.svg]]\n")
        rule.runOnUiThread { vm.refresh() }
        rule.waitUntilExactlyOneExists(hasText("Voyage"), 8000)
        rule.onNodeWithText("Voyage").performClick()
        rule.waitUntilExactlyOneExists(hasContentDescription("Drawing: Boat"), 8000)
        rule.onNodeWithContentDescription("Drawing: Boat").performClick()
        rule.waitUntilExactlyOneExists(hasContentDescription("Drawing canvas with 1 strokes"), 8000)
    }
}

/** Opening a vault the size people report long waits at. */
@RunWith(AndroidJUnit4::class)
class BigVaultTest {
    @Test fun tenThousandNotesOpenQuickly() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val root = File(context.filesDir, "vault")
        val index = File(context.cacheDir, "notes-index.json")
        root.listFiles()?.filter { it.name != ".seeded" }?.forEach { it.deleteRecursively() }
        index.delete()
        repeat(10_000) { i -> File(root, "n$i.md").writeText("# Note $i\nSee [[n${(i * 7) % 10_000}]] #tag${i % 40}\n- [ ] task\n") }
        fun launch(): Long {
            val start = System.nanoTime()
            var ms = -1L
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val deadline = System.currentTimeMillis() + 60_000
                while (ms < 0 && System.currentTimeMillis() < deadline) {
                    scenario.onActivity { a ->
                        if (ViewModelProvider(a)[NotesViewModel::class.java].library.value.notes.size == 10_000) ms = (System.nanoTime() - start) / 1_000_000
                    }
                    if (ms < 0) Thread.sleep(10)
                }
                Thread.sleep(3500) // Long enough for the index to be written.
            }
            return ms
        }
        val first = launch()
        val second = launch()
        println("SCALE first open ${first}ms, later open ${second}ms")
        android.util.Log.i("SquillScale", "first open ${first}ms, later open ${second}ms")
        root.listFiles()?.filter { it.name != ".seeded" }?.forEach { it.deleteRecursively() }
        index.delete()
        assertTrue("first open took ${first}ms", first in 0..30_000)
        assertTrue("later open took ${second}ms", second in 0..5_000)
    }
}
