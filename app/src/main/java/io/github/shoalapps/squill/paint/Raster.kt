package io.github.shoalapps.squill.paint

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Pixels are plain ARGB ints, not premultiplied. Everything here works on arrays, so it can all be tested. */
inline fun alphaOf(c: Int) = c ushr 24
inline fun redOf(c: Int) = (c shr 16) and 255
inline fun greenOf(c: Int) = (c shr 8) and 255
inline fun blueOf(c: Int) = c and 255
inline fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
fun clamp8(v: Float) = if (v <= 0f) 0 else if (v >= 255f) 255 else (v + .5f).toInt()

data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val isEmpty get() = right <= left || bottom <= top
    fun union(o: Rect) = if (isEmpty) o else if (o.isEmpty) this else Rect(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))
    fun clip(w: Int, h: Int) = Rect(left.coerceIn(0, w), top.coerceIn(0, h), right.coerceIn(0, w), bottom.coerceIn(0, h))

    companion object {
        val EMPTY = Rect(0, 0, 0, 0)
    }
}

/** Blend modes, named as Krita and OpenRaster name them where those names exist. */
enum class Blend(val label: String, val ora: String) {
    NORMAL("Normal", "svg:src-over"), MULTIPLY("Multiply", "svg:multiply"), SCREEN("Screen", "svg:screen"), OVERLAY("Overlay", "svg:overlay"),
    DARKEN("Darken", "svg:darken"), LIGHTEN("Lighten", "svg:lighten"), DODGE("Colour dodge", "svg:color-dodge"), BURN("Colour burn", "svg:color-burn"),
    HARD_LIGHT("Hard light", "svg:hard-light"), SOFT_LIGHT("Soft light", "svg:soft-light"), DIFFERENCE("Difference", "svg:difference"), EXCLUSION("Exclusion", "svg:exclusion"),
    HUE("Hue", "svg:hue"), SATURATION("Saturation", "svg:saturation"), COLOUR("Colour", "svg:color"), LUMINOSITY("Luminosity", "svg:luminosity"),
    ADD("Add", "svg:plus"), SUBTRACT("Subtract", "krita:subtract"), DIVIDE("Divide", "krita:divide"), LINEAR_BURN("Linear burn", "krita:linear_burn"),
    LINEAR_LIGHT("Linear light", "krita:linear light"), VIVID_LIGHT("Vivid light", "krita:vivid_light"), PIN_LIGHT("Pin light", "krita:pin_light"),
    HARD_MIX("Hard mix", "krita:hard mix"), GRAIN_EXTRACT("Grain extract", "krita:grain_extract"), GRAIN_MERGE("Grain merge", "krita:grain_merge");

    companion object {
        fun fromOra(name: String?) = entries.firstOrNull { it.ora == name } ?: NORMAL
    }
}

private fun lum(r: Float, g: Float, b: Float) = .3f * r + .59f * g + .11f * b
private fun sat(r: Float, g: Float, b: Float) = max(r, max(g, b)) - min(r, min(g, b))

private fun clipColour(c: FloatArray) {
    val l = lum(c[0], c[1], c[2])
    val n = min(c[0], min(c[1], c[2]))
    val x = max(c[0], max(c[1], c[2]))
    if (n < 0f) for (i in 0..2) c[i] = l + (c[i] - l) * l / (l - n)
    if (x > 1f) for (i in 0..2) c[i] = l + (c[i] - l) * (1f - l) / (x - l)
}

private fun setLum(c: FloatArray, l: Float) {
    val d = l - lum(c[0], c[1], c[2])
    for (i in 0..2) c[i] += d
    clipColour(c)
}

private fun setSat(c: FloatArray, s: Float) {
    val hi = max(c[0], max(c[1], c[2]))
    val lo = min(c[0], min(c[1], c[2]))
    for (i in 0..2) c[i] = if (hi > lo) (c[i] - lo) * s / (hi - lo) else 0f
}

/** One colour channel, 0 to 1: b is what's underneath, s is what's on top. */
fun blendChannel(mode: Blend, b: Float, s: Float): Float = when (mode) {
    Blend.MULTIPLY -> b * s
    Blend.SCREEN -> b + s - b * s
    Blend.OVERLAY -> if (b <= .5f) 2f * b * s else 1f - 2f * (1f - b) * (1f - s)
    Blend.DARKEN -> min(b, s)
    Blend.LIGHTEN -> max(b, s)
    Blend.DODGE -> if (b == 0f) 0f else if (s >= 1f) 1f else min(1f, b / (1f - s))
    Blend.BURN -> if (b >= 1f) 1f else if (s <= 0f) 0f else 1f - min(1f, (1f - b) / s)
    Blend.HARD_LIGHT -> if (s <= .5f) 2f * s * b else 1f - 2f * (1f - s) * (1f - b)
    Blend.SOFT_LIGHT -> if (s <= .5f) b - (1f - 2f * s) * b * (1f - b) else b + (2f * s - 1f) * ((if (b <= .25f) ((16f * b - 12f) * b + 4f) * b else sqrt(b)) - b)
    Blend.DIFFERENCE -> abs(b - s)
    Blend.EXCLUSION -> b + s - 2f * b * s
    Blend.ADD -> min(1f, b + s)
    Blend.SUBTRACT -> max(0f, b - s)
    Blend.DIVIDE -> if (s <= 0f) 1f else min(1f, b / s)
    Blend.LINEAR_BURN -> max(0f, b + s - 1f)
    Blend.LINEAR_LIGHT -> (b + 2f * s - 1f).coerceIn(0f, 1f)
    Blend.VIVID_LIGHT -> if (s <= .5f) blendChannel(Blend.BURN, b, 2f * s) else blendChannel(Blend.DODGE, b, 2f * s - 1f)
    Blend.PIN_LIGHT -> if (s <= .5f) min(b, 2f * s) else max(b, 2f * s - 1f)
    Blend.HARD_MIX -> if (b + s >= 1f) 1f else 0f
    Blend.GRAIN_EXTRACT -> (b - s + .5f).coerceIn(0f, 1f)
    Blend.GRAIN_MERGE -> (b + s - .5f).coerceIn(0f, 1f)
    else -> s
}

/** Lays [src] (at extra [opacity]) over [dst], by the standard compositing formula. */
fun composite(dst: Int, src: Int, mode: Blend, opacity: Float = 1f): Int {
    val sa = alphaOf(src) / 255f * opacity
    if (sa <= 0f) return dst
    val da = alphaOf(dst) / 255f
    if (mode == Blend.NORMAL && sa >= 1f) return src or (0xFF shl 24)
    val sr = redOf(src) / 255f; val sg = greenOf(src) / 255f; val sb = blueOf(src) / 255f
    val dr = redOf(dst) / 255f; val dg = greenOf(dst) / 255f; val db = blueOf(dst) / 255f
    val mr: Float; val mg: Float; val mb: Float
    if (da <= 0f || mode == Blend.NORMAL) {
        mr = sr; mg = sg; mb = sb
    } else if (mode == Blend.HUE || mode == Blend.SATURATION || mode == Blend.COLOUR || mode == Blend.LUMINOSITY) {
        val c: FloatArray
        when (mode) {
            Blend.HUE -> { c = floatArrayOf(sr, sg, sb); setSat(c, sat(dr, dg, db)); setLum(c, lum(dr, dg, db)) }
            Blend.SATURATION -> { c = floatArrayOf(dr, dg, db); setSat(c, sat(sr, sg, sb)); setLum(c, lum(dr, dg, db)) }
            Blend.COLOUR -> { c = floatArrayOf(sr, sg, sb); setLum(c, lum(dr, dg, db)) }
            else -> { c = floatArrayOf(dr, dg, db); setLum(c, lum(sr, sg, sb)) }
        }
        mr = c[0]; mg = c[1]; mb = c[2]
    } else {
        mr = blendChannel(mode, dr, sr); mg = blendChannel(mode, dg, sg); mb = blendChannel(mode, db, sb)
    }
    val oa = sa + da * (1f - sa)
    if (oa <= 0f) return 0
    fun out(d: Float, s: Float, m: Float): Float = ((1f - sa) * da * d + sa * (1f - da) * s + sa * da * m) / oa
    return argb(clamp8(oa * 255f), clamp8(out(dr, sr, mr) * 255f), clamp8(out(dg, sg, mg) * 255f), clamp8(out(db, sb, mb) * 255f))
}

object Raster {
    /**
     * Which pixels a fill reaches from (x, y): those within [tolerance] (0 to 255) of the start's
     * colour, joined to it unless [contiguous] is off.
     */
    fun floodMask(pixels: IntArray, w: Int, h: Int, x: Int, y: Int, tolerance: Int, contiguous: Boolean = true): ByteArray {
        val mask = ByteArray(w * h)
        if (x !in 0 until w || y !in 0 until h) return mask
        val start = pixels[y * w + x]
        fun near(c: Int): Boolean {
            // Fully see-through pixels are all alike, whatever colour they happen to hold.
            if (alphaOf(c) == 0 && alphaOf(start) == 0) return true
            return abs(alphaOf(c) - alphaOf(start)) <= tolerance && abs(redOf(c) - redOf(start)) <= tolerance &&
                abs(greenOf(c) - greenOf(start)) <= tolerance && abs(blueOf(c) - blueOf(start)) <= tolerance
        }
        if (!contiguous) {
            for (i in pixels.indices) if (near(pixels[i])) mask[i] = -1
            return mask
        }
        // Row spans, so a large fill can't overflow the stack or take forever.
        val stack = IntArray(max(1024, (w + h) * 8))
        var top = 0
        var grown = stack
        fun push(v: Int) {
            if (top == grown.size) grown = grown.copyOf(grown.size * 2)
            grown[top++] = v
        }
        push(y * w + x)
        while (top > 0) {
            val p = grown[--top]
            val py = p / w
            var l = p % w
            if (mask[py * w + l] != 0.toByte() || !near(pixels[py * w + l])) continue
            while (l > 0 && mask[py * w + l - 1] == 0.toByte() && near(pixels[py * w + l - 1])) l--
            var r = l
            var upOpen = false
            var downOpen = false
            while (r < w && mask[py * w + r] == 0.toByte() && near(pixels[py * w + r])) {
                mask[py * w + r] = -1
                if (py > 0) {
                    val q = (py - 1) * w + r
                    val ok = mask[q] == 0.toByte() && near(pixels[q])
                    if (ok && !upOpen) push(q)
                    upOpen = ok
                }
                if (py < h - 1) {
                    val q = (py + 1) * w + r
                    val ok = mask[q] == 0.toByte() && near(pixels[q])
                    if (ok && !downOpen) push(q)
                    downOpen = ok
                }
                r++
            }
        }
        return mask
    }

    /** The inside of a polygon, for lassos and filled shapes. */
    fun polygonMask(xs: FloatArray, ys: FloatArray, w: Int, h: Int): ByteArray {
        val mask = ByteArray(w * h)
        val n = xs.size
        if (n < 3) return mask
        val crossings = FloatArray(n)
        for (y in 0 until h) {
            val cy = y + .5f
            var count = 0
            for (i in 0 until n) {
                val j = (i + 1) % n
                if ((ys[i] <= cy && ys[j] > cy) || (ys[j] <= cy && ys[i] > cy)) {
                    crossings[count++] = xs[i] + (cy - ys[i]) / (ys[j] - ys[i]) * (xs[j] - xs[i])
                }
            }
            java.util.Arrays.sort(crossings, 0, count)
            var c = 0
            while (c + 1 < count) {
                val from = max(0, floor(crossings[c] + .5f).toInt())
                val to = min(w, floor(crossings[c + 1] + .5f).toInt())
                for (x in from until to) mask[y * w + x] = -1
                c += 2
            }
        }
        return mask
    }

    fun rectMask(x0: Int, y0: Int, x1: Int, y1: Int, w: Int, h: Int): ByteArray {
        val mask = ByteArray(w * h)
        val r = Rect(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1)).clip(w, h)
        for (y in r.top until r.bottom) java.util.Arrays.fill(mask, y * w + r.left, y * w + r.right, -1)
        return mask
    }

    fun ellipseMask(x0: Int, y0: Int, x1: Int, y1: Int, w: Int, h: Int): ByteArray {
        val mask = ByteArray(w * h)
        val cx = (x0 + x1) / 2f
        val cy = (y0 + y1) / 2f
        val rx = abs(x1 - x0) / 2f
        val ry = abs(y1 - y0) / 2f
        if (rx <= 0f || ry <= 0f) return mask
        val r = Rect((cx - rx).toInt(), (cy - ry).toInt(), (cx + rx).toInt() + 2, (cy + ry).toInt() + 2).clip(w, h)
        for (y in r.top until r.bottom) for (x in r.left until r.right) {
            val dx = (x + .5f - cx) / rx
            val dy = (y + .5f - cy) / ry
            if (dx * dx + dy * dy <= 1f) mask[y * w + x] = -1
        }
        return mask
    }

    /** Where a mask is set, or null if nothing is. */
    fun bounds(mask: ByteArray, w: Int, h: Int): Rect? {
        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) for (x in 0 until w) if (mask[y * w + x] != 0.toByte()) {
            if (x < l) l = x
            if (x > r) r = x
            if (y < t) t = y
            if (y > b) b = y
        }
        return if (r < 0) null else Rect(l, t, r + 1, b + 1)
    }

    fun gradient(
        pixels: IntArray, w: Int, h: Int, x0: Float, y0: Float, x1: Float, y1: Float, from: Int, to: Int,
        radial: Boolean, mode: Blend, opacity: Float, selection: ByteArray?, alphaLock: Boolean,
    ) {
        val dx = x1 - x0
        val dy = y1 - y0
        val len2 = dx * dx + dy * dy
        if (len2 <= 0f) return
        val len = sqrt(len2)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (selection != null && selection[i] == 0.toByte()) continue
            val t = (if (radial) sqrt((x - x0) * (x - x0) + (y - y0) * (y - y0)) / len else ((x - x0) * dx + (y - y0) * dy) / len2).coerceIn(0f, 1f)
            val c = argb(
                clamp8(alphaOf(from) + (alphaOf(to) - alphaOf(from)) * t), clamp8(redOf(from) + (redOf(to) - redOf(from)) * t),
                clamp8(greenOf(from) + (greenOf(to) - greenOf(from)) * t), clamp8(blueOf(from) + (blueOf(to) - blueOf(from)) * t),
            )
            val out = composite(pixels[i], c, mode, opacity)
            pixels[i] = if (alphaLock) (out and 0xFFFFFF) or (pixels[i] and (0xFF shl 24)) else out
        }
    }

    /** Fills a mask's area, softly mixed in over what's there. */
    fun fill(pixels: IntArray, mask: ByteArray, colour: Int, mode: Blend, opacity: Float, selection: ByteArray?, alphaLock: Boolean) {
        for (i in pixels.indices) {
            if (mask[i] == 0.toByte() || (selection != null && selection[i] == 0.toByte())) continue
            val out = composite(pixels[i], colour, mode, opacity)
            pixels[i] = if (alphaLock) (out and 0xFFFFFF) or (pixels[i] and (0xFF shl 24)) else out
        }
    }

    /** Scales and turns [src] about its centre, then offsets it. Empty where nothing lands. */
    fun transform(src: IntArray, w: Int, h: Int, scaleX: Float, scaleY: Float, radians: Float, dx: Float, dy: Float, smooth: Boolean = true): IntArray {
        val out = IntArray(w * h)
        val cos = kotlin.math.cos(radians)
        val sin = kotlin.math.sin(radians)
        val cx = w / 2f
        val cy = h / 2f
        if (scaleX == 0f || scaleY == 0f) return out
        for (y in 0 until h) for (x in 0 until w) {
            val ox = x + .5f - cx - dx
            val oy = y + .5f - cy - dy
            val sx = (ox * cos + oy * sin) / scaleX + cx - .5f
            val sy = (-ox * sin + oy * cos) / scaleY + cy - .5f
            if (sx < -1f || sy < -1f || sx > w.toFloat() || sy > h.toFloat()) continue
            out[y * w + x] = if (smooth) sample(src, w, h, sx, sy) else {
                val nx = (sx + .5f).toInt(); val ny = (sy + .5f).toInt()
                if (nx in 0 until w && ny in 0 until h) src[ny * w + nx] else 0
            }
        }
        return out
    }

    private fun sample(src: IntArray, w: Int, h: Int, x: Float, y: Float): Int {
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        val fx = x - x0; val fy = y - y0
        var a = 0f; var r = 0f; var g = 0f; var b = 0f
        for (j in 0..1) for (i in 0..1) {
            val px = x0 + i; val py = y0 + j
            val c = if (px in 0 until w && py in 0 until h) src[py * w + px] else 0
            val weight = (if (i == 0) 1f - fx else fx) * (if (j == 0) 1f - fy else fy)
            val ca = alphaOf(c) * weight
            a += ca; r += redOf(c) * ca; g += greenOf(c) * ca; b += blueOf(c) * ca
        }
        if (a <= 0f) return 0
        return argb(clamp8(a), clamp8(r / a), clamp8(g / a), clamp8(b / a))
    }
}

/** Colours as hue (0 to 360), saturation and value (0 to 1). */
fun rgbToHsv(c: Int): FloatArray {
    val r = redOf(c) / 255f; val g = greenOf(c) / 255f; val b = blueOf(c) / 255f
    val hi = max(r, max(g, b)); val lo = min(r, min(g, b)); val d = hi - lo
    val h = when {
        d == 0f -> 0f
        hi == r -> 60f * (((g - b) / d + 6f) % 6f)
        hi == g -> 60f * ((b - r) / d + 2f)
        else -> 60f * ((r - g) / d + 4f)
    }
    return floatArrayOf(h, if (hi == 0f) 0f else d / hi, hi)
}

fun hsvToRgb(h: Float, s: Float, v: Float, alpha: Int = 255): Int {
    val c = v * s
    val hh = ((h % 360f) + 360f) % 360f / 60f
    val x = c * (1f - abs(hh % 2f - 1f))
    val (r, g, b) = when (hh.toInt()) {
        0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x); 3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
    }
    val m = v - c
    return argb(alpha, clamp8((r + m) * 255f), clamp8((g + m) * 255f), clamp8((b + m) * 255f))
}

/** Filters, applied to a layer. All leave alpha alone unless they say otherwise. */
enum class Filter(val label: String, val hasAmount: Boolean) {
    BLUR("Blur", true), SHARPEN("Sharpen", true), BRIGHTNESS("Brightness", true), CONTRAST("Contrast", true), HUE("Hue shift", true), SATURATION("Saturation", true),
    INVERT("Invert", false), DESATURATE("Greyscale", false), POSTERISE("Posterise", true), PIXELATE("Pixelate", true), NOISE("Noise", true), THRESHOLD("Threshold", true),
    EDGES("Find edges", false), EMBOSS("Emboss", false), SEPIA("Sepia", false), WARMTH("Warmth", true), VIGNETTE("Vignette", true),
}

object Filters {
    /** [amount] runs from -1 to 1 for two-way filters and 0 to 1 otherwise. Returns a new array. */
    fun apply(filter: Filter, src: IntArray, w: Int, h: Int, amount: Float, seed: Int = 7): IntArray = when (filter) {
        Filter.BLUR -> blur(src, w, h, (amount.coerceIn(0f, 1f) * 40f).toInt().coerceAtLeast(1))
        Filter.SHARPEN -> {
            val soft = blur(src, w, h, 2)
            map2(src, soft) { c, b -> c + (c - b) * amount.coerceIn(0f, 1f) * 2f }
        }
        Filter.BRIGHTNESS -> mapChannels(src) { it + amount * 255f }
        Filter.CONTRAST -> { val f = 1f + amount * (if (amount > 0) 3f else 1f); mapChannels(src) { (it - 128f) * f + 128f } }
        Filter.HUE -> mapHsv(src) { hsv -> hsv[0] += amount * 180f }
        Filter.SATURATION -> mapHsv(src) { hsv -> hsv[1] = (hsv[1] * (1f + amount)).coerceIn(0f, 1f) }
        Filter.INVERT -> mapChannels(src) { 255f - it }
        Filter.DESATURATE -> IntArray(src.size) { i -> val c = src[i]; val l = clamp8(.3f * redOf(c) + .59f * greenOf(c) + .11f * blueOf(c)); argb(alphaOf(c), l, l, l) }
        Filter.POSTERISE -> { val levels = (2 + ((1f - amount.coerceIn(0f, 1f)) * 14f).toInt()); mapChannels(src) { floor(it / 255f * (levels - 1) + .5f) * 255f / (levels - 1) } }
        Filter.PIXELATE -> pixelate(src, w, h, (amount.coerceIn(0f, 1f) * 48f).toInt().coerceAtLeast(2))
        Filter.NOISE -> {
            val random = java.util.Random(seed.toLong())
            val strength = amount.coerceIn(0f, 1f) * 80f
            IntArray(src.size) { i -> val c = src[i]; if (alphaOf(c) == 0) 0 else { val n = (random.nextFloat() - .5f) * 2f * strength; argb(alphaOf(c), clamp8(redOf(c) + n), clamp8(greenOf(c) + n), clamp8(blueOf(c) + n)) } }
        }
        Filter.THRESHOLD -> { val limit = amount.coerceIn(0f, 1f) * 255f; IntArray(src.size) { i -> val c = src[i]; val v = if (.3f * redOf(c) + .59f * greenOf(c) + .11f * blueOf(c) >= limit) 255 else 0; argb(alphaOf(c), v, v, v) } }
        Filter.EDGES -> convolve(src, w, h, intArrayOf(-1, -1, -1, -1, 8, -1, -1, -1, -1), 0f)
        Filter.EMBOSS -> convolve(src, w, h, intArrayOf(-2, -1, 0, -1, 1, 1, 0, 1, 2), 0f)
        Filter.SEPIA -> IntArray(src.size) { i -> val c = src[i]; val r = redOf(c); val g = greenOf(c); val b = blueOf(c); argb(alphaOf(c), clamp8(.393f * r + .769f * g + .189f * b), clamp8(.349f * r + .686f * g + .168f * b), clamp8(.272f * r + .534f * g + .131f * b)) }
        Filter.WARMTH -> IntArray(src.size) { i -> val c = src[i]; argb(alphaOf(c), clamp8(redOf(c) + amount * 40f), greenOf(c), clamp8(blueOf(c) - amount * 40f)) }
        Filter.VIGNETTE -> IntArray(src.size) { i ->
            val c = src[i]
            val dx = (i % w) / w.toFloat() - .5f; val dy = (i / w) / h.toFloat() - .5f
            val f = 1f - amount.coerceIn(0f, 1f) * ((dx * dx + dy * dy) * 2f).coerceIn(0f, 1f)
            argb(alphaOf(c), clamp8(redOf(c) * f), clamp8(greenOf(c) * f), clamp8(blueOf(c) * f))
        }
    }

    private inline fun mapChannels(src: IntArray, f: (Float) -> Float) = IntArray(src.size) { i ->
        val c = src[i]
        if (alphaOf(c) == 0) c else argb(alphaOf(c), clamp8(f(redOf(c).toFloat())), clamp8(f(greenOf(c).toFloat())), clamp8(f(blueOf(c).toFloat())))
    }

    private inline fun map2(a: IntArray, b: IntArray, f: (Float, Float) -> Float) = IntArray(a.size) { i ->
        val c = a[i]; val d = b[i]
        if (alphaOf(c) == 0) c else argb(alphaOf(c), clamp8(f(redOf(c).toFloat(), redOf(d).toFloat())), clamp8(f(greenOf(c).toFloat(), greenOf(d).toFloat())), clamp8(f(blueOf(c).toFloat(), blueOf(d).toFloat())))
    }

    private inline fun mapHsv(src: IntArray, f: (FloatArray) -> Unit) = IntArray(src.size) { i ->
        val c = src[i]
        if (alphaOf(c) == 0) c else { val hsv = rgbToHsv(c); f(hsv); hsvToRgb(hsv[0], hsv[1], hsv[2], alphaOf(c)) }
    }

    /** A box blur run three times, which is very nearly a Gaussian, weighting colour by alpha so edges don't go dark. */
    fun blur(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val r = radius.coerceAtLeast(1)
        var a = FloatArray(src.size); var cr = FloatArray(src.size); var cg = FloatArray(src.size); var cb = FloatArray(src.size)
        for (i in src.indices) { val c = src[i]; val al = alphaOf(c).toFloat(); a[i] = al; cr[i] = redOf(c) * al; cg[i] = greenOf(c) * al; cb[i] = blueOf(c) * al }
        val pass = (r / 1.7f).toInt().coerceAtLeast(1)
        repeat(3) { a = box(a, w, h, pass); cr = box(cr, w, h, pass); cg = box(cg, w, h, pass); cb = box(cb, w, h, pass) }
        return IntArray(src.size) { i -> val al = a[i]; if (al <= .5f) 0 else argb(clamp8(al), clamp8(cr[i] / al), clamp8(cg[i] / al), clamp8(cb[i] / al)) }
    }

    private fun box(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val mid = FloatArray(src.size)
        val size = 2 * r + 1
        for (y in 0 until h) {
            var sum = 0f
            for (x in -r..r) sum += src[y * w + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                mid[y * w + x] = sum / size
                sum += src[y * w + (x + r + 1).coerceAtMost(w - 1)] - src[y * w + (x - r).coerceAtLeast(0)]
            }
        }
        val out = FloatArray(src.size)
        for (x in 0 until w) {
            var sum = 0f
            for (y in -r..r) sum += mid[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = sum / size
                sum += mid[(y + r + 1).coerceAtMost(h - 1) * w + x] - mid[(y - r).coerceAtLeast(0) * w + x]
            }
        }
        return out
    }

    private fun pixelate(src: IntArray, w: Int, h: Int, block: Int): IntArray {
        val out = IntArray(src.size)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                var a = 0L; var r = 0L; var g = 0L; var b = 0L; var n = 0
                for (j in y until min(y + block, h)) for (i in x until min(x + block, w)) {
                    val c = src[j * w + i]; val al = alphaOf(c)
                    a += al; r += redOf(c) * al; g += greenOf(c) * al; b += blueOf(c) * al; n++
                }
                val colour = if (a == 0L) 0 else argb((a / n).toInt(), (r / a).toInt(), (g / a).toInt(), (b / a).toInt())
                for (j in y until min(y + block, h)) java.util.Arrays.fill(out, j * w + x, j * w + min(x + block, w), colour)
                x += block
            }
            y += block
        }
        return out
    }

    private fun convolve(src: IntArray, w: Int, h: Int, kernel: IntArray, bias: Float): IntArray {
        val out = IntArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            var r = bias; var g = bias; var b = bias
            for (ky in -1..1) for (kx in -1..1) {
                val c = src[(y + ky).coerceIn(0, h - 1) * w + (x + kx).coerceIn(0, w - 1)]
                val k = kernel[(ky + 1) * 3 + kx + 1]
                r += redOf(c) * k; g += greenOf(c) * k; b += blueOf(c) * k
            }
            out[y * w + x] = argb(alphaOf(src[y * w + x]), clamp8(r), clamp8(g), clamp8(b))
        }
        return out
    }
}
