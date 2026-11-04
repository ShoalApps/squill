package io.github.shoalapps.squill.paint

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class BrushKind { PAINT, ERASE, SMUDGE, BLUR }
enum class TipShape { ROUND, SQUARE, GRAIN, SPLATTER }

/** Everything that makes one brush different from another. */
data class BrushSettings(
    val name: String,
    val kind: BrushKind = BrushKind.PAINT,
    val tip: TipShape = TipShape.ROUND,
    val size: Float = 24f,
    val opacity: Float = 1f,
    /** How much each dab adds. Low flow builds up slowly within a stroke. */
    val flow: Float = 1f,
    /** 1 is a crisp edge, 0 is all soft. */
    val hardness: Float = .9f,
    /** Gap between dabs, as a share of the brush size. */
    val spacing: Float = .1f,
    val scatter: Float = 0f,
    val sizeJitter: Float = 0f,
    val opacityJitter: Float = 0f,
    /** Flattens the tip: 1 is round, small is a calligraphy nib. */
    val roundness: Float = 1f,
    /** Nib angle in degrees. */
    val angle: Float = 0f,
    val pressureSize: Boolean = true,
    val pressureOpacity: Boolean = false,
    val pressureFlow: Boolean = false,
    /** Turns the nib with the pen's tilt. */
    val tiltAngle: Boolean = false,
    val blend: Blend = Blend.NORMAL,
    /** Pixel art: no soft edges at all. */
    val aliased: Boolean = false,
    /** 0 is raw input, 1 is heavily smoothed. */
    val stabiliser: Float = .15f,
    /** Smudge strength: how much colour is carried along. */
    val strength: Float = .6f,
)

object BrushPresets {
    val all = listOf(
        BrushSettings("Ink pen", size = 10f, hardness = 1f, spacing = .06f, stabiliser = .25f),
        BrushSettings("Fine liner", size = 4f, hardness = 1f, spacing = .05f, pressureSize = false, stabiliser = .4f),
        BrushSettings("Pencil", tip = TipShape.GRAIN, size = 8f, hardness = .7f, flow = .55f, spacing = .08f, pressureOpacity = true, stabiliser = .05f),
        BrushSettings("Charcoal", tip = TipShape.GRAIN, size = 34f, hardness = .5f, flow = .45f, spacing = .1f, scatter = .08f, sizeJitter = .15f, pressureOpacity = true),
        BrushSettings("Marker", size = 26f, hardness = .85f, opacity = .85f, spacing = .08f, pressureSize = false, blend = Blend.MULTIPLY),
        BrushSettings("Calligraphy", size = 28f, hardness = 1f, spacing = .03f, roundness = .25f, angle = 40f, tiltAngle = true, stabiliser = .3f),
        BrushSettings("Oil brush", tip = TipShape.GRAIN, size = 44f, hardness = .75f, spacing = .05f, roundness = .7f, tiltAngle = true, opacityJitter = .1f),
        BrushSettings("Watercolour", size = 60f, hardness = .1f, opacity = .55f, flow = .12f, spacing = .12f, blend = Blend.MULTIPLY, pressureFlow = true),
        BrushSettings("Airbrush", size = 90f, hardness = 0f, flow = .06f, spacing = .1f, pressureSize = false, pressureFlow = true),
        BrushSettings("Spray", tip = TipShape.SPLATTER, size = 70f, hardness = .8f, flow = .5f, spacing = .25f, pressureSize = false),
        BrushSettings("Splatter", tip = TipShape.SPLATTER, size = 50f, hardness = 1f, spacing = .5f, scatter = .6f, sizeJitter = .7f),
        BrushSettings("Glaze", size = 50f, hardness = .4f, opacity = .35f, flow = .4f, spacing = .1f, blend = Blend.OVERLAY),
        BrushSettings("Highlight", size = 40f, hardness = .3f, flow = .3f, spacing = .1f, blend = Blend.DODGE),
        BrushSettings("Shadow", size = 40f, hardness = .3f, flow = .3f, spacing = .1f, blend = Blend.BURN),
        BrushSettings("Pixel", tip = TipShape.SQUARE, size = 1f, hardness = 1f, spacing = .5f, pressureSize = false, aliased = true, stabiliser = 0f),
        BrushSettings("Eraser", kind = BrushKind.ERASE, size = 40f, hardness = .8f, spacing = .08f, pressureSize = false),
        BrushSettings("Soft eraser", kind = BrushKind.ERASE, size = 70f, hardness = .1f, flow = .3f, spacing = .1f, pressureSize = false),
        BrushSettings("Smudge", kind = BrushKind.SMUDGE, size = 40f, hardness = .5f, spacing = .08f, pressureSize = false, strength = .75f),
        BrushSettings("Blur brush", kind = BrushKind.BLUR, size = 50f, hardness = .4f, spacing = .15f, pressureSize = false, strength = .6f),
    )
    /** The handful shown in Simple mode. */
    val simple = listOf("Ink pen", "Pencil", "Marker", "Watercolour", "Eraser")
}

/** Mirrors: every dab is repeated across these. */
data class Symmetry(val mirrorX: Boolean = false, val mirrorY: Boolean = false, val radial: Int = 1)

/** A soft or hard circle, cached by size so each dab doesn't recalculate it. */
internal class Tip(val size: Int, val mask: FloatArray)

internal object Tips {
    private val cache = object : LinkedHashMap<Long, Tip>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Tip>?) = size > 64
    }

    fun get(diameter: Int, hardness: Float, aliased: Boolean, square: Boolean): Tip = synchronized(cache) {
        val key = diameter.toLong() or ((hardness * 100).toLong() shl 20) or ((if (aliased) 1L else 0L) shl 40) or ((if (square) 1L else 0L) shl 41)
        cache.getOrPut(key) {
            val d = diameter.coerceAtLeast(1)
            val mask = FloatArray(d * d)
            val r = d / 2f
            val inner = r * hardness.coerceIn(0f, 1f)
            for (y in 0 until d) for (x in 0 until d) {
                if (square) { mask[y * d + x] = 1f; continue }
                val dist = hypot(x + .5f - r, y + .5f - r)
                mask[y * d + x] = when {
                    aliased -> if (dist <= r) 1f else 0f
                    dist <= inner - .5f -> 1f
                    dist >= r + .5f -> 0f
                    r - inner < 1f -> (r + .5f - dist).coerceIn(0f, 1f)
                    else -> { val t = ((dist - inner) / (r - inner)).coerceIn(0f, 1f); (1f - t) * (1f - t) * (1f + 2f * t) }
                }
            }
            Tip(d, mask)
        }
    }
}

/**
 * Lays one brush stroke onto a layer. It works from a copy of the layer as it was, so a stroke's
 * opacity is a ceiling however many times the stroke crosses itself, as in Krita's "wash" mode.
 */
class StrokePainter(
    private val pixels: IntArray,
    private val width: Int,
    private val height: Int,
    private val brush: BrushSettings,
    private val colour: Int,
    private val selection: ByteArray? = null,
    private val alphaLock: Boolean = false,
    private val symmetry: Symmetry = Symmetry(),
    /** Only for a clipped layer: the pixels whose alpha this layer stays inside. */
    private val clipAlpha: IntArray? = null,
    seed: Int = 11,
) {
    private val before = pixels.copyOf()
    private val coverage = FloatArray(pixels.size)
    private val random = java.util.Random(seed.toLong())
    private var dirty = Rect.EMPTY
    private var smoothX = 0f; private var smoothY = 0f; private var smoothP = 1f
    private var lastX = 0f; private var lastY = 0f
    private var leftover = 0f
    private var started = false
    private var carried: IntArray? = null
    private var carriedSize = 0

    /** Everything changed so far. */
    val changed: Rect get() = dirty
    /** The layer as it was before this stroke, for undo. */
    val original: IntArray get() = before

    /** Adds a point. [pressure] is 0 to 1, or 1 for inputs without any. Returns the area changed by this step. */
    fun add(x: Float, y: Float, pressure: Float = 1f, tilt: Float = 0f, orientation: Float = 0f): Rect {
        val keep = brush.stabiliser.coerceIn(0f, .97f)
        if (!started) {
            started = true
            smoothX = x; smoothY = y; smoothP = pressure; lastX = x; lastY = y
            val touched = dab(x, y, pressure, tilt, orientation)
            dirty = dirty.union(touched)
            return touched
        }
        smoothX = smoothX * keep + x * (1f - keep)
        smoothY = smoothY * keep + y * (1f - keep)
        smoothP = smoothP * keep + pressure * (1f - keep)
        return lineTo(smoothX, smoothY, smoothP, tilt, orientation)
    }

    /** Catches up with the real end of the stroke, so a smoothed line doesn't stop short. */
    fun finish(x: Float, y: Float, pressure: Float = 1f): Rect = if (!started) add(x, y, pressure) else lineTo(x, y, min(smoothP, pressure), 0f, 0f)

    private fun lineTo(x: Float, y: Float, pressure: Float, tilt: Float, orientation: Float): Rect {
        var touched = Rect.EMPTY
        val dist = hypot(x - lastX, y - lastY)
        if (dist <= 0f) return touched
        val step = max(1f, brush.size * sizeFactor(pressure) * brush.spacing)
        var travelled = step - leftover
        while (travelled <= dist) {
            val f = travelled / dist
            touched = touched.union(dab(lastX + (x - lastX) * f, lastY + (y - lastY) * f, pressure, tilt, orientation))
            travelled += step
        }
        leftover = dist - (travelled - step)
        lastX = x; lastY = y
        dirty = dirty.union(touched)
        return touched
    }

    private fun sizeFactor(p: Float) = if (brush.pressureSize) .15f + .85f * p.coerceIn(0f, 1f) else 1f

    private fun dab(x: Float, y: Float, pressure: Float, tilt: Float, orientation: Float): Rect {
        var size = brush.size * sizeFactor(pressure)
        if (brush.sizeJitter > 0f) size *= 1f - random.nextFloat() * brush.sizeJitter
        var flow = brush.flow * (if (brush.pressureFlow) pressure.coerceIn(0f, 1f) else 1f)
        if (brush.opacityJitter > 0f) flow *= 1f - random.nextFloat() * brush.opacityJitter
        var px = x; var py = y
        if (brush.scatter > 0f) {
            px += (random.nextFloat() - .5f) * brush.size * brush.scatter * 2f
            py += (random.nextFloat() - .5f) * brush.size * brush.scatter * 2f
        }
        val radians = Math.toRadians(brush.angle.toDouble()).toFloat() + (if (brush.tiltAngle && tilt > 0f) orientation else 0f)
        var touched = Rect.EMPTY
        // The original dab first, then its reflections.
        val positions = symmetricPoints(px, py)
        positions.forEach { (qx, qy, turn) -> touched = touched.union(stamp(qx, qy, size, flow, radians + turn)) }
        return touched
    }

    private fun symmetricPoints(x: Float, y: Float): List<Triple<Float, Float, Float>> {
        val cx = width / 2f
        val cy = height / 2f
        val out = ArrayList<Triple<Float, Float, Float>>(4)
        val n = symmetry.radial.coerceAtLeast(1)
        for (i in 0 until n) {
            val a = (2.0 * Math.PI * i / n).toFloat()
            val c = cos(a); val s = sin(a)
            val rx = cx + (x - cx) * c - (y - cy) * s
            val ry = cy + (x - cx) * s + (y - cy) * c
            out += Triple(rx, ry, a)
            if (symmetry.mirrorX) out += Triple(2f * cx - rx, ry, -a)
            if (symmetry.mirrorY) out += Triple(rx, 2f * cy - ry, -a)
            if (symmetry.mirrorX && symmetry.mirrorY) out += Triple(2f * cx - rx, 2f * cy - ry, a)
        }
        return out
    }

    private fun stamp(x: Float, y: Float, size: Float, flow: Float, radians: Float): Rect {
        val d = max(1, (size + .5f).toInt())
        val hard = brush.hardness
        val tip = Tips.get(d, hard, brush.aliased, brush.tip == TipShape.SQUARE)
        val half = d / 2f
        // A flattened nib is the same tip, sampled through a turned and squashed lookup.
        val flat = brush.roundness < .999f || (brush.tip != TipShape.ROUND && radians != 0f && brush.tip == TipShape.SQUARE)
        val left = floor(x - half).toInt()
        val top = floor(y - half).toInt()
        val area = Rect(left, top, left + d + 1, top + d + 1).clip(width, height)
        if (area.isEmpty) return Rect.EMPTY
        val cos = cos(radians); val sin = sin(radians)
        val round = brush.roundness.coerceIn(.05f, 1f)
        val grain = brush.tip == TipShape.GRAIN
        val splatter = brush.tip == TipShape.SPLATTER
        val smudging = brush.kind == BrushKind.SMUDGE
        val blurring = brush.kind == BrushKind.BLUR
        if (smudging) prepareCarry(area, d, x, y)
        for (py in area.top until area.bottom) {
            val row = py * width
            for (px in area.left until area.right) {
                val i = row + px
                if (selection != null && selection[i] == 0.toByte()) continue
                var m: Float
                if (flat) {
                    val ox = px + .5f - x
                    val oy = py + .5f - y
                    val u = (ox * cos + oy * sin) + half
                    val v = (-ox * sin + oy * cos) / round + half
                    val iu = u.toInt(); val iv = v.toInt()
                    m = if (u < 0f || v < 0f || iu >= d || iv >= d) 0f else tip.mask[iv * d + iu]
                } else {
                    val iu = px - left; val iv = py - top
                    m = if (iu >= d || iv >= d) 0f else tip.mask[iv * d + iu]
                }
                if (m <= 0f) continue
                if (grain) m *= grainAt(px, py)
                if (splatter && random.nextFloat() > .12f) continue
                m *= flow
                when {
                    smudging -> smudgePixel(i, px, py, m)
                    blurring -> blurPixel(i, px, py, m)
                    else -> paintPixel(i, m)
                }
            }
        }
        return area
    }

    /** A stable paper-like tooth, the same at any point however many times a stroke crosses it. */
    private fun grainAt(x: Int, y: Int): Float {
        var h = x * 374761393 + y * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        val n = ((h xor (h ushr 16)) and 255) / 255f
        return .35f + .65f * n * n
    }

    private fun paintPixel(i: Int, m: Float) {
        // Coverage only rises, towards the brush's opacity: its ceiling within this stroke.
        val c = coverage[i] + (1f - coverage[i]) * m
        coverage[i] = c
        val strength = c * brush.opacity
        val base = before[i]
        if (brush.kind == BrushKind.ERASE) {
            val a = clamp8(alphaOf(base) * (1f - strength))
            pixels[i] = (base and 0xFFFFFF) or (a shl 24)
            return
        }
        val out = composite(base, colour, brush.blend, strength)
        var alpha = alphaOf(out)
        if (alphaLock) alpha = alphaOf(base)
        clipAlpha?.let { alpha = min(alpha, alphaOf(it[i])) }
        pixels[i] = (out and 0xFFFFFF) or (alpha shl 24)
    }

    private fun prepareCarry(area: Rect, d: Int, x: Float, y: Float) {
        if (carried == null || carriedSize != d) {
            carried = IntArray(d * d) { 0 }
            carriedSize = d
            val c = carried!!
            val left = floor(x - d / 2f).toInt(); val top = floor(y - d / 2f).toInt()
            for (j in 0 until d) for (k in 0 until d) {
                val px = left + k; val py = top + j
                c[j * d + k] = if (px in 0 until width && py in 0 until height) pixels[py * width + px] else 0
            }
        }
        smudgeLeft = floor(x - d / 2f).toInt()
        smudgeTop = floor(y - d / 2f).toInt()
    }

    private var smudgeLeft = 0
    private var smudgeTop = 0

    private fun smudgePixel(i: Int, px: Int, py: Int, m: Float) {
        val c = carried ?: return
        val d = carriedSize
        val j = (py - smudgeTop) * d + (px - smudgeLeft)
        if (j !in c.indices) return
        val mix = m * brush.strength.coerceIn(0f, 1f)
        val here = pixels[i]
        val mixed = lerp(here, c[j], mix)
        var out = mixed
        if (alphaLock) out = (out and 0xFFFFFF) or (here and (0xFF shl 24))
        pixels[i] = out
        c[j] = lerp(c[j], here, 1f - brush.strength.coerceIn(0f, 1f))
    }

    private fun blurPixel(i: Int, px: Int, py: Int, m: Float) {
        var a = 0f; var r = 0f; var g = 0f; var b = 0f; var n = 0
        for (dy in -2..2) for (dx in -2..2) {
            val qx = (px + dx); val qy = (py + dy)
            if (qx !in 0 until width || qy !in 0 until height) continue
            val c = pixels[qy * width + qx]
            val al = alphaOf(c).toFloat()
            a += al; r += redOf(c) * al; g += greenOf(c) * al; b += blueOf(c) * al; n++
        }
        if (n == 0) return
        val soft = if (a <= 0f) 0 else argb(clamp8(a / n), clamp8(r / a), clamp8(g / a), clamp8(b / a))
        pixels[i] = lerp(pixels[i], soft, m * brush.strength)
    }

    private fun lerp(from: Int, to: Int, t: Float): Int {
        val fa = alphaOf(from).toFloat(); val ta = alphaOf(to).toFloat()
        val a = fa + (ta - fa) * t
        if (a <= 0f) return 0
        fun mix(f: Int, o: Int) = clamp8((f * fa * (1f - t) + o * ta * t) / a)
        return argb(clamp8(a), mix(redOf(from), redOf(to)), mix(greenOf(from), greenOf(to)), mix(blueOf(from), blueOf(to)))
    }
}

/** Distance helper shared with the screen. */
fun distance(x0: Float, y0: Float, x1: Float, y1: Float) = sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0))
internal fun absf(v: Float) = abs(v)
