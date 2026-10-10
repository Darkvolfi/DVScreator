package io.github.darkvolfi.dvscreator.core.imaging

import android.content.Context
import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import io.github.darkvolfi.dvscreator.core.webp.NativeImage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Videodan kare çıkarma — `MediaCodec` ile TEK GEÇİŞTE, sırayla.
 *
 * `MediaMetadataRetriever.getFrameAtIndex` her kare için en yakın anahtar kareye geri sarıp
 * yeniden çözüyor: gerçek 81 sn'lik videoda 150 kare 52.7 sn sürdü (2026-10-10 ölçümü).
 * Burada aralığın başındaki anahtar kareden itibaren donanım çözücüyle bir kez geçilir,
 * istenen anlara en yakın kareler alınır, diğerleri hemen bırakılır.
 *
 * YUV → RGB dönüşümü küçültmeyle birlikte yapılır: kare, uzun kenarı [targetLongSide]'ın
 * altına düşmeyecek tam sayı katlı alan ortalamasıyla RGB'ye çevrilir (1080p → 960×540).
 *
 * Desteklenmeyen biçimlerde (ör. 10-bit HDR / P010) [UnsupportedVideoException] atılır →
 * çağıran eski yönteme (MediaMetadataRetriever) döner.
 */
class VideoFrameExtractor(private val context: Context) {

    class UnsupportedVideoException(message: String) : Exception(message)

    /**
     * @param timesMs istenen kare anları (artan sırada, kaynak zaman çizelgesinde)
     * @param onFrame her istenen an için bir kez çağrılır (sırayla); görüntü döndürme uygulanmış
     */
    suspend fun extract(
        uri: Uri,
        timesMs: DoubleArray,
        targetLongSide: Int,
        onFrame: suspend (index: Int, image: ArgbImage) -> Unit,
    ) {
        require(timesMs.isNotEmpty())
        val rotation = readRotation(uri)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: throw UnsupportedVideoException("video izi yok")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val startUs = (timesMs.first() * 1000).toLong()
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var next = 0   // sıradaki istenen kare
            var colors = ColorSpec.from(codec.outputFormat, format)
            // Yarım kare toleransı: istenen ana en yakın kare alınsın
            val frameUs = 1_000_000.0 / (format.takeIf { it.containsKey(MediaFormat.KEY_FRAME_RATE) }
                ?.getInteger(MediaFormat.KEY_FRAME_RATE)?.coerceAtLeast(1) ?: 30)

            while (next < timesMs.size) {
                currentCoroutineContext().ensureActive()
                // Yalnızca yapacak iş yokken bekle: her adımda sabit bekleme 90 karede saniyeler ekliyordu
                var fed = false
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(0)
                    if (inIndex >= 0) {
                        fed = true
                        val buffer = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, if (fed) 0 else TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> colors = ColorSpec.from(codec.outputFormat, format)
                    outIndex >= 0 -> {
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val pts = info.presentationTimeUs
                        // Bu kare, istenen anı (yarım kare toleransla) geçtiyse o an için kullanılır.
                        // Kaynak hedeften seyrekse aynı kare birden çok ana denk gelebilir.
                        if (info.size > 0 && pts + frameUs / 2 >= timesMs[next] * 1000) {
                            val image = codec.getOutputImage(outIndex) ?: throw UnsupportedVideoException("çıkış görüntüsü yok")
                            val argb = try {
                                toArgb(image, targetLongSide, colors)
                            } finally {
                                image.close()
                            }
                            val rotated = argb.rotate(rotation)
                            while (next < timesMs.size && pts + frameUs / 2 >= timesMs[next] * 1000) {
                                onFrame(next, rotated)
                                next++
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (eos) break
                    }
                }
            }
            // Video istenenden kısa kaldıysa (yuvarlama) eksik anlar son kareyle doldurulamaz → çağıran bilir
            if (next < timesMs.size) throw UnsupportedVideoException("video erken bitti: ${timesMs.size - next} kare eksik")
        } catch (e: UnsupportedVideoException) {
            throw e
        } catch (e: IllegalStateException) {
            throw UnsupportedVideoException("çözücü hatası: ${e.message}")
        } catch (e: IllegalArgumentException) {
            throw UnsupportedVideoException("çözücü yapılandırılamadı: ${e.message}")
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun readRotation(uri: Uri): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } catch (_: Exception) {
            0
        } finally {
            retriever.release()
        }
    }

    /** YUV → RGB katsayıları (renk standardı ve aralığına göre). */
    internal class ColorSpec(val bt709: Boolean, val fullRange: Boolean) {
        companion object {
            fun from(output: MediaFormat?, input: MediaFormat): ColorSpec {
                fun int(key: String) = listOfNotNull(output, input).firstOrNull { it.containsKey(key) }?.getInteger(key)
                val height = int(MediaFormat.KEY_HEIGHT) ?: 0
                val standard = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) int(MediaFormat.KEY_COLOR_STANDARD) else null
                val range = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) int(MediaFormat.KEY_COLOR_RANGE) else null
                val bt709 = when (standard) {
                    MediaFormat.COLOR_STANDARD_BT709 -> true
                    MediaFormat.COLOR_STANDARD_BT601_NTSC, MediaFormat.COLOR_STANDARD_BT601_PAL -> false
                    else -> height >= 720   // belirtilmemişse: HD → BT.709 (yaygın kural)
                }
                return ColorSpec(bt709, fullRange = range == MediaFormat.COLOR_RANGE_FULL)
            }
        }
    }

    internal companion object {
        const val TIMEOUT_US = 10_000L

        /**
         * YUV_420_888 → ARGB; aynı anda `f×f` alan ortalamasıyla küçültür (f = en büyük tam sayı,
         * uzun kenar [targetLongSide]'ın altına inmeyecek şekilde). Kroma her blokta merkezden örneklenir
         * (zaten yarım çözünürlüklü).
         */
        fun toArgb(image: Image, targetLongSide: Int, colors: ColorSpec): ArgbImage {
            if (image.format != ImageFormat.YUV_420_888) throw UnsupportedVideoException("desteklenmeyen biçim: ${image.format}")
            val crop = image.cropRect
            val srcW = crop.width()
            val srcH = crop.height()
            val f = max(1, max(srcW, srcH) / targetLongSide)
            val outW = srcW / f
            val outH = srcH / f

            // Hızlı yol: C (Kotlin döngüsü debug derlemede 1280×720 kare başına ~200 ms)
            val (yp, up, vp) = image.planes
            val native = ArgbImage(outW, outH)
            if (NativeImage.yuvToArgb(
                    yp.buffer, yp.rowStride, yp.pixelStride, up.buffer, up.rowStride, up.pixelStride,
                    vp.buffer, vp.rowStride, vp.pixelStride, crop.left, crop.top, outW, outH, f,
                    colors.bt709, colors.fullRange, native.pixels,
                )
            ) return native

            val (yPlane, uPlane, vPlane) = image.planes
            val y = ByteArray(yPlane.buffer.remaining()).also { yPlane.buffer.get(it) }
            val u = ByteArray(uPlane.buffer.remaining()).also { uPlane.buffer.get(it) }
            val v = ByteArray(vPlane.buffer.remaining()).also { vPlane.buffer.get(it) }
            val yRow = yPlane.rowStride; val yPix = yPlane.pixelStride
            val uRow = uPlane.rowStride; val uPix = uPlane.pixelStride
            val vRow = vPlane.rowStride; val vPix = vPlane.pixelStride

            // Katsayılar: sınırlı aralıkta Y 16..235, C 16..240
            val yScale = if (colors.fullRange) 1f else 255f / 219f
            val yOffset = if (colors.fullRange) 0 else 16
            val cScale = if (colors.fullRange) 1f else 255f / 224f
            val (kRv, kGu, kGv, kBu) = if (colors.bt709) {
                floatArrayOf(1.5748f, 0.1873f, 0.4681f, 1.8556f)
            } else {
                floatArrayOf(1.402f, 0.344136f, 0.714136f, 1.772f)
            }

            val out = ArgbImage(outW, outH)
            val n = f * f
            for (oy in 0 until outH) {
                val sy0 = crop.top + oy * f
                for (ox in 0 until outW) {
                    val sx0 = crop.left + ox * f
                    var ySum = 0
                    for (dy in 0 until f) {
                        var i = (sy0 + dy) * yRow + sx0 * yPix
                        for (dx in 0 until f) {
                            ySum += y[i].toInt() and 0xff
                            i += yPix
                        }
                    }
                    val cx = (sx0 + f / 2) / 2
                    val cy = (sy0 + f / 2) / 2
                    val uu = ((u[cy * uRow + cx * uPix].toInt() and 0xff) - 128) * cScale
                    val vv = ((v[cy * vRow + cx * vPix].toInt() and 0xff) - 128) * cScale
                    val yy = ((ySum.toFloat() / n) - yOffset) * yScale
                    val r = (yy + kRv * vv).roundToInt().coerceIn(0, 255)
                    val g = (yy - kGu * uu - kGv * vv).roundToInt().coerceIn(0, 255)
                    val b = (yy + kBu * uu).roundToInt().coerceIn(0, 255)
                    out.pixels[oy * outW + ox] = argbOf(255, r, g, b)
                }
            }
            return out
        }
    }
}
