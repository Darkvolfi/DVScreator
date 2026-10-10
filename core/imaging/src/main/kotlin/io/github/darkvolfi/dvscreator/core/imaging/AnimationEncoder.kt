package io.github.darkvolfi.dvscreator.core.imaging

import io.github.darkvolfi.dvscreator.core.model.EncodeMode
import io.github.darkvolfi.dvscreator.core.webp.NativeWebp
import io.github.darkvolfi.dvscreator.core.webp.WebpOptions
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt

class EncodedAnimation(
    val bytes: ByteArray,
    val mode: EncodeMode,
    /** Kayıplıda WebP kalitesi; kayıpsızda 100. */
    val quality: Int,
    val frameCount: Int,
    val fps: Double,
    val durationMs: Int,
    /** Sabitlenmiş kaynak karelere göre ortalama ve en kötü kare SSIM'i (kayıplıda örneklenmiş karelerle). */
    val ssimMean: Double,
    val ssimMin: Double,
    val attempts: Int,
    /** Teşhis: kodlama denemelerine ve kalite ölçümüne harcanan süre. */
    val encodeMs: Long = 0,
    val measureMs: Long = 0,
    /** İyi kalite kademesine sığmadı; düşük kalite/kare hızıyla üretildi → kullanıcıya kısa seçim önerilmeli. */
    val degraded: Boolean = false,
)

class AnimationTooLargeException(val smallestBytes: Int, val limitBytes: Int) :
    Exception("En düşük ayarla bile $smallestBytes bayt; sınır $limitBytes bayt — süreyi kısaltmak gerekiyor")

/**
 * Hareketli sticker için BEST_FIT (agents/02 — madde 9). Kare silme en son çare:
 * 1. Zamansal sabitleme (fark kodlaması tam verimli çalışsın)
 * 2. Kayıpsız dene (önce ilk kareler üzerinden tahmin; umutsuzsa atlanır)
 * 3. Kayıplı: önce [minQuality] — o bile sığmıyorsa doğrudan 4'e; sığıyorsa
 *    interpolasyonlu arama (boyut kaliteyle ~üstel büyür → 3-4 denemede bulunur)
 * 4. Sığmazsa FPS düşür (kare süreleri uzatılır, hareket hızı aynı kalır) ve 3'e dön
 * 5. İyi kalite kademesinde (q ≥ 50, ≥ 10 fps) hiç sığmazsa düşük kademe (q ≥ 20, ≥ 5 fps) —
 *    sonuç [EncodedAnimation.degraded] işaretlenir. Hiç sticker çıkmamasından iyidir.
 */
class AnimationEncoder(
    private val limitBytes: Int = ANIMATED_LIMIT_BYTES,
    private val stabilizeThreshold: Int = DEFAULT_STABILIZE_THRESHOLD,
    private val minQuality: Int = 50,
    private val minFps: Double = 10.0,
) {
    private var attempts = 0
    private var encodeNs = 0L
    private var smallest = Int.MAX_VALUE

    /**
     * DİKKAT: [source] kareleri bellek için **yerinde** sabitlenir (10 sn klip ~150 MB; kopyası sığmayabilir).
     * SSIM sabitlenmiş karelere göre ölçülür; sabitlemenin kendi hatası piksel başına sınırlıdır.
     */
    suspend fun encode(source: AnimationClip, onProgress: (Progress) -> Unit = {}): EncodedAnimation {
        attempts = 0; encodeNs = 0; smallest = Int.MAX_VALUE
        progress = onProgress
        val stabilized = TemporalStabilizer.stabilize(source, stabilizeThreshold, inPlace = true)
        // Kademe 1: iyi kalite (q ≥ minQuality, ≥ minFps). Kademe 2: hiç sticker olmamasındansa
        // düşük kalite — sonuç "degraded" işaretlenir ve kullanıcıya daha kısa seçim önerilir.
        val tiers = listOf(minQuality to minFps, FALLBACK_MIN_QUALITY to FALLBACK_MIN_FPS)
        var step = 1
        for ((tierIndex, tier) in tiers.withIndex()) {
            val (tierQuality, tierFps) = tier
            val degraded = tierIndex > 0
            val maxStep = maxOf(1, floor(source.fps / tierFps).toInt())
            while (true) {
                // Sabitlenmiş klipten kare seçmek tutarlıdır: dondurulan pikseller tüm karelerde zaten aynı
                val clip = stabilized.decimate(step)
                if (clip.frames.size < 2) break   // 2 kareden az → WhatsApp hareketli saymaz
                if (!degraded && step == 1 && isStill(clip)) return encodeStill(clip)

                if (!degraded && step == 1 && losslessMightFit(clip)) {
                    val lossless = tryEncode(clip, WebpOptions.lossless(LOSSLESS_LEVEL))
                    if (lossless.size <= limitBytes) return result(lossless, EncodeMode.LOSSLESS, 100, clip)
                }

                searchLossy(clip, tierQuality)?.let { (bytes, q) -> return result(bytes, EncodeMode.LOSSY, q, clip, degraded = degraded) }

                // Bu kademenin en düşük kalitesinde bile sığmadı → akıcılığı azalt (süre aynı kalır).
                // Adım adım değil: boyut kare sayısıyla ~doğru orantılı → gereken kare sayısına doğrudan atla.
                if (step >= maxStep) break
                val neededFrames = clip.frames.size * limitBytes.toDouble() / lastFailedSize * STEP_PREDICTION_MARGIN
                val predicted = ceil(source.frames.size / neededFrames).toInt()
                step = predicted.coerceIn(step + 1, maxStep)
            }
        }
        throw AnimationTooLargeException(smallest, limitBytes)
    }

    /** Kodlama ilerlemesi (arayüzde "deneme 3" gibi göstermek için). */
    sealed interface Progress {
        data class Encoding(val attempt: Int, val frames: Int) : Progress
        data object Measuring : Progress
    }

    private var progress: (Progress) -> Unit = {}
    /** Son başarısız (sınırı aşan) en düşük kalite denemesinin boyutu — kare sayısı tahmini için. */
    private var lastFailedSize = 1

    private suspend fun tryEncode(clip: AnimationClip, options: WebpOptions): ByteArray {
        attempts++
        progress(Progress.Encoding(attempts, clip.frames.size))
        val t = System.nanoTime()
        val bytes = NativeWebp.encodeAnimation(clip.frames.map { it.pixels }, clip.durationsMs, clip.width, clip.height, options)
        encodeNs += System.nanoTime() - t
        smallest = minOf(smallest, bytes.size)
        currentCoroutineContext().ensureActive()
        return bytes
    }

    /**
     * İlk [LOSSLESS_PROBE_FRAMES] kareden tüm klibin kayıpsız boyutunu tahmin eder.
     * İlk kare (tam görüntü) diğerlerinden (yalnızca fark) çok büyük olduğu için ayrı hesaplanır:
     * tahmin = ilk kare + (n-1) × ortalama fark karesi.
     */
    private suspend fun losslessMightFit(clip: AnimationClip): Boolean {
        if (clip.frames.size <= LOSSLESS_PROBE_FRAMES * 2) return true
        val probe = AnimationClip(clip.frames.take(LOSSLESS_PROBE_FRAMES), clip.durationsMs.copyOf(LOSSLESS_PROBE_FRAMES))
        val probeSize = tryEncode(probe, WebpOptions.lossless(LOSSLESS_LEVEL)).size
        val first = clip.frames[0]
        val keySize = NativeWebp.encode(first.pixels, first.width, first.height, WebpOptions.lossless(LOSSLESS_LEVEL)).size
        val perDelta = (probeSize - keySize).coerceAtLeast(0).toDouble() / (LOSSLESS_PROBE_FRAMES - 1)
        val estimate = keySize + perDelta * (clip.frames.size - 1)
        return estimate <= limitBytes * LOSSLESS_ESTIMATE_SLACK
    }

    /**
     * [minQuality, 100] aralığında sığan en yüksek kalite. ln(boyut) kaliteyle yaklaşık doğrusal
     * olduğundan iki ölçümden aradaki doğru üzerinde interpolasyon yapılır.
     */
    private suspend fun searchLossy(clip: AnimationClip, minQuality: Int): Pair<ByteArray, Int>? {
        suspend fun encodeAt(q: Int) = tryEncode(clip, WebpOptions.lossy(q.toFloat(), method = SEARCH_METHOD))
        var loQ = minQuality
        var loBytes = encodeAt(loQ)
        if (loBytes.size > limitBytes) {
            lastFailedSize = loBytes.size
            return null
        }
        var hiQ = 101                 // henüz sığmayan bilinen bir kalite yok
        var hiSize = Double.NaN
        repeat(MAX_SEARCH_STEPS) {
            if (hiQ - loQ <= 1 || loQ == 100) return loBytes to loQ
            val target = ln(limitBytes * SEARCH_TARGET.toDouble())
            val guess = if (hiSize.isNaN()) {
                loQ + ((target - ln(loBytes.size.toDouble())) / TYPICAL_LN_SIZE_PER_Q).roundToInt()
            } else {
                val loLn = ln(loBytes.size.toDouble())
                loQ + ((target - loLn) / (ln(hiSize) - loLn) * (hiQ - loQ)).roundToInt()
            }
            val q = guess.coerceIn(loQ + 1, minOf(hiQ - 1, 100))
            val bytes = encodeAt(q)
            if (bytes.size <= limitBytes) { loQ = q; loBytes = bytes } else { hiQ = q; hiSize = bytes.size.toDouble() }
        }
        return loBytes to loQ
    }

    /** Tüm kareler sabitlemeden sonra aynıysa (hareketsiz video) kodlayıcı tek kareye indirir. */
    private fun isStill(clip: AnimationClip): Boolean {
        val first = clip.frames[0].pixels
        return clip.frames.all { it.pixels === first || it.pixels.contentEquals(first) }
    }

    private suspend fun encodeStill(reference: AnimationClip): EncodedAnimation {
        val still = StickerEncoder(NativeWebp, limitBytes - MUX_OVERHEAD_BYTES).encode(reference.frames[0])
        val bytes = NativeWebp.staticAsAnimation(still.bytes, reference.width, reference.height)
        attempts += still.attempts
        return result(bytes, still.mode, still.quality, reference, measured = still.ssim)
    }

    /**
     * Kalite ölçümü. Kayıpsızda ölçülmez (tanım gereği birebir). Kayıplıda en fazla
     * [MAX_MEASURED_FRAMES] kare örneklenir (SSIM kare başına ~0.3 sn). Kodlayıcı aynı ardışık
     * kareleri birleştirebildiği için kareler zaman damgasından eşleştirilir.
     */
    private fun result(
        bytes: ByteArray, mode: EncodeMode, quality: Int, reference: AnimationClip, measured: Double? = null,
        degraded: Boolean = false,
    ): EncodedAnimation {
        val measureStart = System.nanoTime()
        if (mode != EncodeMode.LOSSLESS) progress(Progress.Measuring)
        var sum = 0.0
        var min = 1.0
        var count = 0
        if (measured != null) {
            sum = measured; min = measured; count = 1
        } else if (mode != EncodeMode.LOSSLESS) {
            val every = (reference.frames.size + MAX_MEASURED_FRAMES - 1) / MAX_MEASURED_FRAMES
            var refIndex = 0
            var refEnd = reference.durationsMs[0]
            NativeWebp.forEachFrame(bytes) { _, argb, endTs ->
                while (refIndex < reference.frames.size && refEnd <= endTs) {
                    if (refIndex % every == 0) {
                        val s = Ssim.compute(reference.frames[refIndex], ArgbImage(reference.width, reference.height, argb))
                        sum += s; min = minOf(min, s); count++
                    }
                    refIndex++
                    if (refIndex < reference.frames.size) refEnd += reference.durationsMs[refIndex]
                }
            }
        } else {
            sum = 1.0; count = 1
        }
        return EncodedAnimation(
            bytes = bytes, mode = mode, quality = quality,
            frameCount = reference.frames.size, fps = reference.fps, durationMs = reference.totalDurationMs,
            ssimMean = if (count > 0) sum / count else 0.0, ssimMin = min, attempts = attempts,
            encodeMs = encodeNs / 1_000_000, measureMs = (System.nanoTime() - measureStart) / 1_000_000,
            degraded = degraded,
        )
    }

    companion object {
        const val ANIMATED_LIMIT_BYTES = 500 * 1024
        /** 8×8 blok ortalama fark eşiği (0-255). Ölçüm (±3 gürültü): 2 → kazanç yok, 3-4 → 5-6 kat küçük. */
        const val DEFAULT_STABILIZE_THRESHOLD = 4
        /** staticAsAnimation'ın 2. kare + ANMF başlıkları için ayrılan pay (ölçülen < 200 bayt). */
        private const val MUX_OVERHEAD_BYTES = 512
        private const val LOSSLESS_LEVEL = 3
        private const val LOSSLESS_PROBE_FRAMES = 8
        /** Tahmin hatası payı: ilk 8 kare klibin geri kalanını tam temsil etmeyebilir. */
        private const val LOSSLESS_ESTIMATE_SLACK = 1.3
        private const val SEARCH_METHOD = 4
        private const val MAX_SEARCH_STEPS = 4
        /** Hedef sınırın biraz altı: interpolasyon hatası sınırı aşan tahminleri azaltır. */
        private const val SEARCH_TARGET = 0.97
        /** İlk tahmin için: kalite +1 → boyut yaklaşık %3 artar. */
        private const val TYPICAL_LN_SIZE_PER_Q = 0.03
        private const val MAX_MEASURED_FRAMES = 10
        /** Düşük kalite kademesi: gerçek videolarda (2026-10-10) q50/10 fps ile 569 KB ve 1.27 MB kalan klipler için. */
        private const val FALLBACK_MIN_QUALITY = 20
        private const val FALLBACK_MIN_FPS = 5.0
        /** Kare sayısı tahmininde pay: ilk (anahtar) kare sabit maliyetli, boyut kareyle tam orantılı değil. */
        private const val STEP_PREDICTION_MARGIN = 0.85
    }
}
