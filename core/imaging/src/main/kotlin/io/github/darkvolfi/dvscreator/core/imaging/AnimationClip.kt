package io.github.darkvolfi.dvscreator.core.imaging

import kotlin.math.abs

/** Sticker tuvaline (512×512) yerleştirilmiş kareler ve her birinin süresi. */
class AnimationClip(val frames: List<ArgbImage>, val durationsMs: IntArray) {
    init {
        require(frames.isNotEmpty()) { "kare yok" }
        require(frames.size == durationsMs.size) { "kare/süre sayısı farklı" }
        require(frames.all { it.width == frames[0].width && it.height == frames[0].height }) { "kare boyutları farklı" }
    }

    val width get() = frames[0].width
    val height get() = frames[0].height
    val totalDurationMs get() = durationsMs.sum()
    val fps get() = frames.size * 1000.0 / totalDurationMs

    /**
     * Her [step] kareden birini tutar; atılan karelerin süresi tutulan kareye eklenir —
     * toplam süre ve hareket hızı DEĞİŞMEZ, yalnızca akıcılık azalır (agents/02 — madde 9, adım 3).
     */
    fun decimate(step: Int): AnimationClip {
        if (step <= 1) return this
        val keptFrames = mutableListOf<ArgbImage>()
        val keptDurations = mutableListOf<Int>()
        for (start in frames.indices step step) {
            keptFrames += frames[start]
            keptDurations += (start until minOf(start + step, frames.size)).sumOf { durationsMs[it] }
        }
        return AnimationClip(keptFrames, keptDurations.toIntArray())
    }
}

/**
 * Zamansal sabitleme (agents/02 — madde 9): Video sıkıştırma gürültüsü "sabit" arka planın
 * piksellerini her karede biraz değiştirir ve WebP'nin fark kodlamasını boşa çıkarır.
 *
 * Karar 8×8 blok bazında verilir: bloktaki ortalama fark ≤ [meanThreshold] VE en büyük fark
 * ≤ [maxThreshold] ise blok önceki ÇIKTI karesinden kopyalanır. Bağımsız gürültü ortalamada
 * küçük kalır (±3 gürültüde ~2.3), gerçek hareket ise büyük → piksel bazlı eşikten çok daha güvenilir
 * (ilk ölçüm: piksel bazlı eşik 4 ile ±3 gürültüde kazanç %0.5'ti).
 *
 * Önceki çıktıyla karşılaştırıldığı için yavaş değişimler (solma, ışık) birikip eşiği aşınca
 * güncellenir — bir pikselin hatası hiçbir zaman [maxThreshold]'u geçmez.
 */
object TemporalStabilizer {
    private const val BLOCK = 8

    /**
     * @param inPlace true ise kareler yerinde değiştirilir (bellek: 10 sn klip ~150 MB, kopyası sığmayabilir).
     */
    fun stabilize(
        clip: AnimationClip,
        meanThreshold: Int,
        maxThreshold: Int = meanThreshold * 4,
        inPlace: Boolean = false,
    ): AnimationClip {
        if (meanThreshold <= 0 || clip.frames.size < 2) return clip
        val w = clip.width
        val h = clip.height
        val out = if (inPlace) clip.frames else clip.frames.map { it.copy() }
        for (f in 1 until out.size) {
            val prev = out[f - 1].pixels
            val cur = out[f].pixels
            for (by in 0 until h step BLOCK) for (bx in 0 until w step BLOCK) {
                val yEnd = minOf(by + BLOCK, h)
                val xEnd = minOf(bx + BLOCK, w)
                var sum = 0
                var max = 0
                for (y in by until yEnd) for (x in bx until xEnd) {
                    val i = y * w + x
                    val d = maxChannelDiff(prev[i], cur[i])
                    sum += d
                    if (d > max) max = d
                }
                val count = (yEnd - by) * (xEnd - bx)
                if (sum == 0 || (max <= maxThreshold && sum <= meanThreshold * count)) {
                    for (y in by until yEnd) System.arraycopy(prev, y * w + bx, cur, y * w + bx, xEnd - bx)
                }
            }
        }
        return if (inPlace) clip else AnimationClip(out, clip.durationsMs)
    }

    private fun maxChannelDiff(a: Int, b: Int): Int = maxOf(
        maxOf(abs(alphaOf(a) - alphaOf(b)), abs(redOf(a) - redOf(b))),
        maxOf(abs(greenOf(a) - greenOf(b)), abs(blueOf(a) - blueOf(b))),
    )
}

/**
 * Saydamlık sabitleme — arka planı kaldırılmış videolar için, [TemporalStabilizer]'dan ÖNCE.
 *
 * Maske her karede ayrı hesaplandığı için kenarlar kareden kareye biraz oynar ve tamamen saydam
 * piksellerin görünmeyen renkleri (videonun eski arka planı) her karede farklıdır. İkisi de kareler
 * arası fark kodlamasını boşa çıkarıyordu: arka planı kaldırılmış 3 video en düşük ayarda bile
 * 650–920 KB çıktı (2026-10-10). Bu adım:
 * - yarı saydam geçişi [steepness] kat daraltır (ölçüm, gerçek GIF 50 kare q50: yumuşak maske 697 KB,
 *   4 kat dik 353 KB, tamamen ikili 261 KB, arka plan duruyorken 203 KB — 4 kat kenarı hâlâ 1-2 px yumuşak bırakır),
 * - önceki çıktıya göre [hysteresis]'ten az değişen alfayı önceki değerde tutar (titreme gider,
 *   gerçek hareket 255'e yakın sıçradığı için korunur),
 * - tamamen saydam piksellerin rengini 0 yapar (görünmez; artık karelerde aynı).
 */
object AlphaStabilizer {
    private const val LOW = 16
    private const val HIGH = 240

    /** Kareleri yerinde değiştirir. @return klipte yarı saydam piksel var mı (maske uygulanmış mı) */
    fun stabilize(clip: AnimationClip, hysteresis: Int = DEFAULT_HYSTERESIS, steepness: Int = DEFAULT_STEEPNESS): Boolean {
        var partial = false
        var prev: IntArray? = null
        for (frame in clip.frames) {
            val px = frame.pixels
            for (i in px.indices) {
                val p = px[i]
                var a = p ushr 24
                if (a in 1 until 255) partial = true
                a = ((a - 128) * steepness + 128).coerceIn(0, 255)
                a = when {
                    a < LOW -> 0
                    a > HIGH -> 255
                    else -> a
                }
                prev?.let { pp ->
                    val pa = pp[i] ushr 24
                    if (kotlin.math.abs(a - pa) <= hysteresis) a = pa
                }
                px[i] = if (a == 0) 0 else (a shl 24) or (p and 0xFFFFFF)
            }
            prev = px
        }
        return partial
    }

    const val DEFAULT_HYSTERESIS = 40
    const val DEFAULT_STEEPNESS = 4
}
