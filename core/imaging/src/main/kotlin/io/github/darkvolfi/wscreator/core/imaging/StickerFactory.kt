package io.github.darkvolfi.wscreator.core.imaging

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import io.github.darkvolfi.wscreator.core.model.EditRecipe
import io.github.darkvolfi.wscreator.core.model.EncodeMode
import io.github.darkvolfi.wscreator.core.webp.NativeWebp

/** Üretilmiş sticker ve kalite/performans bilgisi (arayüzde gösterilmek için). */
class CreatedSticker(
    /** Her zaman hareketli WebP (≥ 2 kare): fotoğraflar "maksimum kalite modu" ile sarılır. */
    val webp: ByteArray,
    val fromVideo: Boolean,
    val mode: EncodeMode,
    val quality: Int,
    val ssimMean: Double,
    val ssimMin: Double,
    val frameCount: Int,
    val fps: Double,
    val durationMs: Int,
    /** Kaynak 10 sn / 150 kare sınırı için kırpıldı. */
    val trimmed: Boolean,
    /** Sığdırmak için kare sayısı azaltıldı. */
    val framesReduced: Boolean,
    val sourceDescription: String,
    val loadMs: Long,
    val totalMs: Long,
)

/**
 * Galeriden seçilen herhangi bir kaynaktan (fotoğraf / video / GIF) WhatsApp'a hazır sticker üretir.
 * Tüm stickerlar hareketli pakete girer (agents/06 — S8: maksimum kalite modu varsayılan açık):
 * fotoğraflar 2 kareli hareketli WebP olarak 500 KB bütçeyle kodlanır.
 */
object StickerFactory {

    private const val ANIMATED_LIMIT = AnimationEncoder.ANIMATED_LIMIT_BYTES
    /** staticAsAnimation'ın eklediği 2. kare ve başlıklar için pay (ölçülen < 200 bayt). */
    private const val MUX_RESERVE = 512

    fun isAnimatedSource(mimeType: String) = mimeType.startsWith("video/") || mimeType == "image/gif"

    suspend fun create(context: Context, uri: Uri, mimeType: String): CreatedSticker =
        if (isAnimatedSource(mimeType)) animated(context, uri, mimeType) else still(context, uri)

    private suspend fun still(context: Context, uri: Uri): CreatedSticker {
        val start = SystemClock.elapsedRealtime()
        val loaded = ImageLoader(context.contentResolver).load(uri)
        val loadMs = SystemClock.elapsedRealtime() - start
        val result = StickerPipeline(NativeWebp, limitBytes = ANIMATED_LIMIT - MUX_RESERVE)
            .export(loaded.image, EditRecipe(), loaded.scale)
        val bytes = NativeWebp.staticAsAnimation(result.bytes, StickerPipeline.STICKER_SIZE, StickerPipeline.STICKER_SIZE)
        return CreatedSticker(
            webp = bytes, fromVideo = false, mode = result.mode, quality = result.quality,
            ssimMean = result.ssim, ssimMin = result.ssim, frameCount = 1, fps = 0.0, durationMs = 0,
            trimmed = false, framesReduced = false,
            sourceDescription = "${loaded.image.width}×${loaded.image.height} px",
            loadMs = loadMs, totalMs = SystemClock.elapsedRealtime() - start,
        )
    }

    private suspend fun animated(context: Context, uri: Uri, mimeType: String): CreatedSticker {
        val start = SystemClock.elapsedRealtime()
        val loaded = AnimationLoader(context).load(uri, mimeType)
        val loadMs = SystemClock.elapsedRealtime() - start
        val sourceFrames = loaded.clip.frames.size
        val r = AnimationEncoder().encode(loaded.clip)
        return CreatedSticker(
            webp = r.bytes, fromVideo = true, mode = r.mode, quality = r.quality,
            ssimMean = r.ssimMean, ssimMin = r.ssimMin, frameCount = r.frameCount, fps = r.fps, durationMs = r.durationMs,
            trimmed = loaded.trimmed, framesReduced = r.frameCount < sourceFrames,
            sourceDescription = "%.1f sn, %.1f fps".format(loaded.sourceDurationMs / 1000.0, loaded.sourceFps),
            loadMs = loadMs, totalMs = SystemClock.elapsedRealtime() - start,
        )
    }

    /** WhatsApp paket simgesi: 96×96 PNG, stickerın ilk karesinden. */
    fun trayIcon(stickerWebp: ByteArray, size: Int = 96): ByteArray {
        var first: ArgbImage? = null
        NativeWebp.forEachFrame(stickerWebp) { index, argb, _ ->
            if (index == 0) first = ArgbImage(StickerPipeline.STICKER_SIZE, StickerPipeline.STICKER_SIZE, argb.copyOf())
        }
        val frame = checkNotNull(first) { "sticker karesi okunamadı" }
        val icon = StickerCanvas.fit(frame, size, margin = 0)
        val out = java.io.ByteArrayOutputStream()
        icon.toBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }
}
