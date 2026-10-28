package io.github.darkvolfi.dvscreator.core.segmentation

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.darkvolfi.dvscreator.core.imaging.ArgbImage
import io.github.darkvolfi.dvscreator.core.imaging.Matte
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.concurrent.thread

/** Hız taraması: iş parçacığı × NNAPI, paralel oturumlar, tam matte() süresi. Yalnızca ölçüm, doğrulama yok. */
@RunWith(AndroidJUnit4::class)
class BackgroundRemoverBenchTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Video karesi boyutunda (uzun kenar ~960) rastgele görüntü. */
    private val img = ArgbImage(960, 540).apply { for (i in pixels.indices) pixels[i] = (0xFF shl 24) or (i * 2654435761L).toInt().and(0xFFFFFF) }

    @Test
    fun bench() {
        for ((threads, nnapi) in listOf(2 to false, 4 to false, 6 to false, 8 to false, 4 to true)) {
            val r = runCatching {
                BackgroundRemover.open(context, threads = threads, useNnapi = nnapi).use { remover ->
                    remover.maskOf(img)   // ısınma
                    (0 until 3).map { remover.maskOf(img); remover.lastInferenceMs }
                }
            }
            Log.i(TAG, "iş=$threads nnapi=$nnapi → çıkarım ms ${r.getOrElse { "HATA: ${it.message?.take(200)}" }}")
        }
    }

    /** Tam matte() (ön/son işlem, delik doldurma, kenar temizliği dahil) kare başına süre. */
    @Test
    fun fullMatte() {
        BackgroundRemover.open(context).use { remover ->
            val matte = remover.matte()
            matte.apply(img, Matte.Region.FULL)
            val t0 = System.nanoTime()
            repeat(5) { matte.apply(img, Matte.Region.FULL) }
            Log.i(TAG, "tam matte: ${(System.nanoTime() - t0) / 5_000_000} ms/kare, son çıkarım ${remover.lastInferenceMs} ms")
        }
    }

    /** Aynı anda N oturum (her biri threads iş parçacığı): toplam verim kare/sn. */
    @Test
    fun parallelSessions() {
        for ((sessions, threads) in listOf(1 to 4, 2 to 4, 2 to 3, 3 to 2)) {
            val removers = List(sessions) { BackgroundRemover.open(context, threads = threads) }
            try {
                removers.forEach { it.maskOf(img) }   // ısınma
                val perSession = 4
                val t0 = System.nanoTime()
                removers.map { r -> thread { repeat(perSession) { r.maskOf(img) } } }.forEach { it.join() }
                val ms = (System.nanoTime() - t0) / 1_000_000
                Log.i(TAG, "paralel oturum=$sessions iş=$threads → ${sessions * perSession} kare $ms ms = ${ms / (sessions * perSession)} ms/kare")
            } finally {
                removers.forEach { it.close() }
            }
        }
    }

    companion object { private const val TAG = "WSC_SEGMENT" }
}
