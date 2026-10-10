package io.github.darkvolfi.dvscreator.core.segmentation

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.darkvolfi.dvscreator.core.imaging.ArgbImage
import org.junit.Test
import org.junit.runner.RunWith

/** Hız taraması: girdi boyutu × iş parçacığı × NNAPI. Yalnızca ölçüm, doğrulama yok. */
@RunWith(AndroidJUnit4::class)
class BackgroundRemoverBenchTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun bench() {
        val img = ArgbImage(720, 720).apply { for (i in pixels.indices) pixels[i] = (0xFF shl 24) or (i * 2654435761L).toInt().and(0xFFFFFF) }
        val configs = listOf(Triple(0, 4, false), Triple(0, 6, false), Triple(0, 4, true))
        for ((size, threads, nnapi) in configs) {
            val r = runCatching {
                BackgroundRemover.open(context, threads = threads, useNnapi = nnapi).use { remover ->
                    remover.maskOf(img)   // ısınma
                    (0 until 3).map { remover.maskOf(img); remover.lastInferenceMs }
                }
            }
            Log.i(TAG, "boyut=$size(model) iş=$threads nnapi=$nnapi → ${r.getOrElse { "HATA: ${it.message?.take(200)}" }}")
        }
    }

    companion object { private const val TAG = "WSC_SEGMENT" }
}
