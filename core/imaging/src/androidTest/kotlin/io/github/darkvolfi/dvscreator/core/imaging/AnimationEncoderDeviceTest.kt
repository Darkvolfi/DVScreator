package io.github.darkvolfi.dvscreator.core.imaging

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.darkvolfi.dvscreator.core.webp.NativeWebp
import io.github.darkvolfi.dvscreator.core.webp.WebpOptions
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import kotlin.time.Duration.Companion.minutes

/** Animasyon BEST_FIT cihaz testleri. Sonuçlar: `adb logcat -s WSC_ANIM` */
@RunWith(AndroidJUnit4::class)
class AnimationEncoderDeviceTest {

    private val limit = AnimationEncoder.ANIMATED_LIMIT_BYTES

    @Test
    fun sabitleme_fark_kodlamasini_etkinlestirir() {
        val clip = TestClips.movingBallOnNoisyBackground(frames = 30, noise = 3)
        val options = WebpOptions.lossy(80f, method = 4)
        fun size(c: AnimationClip) =
            NativeWebp.encodeAnimation(c.frames.map { it.pixels }, c.durationsMs, 512, 512, options).size
        val raw = size(clip)
        // Eşik taraması: boyut kazancı vs sabitlemenin kendi kalite kaybı (orijinal karelere göre SSIM)
        val rows = mutableListOf(String.format(Locale.ROOT, "eşik  0 (yok): %6.1f KB", raw / 1024.0))
        for (t in listOf(2, 3, 4, 6)) {
            val s = TemporalStabilizer.stabilize(clip, meanThreshold = t)
            val ssim = clip.frames.indices.minOf { Ssim.compute(clip.frames[it], s.frames[it]) }
            rows += String.format(Locale.ROOT, "eşik %2d      : %6.1f KB  (%.1f kat)  sabitleme SSIM en kötü kare=%.4f",
                t, size(s) / 1024.0, raw.toDouble() / size(s), ssim)
        }
        Log.i(TAG, "Sabitleme taraması (q80, 30 kare, ±3 gürültü):\n" + rows.joinToString("\n"))
        val stabilized = size(TemporalStabilizer.stabilize(clip, AnimationEncoder.DEFAULT_STABILIZE_THRESHOLD))
        assertTrue("sabitleme boyutu yeterince düşürmedi: $raw → $stabilized", stabilized * 2 < raw)
    }

    @Test
    fun best_fit_klipler_sinira_uyar_ve_whatsapp_kurallarina_uyar() = runTest(timeout = 10.minutes) {
        val clips = listOf(
            "top_gurultulu_30k" to TestClips.movingBallOnNoisyBackground(frames = 30, noise = 3),
            "top_gurultulu_75k" to TestClips.movingBallOnNoisyBackground(frames = 75, noise = 3),
            "tam_hareket_60k" to TestClips.fullMotion(frames = 60),
            "tam_hareket_120k" to TestClips.fullMotion(frames = 120, frameMs = 33),
        )
        val rows = mutableListOf<String>()
        for ((name, clip) in clips) {
            val t = SystemClock.elapsedRealtime()
            val r = AnimationEncoder().encode(clip)
            val ms = SystemClock.elapsedRealtime() - t
            val info = NativeWebp.info(r.bytes)
            assertTrue("$name: ${r.bytes.size} > $limit", r.bytes.size <= limit)
            assertEquals(512, info.canvasWidth)
            assertTrue("$name: WhatsApp en az 2 kare ister", info.frameCount >= 2)
            assertTrue(info.frameDurationsMs.all { it >= 8 })
            assertTrue(info.totalDurationMs <= 10_000)
            assertEquals("$name: süre değişmemeli", clip.totalDurationMs, info.totalDurationMs)
            rows += String.format(
                Locale.ROOT, "%-18s %3d kare %4.1f fps → %-8s q=%3d %3d kare %4.1f fps %6.1f KB  SSIM ort=%.4f min=%.4f  deneme=%2d  %5d ms (kodlama %d, ölçüm %d)",
                name, clip.frames.size, clip.fps, r.mode, r.quality, r.frameCount, r.fps, r.bytes.size / 1024.0,
                r.ssimMean, r.ssimMin, r.attempts, ms, r.encodeMs, r.measureMs,
            )
        }
        Log.i(TAG, "Animasyon BEST_FIT:\n" + rows.joinToString("\n"))
    }

    private companion object {
        const val TAG = "WSC_ANIM"
    }
}
