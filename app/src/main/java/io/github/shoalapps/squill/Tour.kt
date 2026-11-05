package io.github.shoalapps.squill

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private class TourPage(val act: SquillyAct, val title: String, val says: String, val points: List<String>)

private val Pages = listOf(
    TourPage(SquillyAct.WAVE, "Hello, I'm Squilly", "Let me show you around. I've got plenty of arms for pointing.", listOf("Squill keeps your notes, drawings, paintings, slides and reminders in one place.", "You can take this tour again any time from Settings.")),
    TourPage(SquillyAct.WRITE, "Notes", "Just start writing. I save everything as you go.", listOf("Tap New note and type. The eye and pencil at the top switch between reading and writing.", "Pin a note, give it a colour, or sort notes into folders. Add #tags to find them later.", "The small + button makes checklists, drawings, paintings and presentations. Long-press a card for more.")),
    TourPage(SquillyAct.LINK, "Links and the graph", "Drag one dot onto another and I'll link those notes.", listOf("Type [[ in a note to link to another one. Each note shows what links back to it.", "The Graph tab shows every note as a dot and every link as a line.", "Pinch to zoom, and tap a dot to open that note.")),
    TourPage(SquillyAct.SKETCH, "Drawing", "Quick sketches, with very little delay. Lovely with a pen.", listOf("Once you've used a pen, fingers only move the page around, so a resting palm draws nothing.", "A pen's eraser end, or its button, rubs things out.", "The brush button while you're writing puts a drawing right inside your note.")),
    TourPage(SquillyAct.PAINT, "Painting", "This is where things get messy, in the best way.", listOf("A full studio with layers, blend modes, brushes, selections and filters.", "Simple mode keeps things easy. The menu switches to Advanced for everything, or Focus for just the canvas.", "Open pictures in Squill from other apps, and save in a format Krita can open.")),
    TourPage(SquillyAct.SLIDES, "Slides and documents", "Make a whole presentation on your phone. I'll hold the clicker.", listOf("Drag things around, tap Play to present, and have things appear click by click.", "Open and save Word and PowerPoint files, and save notes as PDFs.")),
    TourPage(SquillyAct.BELL, "Reminders", "I'll ring at the exact minute. Very punctual, squid.", listOf("The Reminders tab has alarms, once or on a repeat, with Done and Snooze on the notification.", "Any note's menu has Remind me, and there's a widget for your home screen.")),
    TourPage(SquillyAct.LOCK, "Keeping things private", "No accounts, no cloud, no tracking. I can keep a secret.", listOf("Locked notes have their own password, and lock again when you leave.", "Settings can encrypt all your notes, or sync them directly with your computer.")),
)

@Composable
private fun SquillyStage(act: SquillyAct, says: String) {
    val c = MaterialTheme.colorScheme
    Squilly(act, ink = c.onSurface, paper = c.surface, accent = c.primary, modifier = Modifier.size(width = 280.dp, height = 200.dp))
    Surface(color = c.secondaryContainer, shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)) {
        Text(says, style = MaterialTheme.typography.bodyLarge, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = c.onSecondaryContainer, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
    }
}

/** What each part of Squill does, shown once, and whenever it's asked for from Settings. */
@Composable
fun TourScreen(onDone: (chooseFolder: Boolean) -> Unit) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    val total = Pages.size + 1
    Scaffold { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onDone(false) }) { Text("Skip") }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().widthIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val page = Pages.getOrNull(index)
                if (page != null) {
                    SquillyStage(page.act, page.says)
                    Text(page.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    page.points.forEach { Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth()) }
                } else {
                    SquillyStage(SquillyAct.HOME, "Last thing, then I'll get out of your way.")
                    Text("Where should your notes live?", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("They start out private to Squill, where no other app can reach them.", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth())
                    Text("Or keep them in Documents/Squill, as ordinary files that apps like file managers and Obsidian can open.", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth())
                    Text("You can change this later in Settings.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { onDone(false) }, modifier = Modifier.fillMaxWidth()) { Text("Keep them private") }
                    OutlinedButton(onClick = { onDone(true) }, modifier = Modifier.fillMaxWidth()) { Text("Use Documents/Squill") }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (index > 0) TextButton(onClick = { index-- }) { Text("Back") } else Spacer(Modifier.size(64.dp))
                Row(Modifier.weight(1f).semantics { contentDescription = "Step ${index + 1} of $total" }, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                    repeat(total) { i ->
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (i == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
                    }
                }
                if (index < total - 1) Button(onClick = { index++ }) { Text("Next") } else Spacer(Modifier.size(64.dp))
            }
        }
    }
}
