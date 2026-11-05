package io.github.shoalapps.squill

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.PI
import kotlin.math.sin

/** What Squilly is up to on each page of the tour. */
enum class SquillyAct { WAVE, WRITE, LINK, SKETCH, PAINT, SLIDES, BELL, LOCK, HOME }

/**
 * Squilly, Squill's squid, drawn entirely in code so every arm can move. [ink] and [paper] are the
 * squid's colours, and [accent] brings the props to life. Stays still if animations are switched off.
 */
@Composable
fun Squilly(act: SquillyAct, ink: Color, paper: Color, accent: Color, modifier: Modifier = Modifier) {
    val animated = remember { android.animation.ValueAnimator.areAnimatorsEnabled() }
    val clock = rememberInfiniteTransition(label = "squilly")
    val runningT by clock.animateFloat(0f, 1f, infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Restart), label = "t")
    val t = if (animated) runningT else .35f
    Canvas(modifier.semantics { contentDescription = "Squilly the squid" }) {
        val unit = size.minDimension / 200f
        val cx = size.width / 2f
        val cy = size.height / 2f
        scale(unit, unit, Offset(cx, cy)) {
            translate(cx - 100f, cy - 100f) {
                val alone = act == SquillyAct.WAVE || act == SquillyAct.HOME
                translate(if (alone) 0f else 40f, 0f) { prop(act, t, ink, paper, accent) }
                val bob = sin(t * 2f * PI.toFloat()) * 5f
                translate(if (alone) 0f else -40f, bob) { squid(act, t, ink, paper, accent) }
            }
        }
    }
}

private fun wave(t: Float, speed: Float = 1f, phase: Float = 0f) = sin((t * speed + phase) * 2f * PI.toFloat())

private fun DrawScope.squid(act: SquillyAct, t: Float, ink: Color, paper: Color, accent: Color) {
    // Arms first, so the body sits over them.
    val starts = listOf(82f, 90f, 98f, 106f, 114f, 122f)
    starts.forEachIndexed { i, x ->
        val writing = i == 5 && act != SquillyAct.WAVE
        val waving = i == 5 && act == SquillyAct.WAVE
        val path = Path().apply {
            moveTo(x, 112f)
            val sway = wave(t, 1f, i * .17f) * 9f
            when {
                waving -> { val lift = wave(t, 2f) * 12f; cubicTo(x + 18f, 112f, x + 26f, 96f, x + 34f + lift * .4f, 74f + lift) }
                writing -> { val reach = wave(t, 1f) * 4f; cubicTo(x + 14f, 122f, x + 26f, 122f, x + 40f + reach, 112f + reach) }
                else -> {
                    val spread = (x - 102f) * .8f
                    cubicTo(x + spread * .3f, 130f, x + spread * .8f - sway, 144f, x + spread + sway, 160f)
                    relativeQuadraticTo(sway * .4f + spread * .3f, 8f, sway + spread * .5f, 2f)
                }
            }
        }
        drawPath(path, ink, style = Stroke(7f, cap = StrokeCap.Round))
    }
    // The mantle, with its two fins.
    val fin = wave(t, 2f) * 3f
    val body = Path().apply {
        moveTo(102f, 24f)
        cubicTo(112f, 36f, 121f, 50f, 124f, 66f)
        lineTo(146f + fin, 84f - fin)
        cubicTo(136f, 94f, 128f, 94f, 124f, 92f)
        cubicTo(128f, 112f, 120f, 122f, 102f, 122f)
        cubicTo(84f, 122f, 76f, 112f, 80f, 92f)
        cubicTo(76f, 94f, 68f, 94f, 58f - fin, 84f - fin)
        lineTo(80f, 66f)
        cubicTo(83f, 50f, 92f, 36f, 102f, 24f)
        close()
    }
    drawPath(body, ink)
    // Eyes that blink now and then, and glance at what's going on.
    val blink = (t * 3f) % 1f in .92f..1f
    val look = if (act == SquillyAct.WAVE || act == SquillyAct.HOME) 0f else 2f
    listOf(91f, 113f).forEach { x ->
        if (blink) drawLine(paper, Offset(x - 6f, 96f), Offset(x + 6f, 96f), 3f, StrokeCap.Round)
        else {
            drawCircle(paper, 8f, Offset(x, 96f))
            drawCircle(ink, 4f, Offset(x + look, 96f))
        }
    }
    drawArc(paper, 25f, 130f, false, Offset(95f, 100f), Size(14f, 10f), style = Stroke(2.5f, cap = StrokeCap.Round))
    // The quill, always in hand except when waving hello.
    if (act != SquillyAct.WAVE) {
        val tip = Offset(162f + wave(t, 1f) * 4f, 112f + wave(t, 1f) * 4f)
        rotate(wave(t, 2f) * 4f, tip) {
            val quill = Path().apply {
                moveTo(tip.x, tip.y)
                cubicTo(tip.x + 2f, tip.y - 30f, tip.x + 16f, tip.y - 50f, tip.x + 26f, tip.y - 66f)
                cubicTo(tip.x + 34f, tip.y - 40f, tip.x + 24f, tip.y - 18f, tip.x, tip.y)
                close()
            }
            drawPath(quill, accent)
            drawLine(paper.copy(alpha = .7f), tip, Offset(tip.x + 20f, tip.y - 50f), 1.5f)
        }
    }
}

private fun DrawScope.prop(act: SquillyAct, t: Float, ink: Color, paper: Color, accent: Color) {
    val x0 = 112f
    val soft = accent.copy(alpha = .35f)
    fun outline(c: Color, w: Float = 3f) = Stroke(w, cap = StrokeCap.Round)
    when (act) {
        SquillyAct.WAVE -> {
            repeat(3) { i ->
                val rise = (t + i / 3f) % 1f
                drawCircle(accent.copy(alpha = 1f - rise), 3f + i * 1.5f, Offset(148f + i * 12f, 110f - rise * 80f), style = outline(accent, 2f))
            }
        }
        SquillyAct.WRITE -> {
            drawRoundRect(paper, Offset(x0, 116f), Size(82f, 66f), CornerRadius(5f))
            drawRoundRect(soft, Offset(x0, 116f), Size(82f, 66f), CornerRadius(5f), style = outline(accent, 2f))
            repeat(4) { i ->
                val done = ((t * 4f - i) % 4f + 4f) % 4f
                val f = done.coerceIn(0f, 1f)
                val y = 128f + i * 13f
                drawLine(ink.copy(alpha = .75f), Offset(x0 + 8f, y), Offset(x0 + 8f + 66f * f, y), 3f, StrokeCap.Round)
            }
        }
        SquillyAct.LINK -> {
            val a = Offset(128f, 60f); val b = Offset(182f, 92f); val c = Offset(136f, 150f)
            val pull = ((t * 2f) % 1f).coerceIn(0f, 1f)
            drawLine(accent, a, b, 3f)
            val moving = Offset(c.x + (b.x - c.x) * pull * .75f, c.y + (b.y - c.y) * pull * .75f)
            if (pull > .7f) drawLine(accent, moving, b, 3f)
            drawCircle(accent, 9f, a); drawCircle(accent, 11f, b)
            drawCircle(ink, 9f, moving)
            drawCircle(accent.copy(alpha = pull), 16f + pull * 6f, b, style = outline(accent))
        }
        SquillyAct.SKETCH -> {
            drawRoundRect(paper, Offset(x0, 110f), Size(82f, 74f), CornerRadius(5f))
            val steps = 40
            val shown = (((t * 2f) % 1f) * steps).toInt()
            val p = Path().apply {
                moveTo(x0 + 8f, 150f)
                for (i in 1..shown) lineTo(x0 + 8f + i * 1.6f, 150f + sin(i * .55f) * (14f - i * .15f))
            }
            drawPath(p, ink, style = Stroke(3f, cap = StrokeCap.Round))
        }
        SquillyAct.PAINT -> {
            val colours = listOf(Color(0xFFE53935), Color(0xFFF9A825), Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFF8E24AA))
            colours.forEachIndexed { i, c ->
                val pulse = 12f + wave(t, 2f, i * .2f) * 3f
                drawCircle(c.copy(alpha = .85f), pulse, Offset(132f + i % 3 * 26f, 128f + (i / 3) * 28f))
            }
            val drop = (t * 2f) % 1f
            drawCircle(colours[((t * 5f).toInt()) % 5], 4f, Offset(166f, 110f + drop * 20f))
        }
        SquillyAct.SLIDES -> {
            drawRoundRect(paper, Offset(x0, 74f), Size(84f, 56f), CornerRadius(4f))
            drawRoundRect(accent, Offset(x0, 74f), Size(84f, 56f), CornerRadius(4f), style = outline(accent, 2f))
            drawLine(ink, Offset(x0 + 42f, 130f), Offset(x0 + 42f, 150f), 3f)
            drawLine(ink, Offset(x0 + 28f, 152f), Offset(x0 + 56f, 152f), 3f, StrokeCap.Round)
            val step = ((t * 4f).toInt()) % 4
            drawLine(ink, Offset(x0 + 10f, 86f), Offset(x0 + 60f, 86f), 4f, StrokeCap.Round)
            if (step >= 1) drawLine(accent, Offset(x0 + 10f, 100f), Offset(x0 + 70f, 100f), 3f, StrokeCap.Round)
            if (step >= 2) drawLine(accent, Offset(x0 + 10f, 110f), Offset(x0 + 56f, 110f), 3f, StrokeCap.Round)
            if (step >= 3) drawLine(accent, Offset(x0 + 10f, 120f), Offset(x0 + 66f, 120f), 3f, StrokeCap.Round)
        }
        SquillyAct.BELL -> {
            rotate(wave(t, 3f) * 16f, Offset(158f, 66f)) {
                val bell = Path().apply {
                    moveTo(158f, 66f); cubicTo(180f, 66f, 176f, 100f, 186f, 112f); lineTo(130f, 112f); cubicTo(140f, 100f, 136f, 66f, 158f, 66f); close()
                }
                drawPath(bell, accent)
                drawCircle(ink, 6f, Offset(158f, 118f))
            }
            repeat(2) { i -> drawArc(soft, -40f, 80f, false, Offset(178f + i * 8f, 76f - i * 4f), Size(16f, 28f + i * 8f), style = outline(accent)) }
        }
        SquillyAct.LOCK -> {
            val closed = ((t * 2f) % 2f) > 1f
            val lift = if (closed) 0f else 14f
            drawArc(accent, 180f, 180f, false, Offset(138f, 84f - lift), Size(44f, 44f), style = Stroke(7f))
            if (!closed) drawLine(accent, Offset(182f, 106f - lift), Offset(182f, 110f), 7f)
            drawRoundRect(accent, Offset(130f, 106f), Size(60f, 48f), CornerRadius(8f))
            drawCircle(paper, 6f, Offset(160f, 128f))
        }
        SquillyAct.HOME -> {
            repeat(5) { i ->
                val rise = (t + i / 5f) % 1f
                drawCircle(accent.copy(alpha = (1f - rise) * .9f), 3f + i, Offset(30f + i * 34f, 170f - rise * 150f), style = outline(accent, 2f))
            }
        }
    }
}
