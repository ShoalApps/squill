package io.github.shoalapps.squill

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shoalapps.squill.notes.NotesViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Drags a real note onto another with a real gesture, finding them by looking at the screen. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class GraphFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val root = File(context.filesDir, "vault")
    private val vm get() = ViewModelProvider(rule.activity)[NotesViewModel::class.java]

    @Before fun clean() {
        root.listFiles()?.filter { it.name != ".seeded" }?.forEach { it.deleteRecursively() }
        File(root, "Alpha.md").writeText("first")
        File(root, "Beta.md").writeText("second")
        rule.runOnUiThread { vm.refresh() }
        rule.waitForIdle()
        Thread.sleep(300)
    }

    private fun graph() = rule.onNode(hasContentDescription("Graph of", substring = true))

    /** The filled dots: the two largest patches of the most common colour that isn't the background. */
    private fun dots(): List<Offset> {
        val map = graph().captureToImage().toPixelMap()
        val counts = HashMap<Int, Int>()
        for (y in 0 until map.height) for (x in 0 until map.width) counts.merge(map.buffer[map.bufferOffset + y * map.stride + x], 1, Int::plus)
        val ink = counts.entries.sortedByDescending { it.value }[1].key
        val seen = BooleanArray(map.width * map.height)
        val patches = mutableListOf<Pair<Int, Offset>>()
        for (y in 0 until map.height) for (x in 0 until map.width) {
            if (seen[y * map.width + x] || map.buffer[map.bufferOffset + y * map.stride + x] != ink) continue
            var n = 0; var sx = 0L; var sy = 0L
            val todo = ArrayDeque(listOf(x to y)); seen[y * map.width + x] = true
            while (todo.isNotEmpty()) {
                val (a, b) = todo.removeLast(); n++; sx += a; sy += b
                for ((c, d) in listOf(a + 1 to b, a - 1 to b, a to b + 1, a to b - 1)) {
                    if (c !in 0 until map.width || d !in 0 until map.height || seen[d * map.width + c]) continue
                    if (map.buffer[map.bufferOffset + d * map.stride + c] != ink) continue
                    seen[d * map.width + c] = true; todo.addLast(c to d)
                }
            }
            patches += n to Offset(sx.toFloat() / n, sy.toFloat() / n)
        }
        return patches.sortedByDescending { it.first }.take(2).map { it.second }
    }

    @Test fun droppingANoteOnAnotherLinksThem() {
        rule.onNodeWithText("Graph").performClick()
        rule.waitUntilExactlyOneExists(hasContentDescription("Graph of 2 notes", substring = true), 8000)
        rule.waitForIdle()
        val (from, to) = dots().also { assertEquals(2, it.size) }
        assertTrue("the dots should be apart: $from $to", (from - to).getDistance() > 100f)
        graph().performTouchInput { swipe(from, to, 800) }
        rule.waitUntil(8000) { "[[" in File(root, "Alpha.md").readText() + File(root, "Beta.md").readText() }
        rule.waitUntilExactlyOneExists(hasText("Linked", substring = true), 8000)
        rule.waitForIdle()
        rule.waitUntilExactlyOneExists(hasContentDescription("Graph of 2 notes and 1 links", substring = true), 4000)
        assertEquals(2, dots().size)
    }

}
