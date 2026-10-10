package io.github.darkvolfi.dvscreator.core.segmentation

import org.junit.Assert.assertEquals
import org.junit.Test

class MaskHolesTest {
    private fun grid(vararg rows: String): FloatArray =
        rows.joinToString("").map { if (it == '#') 1f else 0f }.toFloatArray()

    @Test
    fun `çizgiyle çevrili iç bölge doldurulur`() {
        val m = grid(
            ".....",
            ".###.",
            ".#.#.",
            ".###.",
            ".....",
        )
        MaskHoles.fill(m, 5, 5, 0.5f, 1f, edgeBand = 0)
        assertEquals(1f, m[2 * 5 + 2])
        assertEquals(0f, m[0])
    }

    @Test
    fun `alt kenara değen iç bölge de doldurulur, dış arka plan kalır`() {
        val m = grid(
            ".....",
            ".###.",
            ".#.#.",
            ".#.#.",
        )
        MaskHoles.fill(m, 5, 4, 0.5f, 1f, edgeBand = 0)
        assertEquals(1f, m[3 * 5 + 2])   // gövde içi (alt kenarda)
        assertEquals(0f, m[3 * 5 + 0])   // yanlardaki arka plan
    }

    @Test
    fun `kenara açılan bölge arka plan kalır`() {
        val m = grid(
            ".....",
            ".#.#.",
            ".#.#.",
            ".###.",
        )
        MaskHoles.fill(m, 5, 4, 0.5f, 1f, edgeBand = 0)
        assertEquals(0f, m[2 * 5 + 2])
    }

    @Test
    fun `çapraz boşluktan sızılmaz`() {
        val m = grid(
            "......",
            "..##..",
            ".#..#.",
            ".#..#.",
            "..##..",
            "......",
        )
        MaskHoles.fill(m, 6, 6, 0.5f, 1f, edgeBand = 0)
        assertEquals(1f, m[2 * 6 + 2])
    }

    @Test
    fun `arka plana yakın yumuşak kenar korunur, uzaktaki yarı saydam iz kapanır`() {
        val w = 9
        val m = FloatArray(w * w) { i -> val x = i % w; val y = i / w; if (x in 1..7 && y in 1..7) 0.6f else 0f }
        MaskHoles.fill(m, w, w, 0.5f, 1f, edgeBand = 2)
        assertEquals(0.6f, m[1 * w + 1])   // dış kenar: değişmez
        assertEquals(1f, m[4 * w + 4])     // merkez: tam ön plan
    }
}
