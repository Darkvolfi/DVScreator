package io.github.darkvolfi.dvscreator.core.imaging

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.bumptech.glide.gifdecoder.GifDecoder
import com.bumptech.glide.gifdecoder.StandardGifDecoder
import io.github.darkvolfi.dvscreator.core.model.TrimRange
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.min
import kotlin.math.roundToInt

/** Yüklenen hareketli kaynak: sticker tuvaline yerleştirilmiş kareler + kaynak hakkında bilgi. */
class LoadedAnimation(
    val clip: AnimationClip,
    val sourceDurationMs: Int,
    val sourceFps: Double,
    /** Kaynak 10 sn'den uzunsa baştan kırpıldı. */
    val trimmed: Boolean,
)

/**
 * Video (MP4 vb.) ve GIF'ten kare çıkarır; her kare hemen 512 px tuvale küçültülür
 * (bellekte tam çözünürlükte yalnızca tek kare tutulur).
 */
class AnimationLoader(private val context: Context) {

    /**
     * @param mimeType bilinmiyorsa null → ContentResolver'dan sorulur (file:// adreslerinde bilinmez,
     *                 uygulamaya kopyalanmış kaynaklar için açıkça verilmeli)
     */
    suspend fun load(
        uri: Uri,
        mimeType: String? = null,
        trim: TrimRange? = null,
        maxDurationMs: Int = MAX_DURATION_MS,
        maxFps: Double = DEFAULT_MAX_FPS,
        margin: Int = DEFAULT_MARGIN,
        /** Her kare çıkarıldığında (yapılan, toplam). */
        onFrame: (Int, Int) -> Unit = { _, _ -> },
    ): LoadedAnimation {
        val type = mimeType ?: context.contentResolver.getType(uri).orEmpty()
        return if (type == "image/gif") loadGif(uri, trim, maxDurationMs, margin, onFrame)
        else loadVideo(uri, trim, maxDurationMs, maxFps, margin, onFrame)
    }

    private suspend fun loadVideo(
        uri: Uri, trim: TrimRange?, maxDurationMs: Int, maxFps: Double, margin: Int, onFrame: (Int, Int) -> Unit,
    ): LoadedAnimation {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val info = readVideoInfo(retriever)
            val durationMs = info.durationMs
            val frameCount = info.frameCount
            val sourceFps = info.fps
            val startMs = (trim?.startMs ?: 0).coerceIn(0, durationMs - 1)
            val endMs = (trim?.endMs ?: durationMs).coerceIn(startMs + 1, durationMs)
            val clipMs = min(endMs - startMs, maxDurationMs)
            val fps = plannedFps(clipMs, sourceFps, maxFps)
            val count = (clipMs * fps / 1000).roundToInt().coerceAtLeast(2)
            val frameMs = clipMs.toDouble() / count

            val frames = ArrayList<ArgbImage>(count)
            val durations = IntArray(count)
            var prevEnd = 0
            for (k in 0 until count) {
                currentCoroutineContext().ensureActive()
                val timeMs = startMs + k * frameMs
                val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && frameCount != null) {
                    val index = (timeMs * sourceFps / 1000).roundToInt().coerceIn(0, frameCount - 1)
                    retriever.getFrameAtIndex(index)
                } else {
                    retriever.getFrameAtTime((timeMs * 1000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST)
                } ?: error("Kare okunamadı: ${timeMs.roundToInt()} ms")
                frames += toStickerFrame(bitmap, margin)
                onFrame(k + 1, count)
                // Yuvarlama hatası birikmesin: süreler mutlak zaman çizelgesinden hesaplanır
                val end = ((k + 1) * frameMs).roundToInt()
                durations[k] = (end - prevEnd).coerceAtLeast(MIN_FRAME_MS)
                prevEnd = end
            }
            // "trimmed" = kullanıcının seçimi (ya da tüm video) sınır yüzünden otomatik kısaltıldı
            return LoadedAnimation(AnimationClip(frames, durations), durationMs, sourceFps, trimmed = endMs - startMs > maxDurationMs)
        } finally {
            retriever.release()
        }
    }

    private suspend fun loadGif(uri: Uri, trim: TrimRange?, maxDurationMs: Int, margin: Int, onFrame: (Int, Int) -> Unit): LoadedAnimation {
        val data = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("GIF okunamadı")
        val decoder = StandardGifDecoder(SimpleBitmapProvider)
        decoder.read(data)
        check(decoder.status == GifDecoder.STATUS_OK) { "GIF çözülemedi (durum ${decoder.status})" }

        val startMs = trim?.startMs ?: 0
        val endMs = trim?.endMs ?: Int.MAX_VALUE
        val frames = ArrayList<ArgbImage>()
        val durations = ArrayList<Int>()
        var total = 0
        var sourceTotal = 0
        for (i in 0 until decoder.frameCount) {
            // Tarayıcılarla aynı kural: 20 ms altı gecikme 100 ms sayılır
            val delay = decoder.getDelay(i).let { if (it < 20) 100 else it }
            val frameStart = sourceTotal
            sourceTotal += delay
            if (total >= maxDurationMs || frames.size >= MAX_FRAMES || frameStart >= endMs) continue   // kaynak süresi yine de sayılır
            currentCoroutineContext().ensureActive()
            // GIF kareleri bir öncekinin üzerine çizilir: seçim öncesindeki kareler de çözülmeli
            decoder.advance()
            val bitmap = decoder.nextFrame ?: break
            val visible = min(sourceTotal, endMs) - maxOf(frameStart, startMs)   // seçimle kesişen süre
            if (visible <= 0) continue
            frames += toStickerFrame(bitmap, margin, recycle = false)   // çözücü bitmap'i yeniden kullanabilir
            onFrame(i + 1, decoder.frameCount)
            val d = min(visible, maxDurationMs - total).coerceAtLeast(MIN_FRAME_MS)
            durations += d
            total += d
        }
        decoder.clear()
        check(frames.isNotEmpty()) { "GIF'te kare yok" }
        if (frames.size == 1) {   // tek kareli GIF: hareketli sayılması için 2 kare gerekir
            frames += frames[0]; durations += durations[0]
        }
        val fps = frames.size * 1000.0 / durations.sum()
        return LoadedAnimation(AnimationClip(frames, durations.toIntArray()), sourceTotal, fps, trimmed = total < sourceTotal)
    }

    private fun toStickerFrame(bitmap: Bitmap, margin: Int, recycle: Boolean = true): ArgbImage {
        val image = bitmap.toArgbImage()
        if (recycle) bitmap.recycle()
        return StickerCanvas.fit(image, STICKER_SIZE, margin, fast = true)
    }

    /** gifdecoder'ın istediği bellek sağlayıcısı — havuz yok, her istekte yeni nesne. */
    private object SimpleBitmapProvider : GifDecoder.BitmapProvider {
        override fun obtain(width: Int, height: Int, config: Bitmap.Config): Bitmap = Bitmap.createBitmap(width, height, config)
        override fun release(bitmap: Bitmap) = bitmap.recycle()
        override fun obtainByteArray(size: Int) = ByteArray(size)
        override fun release(bytes: ByteArray) = Unit
        override fun obtainIntArray(size: Int) = IntArray(size)
        override fun release(array: IntArray) = Unit
    }

    companion object {
        /** Kaynak videonun süresi ve kare hızı (kırpma ekranındaki tahmin için de kullanılır). */
        class VideoInfo(val durationMs: Int, val frameCount: Int?, val fps: Double)

        fun readVideoInfo(retriever: MediaMetadataRetriever): VideoInfo {
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull()
                ?: error("Video süresi okunamadı")
            val frameCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull()
            } else null
            val fps = frameCount?.let { it * 1000.0 / durationMs }
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull()
                ?: 30.0
            return VideoInfo(durationMs, frameCount, fps)
        }

        /** Bellek ve 500 KB bütçe için kare sayısı sınırlı: 5 sn → 30 fps, 10 sn → 15 fps. */
        fun plannedFps(clipMs: Int, sourceFps: Double, maxFps: Double = DEFAULT_MAX_FPS): Double =
            minOf(sourceFps, maxFps, MAX_FRAMES * 1000.0 / clipMs)

        const val STICKER_SIZE = 512
        const val MAX_DURATION_MS = 10_000
        const val DEFAULT_MAX_FPS = 30.0
        /** 512×512 kare ~1 MB; 150 kare ~150 MB. 500 KB bütçeye zaten daha fazlası anlamlı sığmaz. */
        const val MAX_FRAMES = 150
        const val DEFAULT_MARGIN = 16
        /** WhatsApp: her kare ≥ 8 ms. */
        const val MIN_FRAME_MS = 8
    }
}
