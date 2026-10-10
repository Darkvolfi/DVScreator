package io.github.darkvolfi.dvscreator.core.imaging

import org.junit.Assert.assertEquals
import org.junit.Test

class StickerOutlineTest {
    @Test
    fun `konunun etrafına verilen kalınlıkta çizgi çekilir, konu değişmez`() {
        val n = 41
        // Ortada 11×11 kırmızı kare, gerisi şeffaf
        val img = ArgbImage(n, n, IntArray(n * n) { i -> if (i % n in 15..25 && i / n in 15..25) 0xFFFF0000.toInt() else 0 })
        StickerOutline.apply(img, radius = 4, colorArgb = 0xFFFFFFFF.toInt())
        assertEquals(0xFFFF0000.toInt(), img.pixels[20 * n + 20])          // konu
        assertEquals(0xFFFFFFFF.toInt(), img.pixels[20 * n + 12])          // 3 px dışarıda: beyaz
        assertEquals(0, img.pixels[20 * n + 8] ushr 24)                    // 7 px dışarıda: şeffaf
        assertEquals(0, img.pixels[0] ushr 24)
        // Köşede çapraz uzaklık: (25+3, 25+3) → √18 ≈ 4.24 > 4.5? hayır, kısmen kaplı
        val corner = img.pixels[28 * n + 28] ushr 24
        assertEquals(true, corner in 1..254)
    }
}
