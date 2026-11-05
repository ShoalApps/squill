package io.github.shoalapps.squill

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shoalapps.squill.notes.NotesViewModel
import io.github.shoalapps.squill.notes.Vault
import io.github.shoalapps.squill.paint.Blend
import io.github.shoalapps.squill.paint.Layer
import io.github.shoalapps.squill.paint.OraFile
import io.github.shoalapps.squill.paint.Painting
import io.github.shoalapps.squill.paint.alphaOf
import io.github.shoalapps.squill.paint.frozen
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PaintFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val root = File(context.filesDir, "vault")
    private val vm get() = ViewModelProvider(rule.activity)[NotesViewModel::class.java]

    @Before fun clean() {
        root.listFiles()?.filter { it.name != ".seeded" }?.forEach { it.deleteRecursively() }
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().remove("paint-mode").commit()
        rule.runOnUiThread { vm.refresh() }
        rule.waitForIdle()
        Thread.sleep(300)
    }

    private fun waitFor(ms: Long = 15000, check: () -> Boolean) = rule.waitUntil(ms, check)
    private fun saved() = OraFile.load(File(root, "Painting.ora").readBytes())
    private fun canvas() = rule.onNode(hasContentDescription("Painting canvas", substring = true))
    private fun open(advanced: Boolean = false) {
        if (advanced) context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("paint-mode", "ADVANCED").commit()
        rule.onNodeWithContentDescription("New checklist, drawing, painting or presentation").performClick()
        rule.onNodeWithText("New painting").performClick()
        rule.waitUntilExactlyOneExists(hasContentDescription("Painting canvas", substring = true), 15000)
        rule.waitForIdle()
    }
    private fun paint() = canvas().performTouchInput { swipe(Offset(width * .3f, height * .4f), Offset(width * .7f, height * .6f), 300) }
    private fun back() { rule.onNodeWithContentDescription("Back").performClick(); rule.waitForIdle() }

    @Test fun aNewPaintingIsAValidFile() {
        open()
        val p = saved()
        assertEquals(2048, p.width)
        assertEquals(1, p.layers.size)
    }

    @Test fun simpleModePaintsAndSaves() {
        open()
        paint()
        waitFor { saved().layers[0].pixels.any { alphaOf(it) > 0 } }
    }

    @Test fun leavingStraightAwaySaves() {
        open()
        paint()
        rule.waitForIdle()
        back()
        waitFor { saved().layers[0].pixels.any { alphaOf(it) > 0 } }
    }

    @Test fun undoTakesAStrokeBack() {
        open()
        paint()
        rule.waitUntilExactlyOneExists(hasContentDescription("Undo") and androidx.compose.ui.test.isEnabled(), 8000)
        rule.onNodeWithContentDescription("Undo").performClick()
        rule.waitForIdle()
        back()
        waitFor { saved().layers[0].pixels.none { alphaOf(it) > 0 } }
    }

    @Test fun layersAreAddedInTheSheet() {
        open(advanced = true)
        rule.onNodeWithText("Layers").performClick()
        rule.waitUntilExactlyOneExists(hasText("Add layer"), 8000)
        rule.onNodeWithText("Add layer").performClick()
        rule.waitForIdle()
        waitFor { saved().layers.size == 2 }
    }

    @Test fun rulerGuidesBrushStrokesAlongTheCanvasMiddle() {
        open(advanced = true)
        rule.onNodeWithContentDescription("Open guides").performScrollTo().performClick()
        rule.onNodeWithText("Add ruler").performClick()
        // There is also a Brush sheet button; this selects the tool chip.
        rule.onNodeWithContentDescription("Brush tool").performScrollTo().performClick()
        canvas().performTouchInput { swipe(Offset(width * .3f, height * .3f), Offset(width * .7f, height * .7f), 400) }
        waitFor { val p = saved(); p.assistants.size == 1 && p.active.pixels.any { alphaOf(it) > 0 } }
        val p = saved()
        val ys = p.active.pixels.indices.filter { alphaOf(p.active.pixels[it]) > 0 }.map { it / p.width }
        assertTrue(ys.max() - ys.min() <= 10 + 4)
        assertEquals(p.height / 2f, (ys.min() + ys.max()) / 2f, 2f)
    }

    @Test fun theFillToolFloods() {
        open(advanced = true)
        rule.onNodeWithText("Fill").performScrollTo().performClick()
        canvas().performTouchInput { click(Offset(width / 2f, height / 2f)) }
        waitFor { saved().layers[0].pixels.all { alphaOf(it) == 255 } }
    }

    @Test fun selectingLimitsTheFill() {
        open(advanced = true)
        rule.onNodeWithText("Select box").performScrollTo().performClick()
        canvas().performTouchInput { swipe(Offset(width * .4f, height * .4f), Offset(width * .6f, height * .6f), 200) }
        rule.waitUntilExactlyOneExists(hasText("Deselect"), 8000)
        rule.onNodeWithText("Fill").performScrollTo().performClick()
        canvas().performTouchInput { click(Offset(width / 2f, height / 2f)) }
        waitFor { saved().layers[0].pixels.any { alphaOf(it) == 255 } }
        val filled = saved().layers[0].pixels.count { alphaOf(it) == 255 }
        assertTrue("$filled pixels", filled < 2048 * 1536 / 4)
    }

    @Test fun filtersRun() {
        open(advanced = true)
        rule.onNodeWithText("Fill").performScrollTo().performClick()
        canvas().performTouchInput { click(Offset(width / 2f, height / 2f)) }
        rule.onNodeWithText("Filters").performScrollTo().performClick()
        rule.waitUntilExactlyOneExists(hasText("Invert"), 8000)
        rule.onNodeWithText("Invert").performScrollTo().performClick()
        rule.onNodeWithText("Apply invert").performClick()
        // Black paint inverted is white.
        waitFor { saved().layers[0].pixels[0] == 0xFFFFFFFF.toInt() }
    }

    @Test fun modesChangeWhatsShownAndAreRemembered() {
        open()
        rule.onNodeWithText("Filters").assertDoesNotExist()
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Advanced mode").performClick()
        rule.onNodeWithText("Filters").assertExists()
        assertEquals("ADVANCED", context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("paint-mode", null))
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Focus mode").performClick()
        rule.onNodeWithContentDescription("Undo").assertDoesNotExist()
        rule.onNodeWithText("Show tools").performClick()
        rule.onNodeWithContentDescription("Undo").assertExists()
    }

    @Test fun layersSurviveAFileRoundTrip() {
        val p = Painting(64, 48)
        val a = Layer(1, "Bottom <one>", IntArray(64 * 48) { 0xFF112233.toInt() })
        val b = Layer(2, "Top", IntArray(64 * 48) { if (it % 2 == 0) 0x80FF0000.toInt() else 0 }, visible = false, opacity = .5f, blend = Blend.MULTIPLY, alphaLock = true, clipped = true)
        p.root.children += a; p.root.children += b
        val bytes = ByteArrayOutputStream().also { OraFile.save(p.frozen(), it) }.toByteArray()
        val back = OraFile.load(bytes)
        assertEquals(64, back.width)
        assertEquals(listOf("Bottom <one>", "Top"), back.layers.map { it.name })
        assertArrayEquals(a.pixels, back.layers[0].pixels)
        assertEquals(.5f, back.layers[1].opacity, .001f)
        assertEquals(Blend.MULTIPLY, back.layers[1].blend)
        assertTrue(back.layers[1].alphaLock && back.layers[1].clipped && !back.layers[1].visible)
        assertEquals(0x80FF0000.toInt(), back.layers[1].pixels[0])
        File(rule.activity.getExternalFilesDir(null), "sample.ora").writeBytes(bytes)
    }
    @Test fun groupsMoveLayersAndSurviveReopening() {
        open(advanced = true)
        rule.onNodeWithText("Layers").performClick()
        rule.waitUntilExactlyOneExists(hasText("Add group"), 8000)
        rule.onNodeWithText("Add group").performClick()
        rule.onNodeWithText("Layer 1", substring = false).performClick()
        rule.onNodeWithText("Move up").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Show Group 1").performScrollTo().performClick()
        waitFor {
            val g = saved().root.children.singleOrNull() as? io.github.shoalapps.squill.paint.Group
            g != null && !g.visible && g.children.singleOrNull() is Layer
        }
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithText("Painting").performClick()
        rule.waitUntilExactlyOneExists(hasContentDescription("Painting canvas", substring = true), 15000)
        rule.onNodeWithText("Layers").performClick()
        rule.onNodeWithText("Group 1", substring = false).assertExists()
        rule.onNodeWithText("Layer 1", substring = false).assertExists()
        assertTrue(!(saved().root.children.single() as io.github.shoalapps.squill.paint.Group).visible)
    }

    @Test fun leavingDuringATransformKeepsTheArtwork() {
        open(advanced = true)
        paint()
        waitFor { saved().layers[0].pixels.any { alphaOf(it) > 0 } }
        val painted = saved().layers[0].pixels.count { alphaOf(it) > 0 }
        paint()
        rule.onNodeWithText("Transform").performScrollTo().performClick()
        rule.waitForIdle()
        back()
        rule.waitForIdle()
        waitFor { saved().layers[0].pixels.count { alphaOf(it) > 0 } >= painted }
    }

    @Test fun aPaintingWithAMissingLayerIsRefusedNotBlanked() {
        val out = ByteArrayOutputStream()
        val zip = java.util.zip.ZipOutputStream(out)
        zip.putNextEntry(java.util.zip.ZipEntry("stack.xml"))
        zip.write("<image w=\"4\" h=\"4\"><stack><layer name=\"a\" src=\"data/gone.png\"/></stack></image>".toByteArray())
        zip.closeEntry(); zip.close()
        val refused = runCatching { OraFile.load(out.toByteArray()) }
        assertTrue(refused.exceptionOrNull() is io.github.shoalapps.squill.notes.OfficeException)
    }
}
