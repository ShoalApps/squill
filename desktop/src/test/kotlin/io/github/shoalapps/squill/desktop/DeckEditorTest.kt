package io.github.shoalapps.squill.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.github.shoalapps.squill.notes.Deck
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class DeckEditorTest {
    private val start = Deck(listOf(Deck.titleSlide(1), Deck.contentSlide(2)))

    @Test fun typingAndSlideButtonsAreSaved() = runDesktopComposeUiTest(1200, 1000) {
        var saved: Deck? = null
        setContent { MaterialTheme { DeckEditor(start, { saved = it }) } }
        onNodeWithContentDescription("Text 1").performTextReplacement("Boats")
        onNodeWithContentDescription("Speaker notes").performTextReplacement("Mention the sea")
        mainClock.advanceTimeBy(SAVE_AFTER_MS + 100)
        waitForIdle()
        assertEquals("Boats", saved!!.slides[0].elements[0].text)
        assertEquals("Mention the sea", saved!!.slides[0].notes)

        onNodeWithText("Move down").performClick()
        mainClock.advanceTimeBy(SAVE_AFTER_MS + 100)
        waitForIdle()
        assertEquals(listOf(2L, 1L), saved!!.slides.map { it.id })
        onNodeWithText("Slide 2 of 2").assertExists()

        onNodeWithText("New slide").performClick()
        onNodeWithText("Slide 3 of 3").assertExists()
        onNodeWithText("Undo").performClick()
        onNodeWithText("Slide 2 of 2").assertExists()
        onNodeWithText("Delete").performClick()
        onNodeWithText("Slide 1 of 1").assertExists()
        mainClock.advanceTimeBy(SAVE_AFTER_MS + 100)
        waitForIdle()
        assertEquals(listOf(2L), saved!!.slides.map { it.id })
    }

    @Test fun aChangeFromThePhoneShowsWhenNothingIsUnsaved() = runDesktopComposeUiTest(1200, 1000) {
        var deck by androidx.compose.runtime.mutableStateOf(start)
        setContent { MaterialTheme { DeckEditor(deck, {}) } }
        onNodeWithText("Slide 1 of 2").assertExists()
        deck = Deck(start.slides + Deck.contentSlide(3))
        waitForIdle()
        onNodeWithText("Slide 1 of 3").assertExists()
    }
}
