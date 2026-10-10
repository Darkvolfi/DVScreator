package io.github.darkvolfi.dvscreator.core.imaging

import org.junit.Assert.assertEquals
import org.junit.Test

class EdgeCleanupTest {
    @Test
    fun `yarı saydam kenar konunun rengini alır, alfa ve opak pikseller değişmez`() {
        // Sol yarı opak sarı konu, ortada yarı saydam koyu (arka plandan taşmış) kenar, sağ şeffaf
        val w = 20; val h = 5
        val img = ArgbImage(w, h, IntArray(w * h) { i ->
            when (i % w) {
                in 0..9 -> 0xFFFFE000.toInt()
                10 -> 0x80202020.toInt()
                else -> 0x00000000
            }
        })
        EdgeCleanup.decontaminate(img, radius = 3)
        val edge = img.pixels[2 * w + 10]
        assertEquals(0x80, edge ushr 24)
        assertEquals(0xFFE000, edge and 0xFFFFFF)
        assertEquals(0xFFFFE000.toInt(), img.pixels[2 * w + 5])
        assertEquals(0, img.pixels[2 * w + 15])
    }
}
