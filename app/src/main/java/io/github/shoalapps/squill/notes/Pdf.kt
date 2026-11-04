package io.github.shoalapps.squill.notes

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import java.io.OutputStream

/** A note as a PDF of A4 pages, using Android's own PDF writer. */
object Pdf {
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 56

    private fun styled(text: String): CharSequence {
        val out = SpannableStringBuilder()
        Markdown.inlines(text).forEach { run ->
            val start = out.length
            out.append(run.text)
            val end = out.length
            if (run.bold && run.italic) out.setSpan(StyleSpan(Typeface.BOLD_ITALIC), start, end, 0)
            else if (run.bold) out.setSpan(StyleSpan(Typeface.BOLD), start, end, 0)
            else if (run.italic) out.setSpan(StyleSpan(Typeface.ITALIC), start, end, 0)
            if (run.strike) out.setSpan(StrikethroughSpan(), start, end, 0)
            if (run.code) out.setSpan(TypefaceSpan("monospace"), start, end, 0)
            if (run.url != null || run.note != null) out.setSpan(UnderlineSpan(), start, end, 0)
        }
        return out
    }

    fun export(title: String, markdown: String, out: OutputStream): Int {
        val doc = PdfDocument()
        var pageNumber = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNumber).create())
        var y = MARGIN.toFloat()
        val width = PAGE_W - 2 * MARGIN
        fun place(text: CharSequence, size: Float, indent: Int = 0, after: Float = 8f, bold: Boolean = false) {
            val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK; textSize = size
                if (bold) typeface = Typeface.DEFAULT_BOLD
            }
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width - indent).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.2f).build()
            var line = 0
            while (line < layout.lineCount) {
                // Lines that fit go on this page; the rest start the next one.
                var last = line
                while (last < layout.lineCount && y + (layout.getLineBottom(last) - layout.getLineTop(line)) <= PAGE_H - MARGIN) last++
                if (last == line) {
                    doc.finishPage(page)
                    pageNumber++
                    page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNumber).create())
                    y = MARGIN.toFloat()
                    continue
                }
                val c = page.canvas
                val top = layout.getLineTop(line).toFloat()
                val bottom = layout.getLineTop(last).toFloat()
                c.save()
                c.translate((MARGIN + indent).toFloat(), y - top)
                c.clipRect(0f, top, (width - indent).toFloat(), bottom)
                layout.draw(c)
                c.restore()
                y += bottom - top
                line = last
            }
            y += after
        }
        place(title, 24f, after = 14f, bold = true)
        Markdown.parse(markdown).forEach { b ->
            when (b) {
                is Block.Heading -> place(styled(b.text), when (b.level) { 1 -> 20f; 2 -> 17f; else -> 14f }, after = 6f, bold = true)
                is Block.Paragraph -> place(styled(b.text), 11f)
                is Block.Bullet -> place(SpannableStringBuilder("•  ").append(styled(b.text)), 11f, 14 * (b.indent + 1), 4f)
                is Block.Numbered -> place(SpannableStringBuilder("${b.number}.  ").append(styled(b.text)), 11f, 14 * (b.indent + 1), 4f)
                is Block.Task -> place(SpannableStringBuilder(if (b.done) "☑  " else "☐  ").append(styled(b.text)), 11f, 14 * (b.indent + 1), 4f)
                is Block.Quote -> place(SpannableStringBuilder(styled(b.text)).also { it.setSpan(StyleSpan(Typeface.ITALIC), 0, it.length, 0) }, 11f, 16)
                is Block.Code -> place(SpannableStringBuilder(b.text).also { it.setSpan(TypefaceSpan("monospace"), 0, it.length, 0) }, 9.5f, 8)
                is Block.Table -> (listOf(b.header) + b.rows).forEachIndexed { i, row ->
                    place(row.joinToString("   |   ") { c -> Markdown.inlines(c).joinToString("") { it.text } }, 10f, after = 3f, bold = i == 0)
                }
                is Block.Rule -> y += 10f
                is Block.Embed -> place(SpannableStringBuilder("[Drawing: ${b.target.removeSuffix(".svg")}]").also { it.setSpan(StyleSpan(Typeface.ITALIC), 0, it.length, 0) }, 10f)
            }
        }
        doc.finishPage(page)
        doc.writeTo(out)
        doc.close()
        return pageNumber
    }
}
