package io.github.darkvolfi.wscreator.core.imaging

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
