package io.github.darkvolfi.dvscreator.core.imaging

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.darkvolfi.dvscreator.core.model.CropRect
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import kotlin.time.Duration.Companion.minutes

/**
 * Hızlı kare çıkarıcıyı (MediaCodec) eski yöntemle (MediaMetadataRetriever) karşılaştırır:
 * renk dönüşümü doğru mu, kareler doğru anlardan mı, ne kadar hızlı? `adb logcat -s WSC_VIDEO`
 */
@RunWith(AndroidJUnit4::class)
class VideoExtractionDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun hizli_yol_eski_yontemle_ayni_kareleri_verir() = runTest(timeout = 5.minutes) {
        val video = makeTestVideo(File(context.cacheDir, "extract_test.mp4"), width = 1280, height = 720, frames = 90, fps = 30)
        val uri = Uri.fromFile(video)
        val times = DoubleArray(30) { 200.0 + it * 90.0 }   // 0.2 sn'den 2.81 sn'ye

        val t0 = SystemClock.elapsedRealtime()
        val fast = ArrayList<ArgbImage>()
        VideoFrameExtractor(context).extract(uri, times, targetLongSide = 960) { _, img -> fast += img }
        val fastMs = SystemClock.elapsedRealtime() - t0

        val t1 = SystemClock.elapsedRealtime()
        val retriever = MediaMetadataRetriever().apply { setDataSource(context, uri) }
        val slow = times.map { t ->
            val bmp = retriever.getFrameAtTime((t * 1000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST)!!
            val small = android.graphics.Bitmap.createScaledBitmap(bmp, fast[0].width, fast[0].height, true)
            small.toArgbImage()
        }
        retriever.release()
        val slowMs = SystemClock.elapsedRealtime() - t1

        assertEquals(times.size, fast.size)
        // 1280 / 960 < 2 → tam sayı katlı küçültme yok, 1280×720 kalır (son küçültmeyi Lanczos yapar)
        assertEquals(1280, fast[0].width)
        assertEquals(720, fast[0].height)
        val ssims = fast.indices.map { Ssim.compute(slow[it], fast[it]) }
        Log.i(TAG, String.format(Locale.ROOT,
            "30 kare 1280×720: hızlı yol %d ms, eski yöntem %d ms (%.1f kat). SSIM (eski yönteme göre) ort=%.4f min=%.4f",
            fastMs, slowMs, slowMs.toDouble() / fastMs, ssims.average(), ssims.min()))
        // Eski yöntem farklı ölçekleyici (bilinear) kullanıyor → birebir değil ama renk/zaman hatası SSIM'i çok düşürür
        assertTrue("renk ya da zaman uyuşmuyor: min SSIM ${ssims.min()}", ssims.min() > 0.90)
    }

    @Test
    fun kirpma_videonun_dogru_bolgesini_alir() = runTest(timeout = 5.minutes) {
        val video = makeTestVideo(File(context.cacheDir, "crop_test.mp4"), width = 1280, height = 720, frames = 30, fps = 30)
        val uri = Uri.fromFile(video)
        fun saturation(img: ArgbImage): Int {
            val p = img[256, 256]   // sticker merkezi
            return maxOf(redOf(p), greenOf(p), blueOf(p)) - minOf(redOf(p), greenOf(p), blueOf(p))
        }
        // Videonun sol çeyreği (x < 320) renkli blok, gerisi gri (kroma nötr)
        val left = AnimationLoader(context).load(uri, "video/mp4", crop = CropRect(0, 0, 300, 300))
        val right = AnimationLoader(context).load(uri, "video/mp4", crop = CropRect(900, 300, 1200, 600))
        val leftSat = saturation(left.clip.frames[5])
        val rightSat = saturation(right.clip.frames[5])
        Log.i(TAG, "Kırpma testi: sol (renkli) doygunluk=$leftSat, sağ (gri) doygunluk=$rightSat")
        assertTrue("sol kırpma renkli bölgeyi almadı: $leftSat", leftSat > 40)
        assertTrue("sağ kırpma gri bölgeyi almadı: $rightSat", rightSat < 15)
        // Kare kırpma → sticker kenar boşlukları dışında tamamen dolu (şeffaf yan boşluk yok)
        assertEquals(255, alphaOf(left.clip.frames[0][20, 256]))
    }

    /**
     * Donanım kodlayıcıyla test videosu: yatay degrade + kayan beyaz şerit + karede değişen renk bloğu.
     * Her karede farklı içerik → yanlış kare seçilirse SSIM düşer.
     */
    private fun makeTestVideo(out: File, width: Int, height: Int, frames: Int, fps: Int): File {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        val info = MediaCodec.BufferInfo()
        var submitted = 0
        var done = false
        while (!done) {
            if (submitted <= frames) {
                val inIndex = encoder.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    if (submitted == frames) {
                        encoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    } else {
                        val image = encoder.getInputImage(inIndex)!!
                        val (yp, up, vp) = image.planes
                        val bar = submitted * width / frames
                        for (y in 0 until height) for (x in 0 until width) {
                            val luma = if (x in bar until bar + 40) 235 else 16 + x * 200 / width
                            yp.buffer.put(y * yp.rowStride + x * yp.pixelStride, luma.toByte())
                        }
                        val cu = (64 + submitted * 2).coerceAtMost(240)
                        for (y in 0 until height / 2) for (x in 0 until width / 2) {
                            val block = x < width / 4
                            up.buffer.put(y * up.rowStride + x * up.pixelStride, (if (block) cu else 128).toByte())
                            vp.buffer.put(y * vp.rowStride + x * vp.pixelStride, (if (block) 200 else 128).toByte())
                        }
                        encoder.queueInputBuffer(inIndex, 0, width * height * 3 / 2, submitted * 1_000_000L / fps, 0)
                    }
                    submitted++
                }
            }
            val outIndex = encoder.dequeueOutputBuffer(info, 10_000)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(encoder.outputFormat); muxer.start() }
                outIndex >= 0 -> {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        muxer.writeSampleData(track, encoder.getOutputBuffer(outIndex)!!, info)
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                }
            }
        }
        encoder.stop(); encoder.release()
        muxer.stop(); muxer.release()
        return out
    }

    private companion object {
        const val TAG = "WSC_VIDEO"
    }
}
