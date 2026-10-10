package io.github.darkvolfi.dvscreator.core.imaging

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import io.github.darkvolfi.dvscreator.core.model.EditRecipe
import io.github.darkvolfi.dvscreator.core.model.EncodeMode
import io.github.darkvolfi.dvscreator.core.model.TrimRange
import io.github.darkvolfi.dvscreator.core.webp.NativeWebp

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
    /** İyi kalite kademesine sığmadı; düşük kaliteyle üretildi → daha kısa seçim önerilmeli. */
    val degraded: Boolean = false,
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

    private const val TAG = "WSC_FACTORY"
    private const val ANIMATED_LIMIT = AnimationEncoder.ANIMATED_LIMIT_BYTES
    /** staticAsAnimation'ın eklediği 2. kare ve başlıklar için pay (ölçülen < 200 bayt). */
    private const val MUX_RESERVE = 512

    fun isAnimatedSource(mimeType: String) = mimeType.startsWith("video/") || mimeType == "image/gif"

    /** Arayüzde gösterilecek ilerleme. */
    sealed interface Progress {
        data class Extracting(val done: Int, val total: Int) : Progress
        data class Encoding(val attempt: Int, val frames: Int) : Progress
        data object Measuring : Progress
    }

    /** @param trim yalnızca video / GIF için; null = baştan, en fazla 10 sn */
    suspend fun create(
        context: Context,
        uri: Uri,
        mimeType: String,
        trim: TrimRange? = null,
        onProgress: (Progress) -> Unit = {},
    ): CreatedSticker =
        if (isAnimatedSource(mimeType)) animated(context, uri, mimeType, trim, onProgress) else still(context, uri)

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

    private suspend fun animated(
        context: Context, uri: Uri, mimeType: String, trim: TrimRange?, onProgress: (Progress) -> Unit,
    ): CreatedSticker {
        val start = SystemClock.elapsedRealtime()
        val loaded = AnimationLoader(context).load(uri, mimeType, trim, onFrame = { done, total -> onProgress(Progress.Extracting(done, total)) })
        val loadMs = SystemClock.elapsedRealtime() - start
        val sourceFrames = loaded.clip.frames.size
        val r = AnimationEncoder().encode(loaded.clip) { p ->
            onProgress(
                when (p) {
                    is AnimationEncoder.Progress.Encoding -> Progress.Encoding(p.attempt, p.frames)
                    AnimationEncoder.Progress.Measuring -> Progress.Measuring
                },
            )
        }
        // Teşhis: gerçek videolarda darboğazı ölçmek için (agents/raporlar)
        Log.i(
            TAG,
            "video ${loaded.sourceDurationMs} ms @ %.1f fps → %d kare çıkarma %d ms | %s q=%d %d kare %.1f fps %d KB | kodlama %d ms (%d deneme) ölçüm %d ms degraded=%s"
                .format(loaded.sourceFps, sourceFrames, loadMs, r.mode, r.quality, r.frameCount, r.fps, r.bytes.size / 1024,
                    r.encodeMs, r.attempts, r.measureMs, r.degraded),
        )
        return CreatedSticker(
            webp = r.bytes, fromVideo = true, mode = r.mode, quality = r.quality,
            ssimMean = r.ssimMean, ssimMin = r.ssimMin, frameCount = r.frameCount, fps = r.fps, durationMs = r.durationMs,
            trimmed = loaded.trimmed, framesReduced = r.frameCount < sourceFrames, degraded = r.degraded,
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
