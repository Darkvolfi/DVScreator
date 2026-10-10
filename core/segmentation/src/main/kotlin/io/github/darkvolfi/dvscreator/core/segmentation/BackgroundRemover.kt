package io.github.darkvolfi.dvscreator.core.segmentation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import io.github.darkvolfi.dvscreator.core.imaging.ArgbImage
import io.github.darkvolfi.dvscreator.core.imaging.Matte
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Otomatik arka plan kaldırma (agents/06 — S2): IS-Net general-use, ONNX Runtime ile tamamen cihazda.
 *
 * Model [inputSize]² girdide ön plan olasılığı (0..1) üretir; sonuç kaynağın çözünürlüğüne
 * büyütülüp alfa kanalı olarak kullanılır. Oturum açmak pahalı (~170 MB ağırlık) → bir kez açılıp
 * tekrar kullanılmalı; [maskOf] aynı anda birden çok iş parçacığından çağrılabilir (ORT oturumu iş parçacığı güvenli).
 */
class BackgroundRemover private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    /** Modelin girdi boyutu (kare). IS-Net 1024 ile eğitildi; derlemede küçültülür (bkz. build.gradle.kts). */
    val inputSize: Int,
) : Closeable {

    private val inputName = session.inputNames.first()

    /** Kaynak boyutunda ön plan maskesi: 0 = arka plan, 255 = ön plan. */
    fun maskOf(image: ArgbImage): ByteArray {
        val n = inputSize
        val input = preprocess(image)
        val t0 = System.nanoTime()
        val raw = OnnxTensor.createTensor(env, input, longArrayOf(1, 3, n.toLong(), n.toLong())).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { out ->
                val buf = (out[0] as OnnxTensor).floatBuffer
                FloatArray(buf.remaining()).also { buf.get(it) }
            }
        }
        lastInferenceMs = (System.nanoTime() - t0) / 1_000_000
        // rembg ile aynı: çıktı min-max ile 0..1'e yayılır
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (v in raw) { if (v < lo) lo = v; if (v > hi) hi = v }
        val range = (hi - lo).takeIf { it > 1e-6f } ?: 1f
        if (fillHoles) MaskHoles.fill(raw, n, n, lo + range * 0.5f, hi)
        return resizeMask(raw, n, n, image.width, image.height, lo, range)
    }

    /** Ön planla çevrili "arka plan" bölgelerini ön plana kat (bkz. [MaskHoles]). */
    @Volatile var fillHoles: Boolean = true

    /**
     * Görüntünün alfa kanalını ön plan maskesiyle çarpar. Çağrılar sıraya alınır: model zaten 4 çekirdek
     * kullanıyor, aynı anda birden çok çıkarım hızlandırmıyor, yalnızca belleği katlıyor.
     */
    fun matte(): Matte = Matte { image, _ ->
        val mask = synchronized(this) { maskOf(image) }
        val out = ArgbImage(image.width, image.height)
        for (i in image.pixels.indices) {
            val p = image.pixels[i]
            val a = (p ushr 24) * (mask[i].toInt() and 0xFF) / 255
            out.pixels[i] = (a shl 24) or (p and 0xFFFFFF)
        }
        out
    }

    /** Teşhis: son çıkarımın süresi (ön/son işlem hariç). */
    @Volatile var lastInferenceMs: Long = 0
        private set

    override fun close() = session.close()

    /** NCHW, rembg'deki gibi: görüntü en parlak değerine bölünür, 0.5 çıkarılır (std = 1). Bilineer küçültme. */
    private fun preprocess(image: ArgbImage): java.nio.FloatBuffer {
        val n = inputSize
        val plane = n * n
        val out = ByteBuffer.allocateDirect(plane * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val r = FloatArray(plane); val g = FloatArray(plane); val b = FloatArray(plane)
        val sx = image.width.toFloat() / n
        val sy = image.height.toFloat() / n
        var max = 1e-6f
        for (y in 0 until n) {
            val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, image.height - 1f)
            val y0 = fy.toInt(); val y1 = minOf(y0 + 1, image.height - 1); val wy = fy - y0
            for (x in 0 until n) {
                val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, image.width - 1f)
                val x0 = fx.toInt(); val x1 = minOf(x0 + 1, image.width - 1); val wx = fx - x0
                val p00 = image.pixels[y0 * image.width + x0]; val p01 = image.pixels[y0 * image.width + x1]
                val p10 = image.pixels[y1 * image.width + x0]; val p11 = image.pixels[y1 * image.width + x1]
                val i = y * n + x
                r[i] = lerp2(p00 shr 16 and 0xFF, p01 shr 16 and 0xFF, p10 shr 16 and 0xFF, p11 shr 16 and 0xFF, wx, wy)
                g[i] = lerp2(p00 shr 8 and 0xFF, p01 shr 8 and 0xFF, p10 shr 8 and 0xFF, p11 shr 8 and 0xFF, wx, wy)
                b[i] = lerp2(p00 and 0xFF, p01 and 0xFF, p10 and 0xFF, p11 and 0xFF, wx, wy)
                max = maxOf(max, r[i], g[i], b[i])
            }
        }
        for (c in arrayOf(r, g, b)) for (i in 0 until plane) out.put(c[i] / max - 0.5f)
        out.rewind()
        return out
    }

    private fun lerp2(a: Int, b: Int, c: Int, d: Int, wx: Float, wy: Float): Float {
        val top = a + (b - a) * wx
        val bottom = c + (d - c) * wx
        return top + (bottom - top) * wy
    }

    /** Model çıktısını kaynak boyutuna bilineer büyütür ve 0..255'e çevirir. */
    private fun resizeMask(raw: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int, lo: Float, range: Float): ByteArray {
        val out = ByteArray(dw * dh)
        val sx = sw.toFloat() / dw
        val sy = sh.toFloat() / dh
        for (y in 0 until dh) {
            val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, sh - 1f)
            val y0 = fy.toInt(); val y1 = minOf(y0 + 1, sh - 1); val wy = fy - y0
            for (x in 0 until dw) {
                val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, sw - 1f)
                val x0 = fx.toInt(); val x1 = minOf(x0 + 1, sw - 1); val wx = fx - x0
                val top = raw[y0 * sw + x0] + (raw[y0 * sw + x1] - raw[y0 * sw + x0]) * wx
                val bottom = raw[y1 * sw + x0] + (raw[y1 * sw + x1] - raw[y1 * sw + x0]) * wx
                val v = ((top + (bottom - top) * wy) - lo) / range
                out[y * dw + x] = (v * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
            }
        }
        return out
    }

    companion object {
        private const val TAG = "WSC_SEGMENT"
        const val MODEL_ASSET = "models/isnet-general-use.onnx"

        /**
         * Modeli açar (birkaç saniye sürer — ana iş parçacığında çağırma). Model APK'da sıkıştırılmadan
         * durur ve belleğe eşlenir (mmap): 170 MB ayrıca kopyalanmaz.
         */
        fun open(
            context: Context, useXnnpack: Boolean = false, threads: Int = 4, useNnapi: Boolean = false,
            /** Test için: APK dışındaki bir model dosyası. */
            modelFile: java.io.File? = null,
        ): BackgroundRemover {
            val env = OrtEnvironment.getEnvironment()
            val t0 = System.nanoTime()
            val model = if (modelFile != null) FileInputStream(modelFile).channel.use { it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()) }
            else context.assets.openFd(MODEL_ASSET).use { afd ->
                FileInputStream(afd.fileDescriptor).channel.use { ch ->
                    ch.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
                }
            }
            val options = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(threads)
                if (useXnnpack) addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
                if (useNnapi) addNnapi(java.util.EnumSet.of(ai.onnxruntime.providers.NNAPIFlags.USE_FP16))
            }
            val session = env.createSession(model, options)
            // Girdi boyutu derlemede modele yazılır (core/segmentation/build.gradle.kts — inputSize)
            val inputSize = (session.inputInfo.values.first().info as ai.onnxruntime.TensorInfo).shape[2].toInt()
            Log.i(TAG, "model açıldı: ${(System.nanoTime() - t0) / 1_000_000} ms, xnnpack=$useXnnpack nnapi=$useNnapi boyut=$inputSize")
            return BackgroundRemover(env, session, inputSize)
        }
    }
}
