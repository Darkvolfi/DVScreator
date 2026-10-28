package io.github.darkvolfi.dvscreator.core.imaging

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import com.bumptech.glide.gifdecoder.GifDecoder
import com.bumptech.glide.gifdecoder.StandardGifDecoder
import io.github.darkvolfi.dvscreator.core.model.CropRect
import io.github.darkvolfi.dvscreator.core.model.TrimRange
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.ensureActive
import kotlin.math.ceil
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

    /** Teşhis: son video MediaCodec hızlı yoluyla mı çıkarıldı? */
    var lastExtractionWasFast: Boolean? = null
        private set

    /**
     * @param mimeType bilinmiyorsa null → ContentResolver'dan sorulur (file:// adreslerinde bilinmez,
     *                 uygulamaya kopyalanmış kaynaklar için açıkça verilmeli)
     */
    suspend fun load(
        uri: Uri,
        mimeType: String? = null,
        trim: TrimRange? = null,
        /** Kaynağın GÖRÜNEN (döndürülmüş) çözünürlüğünde kırpma alanı; null = tamamı. */
        crop: CropRect? = null,
        maxDurationMs: Int = MAX_DURATION_MS,
        maxFps: Double = DEFAULT_MAX_FPS,
        margin: Int = DEFAULT_MARGIN,
        /** Kırpmadan sonra, küçültmeden önce her kareye uygulanır (ör. arka plan kaldırma). */
        matte: Matte? = null,
        /** Her kare çıkarıldığında (yapılan, toplam). */
        onFrame: (Int, Int) -> Unit = { _, _ -> },
    ): LoadedAnimation {
        val type = mimeType ?: context.contentResolver.getType(uri).orEmpty()
        // Maske (arka plan silme) kare başına ~0.3–1.3 sn: fazladan kare beklemeyi uzatır ama sonuca girmez —
        // saydam kenarlı klipler 500 KB'a zaten ~8 fps'e inerek sığıyor (2026-10-24: 150 kareden 50'si kaldı)
        val fps = if (matte != null) min(maxFps, MATTED_MAX_FPS) else maxFps
        return if (type == "image/gif") loadGif(uri, trim, crop, maxDurationMs, fps, margin, matte, onFrame)
        else loadVideo(uri, trim, crop, maxDurationMs, fps, margin, matte, onFrame)
    }

    private suspend fun loadVideo(
        uri: Uri, trim: TrimRange?, crop: CropRect?, maxDurationMs: Int, maxFps: Double, margin: Int, matte: Matte?,
        onFrame: (Int, Int) -> Unit,
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

            val times = DoubleArray(count) { k -> startMs + k * frameMs }
            val durations = IntArray(count)
            var prevEnd = 0
            for (k in 0 until count) {
                // Yuvarlama hatası birikmesin: süreler mutlak zaman çizelgesinden hesaplanır
                val end = ((k + 1) * frameMs).roundToInt()
                durations[k] = (end - prevEnd).coerceAtLeast(MIN_FRAME_MS)
                prevEnd = end
            }

            // Kırpılan bölge küçükse kareler o kadar yüksek çözünürlükte çıkarılır: kırpılmış alan da
            // Lanczos için hedefin en az 2 katı piksele sahip olsun
            val displayLong = maxOf(info.displayWidth, info.displayHeight)
            val cropLong = crop?.let { maxOf(it.right - it.left, it.bottom - it.top) } ?: displayLong
            val targetLongSide = (2 * (STICKER_SIZE - 2 * margin) * displayLong / cropLong.coerceAtLeast(1)).coerceAtMost(displayLong)

            val region = Matte.Region.of(crop, info.displayWidth, info.displayHeight)
            // Hızlı yol: MediaCodec ile tek geçiş. Desteklenmeyen videoda (ör. 10-bit HDR) eski yönteme dön.
            val frames = ArrayList<ArgbImage>(count)
            val fast = try {
                // Çözme sürerken önceki kareler paralel küçültülür (Lanczos kare başına onlarca ms);
                // aynı anda en fazla FIT_WORKERS ham kare bellekte bekler
                coroutineScope {
                    val gate = Semaphore(FIT_WORKERS)
                    val pending = ArrayList<Deferred<ArgbImage>>(count)
                    VideoFrameExtractor(context).extract(uri, times, targetLongSide) { k, image ->
                        gate.acquire()
                        pending += async(Dispatchers.Default) {
                            try {
                                val cropped = cropScaled(image, crop, info.displayWidth)
                                StickerCanvas.fit(matte?.apply(cropped, region) ?: cropped, STICKER_SIZE, margin, fast = true)
                            } finally {
                                gate.release()
                            }
                        }
                        onFrame(k + 1, count)
                    }
                    pending.forEach { frames += it.await() }
                }
                true
            } catch (e: VideoFrameExtractor.UnsupportedVideoException) {
                Log.w(TAG, "MediaCodec ile çıkarılamadı, yavaş yönteme dönülüyor: ${e.message}")
                frames.clear()
                false
            }
            if (!fast) {
                for (k in 0 until count) {
                    currentCoroutineContext().ensureActive()
                    val timeMs = times[k]
                    val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && frameCount != null) {
                        val index = (timeMs * sourceFps / 1000).roundToInt().coerceIn(0, frameCount - 1)
                        retriever.getFrameAtIndex(index)
                    } else {
                        retriever.getFrameAtTime((timeMs * 1000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST)
                    } ?: error("Kare okunamadı: ${timeMs.roundToInt()} ms")
                    frames += toStickerFrame(bitmap, margin, crop = crop, displayWidth = info.displayWidth, displayHeight = info.displayHeight, matte = matte)
                    onFrame(k + 1, count)
                }
            }
            lastExtractionWasFast = fast
            // "trimmed" = kullanıcının seçimi (ya da tüm video) sınır yüzünden otomatik kısaltıldı
            return LoadedAnimation(AnimationClip(frames, durations), durationMs, sourceFps, trimmed = endMs - startMs > maxDurationMs)
        } finally {
            retriever.release()
        }
    }

    private suspend fun loadGif(
        uri: Uri, trim: TrimRange?, crop: CropRect?, maxDurationMs: Int, maxFps: Double, margin: Int, matte: Matte?, onFrame: (Int, Int) -> Unit,
    ): LoadedAnimation {
        val data = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("GIF okunamadı")
        val decoder = StandardGifDecoder(SimpleBitmapProvider)
        decoder.read(data)
        check(decoder.status == GifDecoder.STATUS_OK) { "GIF çözülemedi (durum ${decoder.status})" }

        val n = decoder.frameCount
        // Tarayıcılarla aynı kural: 20 ms altı gecikme 100 ms sayılır
        val delays = IntArray(n) { decoder.getDelay(it).let { d -> if (d < 20) 100 else d } }
        val sourceTotal = delays.sum()
        val startMs = (trim?.startMs ?: 0).coerceIn(0, maxOf(0, sourceTotal - 1))
        val selectedEnd = min(trim?.endMs ?: sourceTotal, sourceTotal)
        val endMs = min(selectedEnd, startMs + maxDurationMs)
        // Seçimde MAX_FRAMES'ten çok kare varsa kare atlanır (süresi öncekine eklenir): klip kısalmaz, yalnızca akıcılık azalır
        var visibleCount = 0
        run { var t = 0; for (d in delays) { if (min(t + d, endMs) > maxOf(t, startMs)) visibleCount++; t += d } }
        // GIF kendi kare hızıyla kalır; yalnızca arka plan silinirken [maxFps] sınırı uygulanır
        val frameBudget = if (matte == null) MAX_FRAMES else min(MAX_FRAMES, ceil((endMs - startMs) * maxFps / 1000).toInt().coerceAtLeast(2))
        val step = maxOf(1, (visibleCount + frameBudget - 1) / frameBudget)

        val frames = ArrayList<ArgbImage>()
        val durations = ArrayList<Int>()
        var t = 0
        var visibleIndex = 0
        for (i in 0 until n) {
            val frameStart = t
            t += delays[i]
            if (frameStart >= endMs) break
            currentCoroutineContext().ensureActive()
            // GIF kareleri bir öncekinin üzerine çizilir: seçim öncesindeki ve atlanan kareler de çözülmeli
            decoder.advance()
            val bitmap = decoder.nextFrame ?: break
            val visible = min(t, endMs) - maxOf(frameStart, startMs)   // seçimle kesişen süre
            if (visible <= 0) continue
            if (visibleIndex++ % step == 0 || frames.isEmpty()) {
                frames += toStickerFrame(bitmap, margin, recycle = false, crop = crop, displayWidth = decoder.width, displayHeight = decoder.height, matte = matte)   // çözücü bitmap'i yeniden kullanabilir
                durations += visible
            } else {
                durations[durations.lastIndex] += visible
            }
            onFrame(i + 1, n)
        }
        decoder.clear()
        check(frames.isNotEmpty()) { "GIF'te kare yok" }
        if (frames.size == 1) {   // tek kareli GIF: hareketli sayılması için 2 kare gerekir
            frames += frames[0]; durations += durations[0]
        }
        // WhatsApp: her kare ≥ 8 ms ve toplam ≤ 10 sn. Sınırdaki kısa kareler uzatılırsa fazlası en uzun kareden düşülür.
        for (k in durations.indices) durations[k] = durations[k].coerceAtLeast(MIN_FRAME_MS)
        val excess = durations.sum() - maxDurationMs
        if (excess > 0) durations.indices.maxBy { durations[it] }.let { durations[it] -= excess }
        val fps = if (sourceTotal > 0) n * 1000.0 / sourceTotal else 0.0
        // "trimmed" = kullanıcının seçimi (ya da tüm GIF) sınır yüzünden otomatik kısaltıldı — videoyla aynı anlam
        return LoadedAnimation(AnimationClip(frames, durations.toIntArray()), sourceTotal, fps, trimmed = selectedEnd - startMs > maxDurationMs)
    }

    /** GIF'in süresi, kare hızı ve kırpma ekranı için küçük resimler (en fazla [thumbnails] tane, [thumbSize] px). */
    class GifInfo(val durationMs: Int, val fps: Double, val thumbnails: List<Bitmap>)

    fun readGifInfo(uri: Uri, thumbnails: Int = 8, thumbSize: Int = 96): GifInfo {
        val data = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("GIF okunamadı")
        val decoder = StandardGifDecoder(SimpleBitmapProvider)
        decoder.read(data)
        check(decoder.status == GifDecoder.STATUS_OK) { "GIF çözülemedi (durum ${decoder.status})" }
        val n = decoder.frameCount
        val delays = IntArray(n) { decoder.getDelay(it).let { d -> if (d < 20) 100 else d } }   // loadGif ile aynı kural
        val wanted = (0 until thumbnails).map { (it + 0.5) * n / thumbnails }.map { it.toInt() }.toSet()
        val thumbs = ArrayList<Bitmap>()
        for (i in 0 until n) {
            decoder.advance()
            if (i !in wanted) continue
            val frame = decoder.nextFrame ?: break
            thumbs += Bitmap.createScaledBitmap(frame, thumbSize, maxOf(1, thumbSize * frame.height / frame.width), true)
            if (thumbs.size == wanted.size) break
        }
        decoder.clear()
        val total = delays.sum()
        return GifInfo(total, if (total > 0) n * 1000.0 / total else 10.0, thumbs)
    }

    /**
     * Düzenleme ekranındaki hareketli önizleme için GIF kareleri ve süreleri: [startMs]..[endMs] aralığı,
     * uzun kenarı en fazla [maxLongSide] px, en fazla [maxFrames] kare (fazlası atlanır, süresi öncekine eklenir).
     */
    fun readGifPreview(uri: Uri, startMs: Int, endMs: Int, maxLongSide: Int, maxFrames: Int): List<Pair<Bitmap, Int>> {
        val data = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("GIF okunamadı")
        val decoder = StandardGifDecoder(SimpleBitmapProvider)
        decoder.read(data)
        check(decoder.status == GifDecoder.STATUS_OK) { "GIF çözülemedi (durum ${decoder.status})" }
        val n = decoder.frameCount
        val delays = IntArray(n) { decoder.getDelay(it).let { d -> if (d < 20) 100 else d } }   // loadGif ile aynı kural
        var visibleCount = 0
        run { var t = 0; for (d in delays) { if (min(t + d, endMs) > maxOf(t, startMs)) visibleCount++; t += d } }
        val step = maxOf(1, (visibleCount + maxFrames - 1) / maxFrames)
        val k = minOf(1f, maxLongSide.toFloat() / maxOf(decoder.width, decoder.height))
        val out = ArrayList<Pair<Bitmap, Int>>()
        var t = 0
        var visibleIndex = 0
        for (i in 0 until n) {
            val start = t
            t += delays[i]
            if (start >= endMs) break
            // GIF kareleri bir öncekinin üzerine çizilir: atlanan kareler de çözülmeli
            decoder.advance()
            val frame = decoder.nextFrame ?: break
            val visible = min(t, endMs) - maxOf(start, startMs)
            if (visible <= 0) continue
            if (visibleIndex++ % step == 0) {
                val w = maxOf(1, (frame.width * k).roundToInt()); val h = maxOf(1, (frame.height * k).roundToInt())
                out += Bitmap.createScaledBitmap(frame, w, h, true).let { if (it === frame) it.copy(Bitmap.Config.ARGB_8888, false) else it } to visible
            } else if (out.isNotEmpty()) {
                out[out.lastIndex] = out.last().let { it.first to it.second + visible }
            }
        }
        decoder.clear()
        return out
    }

    private fun toStickerFrame(
        bitmap: Bitmap, margin: Int, recycle: Boolean = true, crop: CropRect? = null, displayWidth: Int = bitmap.width, displayHeight: Int = bitmap.height, matte: Matte? = null,
    ): ArgbImage {
        val image = bitmap.toArgbImage()
        if (recycle) bitmap.recycle()
        val cropped = cropScaled(image, crop, displayWidth)
        return StickerCanvas.fit(matte?.apply(cropped, Matte.Region.of(crop, displayWidth, displayHeight)) ?: cropped, STICKER_SIZE, margin, fast = true)
    }

    /**
     * [crop] kaynağın görünen çözünürlüğünde ([displayWidth] genişlik) verilir; kare daha düşük
     * çözünürlükte çıkarılmış olabileceği için orana göre ölçeklenir.
     */
    private fun cropScaled(image: ArgbImage, crop: CropRect?, displayWidth: Int): ArgbImage {
        if (crop == null) return image
        val s = image.width.toDouble() / displayWidth
        return image.crop((crop.left * s).roundToInt(), (crop.top * s).roundToInt(), (crop.right * s).roundToInt(), (crop.bottom * s).roundToInt())
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
        private const val TAG = "WSC_LOADER"
        /** Paralel kare küçültme iş parçacığı sayısı. */
        private const val FIT_WORKERS = 4

        /** Kaynak videonun süresi ve kare hızı (kırpma ekranındaki tahmin için de kullanılır). */
        /** [displayWidth]×[displayHeight]: döndürme uygulanmış (ekranda görünen) çözünürlük. */
        class VideoInfo(
            val durationMs: Int, val frameCount: Int?, val fps: Double,
            val displayWidth: Int, val displayHeight: Int,
        )

        fun readVideoInfo(retriever: MediaMetadataRetriever): VideoInfo {
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull()
                ?: error("Video süresi okunamadı")
            val frameCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull()
            } else null
            val fps = frameCount?.let { it * 1000.0 / durationMs }
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull()
                ?: 30.0
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val swap = rotation == 90 || rotation == 270
            return VideoInfo(durationMs, frameCount, fps, if (swap) h else w, if (swap) w else h)
        }

        /** Bellek ve 500 KB bütçe için kare sayısı sınırlı: 5 sn → 30 fps, 10 sn → 15 fps. */
        fun plannedFps(clipMs: Int, sourceFps: Double, maxFps: Double = DEFAULT_MAX_FPS): Double =
            minOf(sourceFps, maxFps, MAX_FRAMES * 1000.0 / clipMs)

        const val STICKER_SIZE = 512
        const val MAX_DURATION_MS = 10_000
        const val DEFAULT_MAX_FPS = 30.0
        /** Arka planı silinen videolarda kare hızı üst sınırı (bkz. [load]). */
        const val MATTED_MAX_FPS = 15.0
        /** 512×512 kare ~1 MB; 150 kare ~150 MB. 500 KB bütçeye zaten daha fazlası anlamlı sığmaz. */
        const val MAX_FRAMES = 150
        const val DEFAULT_MARGIN = 16
        /** WhatsApp: her kare ≥ 8 ms. */
        const val MIN_FRAME_MS = 8
    }
}
