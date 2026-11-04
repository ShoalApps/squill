package io.github.shoalapps.squill

import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import java.util.Date

/** What's next, up to three of them, and a + to jot something down. */
class SquillWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val now = System.currentTimeMillis()
        val next = Store.load(context).filter { !it.done }
            .mapNotNull { r -> r.nextAfter(now)?.let { r to it } }
            .sortedBy { it.second }.take(3)
            .map { (r, at) -> r.title to widgetWhen(context, at, r.section(now)) }
        val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val jot = Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_JOT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        provideContent {
            GlanceTheme {
                Column(
                    GlanceModifier.fillMaxSize().cornerRadius(24.dp).background(GlanceTheme.colors.widgetBackground)
                        .padding(14.dp).clickable(actionStartActivity(open)),
                ) {
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Squill",
                            modifier = GlanceModifier.defaultWeight(),
                            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                        )
                        Image(
                            ImageProvider(R.drawable.ic_add), contentDescription = "Jot down",
                            colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                            modifier = GlanceModifier.size(28.dp).clickable(actionStartActivity(jot)),
                        )
                    }
                    Spacer(GlanceModifier.height(6.dp))
                    if (next.isEmpty()) Line("Nothing inked yet", null)
                    next.forEach { (title, whenText) -> Line(title, whenText) }
                }
            }
        }
    }

    @Composable
    private fun Line(title: String, whenText: String?) {
        Column(GlanceModifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text(title, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp))
            if (whenText != null) Text(whenText, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
        }
    }

    private fun widgetWhen(context: Context, at: Long, section: Section): String {
        val time = DateFormat.getTimeFormat(context).format(Date(at))
        return when (section) {
            Section.TODAY -> time
            Section.TOMORROW -> "Tomorrow, $time"
            else -> DateFormat.getMediumDateFormat(context).format(Date(at)) + ", $time"
        }
    }
}

class SquillWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = SquillWidget()
}
