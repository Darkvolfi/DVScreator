package io.github.darkvolfi.wscreator.core.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerCanvasTest {

    private fun opaqueBounds(img: ArgbImage): IntArray {
        var l = img.width; var t = img.height; var r = -1; var b = -1
        for (y in 0 until img.height) for (x in 0 until img.width) if (alphaOf(img[x, y]) > 0) {
            l = minOf(l, x); t = minOf(t, y); r = maxOf(r, x); b = maxOf(b, y)
        }
        return intArrayOf(l, t, r, b)
    }

    @Test
    fun `yatay görsel uzatılmaz, ortalanır ve kenar boşluğu korunur`() {
        val src = ArgbImage(3000, 1500, IntArray(3000 * 1500) { 0xFF336699.toInt() })
        val out = StickerCanvas.fit(src, size = 512, margin = 16)
        assertEquals(512, out.width)
        assertEquals(512, out.height)
        val (l, t, r, b) = opaqueBounds(out).toList()
        assertEquals(16, l)
        assertEquals(495, r)                       // 480 px genişlik
        assertEquals(240, b - t + 1)               // 2:1 oran korunmuş
        assertEquals(512 - 1 - b, t)               // dikeyde ortalı
    }

    @Test
    fun `kenar boşluğu sıfır olabilir`() {
        val src = ArgbImage(800, 800, IntArray(800 * 800) { 0xFFFFFFFF.toInt() })
        val out = StickerCanvas.fit(src, 512, 0)
        assertTrue(out.pixels.all { it == 0xFFFFFFFF.toInt() })
    }

    @Test
    fun `alfa temizliği görünmez pikselleri sıfırlar`() {
        val img = ArgbImage(3, 1, intArrayOf(argbOf(2, 255, 0, 0), argbOf(0, 12, 34, 56), argbOf(3, 1, 2, 3)))
        StickerCanvas.cleanAlpha(img)
        assertEquals(0, img.pixels[0])
        assertEquals(0, img.pixels[1])
        assertEquals(argbOf(3, 1, 2, 3), img.pixels[2])
    }

    @Test
    fun `döndürme ve kırpma`() {
        val img = ArgbImage(3, 2, intArrayOf(1, 2, 3, 4, 5, 6))
        assertEquals(listOf(4, 1, 5, 2, 6, 3), img.rotate(90).pixels.toList())
        assertEquals(listOf(6, 5, 4, 3, 2, 1), img.rotate(180).pixels.toList())
        assertEquals(listOf(3, 6, 2, 5, 1, 4), img.rotate(270).pixels.toList())
        assertEquals(listOf(2, 3, 5, 6), img.crop(1, 0, 3, 2).pixels.toList())
    }

    @Test
    fun `örnekleme boyutu hedefin 4 katının altına düşmez`() {
        assertEquals(1, ImageLoader.sampleSizeFor(4000, 2048))
        assertEquals(2, ImageLoader.sampleSizeFor(4096, 2048))
        assertEquals(4, ImageLoader.sampleSizeFor(8192, 2048))
        assertEquals(1, ImageLoader.sampleSizeFor(500, 2048))
    }
}
