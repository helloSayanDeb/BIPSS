package com.bipss.app.export

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream

/**
 * A small A4 document writer on the platform PdfDocument: headings, wrapped
 * paragraphs, tables and monospaced blocks, flowing onto new pages with a
 * footer. No third-party library, and nothing leaves the device.
 */
class PdfBuilder(private val footer: String) {
    private val doc = PdfDocument()
    private var page: PdfDocument.Page? = null
    private var canvas: Canvas? = null
    private var y = 0f
    private var pageNumber = 0

    private val ink = 0xFF1A1A1A.toInt()
    private val muted = 0xFF666666.toInt()
    private val body = paint(10.5f, Typeface.DEFAULT, ink)
    private val bold = paint(10.5f, Typeface.DEFAULT_BOLD, ink)
    private val small = paint(8.5f, Typeface.DEFAULT, muted)
    private val mono = paint(8.5f, Typeface.MONOSPACE, ink)

    private fun paint(size: Float, face: Typeface, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; typeface = face; this.color = color
    }

    private fun ensure(height: Float) {
        if (page == null || y + height > PAGE_H - MARGIN - FOOTER_H) newPage()
    }

    private fun newPage() {
        finishPage()
        pageNumber++
        page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W.toInt(), PAGE_H.toInt(), pageNumber).create())
        canvas = page?.canvas
        y = MARGIN
    }

    private fun finishPage() {
        val p = page ?: return
        canvas?.drawText("$footer · $pageNumber", MARGIN, PAGE_H - MARGIN + 6f, small)
        doc.finishPage(p)
        page = null; canvas = null
    }

    fun title(text: String, subtitle: String? = null) {
        ensure(48f)
        canvas?.drawText(text, MARGIN, y + 20f, paint(20f, Typeface.DEFAULT_BOLD, ink))
        y += 28f
        if (subtitle != null) { canvas?.drawText(subtitle, MARGIN, y + 10f, small); y += 18f }
        y += 8f
    }

    fun heading(text: String) {
        ensure(34f)
        y += 10f
        canvas?.drawText(text, MARGIN, y + 12f, paint(13f, Typeface.DEFAULT_BOLD, ink))
        y += 22f
    }

    fun space(h: Float) { y += h }

    private fun wrap(text: String, p: Paint, width: Float): List<String> {
        val lines = ArrayList<String>()
        for (para in text.split("\n")) {
            if (para.isBlank()) { lines += ""; continue }
            var line = ""
            for (word in para.split(" ")) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (p.measureText(candidate) <= width) line = candidate
                else { if (line.isNotEmpty()) lines += line; line = word }
            }
            lines += line
        }
        return lines
    }

    fun paragraph(text: String, muted: Boolean = false) {
        val p = if (muted) small else body
        val lineH = p.textSize * 1.4f
        for (line in wrap(text, p, CONTENT_W)) {
            ensure(lineH)
            canvas?.drawText(line, MARGIN, y + p.textSize, p)
            y += lineH
        }
        y += 4f
    }

    /** Text kept in a fixed-width face so columns built with padding line up. */
    fun monospaced(text: String) {
        val lineH = mono.textSize * 1.35f
        for (line in text.split("\n")) {
            ensure(lineH)
            canvas?.drawText(line, MARGIN, y + mono.textSize, mono)
            y += lineH
        }
        y += 4f
    }

    fun keyValue(key: String, value: String) {
        ensure(16f)
        canvas?.drawText(key, MARGIN, y + 11f, body)
        canvas?.drawText(value, MARGIN + 220f, y + 11f, bold)
        y += 16f
    }

    /** [widths] are fractions of the content width and should sum to 1. */
    fun table(header: List<String>, rows: List<List<String>>, widths: List<Float>) {
        val rowH = 15f
        fun drawRow(cells: List<String>, p: Paint, rule: Boolean) {
            ensure(rowH)
            var x = MARGIN
            cells.forEachIndexed { i, c ->
                val w = CONTENT_W * widths[i]
                var t = c
                while (t.length > 1 && p.measureText(t) > w - 4f) t = t.dropLast(1)
                canvas?.drawText(t, x + 2f, y + 10.5f, p)
                x += w
            }
            if (rule) canvas?.drawLine(MARGIN, y + rowH - 2f, MARGIN + CONTENT_W, y + rowH - 2f, small)
            y += rowH
        }
        drawRow(header, bold, true)
        rows.forEach { drawRow(it, body, false) }
        y += 6f
    }

    fun finish(file: File): File {
        if (page == null) newPage()
        finishPage()
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        return file
    }

    private companion object {
        const val PAGE_W = 595f
        const val PAGE_H = 842f
        const val MARGIN = 40f
        const val FOOTER_H = 14f
        const val CONTENT_W = PAGE_W - 2 * MARGIN
    }
}
