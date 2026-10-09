package io.github.darkvolfi.wscreator.probe

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import io.github.darkvolfi.wscreator.core.imaging.ArgbImage
import io.github.darkvolfi.wscreator.core.imaging.StickerEncoder
import io.github.darkvolfi.wscreator.core.imaging.toArgbImage
import io.github.darkvolfi.wscreator.core.webp.NativeWebp
import io.github.darkvolfi.wscreator.core.webp.WebpOptions
import io.github.darkvolfi.wscreator.core.whatsapp.PackManifest
import io.github.darkvolfi.wscreator.core.whatsapp.PackStore
import io.github.darkvolfi.wscreator.core.whatsapp.StickerEntry
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * GEÇİCİ fizibilite testi (agents/04 — M2 ilk madde): WhatsApp hangi paketleri kabul ediyor?
 * Her paket 3 sticker içerir (WhatsApp'ın alt sınırı). Sonuçlar agents/raporlar/'a yazılacak.
 */
object WhatsAppProbe {

    class Spec(val id: String, val title: String, val purpose: String)

    val specs = listOf(
        Spec("wsc_probe_1_static100", "1 · Statik ≤100 KB", "Kontrol: normal statik paket"),
        Spec("wsc_probe_2_anim500", "2 · Hareketli ≤500 KB", "Kontrol: normal hareketli paket"),
        Spec("wsc_probe_3_still_as_anim", "3 · Statik → 2 kare, 500 KB", "Maksimum kalite modu"),
        Spec("wsc_probe_4_anim700", "4 · Hareketli ~700 KB", "500 KB sınırı esniyor mu?"),
        Spec("wsc_probe_5_anim1500", "5 · Hareketli ~1.5 MB", "Çok daha büyüğü?"),
        Spec("wsc_probe_6_static200", "6 · Statik ~200 KB", "100 KB sınırı esniyor mu?"),
    )

    private const val SIZE = 512
    private const val KB = 1024

    /** Tüm test paketlerini üretip diske yazar; her paketin özetini döner. */
    suspend fun generateAll(store: PackStore, onProgress: (String) -> Unit): Map<String, String> {
        store.deleteAll()
        val summaries = linkedMapOf<String, String>()
        for ((index, spec) in specs.withIndex()) {
            onProgress(spec.title)
            val stickers = (1..3).map { n -> build(index + 1, n) }
            val files = stickers.mapIndexed { i, bytes -> "s$i.webp" to bytes }.toMap() + ("tray.png" to tray(index + 1))
            store.write(
                PackManifest(
                    identifier = spec.id,
                    name = "WSC Test ${spec.title}",
                    publisher = "WScreator test",
                    trayImageFile = "tray.png",
                    animated = index + 1 in setOf(2, 3, 4, 5),
                    avoidCache = true,
                    stickers = files.keys.filter { it.endsWith(".webp") }.map { StickerEntry(it, listOf("🧪")) },
                ),
                files,
            )
            summaries[spec.id] = stickers.joinToString(" · ") { describe(it) }
        }
        return summaries
    }

    private suspend fun build(pack: Int, n: Int): ByteArray = when (pack) {
        1 -> StickerEncoder(NativeWebp, 100 * KB).encode(photoLike(pack, n, grain = 10)).bytes
        2 -> movingAnimation(pack, n, frames = 20, quality = 80f)
        3 -> {
            // Ham görsel 100 KB'a kayıpsız sığmayacak kadar detaylı; 500 KB bütçeyle kodlanır
            val budget = 500 * KB - 512
            val still = StickerEncoder(NativeWebp, budget).encode(photoLike(pack, n, grain = 18)).bytes
            NativeWebp.staticAsAnimation(still, SIZE, SIZE)
        }
        4 -> noisyAnimation(pack, n, targetBytes = 700 * KB)
        5 -> noisyAnimation(pack, n, targetBytes = 1500 * KB)
        6 -> noisyStatic(pack, n, targetBytes = 200 * KB)
        else -> error("bilinmeyen paket $pack")
    }

    private fun describe(bytes: ByteArray): String {
        val info = NativeWebp.info(bytes)
        return "%.0f KB/%d kare".format(bytes.size / 1024.0, info.frameCount)
    }

    /** Degrade + daireler + gren + büyük etiket ("3·2" = paket 3, sticker 2). */
    private fun photoLike(pack: Int, n: Int, grain: Int, shift: Float = 0f): ArgbImage {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val hue = (pack * 57 + n * 23) % 360f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, SIZE.toFloat(), SIZE.toFloat(),
            Color.HSVToColor(floatArrayOf(hue, 0.6f, 0.95f)), Color.HSVToColor(floatArrayOf((hue + 120) % 360, 0.7f, 0.5f)), Shader.TileMode.CLAMP)
        c.drawRoundRect(16f, 16f, SIZE - 16f, SIZE - 16f, 64f, 64f, paint)
        paint.shader = null
        val rnd = Random(pack * 10 + n)
        repeat(6) {
            paint.color = Color.HSVToColor(180, floatArrayOf(rnd.nextFloat() * 360, 0.8f, 1f))
            c.drawCircle(60 + rnd.nextFloat() * 390 + shift, 60 + rnd.nextFloat() * 390, 30 + rnd.nextFloat() * 70, paint)
        }
        drawLabel(c, "$pack·$n", 150f)
        val img = bmp.toArgbImage()
        if (grain > 0) {
            val g = Random(pack * 100 + n)
            for (i in img.pixels.indices) {
                val p = img.pixels[i]
                if (p ushr 24 == 0) continue
                val d = g.nextInt(-grain, grain + 1)
                fun ch(v: Int) = (v + d).coerceIn(0, 255)
                img.pixels[i] = (p and 0xFF000000.toInt()) or (ch((p shr 16) and 0xff) shl 16) or (ch((p shr 8) and 0xff) shl 8) or ch(p and 0xff)
            }
        }
        return img
    }

    private fun drawLabel(c: Canvas, text: String, size: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
        val y = SIZE / 2f - (p.descent() + p.ascent()) / 2
        p.style = Paint.Style.STROKE; p.strokeWidth = size / 10; p.color = Color.BLACK
        c.drawText(text, SIZE / 2f, y, p)
        p.style = Paint.Style.FILL; p.color = Color.WHITE
        c.drawText(text, SIZE / 2f, y, p)
    }

    /** Etiketin etrafında dönen daireler; sıkıştırılabilir, gerçekçi hareketli içerik. */
    private fun movingAnimation(pack: Int, n: Int, frames: Int, quality: Float): ByteArray {
        val images = (0 until frames).map { f -> photoLike(pack, n, grain = 0, shift = (f * 12f) % 120f).pixels }
        var q = quality
        while (true) {
            val bytes = NativeWebp.encodeAnimation(images, IntArray(frames) { 100 }, SIZE, SIZE, WebpOptions.lossy(q, 4))
            if (bytes.size <= 500 * KB || q <= 10f) return bytes
            q -= 10f
        }
    }

    /** Her karede yenilenen gürültü şeridi → boyutu hedefe doğru şişirir (sıkıştırılamaz). */
    private fun noisyAnimation(pack: Int, n: Int, targetBytes: Int): ByteArray {
        val frames = 20
        val base = photoLike(pack, n, grain = 0)
        var rows = (targetBytes / (frames * SIZE * 3.0)).roundToInt().coerceIn(1, SIZE)
        var result = ByteArray(0)
        repeat(4) {
            val images = (0 until frames).map { f -> withNoiseRows(base, rows, seed = f * 31 + pack).pixels }
            result = NativeWebp.encodeAnimation(images, IntArray(frames) { 100 }, SIZE, SIZE, WebpOptions.lossless(1))
            val ratio = targetBytes.toDouble() / result.size
            if (ratio in 0.95..1.10) return result
            rows = (rows * ratio).roundToInt().coerceIn(1, SIZE)
        }
        return result
    }

    private fun noisyStatic(pack: Int, n: Int, targetBytes: Int): ByteArray {
        val base = photoLike(pack, n, grain = 0)
        var rows = (targetBytes / (SIZE * 3.0)).roundToInt().coerceIn(1, SIZE)
        var result = ByteArray(0)
        repeat(4) {
            val img = withNoiseRows(base, rows, seed = n)
            result = NativeWebp.encode(img.pixels, SIZE, SIZE, WebpOptions.lossless(1))
            val ratio = targetBytes.toDouble() / result.size
            if (ratio in 0.95..1.10) return result
            rows = (rows * ratio).roundToInt().coerceIn(1, SIZE)
        }
        return result
    }

    private fun withNoiseRows(base: ArgbImage, rows: Int, seed: Int): ArgbImage {
        val img = base.copy()
        val rnd = Random(seed)
        val top = SIZE - rows
        for (i in top * SIZE until SIZE * SIZE) img.pixels[i] = 0xFF000000.toInt() or rnd.nextInt(0x1000000)
        return img
    }

    private fun tray(pack: Int): ByteArray {
        val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawCircle(48f, 48f, 46f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.HSVToColor(floatArrayOf(pack * 57f % 360, 0.7f, 0.9f)) })
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 56f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER; color = Color.WHITE }
        c.drawText("$pack", 48f, 48f - (p.descent() + p.ascent()) / 2, p)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }
}
