package io.github.darkvolfi.dvscreator.core.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import io.github.darkvolfi.dvscreator.core.model.TextLayer

/**
 * Sticker üzerindeki yazılar. Konumlar ve boyut SON sticker tuvaline göre (0..1) — yazı kırpma ya da
 * küçültmeden etkilenmez, her zaman 512 px'te keskin çizilir. Düzenleme ekranının önizlemesi de aynı
 * [render] fonksiyonunu kullanır: gördüğün, çıkan stickerla aynı.
 */
object TextOverlay {

    /** Klasik sticker yazısı: kalın, dış çizgi yazı boyunun bu oranında. */
    private const val OUTLINE_FRACTION = 0.14f
    /** Satır genişliği tuvalin bu oranını geçerse yazı küçültülür. */
    private const val MAX_WIDTH_FRACTION = 0.96f

    /** Tüm yazıları [size]×[size] şeffaf bir bitmap'e çizer; yazı yoksa null. */
    fun render(layers: List<TextLayer>, size: Int): Bitmap? {
        if (layers.none { it.text.isNotBlank() }) return null
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        for (layer in layers) draw(canvas, layer, size)
        return bitmap
    }

    /** Yazının tuvaldeki kutusu (dokunarak seçmek için), piksel. */
    fun bounds(layer: TextLayer, size: Int): RectF {
        val (paint, lines) = layout(layer, size)
        val lineHeight = paint.fontSpacing
        val width = lines.maxOf { paint.measureText(it) } + paint.textSize * OUTLINE_FRACTION
        val height = lineHeight * lines.size
        val cx = layer.centerX * size
        val cy = layer.centerY * size
        return RectF(cx - width / 2, cy - height / 2, cx + width / 2, cy + height / 2)
    }

    /** Yazıları her kareye bindirir (yerinde). */
    fun apply(frames: List<ArgbImage>, layers: List<TextLayer>) {
        if (frames.isEmpty()) return
        val bitmap = render(layers, frames[0].width) ?: return
        val overlay = bitmap.toArgbImage()
        bitmap.recycle()
        for (f in frames) composite(f, overlay)
    }

    fun apply(frame: ArgbImage, layers: List<TextLayer>) = apply(listOf(frame), layers)

    private fun layout(layer: TextLayer, size: Int): Pair<Paint, List<String>> {
        val lines = layer.text.split('\n').ifEmpty { listOf("") }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = layer.sizeFraction * size
        }
        val widest = lines.maxOf { paint.measureText(it) } * (1 + OUTLINE_FRACTION)
        if (widest > size * MAX_WIDTH_FRACTION) paint.textSize *= size * MAX_WIDTH_FRACTION / widest
        return paint to lines
    }

    private fun draw(canvas: Canvas, layer: TextLayer, size: Int) {
        if (layer.text.isBlank()) return
        val (paint, lines) = layout(layer, size)
        val lineHeight = paint.fontSpacing
        // Satırlar dikeyde ortalanır; taban çizgisi yazı tipinin üst/alt ölçüsünden
        val top = layer.centerY * size - lineHeight * lines.size / 2
        val ascent = -paint.fontMetrics.ascent
        val descent = paint.fontMetrics.descent
        val pad = (lineHeight - ascent - descent) / 2
        val x = layer.centerX * size
        for ((i, line) in lines.withIndex()) {
            val baseline = top + i * lineHeight + pad + ascent
            layer.outlineArgb?.let { outline ->
                val stroke = Paint(paint).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = paint.textSize * OUTLINE_FRACTION
                    strokeJoin = Paint.Join.ROUND
                    color = outline
                }
                canvas.drawText(line, x, baseline, stroke)
            }
            paint.style = Paint.Style.FILL
            paint.color = layer.colorArgb
            canvas.drawText(line, x, baseline, paint)
        }
    }

    /** Düz (premultiplied olmayan) ARGB'de "üstüne" birleştirme. */
    private fun composite(dst: ArgbImage, src: ArgbImage) {
        val d = dst.pixels
        val s = src.pixels
        for (i in d.indices) {
            val sp = s[i]
            val sa = sp ushr 24
            if (sa == 0) continue
            if (sa == 255) { d[i] = sp; continue }
            val dp = d[i]
            val da = dp ushr 24
            val outA = sa + da * (255 - sa) / 255
            if (outA == 0) continue
            fun ch(shift: Int): Int {
                val sc = (sp shr shift) and 0xFF
                val dc = (dp shr shift) and 0xFF
                return ((sc * sa + dc * da * (255 - sa) / 255) / outA).coerceIn(0, 255)
            }
            d[i] = (outA shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}
