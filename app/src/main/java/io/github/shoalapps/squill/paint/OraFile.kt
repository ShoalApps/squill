package io.github.shoalapps.squill.paint

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.shoalapps.squill.notes.OfficeException
import io.github.shoalapps.squill.notes.readZip
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A frozen copy of a painting, so saving can happen while painting carries on. */
class PaintingCopy(val width: Int, val height: Int, val layers: List<Layer>)

fun Painting.frozen() = PaintingCopy(width, height, layers.map { it.copy() })
fun Painting.frozenCopy() = frozen()

/** OpenRaster: the open layered format that Krita, GIMP and others can open. */
object OraFile {
    private const val MIME = "image/openraster"

    private fun png(pixels: IntArray, w: Int, h: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    fun save(copy: PaintingCopy, output: OutputStream) {
        val merged = Painting(copy.width, copy.height).also { it.layers += copy.layers }.flattened()
        ZipOutputStream(output).use { zip ->
            // The format asks for this first and uncompressed, so a file can be told from its opening bytes.
            val mime = MIME.toByteArray()
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED; size = mime.size.toLong(); compressedSize = mime.size.toLong()
                crc = CRC32().also { it.update(mime) }.value
            })
            zip.write(mime)
            zip.closeEntry()
            fun put(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            val entries = copy.layers.withIndex().toList().asReversed().map { (i, l) ->
                put("data/layer$i.png", png(l.pixels, copy.width, copy.height))
                OraStack.Entry(l.name, "data/layer$i.png", l.opacity, l.visible, l.blend, l.alphaLock, l.clipped)
            }
            put("stack.xml", OraStack.write(copy.width, copy.height, entries).toByteArray())
            put("mergedimage.png", png(merged, copy.width, copy.height))
            val full = Bitmap.createBitmap(merged, copy.width, copy.height, Bitmap.Config.ARGB_8888)
            val scale = 256f / maxOf(copy.width, copy.height)
            val thumb = Bitmap.createScaledBitmap(full, (copy.width * scale).toInt().coerceAtLeast(1), (copy.height * scale).toInt().coerceAtLeast(1), true)
            val thumbBytes = ByteArrayOutputStream().also { thumb.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            put("Thumbnails/thumbnail.png", thumbBytes)
            thumb.recycle(); full.recycle()
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > Painting.MAX_SIDE || bounds.outHeight / sample > Painting.MAX_SIDE) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888; inPremultiplied = false; inSampleSize = sample
        })
    }

    fun load(bytes: ByteArray): Painting {
        val files = readZip(ByteArrayInputStream(bytes))
        val stack = files["stack.xml"] ?: throw OfficeException("That isn't an OpenRaster file")
        val parsed = OraStack.parse(stack)
        if (parsed.width !in 1..Painting.MAX_SIDE || parsed.height !in 1..Painting.MAX_SIDE) throw OfficeException("That picture is too big to open")
        val p = Painting(parsed.width, parsed.height)
        // The file lists the top layer first.
        parsed.entries.withIndex().toList().asReversed().forEach { (i, e) ->
            val pixels = IntArray(p.width * p.height)
            files[e.src]?.let { decode(it) }?.let { bmp ->
                val (ox, oy) = parsed.offsets[i]
                val row = IntArray(bmp.width)
                for (y in 0 until bmp.height) {
                    val ty = y + oy
                    if (ty !in 0 until p.height) continue
                    bmp.getPixels(row, 0, bmp.width, 0, y, bmp.width, 1)
                    for (x in 0 until bmp.width) { val tx = x + ox; if (tx in 0 until p.width) pixels[ty * p.width + tx] = row[x] }
                }
                bmp.recycle()
            }
            p.layers += Layer(p.nextId++, e.name, pixels, e.visible, e.opacity, e.blend, e.alphaLock, e.clipped)
        }
        if (p.layers.isEmpty()) p.newLayer("Layer 1")
        p.activeId = p.layers.last().id
        return p
    }

    /** A picture, or a Krita file's flattened image, as a one-layer painting. */
    fun fromPicture(bytes: ByteArray): Painting {
        // A .kra file is a zip with a flattened copy inside; Krita's own layers use a private format.
        val image = runCatching { readZip(ByteArrayInputStream(bytes))["mergedimage.png"] }.getOrNull() ?: bytes
        val bmp = decode(image) ?: throw OfficeException("That picture couldn't be read")
        val p = Painting(bmp.width, bmp.height)
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        bmp.recycle()
        p.layers += Layer(p.nextId++, "Background", pixels)
        p.activeId = p.layers.last().id
        return p
    }

    fun thumbnail(file: java.io.File): ByteArray? = thumbnail(file.inputStream().buffered())
    fun thumbnail(bytes: ByteArray): ByteArray? = thumbnail(ByteArrayInputStream(bytes))

    private fun thumbnail(input: java.io.InputStream): ByteArray? = runCatching {
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name == "Thumbnails/thumbnail.png") return@use zip.readBytes()
            }
            null
        }
    }.getOrNull()
}
