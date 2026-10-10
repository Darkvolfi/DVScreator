package io.github.darkvolfi.dvscreator.core.segmentation

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.darkvolfi.dvscreator.core.imaging.AlphaStabilizer
import io.github.darkvolfi.dvscreator.core.imaging.AnimationClip
import io.github.darkvolfi.dvscreator.core.imaging.AnimationLoader
import io.github.darkvolfi.dvscreator.core.imaging.TemporalStabilizer
import io.github.darkvolfi.dvscreator.core.webp.NativeWebp
import io.github.darkvolfi.dvscreator.core.webp.WebpOptions
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Ölçüm: arka planı kaldırılmış gerçek bir animasyonda boyutu ne büyütüyor? (q50, aynı kareler) */
@RunWith(AndroidJUnit4::class)
class MattedSizeBreakdownDeviceTest {
    @Test
    fun breakdown() = runTest(timeout = kotlin.time.Duration.parse("30m")) {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val gif = File(File(ctx.filesDir, "seg"), "anim.gif")
        assumeTrue(gif.exists())
        val loader = AnimationLoader(ctx)
        val plain = loader.load(Uri.fromFile(gif), "image/gif", maxDurationMs = 5000).clip
        val matted = BackgroundRemover.open(target).use { r -> loader.load(Uri.fromFile(gif), "image/gif", maxDurationMs = 5000, matte = r.matte()).clip }
        fun copy(c: AnimationClip) = AnimationClip(c.frames.map { it.copy() }, c.durationsMs.copyOf())
        fun kb(c: AnimationClip, alphaQ: Int = 100) = NativeWebp.encodeAnimation(c.frames.map { it.pixels }, c.durationsMs, c.width, c.height,
            WebpOptions.lossy(50f, method = 4).copy(alphaQuality = alphaQ)).size / 1024
        fun stab(c: AnimationClip, alpha: Boolean) = copy(c).also { if (alpha) AlphaStabilizer.stabilize(it); TemporalStabilizer.stabilize(it, 4, inPlace = true) }
        val binary = copy(matted).also { c -> c.frames.forEach { f -> for (i in f.pixels.indices) { val a = f.pixels[i] ushr 24; f.pixels[i] = if (a < 128) 0 else f.pixels[i] or (0xFF shl 24) } } }
        fun steep(k: Int) = copy(matted).also { c -> c.frames.forEach { f -> for (i in f.pixels.indices) { val a = f.pixels[i] ushr 24; val n = ((a - 128) * k + 128).coerceIn(0, 255); f.pixels[i] = (n shl 24) or (f.pixels[i] and 0xFFFFFF) } } }
        Log.i("WSC_SEGMENT", "dik kenar q50 KB: k2=${kb(stab(steep(2), true))} k4=${kb(stab(steep(4), true))} k8=${kb(stab(steep(8), true))} k4+alfaQ70=${kb(stab(steep(4), true), 70)}")
        Log.i("WSC_SEGMENT", "${plain.frames.size} kare q50 KB: " +
            "arka plan var=${kb(stab(plain, false))} | maskeli: renkSabit=${kb(stab(matted, false))} +alfaSabit=${kb(stab(matted, true))} " +
            "+alfaQ70=${kb(stab(matted, true), 70)} +alfaQ40=${kb(stab(matted, true), 40)} ikiliAlfa=${kb(stab(binary, true))}")
    }
}
