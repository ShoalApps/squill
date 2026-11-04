package io.github.shoalapps.squill

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shoalapps.squill.notes.NotesViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Draws the app at sizes the test device isn't, and saves pictures to look at. */
@RunWith(AndroidJUnit4::class)
class LayoutShots {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun shot(name: String, width: Int, height: Int) {
        val provider = ViewModelProvider(rule.activity)
        val notes = provider[NotesViewModel::class.java]
        val reminders = provider[RemindersViewModel::class.java]
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, height.dp))) {
                SquillTheme { SquillHome(reminders, notes) }
            }
        }
        rule.waitForIdle()
        Thread.sleep(800)
        rule.waitForIdle()
        val image = rule.onAllNodes(isRoot()).onFirst().captureToImage().asAndroidBitmap()
        val dir = rule.activity.getExternalFilesDir(null)!!
        File(dir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun tablet() = shot("tablet", 1000, 700)
    @Test fun smallTablet() = shot("small-tablet", 700, 900)
}
