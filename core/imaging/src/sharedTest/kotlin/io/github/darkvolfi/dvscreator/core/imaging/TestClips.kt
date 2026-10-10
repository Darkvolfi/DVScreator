package io.github.darkvolfi.dvscreator.core.imaging

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/** Sentetik hareketli test klipleri (512 px tuval, tohumlu → her çalıştırmada aynı). */
object TestClips {

    /**
     * "Telefon videosu" benzeri: sabit fotoğraf arka plan + her karede yeniden üretilen hafif
     * sıkıştırma gürültüsü (±[noise]) + dairesel hareket eden top. Sabitleme olmadan fark kodlaması
     * neredeyse hiç işe yaramaz.
     */
    fun movingBallOnNoisyBackground(frames: Int = 30, frameMs: Int = 40, noise: Int = 3): AnimationClip {
        val background = StickerCanvas.fit(TestImages.photo(1024, 1024, grain = 0.0), 512, 16)
        val list = (0 until frames).map { f ->
            val rnd = Random(f)
            val img = background.copy()
            val angle = 2 * Math.PI * f / frames
            val cx = 256 + 140 * cos(angle)
            val cy = 256 + 140 * sin(angle)
            for (y in 0 until 512) for (x in 0 until 512) {
                val i = y * 512 + x
                val p = img.pixels[i]
                if (alphaOf(p) == 0) continue
                if (hypot(x - cx, y - cy) < 50) {
                    img.pixels[i] = argbOf(255, 240, 60, 40)
                } else if (noise > 0) {
                    val d = rnd.nextInt(-noise, noise + 1)
                    fun ch(v: Int) = (v + d).coerceIn(0, 255)
                    img.pixels[i] = argbOf(alphaOf(p), ch(redOf(p)), ch(greenOf(p)), ch(blueOf(p)))
                }
            }
            img
        }
        return AnimationClip(list, IntArray(frames) { frameMs })
    }

    /** Tüm sahne değişir (kamera hareketi gibi): fark kodlaması kazandırmaz, en zor durum. */
    fun fullMotion(frames: Int = 60, frameMs: Int = 33): AnimationClip {
        val big = TestImages.photo(1600, 600, grain = 10.0)
        val list = (0 until frames).map { f ->
            val offset = f * 1000 / frames   // tüm klip boyunca 1000 px kaydırma
            StickerCanvas.fit(big.crop(offset, 0, offset + 600, 600), 512, 16, fast = true)
        }
        return AnimationClip(list, IntArray(frames) { frameMs })
    }
}
