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
import io.github.shoalapps.squill.notes.Deck
import io.github.shoalapps.squill.notes.Docx
import io.github.shoalapps.squill.notes.NotesViewModel
import io.github.shoalapps.squill.notes.Pdf
import io.github.shoalapps.squill.notes.Pptx
import io.github.shoalapps.squill.notes.Slide
import io.github.shoalapps.squill.notes.Element
import io.github.shoalapps.squill.notes.Vault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class DeckFlowTest {
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

    private fun saved(path: String = "Presentation.deck") = Deck.parse(File(root, path).readText())
    private fun waitFor(check: () -> Boolean) = rule.waitUntil(8000, check)
    private fun open() {
        rule.onNodeWithContentDescription("New checklist, drawing or presentation").performClick()
        rule.onNodeWithText("New presentation").performClick()
        rule.waitUntilExactlyOneExists(hasContentDescription("Slide 1 of 1", substring = true), 8000)
    }

    @Test fun slidesCanBeAddedAndEverythingSaves() {
        open()
        rule.onNodeWithText("Add slide").performClick()
        rule.waitUntilExactlyOneExists(hasContentDescription("Slide 2 of 2", substring = true), 8000)
        rule.onNodeWithText("Box").performClick()
        rule.waitUntilExactlyOneExists(hasContentDescription("Slide 2 of 2, 3 items"), 8000)
        waitFor { saved()?.slides?.size == 2 && saved()!!.slides[1].elements.size == 3 }
    }

    @Test fun aSelectedThingCanBeMoved() {
        open()
        val before = saved()!!.slides[0].elements[0]
        val slide = rule.onNodeWithContentDescription("Slide 1 of 1", substring = true)
        slide.performTouchInput { swipe(Offset(width * .5f, height * .4f), Offset(width * .7f, height * .6f), 300) }
        waitFor { saved()!!.slides[0].elements[0].x != before.x }
        assertTrue(saved()!!.slides[0].elements[0].y > before.y)
    }

    @Test fun presentingRevealsInStepsThenMovesOn() {
        val deck = Deck(listOf(
            Slide(1, elements = listOf(Element(1, "text", 0f, 0f, 400f, 100f, "Always"), Element(2, "text", 0f, 200f, 400f, 100f, "Later", step = 1))),
            Slide(2, elements = listOf(Element(3, "text", 0f, 0f, 400f, 100f, "Second slide"))),
        ))
        vault.createDeck("", "Talk", deck)
        rule.runOnUiThread { vm.refresh() }
        rule.waitUntilExactlyOneExists(hasText("Talk"), 8000)
        rule.onNodeWithText("Talk").performClick()
        rule.waitUntilExactlyOneExists(hasContentDescription("Present"), 8000)
        rule.onNodeWithContentDescription("Present").performClick()
        rule.waitUntilExactlyOneExists(hasContentDescription("Presenting slide 1 of 2, step 0"), 8000)
        val stage = rule.onNodeWithContentDescription("Presenting", substring = true)
        rule.onNodeWithText("Later").assertDoesNotExist()
        stage.performTouchInput { click(Offset(width * .8f, height / 2f)) }
        rule.waitUntilExactlyOneExists(hasContentDescription("Presenting slide 1 of 2, step 1"), 8000)
        stage.performTouchInput { click(Offset(width * .8f, height / 2f)) }
        rule.waitUntilExactlyOneExists(hasContentDescription("Presenting slide 2 of 2, step 0"), 8000)
        stage.performTouchInput { click(Offset(width * .1f, height / 2f)) }
        rule.waitUntilExactlyOneExists(hasContentDescription("Presenting slide 1 of 2, step 1"), 8000)
    }

    @Test fun androidsOwnXmlParserReadsWordAndPowerPoint() {
        val word = ByteArrayOutputStream().also { Docx.export("# Hi\n\nSome **bold**\n", it) }.toByteArray()
        assertEquals("# Hi\n\nSome **bold**\n", Docx.import(ByteArrayInputStream(word)))
        val slides = ByteArrayOutputStream().also { Pptx.export(Deck(), it) }.toByteArray()
        assertEquals("Your title", Pptx.import(ByteArrayInputStream(slides)).slides[0].elements[0].text)
    }

    @Test fun pdfsArePaginated() {
        val out = ByteArrayOutputStream()
        val long = (1..400).joinToString("\n\n") { "Paragraph **$it** with some words in it to fill the line up nicely." }
        val pages = Pdf.export("Long note", "# Heading\n\n$long\n\n- [x] done\n\n| a | b |\n|---|---|\n| 1 | 2 |\n", out)
        assertTrue("only $pages pages", pages > 3)
        assertEquals("%PDF", out.toByteArray().copyOf(4).toString(Charsets.US_ASCII))
    }
}
