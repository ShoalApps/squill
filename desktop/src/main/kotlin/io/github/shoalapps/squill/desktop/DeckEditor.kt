package io.github.shoalapps.squill.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.shoalapps.squill.notes.Deck
import io.github.shoalapps.squill.notes.addSlide
import io.github.shoalapps.squill.notes.deleteSlide
import io.github.shoalapps.squill.notes.duplicateSlide
import io.github.shoalapps.squill.notes.moveSlide
import io.github.shoalapps.squill.notes.setElementText
import io.github.shoalapps.squill.notes.setSlideNotes
import kotlinx.coroutines.delay

/**
 * Words, speaker notes and the order of slides, from the keyboard. Laying out shapes and pictures is
 * still done on the phone. [onSave] gets each change shortly after typing stops, and [deck] changing
 * underneath, as when a phone syncs, is taken up unless there's something unsaved here.
 */
@Composable
fun DeckEditor(deck: Deck, onSave: (Deck) -> Unit, modifier: Modifier = Modifier) {
    var working by remember { mutableStateOf(deck) }
    var saved by remember { mutableStateOf(deck) }
    var index by remember { mutableIntStateOf(0) }
    var undo by remember { mutableStateOf(emptyList<Deck>()) }
    val save by rememberUpdatedState(onSave)
    var seen by remember { mutableStateOf(deck) }
    if (deck != seen) {
        seen = deck
        if (deck != saved && working == saved) { working = deck; saved = deck; undo = emptyList() }
    }
    index = index.coerceIn(working.slides.indices)
    LaunchedEffect(working) {
        if (working == saved) return@LaunchedEffect
        delay(SAVE_AFTER_MS)
        saved = working
        save(working)
    }
    // Leaving straight after typing mustn't lose it.
    DisposableEffect(Unit) { onDispose { if (working != saved) save(working) } }
    fun change(next: Deck) { if (next != working) { undo = (undo + working).takeLast(100); working = next } }
    val c = MaterialTheme.colorScheme
    val slide = working.slides[index]
    Row(modifier) {
        Column(Modifier.width(190.dp).fillMaxSize().padding(8.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            working.slides.forEachIndexed { i, s ->
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).border(if (i == index) 3.dp else 1.dp, if (i == index) c.primary else c.outlineVariant, RoundedCornerShape(6.dp))
                        .clickable { index = i }.semantics { contentDescription = "Slide ${i + 1}" },
                ) { SlidePane(s, Modifier.fillMaxWidth()) }
            }
        }
        Column(Modifier.weight(1f).fillMaxSize().background(c.surfaceContainerLow).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Slide ${index + 1} of ${working.slides.size}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 8.dp))
                TextButton(onClick = { change(working.addSlide(index)); index++ }) { Text("New slide") }
                TextButton(onClick = { change(working.duplicateSlide(index)); index++ }) { Text("Duplicate") }
                TextButton(enabled = index > 0, onClick = { change(working.moveSlide(index, index - 1)); index-- }) { Text("Move up") }
                TextButton(enabled = index < working.slides.lastIndex, onClick = { change(working.moveSlide(index, index + 1)); index++ }) { Text("Move down") }
                TextButton(enabled = working.slides.size > 1, onClick = { change(working.deleteSlide(index)) }) { Text("Delete") }
                TextButton(enabled = undo.isNotEmpty(), onClick = { working = undo.last(); undo = undo.dropLast(1) }) { Text("Undo") }
            }
            Box(Modifier.widthIn(max = 760.dp).fillMaxWidth()) { SlidePane(slide, Modifier.fillMaxWidth()) }
            slide.elements.filter { it.kind == "text" }.forEachIndexed { i, e ->
                val label = "Text ${i + 1}"
                OutlinedTextField(e.text, { change(working.setElementText(index, e.id, it)) }, label = { Text(label) }, modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().semantics { contentDescription = label })
            }
            OutlinedTextField(slide.notes, { change(working.setSlideNotes(index, it)) }, label = { Text("Speaker notes") }, minLines = 3, modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().semantics { contentDescription = "Speaker notes" })
            Text("Shapes, pictures and layout are edited in Squill on your phone.", style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
        }
    }
}

const val SAVE_AFTER_MS = 600L
