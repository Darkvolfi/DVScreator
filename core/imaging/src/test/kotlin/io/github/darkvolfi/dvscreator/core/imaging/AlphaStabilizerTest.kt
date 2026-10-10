package io.github.darkvolfi.dvscreator.core.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlphaStabilizerTest {
    private fun clip(vararg frames: IntArray) = AnimationClip(frames.map { ArgbImage(frames[0].size, 1, it) }, IntArray(frames.size) { 100 })

    @Test
    fun `saydam piksellerin rengi sıfırlanır`() {
        val c = clip(intArrayOf(0x00FF0000, 0x05123456, 0xFF00FF00.toInt()))
        AlphaStabilizer.stabilize(c)
        assertEquals(0, c.frames[0].pixels[0])
        assertEquals(0, c.frames[0].pixels[1])   // alfa 5 → 0
        assertEquals(0xFF00FF00.toInt(), c.frames[0].pixels[2])
    }

    @Test
    fun `küçük alfa titremesi önceki değerde tutulur (dikleştirme sonrası), büyük değişim korunur`() {
        val c = clip(
            intArrayOf(0x80FFFFFF.toInt(), 0xFFFFFFFF.toInt()),
            intArrayOf(0x88FFFFFF.toInt(), 0x00FFFFFF),        // +8 titreme (dikleştirmeyle +32); opak → saydam (gerçek hareket)
        )
        assertTrue(AlphaStabilizer.stabilize(c))
        assertEquals(0x80, c.frames[1].pixels[0] ushr 24)
        assertEquals(0, c.frames[1].pixels[1])
    }

    @Test
    fun `normal videoda (keskin kenar) yumuşak alfa yok`() {
        val c = clip(intArrayOf(0, 0xFF102030.toInt()), intArrayOf(0, 0xFF102031.toInt()))
        assertFalse(AlphaStabilizer.stabilize(c))
        assertEquals(0xFF102031.toInt(), c.frames[1].pixels[1])   // renge dokunulmaz (TemporalStabilizer'ın işi)
    }
}
