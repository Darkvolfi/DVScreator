package io.github.darkvolfi.dvscreator.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CropFrameTest {
    private val w = 1000
    private val h = 600
    private val frame = CropFrame(200f, 100f, 600f, 500f)   // 400×400

    @Test
    fun `serbest modda köşe iki kenarı birden taşır`() {
        val r = frame.resized(CropFrame.RIGHT or CropFrame.BOTTOM, 100f, -50f, w, h, square = false)
        assertEquals(CropFrame(200f, 100f, 700f, 450f), r)
    }

    @Test
    fun `kare modda karşı köşe sabit kalır ve çerçeve kare kalır`() {
        val r = frame.resized(CropFrame.RIGHT or CropFrame.BOTTOM, 100f, -50f, w, h, square = true)
        assertEquals(200f, r.left); assertEquals(100f, r.top)
        assertEquals(r.width, r.height)
        assertEquals(500f, r.width)   // kaynağın altına sığdığı kadar: 600 - 100
    }

    @Test
    fun `kenar kaynağın dışına ve en küçük boyutun altına çekilemez`() {
        assertEquals(0f, frame.resized(CropFrame.LEFT, -500f, 0f, w, h, square = false).left)
        val min = CropFrame.minSide(w, h)
        assertEquals(min, frame.resized(CropFrame.LEFT, 1000f, 0f, w, h, square = false).width, 0.01f)
    }

    @Test
    fun `taşıma boyutu korur ve kaynağın içinde durur`() {
        val r = frame.moved(900f, 900f, w, h)
        assertEquals(CropFrame(600f, 200f, 1000f, 600f), r)
    }

    @Test
    fun `dokunulan yer doğru tutamacı seçer`() {
        assertEquals(CropFrame.LEFT or CropFrame.TOP, hitHandle(frame, 205f, 95f, 20f))
        assertEquals(CropFrame.RIGHT, hitHandle(frame, 610f, 300f, 20f))
        assertEquals(CropFrame.MOVE, hitHandle(frame, 400f, 300f, 20f))
        assertNull(hitHandle(frame, 900f, 300f, 20f))
    }
}
