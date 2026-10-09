package io.github.darkvolfi.wscreator.core.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.darkvolfi.wscreator.core.model.EditRecipe
import io.github.darkvolfi.wscreator.core.webp.NativeWebp
import io.github.darkvolfi.wscreator.core.webp.WebpOptions
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.time.Duration.Companion.minutes

/**
 * Gerçek libwebp ile cihaz üzerinde kalite testleri.
 * Sonuç tablosu logcat'e yazılır: `adb logcat -s WSC_QUALITY`
 */
@RunWith(AndroidJUnit4::class)
class QualityPipelineDeviceTest {

    private val pipeline = StickerPipeline(NativeWebp)
    private val limit = StickerPipeline.STATIC_LIMIT_BYTES

    @Test
    fun kayipsiz_gidis_donus_piksel_piksel_ayni() {
        val img = StickerCanvas.fit(TestImages.cartoon(1024), 512, 16)
        val bytes = NativeWebp.encode(img.pixels, 512, 512, WebpOptions.lossless())
        val back = NativeWebp.decode(bytes)
        assertEquals(512, back.width)
        for (i in img.pixels.indices) {
            val expected = img.pixels[i]
            val actual = back.argb[i]
            // exact=false: tam şeffaf piksellerin görünmez RGB'si değişebilir, görünür olanlar birebir aynı olmalı
            if (alphaOf(expected) == 0) assertEquals(0, alphaOf(actual)) else assertEquals(expected, actual)
        }
    }

    @Test
    fun altin_set_sinirlara_uyar_ve_kalite_esiklerini_gecer() = runTest(timeout = 5.minutes) {
        val rows = mutableListOf<String>()
        for (named in TestImages.all()) {
            val start = SystemClock.elapsedRealtime()
            val rendered = pipeline.render(named.image, EditRecipe())
            val renderMs = SystemClock.elapsedRealtime() - start
            val result = StickerEncoder(NativeWebp, limit).encode(rendered)
            val totalMs = SystemClock.elapsedRealtime() - start

            val decoded = NativeWebp.decode(result.bytes)
            assertEquals(512, decoded.width)
            assertEquals(512, decoded.height)
            assertTrue("${named.name}: ${result.bytes.size} > $limit", result.bytes.size <= limit)
            val minSsim = MIN_SSIM.getValue(named.name)
            assertTrue("${named.name}: SSIM ${result.ssim} < $minSsim", result.ssim >= minSsim)

            rows += String.format(
                Locale.ROOT, "%-16s %-13s q=%3d  %6.1f KB  SSIM=%.4f  deneme=%2d  hazırlık=%4d ms  toplam=%5d ms",
                named.name, result.mode, result.quality, result.bytes.size / 1024.0, result.ssim,
                result.attempts, renderMs, totalMs,
            )
        }
        Log.i(TAG, "Altın set sonuçları:\n" + rows.joinToString("\n"))
    }

    /** Kıyas: çoğu uygulamanın yaptığı gibi bilinear küçültme + Bitmap.compress. */
    @Test
    fun kiyas_bizim_hat_vs_bitmap_compress() = runTest(timeout = 5.minutes) {
        val rows = mutableListOf<String>()
        for (named in TestImages.all().filter { it.name != "noise_1024" }) {
            // Referans: bizim kodlama öncesi (kayıpsız) 512 px görüntümüz
            val reference = pipeline.render(named.image, EditRecipe())
            val ours = StickerEncoder(NativeWebp, limit).encode(reference)

            val naive = naiveSticker(named.image)
            val naiveDecoded = NativeWebp.decode(naive.first)
            val naiveSsim = Ssim.compute(reference, ArgbImage(512, 512, naiveDecoded.argb))

            rows += String.format(
                Locale.ROOT, "%-16s  bizim: %-13s %6.1f KB SSIM=%.4f | basit: q=%3d %6.1f KB SSIM=%.4f",
                named.name, ours.mode, ours.bytes.size / 1024.0, ours.ssim,
                naive.second, naive.first.size / 1024.0, naiveSsim,
            )
            assertTrue("${named.name}: bizim hat basit yöntemden kötü", ours.ssim >= naiveSsim)
        }
        Log.i(TAG, "Kıyas (referans = bizim kayıpsız 512 px render):\n" + rows.joinToString("\n"))
    }

    private fun naiveSticker(src: ArgbImage): Pair<ByteArray, Int> {
        val box = 480
        val scale = box.toFloat() / maxOf(src.width, src.height)
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src.toBitmap(), w, h, true)
        val canvasBmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        Canvas(canvasBmp).drawBitmap(scaled, ((512 - w) / 2).toFloat(), ((512 - h) / 2).toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
        var lo = 0; var hi = 100; var best: ByteArray? = null; var bestQ = 0
        while (lo <= hi) {
            val q = (lo + hi) / 2
            val out = ByteArrayOutputStream()
            canvasBmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, q, out)
            if (out.size() <= limit) { best = out.toByteArray(); bestQ = q; lo = q + 1 } else hi = q - 1
        }
        return (best ?: error("basit yöntem sığdıramadı")) to bestQ
    }

    private companion object {
        const val TAG = "WSC_QUALITY"

        /**
         * Kalite eşikleri — 2026-10-09'da Samsung SM-S731B üzerinde ölçülüp kilitlendi.
         * Eşik = ölçülen değer − 0.002 (bkz. agents/02 kuralı). Gerileme olursa test kırılır;
         * eşik yalnızca bilinçli bir iyileştirmeden sonra yükseltilir.
         */
        val MIN_SSIM = mapOf(
            "photo_3000x2000" to 0.998,         // ölçülen 1.0000 (kayıpsız)
            "grainy_photo_3000x2000" to 0.994,  // ölçülen 0.9960 (near-lossless 80)
            "cartoon_2048" to 0.998,            // ölçülen 1.0000
            "text_1600x1200" to 0.998,          // ölçülen 1.0000
            "soft_edge_2000" to 0.998,          // ölçülen 1.0000
            "zone_plate_2048" to 0.997,         // ölçülen 0.9993
            "noise_1024" to 0.694,              // ölçülen 0.6962 — sıkıştırılamaz en kötü durum
            "tiny_64x48" to 0.998,              // ölçülen 1.0000
        )
    }
}
