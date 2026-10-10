package io.github.darkvolfi.dvscreator.core.imaging

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.darkvolfi.dvscreator.core.model.EditRecipe
import io.github.darkvolfi.dvscreator.core.webp.NativeWebp
import io.github.darkvolfi.dvscreator.core.webp.WebpOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class AnimationDeviceTest {

    private val sticker = StickerPipeline(NativeWebp).render(TestImages.photo(1200, 900), EditRecipe())

    @Test
    fun statik_hareketli_sarmalama_2_kare_ve_ihmal_edilebilir_ek_boyut() {
        val static = NativeWebp.encode(sticker.pixels, 512, 512, WebpOptions.lossless())
        val animated = NativeWebp.staticAsAnimation(static, 512, 512)
        val info = NativeWebp.info(animated)
        assertEquals(512, info.canvasWidth)
        assertEquals(512, info.canvasHeight)
        assertEquals(2, info.frameCount)
        assertEquals(0, info.loopCount)
        assertTrue(info.frameDurationsMs.all { it >= 8 })
        assertTrue(info.totalDurationMs <= 10_000)
        assertTrue("ek boyut ${animated.size - static.size} bayt", animated.size - static.size < 200)
    }

    @Test
    fun statik_hareketli_sarmalama_android_tarafindan_cozulur_ve_ilk_kare_ayni() {
        val static = NativeWebp.encode(sticker.pixels, 512, 512, WebpOptions.lossless())
        val animated = NativeWebp.staticAsAnimation(static, 512, 512)
        // Android'in kendi çözücüsü ilk kareyi verir — kaynakla piksel piksel aynı olmalı
        val first = BitmapFactory.decodeByteArray(animated, 0, animated.size)
        val decoded = first.toArgbImage()
        for (i in sticker.pixels.indices) {
            if (alphaOf(sticker.pixels[i]) == 0) continue
            assertEquals(sticker.pixels[i], decoded.pixels[i])
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(animated)))
            assertTrue("Android bunu hareketli görmüyor", drawable is AnimatedImageDrawable)
        }
    }

    @Test
    fun animasyon_kodlayici_ayni_kareleri_birlestirir_bu_yuzden_mux_gerekli() {
        val same = NativeWebp.encodeAnimation(
            listOf(sticker.pixels, sticker.pixels), intArrayOf(500, 500), 512, 512, WebpOptions.lossy(90f, 4),
        )
        assertEquals(1, NativeWebp.info(same).frameCount)
    }

    @Test
    fun gercek_animasyon_kare_sayisi_ve_sureler_korunur() {
        val frames = (0 until 10).map { i ->
            sticker.pixels.copyOf().also { px -> for (y in 200 until 300) for (x in 0 until 100) px[y * 512 + (x + i * 40) % 512] = 0xFFFF0000.toInt() }
        }
        val anim = NativeWebp.encodeAnimation(frames, IntArray(10) { 100 }, 512, 512, WebpOptions.lossy(85f, 4))
        val info = NativeWebp.info(anim)
        assertEquals(10, info.frameCount)
        assertEquals(1000, info.totalDurationMs)
    }
}
