package io.github.darkvolfi.dvscreator.core.imaging

import io.github.darkvolfi.dvscreator.core.model.MaskStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionAndWandTest {
    private fun alpha(img: ArgbImage, x: Int, y: Int) = img.pixels[y * img.width + x] ushr 24

    /** Sol yarı mavi, sağ yarı kırmızı; ortada sol yarıdan ayrık küçük mavi kare (sağ yarının içinde). */
    private fun twoHalves(): ArgbImage {
        val img = ArgbImage(100, 100)
        for (y in 0 until 100) for (x in 0 until 100) {
            val blue = x < 50 || (x in 70..79 && y in 40..49)
            img.pixels[y * 100 + x] = if (blue) 0xFF2040C0.toInt() else 0xFFC03020.toInt()
        }
        return img
    }

    private fun wand(x: Float, y: Float, restore: Boolean = false, tolerance: Float = 0.1f) =
        MaskStroke(restore, 0f, listOf(x, y), wandTolerance = tolerance)

    @Test
    fun `değnek yalnızca dokunulan bitişik benzer rengi siler`() {
        val out = Matte.of(null, listOf(wand(0.2f, 0.5f)))!!.apply(twoHalves(), Matte.Region.FULL)
        assertEquals(0, alpha(out, 10, 10))      // sol yarı (dokunulan bölge)
        assertEquals(0, alpha(out, 49, 99))
        assertEquals(255, alpha(out, 60, 10))    // kırmızı: renk farklı
        assertEquals(255, alpha(out, 75, 45))    // aynı renk ama bitişik değil
    }

    @Test
    fun `değnekle geri getir otomatik maskenin sildiğini geri alır`() {
        val clearAll = Matte { i, _ -> ArgbImage(i.width, i.height) }
        val out = Matte.of(clearAll, listOf(wand(0.8f, 0.1f, restore = true)))!!.apply(twoHalves(), Matte.Region.FULL)
        assertEquals(0xFFC03020.toInt(), out.pixels[10 * 100 + 60])
        assertEquals(0, alpha(out, 10, 10))
    }

    @Test
    fun `kırpılmış görüntünün dışına dokunan değnek bir şey yapmaz`() {
        val out = Matte.of(null, listOf(wand(0.2f, 0.5f)))!!.apply(twoHalves(), Matte.Region(0.5f, 0f, 1f, 1f))
        assertTrue(out.pixels.all { (it ushr 24) == 255 })
    }

    @Test
    fun `kement dışını siler, içini korur, kenarı yumuşatır`() {
        val img = twoHalves()
        // (0.25, 0.25) – (0.75, 0.75) kare
        val square = listOf(0.25f, 0.25f, 0.75f, 0.25f, 0.75f, 0.75f, 0.25f, 0.75f)
        val out = Matte.of(null, emptyList(), square)!!.apply(img, Matte.Region.FULL)
        assertEquals(255, alpha(out, 50, 50))
        assertEquals(0, alpha(out, 10, 10))
        assertEquals(0, alpha(out, 90, 50))
        assertEquals(img.pixels[50 * 100 + 50], out.pixels[50 * 100 + 50])
        // Kenar tam piksel sınırında: 25. sütun içeride, 24. dışarıda
        assertEquals(255, alpha(out, 25, 50))
        assertEquals(0, alpha(out, 24, 50))
    }

    @Test
    fun `yarım piksele denk gelen kenar yarı saydam olur`() {
        val cov = Selection.coverage(floatArrayOf(10.5f, 20f, 20f, 10.5f), floatArrayOf(0f, 0f, 10f, 10f), 30, 10)
        assertEquals(0.5f, cov[5 * 30 + 10], 0.01f)
        assertEquals(1f, cov[5 * 30 + 15], 0.01f)
        assertEquals(0f, cov[5 * 30 + 9], 0.01f)
    }

    @Test
    fun `otomatik maske yalnızca seçimin çevresindeki kutuya uygulanır`() {
        var seen: Pair<Int, Int>? = null
        val auto = Matte { i, _ -> seen = i.width to i.height; i }
        val square = listOf(0.4f, 0.4f, 0.6f, 0.4f, 0.6f, 0.6f, 0.4f, 0.6f)
        Matte.of(auto, emptyList(), square)!!.apply(twoHalves(), Matte.Region.FULL)
        val (w, h) = seen!!
        assertTrue("kutu küçük olmalı: $w×$h", w in 20..24 && h in 20..24)
    }
}
