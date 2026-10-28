package io.github.darkvolfi.dvscreator.core.segmentation

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.darkvolfi.dvscreator.core.imaging.ArgbImage
import io.github.darkvolfi.dvscreator.core.imaging.Matte
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Videoda kare kare arka plan silme: CPU oturumu hemen çalışır; cihaz NNAPI'de daha hızlıysa NNAPI oturumu
 * arka planda hazırlanır ve hazır olunca kalan kareleri o işler. İkisi birlikte çalışmaz: NNAPI sürücüsü de
 * işlemciyi kullanıyor, aynı anda koşunca NNAPI 0.3 → 0.6 sn/kare yavaşlıyor (2026-10-24, MT6989).
 *
 * NNAPI cihaza göre çok farklı (2026-10-24 ölçümü, 512 girdi): MediaTek MT6989 tablette 0.3 sn/kare (CPU 1.2 sn,
 * maske CPU'yla aynı), Exynos SM-S731B telefonda 5–14 sn/kare. Oturum açma (derleme) ~8 sn sürdüğü için yalnızca
 * videoda denenir. Karar cihaz başına bir kez verilir ve saklanır:
 * - İlk videoda NNAPI, ilk karede CPU'nun maskesiyle karşılaştırılır: maske aynı (IoU ≥ [MIN_IOU]) ve CPU'nun o videodaki
 *   ortalama kare süresinin [MAX_TIME_RATIO] katından hızlıysa "ok", değilse "slow". NNAPI ölçülürken CPU bekletilir.
 * - Deneme sırasında sürücü uygulamayı çökertirse kayıt "testing" kalır → bir daha denenmez.
 */
class VideoBackgroundRemover private constructor(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val cpu = BackgroundRemover.open(appContext)
    private val key = "nnapi_${Build.FINGERPRINT}_${cpu.inputSize}"
    /** Boştaki oturumlar: her kare birini alır, işi bitince geri koyar. */
    private val free = LinkedBlockingQueue<BackgroundRemover>().apply { put(cpu) }
    /** [closed] ile NNAPI oturumunu işe katma / kapatma aynı anda olmasın diye. */
    private val lifecycle = Any()
    @Volatile private var closed = false
    private var nnapi: BackgroundRemover? = null
    /** CPU kuyrukta değil: NNAPI çalışıyor ya da karşılaştırma için bekletiliyor. */
    private var cpuParked = false

    /** İlk CPU karesi ve maskesi: NNAPI'yi karşılaştırmak için. */
    @Volatile private var sample: Pair<ArgbImage, ByteArray>? = null
    private val sampleReady = CountDownLatch(1)
    /** CPU'nun bu videodaki çıkarım süreleri (ilk kare hariç: ısınma). */
    private val cpuMsTotal = AtomicLong()
    private val cpuFrames = AtomicInteger()

    init {
        when (prefs.getString(key, null)) {
            null -> startNnapi(calibrate = true)
            VERDICT_OK -> startNnapi(calibrate = false)
            VERDICT_TESTING -> prefs.edit().putString(key, VERDICT_SLOW).apply()   // önceki deneme yarıda kaldı (çökme)
        }
    }

    fun matte(): Matte = Matte { image, _ -> matteFrame(image) }

    private fun matteFrame(image: ArgbImage): ArgbImage {
        // Ön ve son işlem oturumsuz: oturum yalnızca çıkarım süresince meşgul, başka kareler bu arada modele girer
        val input = cpu.preprocess(image)
        while (true) {
            val remover = free.take()
            val raw = try {
                remover.infer(input)
            } catch (e: Exception) {
                if (remover === cpu) { free.put(remover); throw e }
                // NNAPI çalışırken bozuldu: bırak, CPU'yu geri çağır, kareyi CPU'yla yeniden dene
                verdict(VERDICT_SLOW, "çalışırken hata: ${e.message}")
                synchronized(lifecycle) { if (cpuParked) { cpuParked = false; free.put(cpu) } }
                input.rewind()
                continue
            }
            val ms = remover.lastInferenceMs
            release(remover)
            val mask = remover.finishMask(raw, image.width, image.height)
            if (remover === cpu) recordCpu(image, mask, ms)
            return remover.applyMask(image, mask)
        }
    }

    /** Oturumu kuyruğa geri koyar; NNAPI işe katıldıysa CPU kenara alınır (aynı anda çalışınca ikisi de yavaşlıyor). */
    private fun release(remover: BackgroundRemover) {
        synchronized(lifecycle) {
            if (remover === cpu && nnapi != null) cpuParked = true else free.put(remover)
        }
    }

    private fun recordCpu(image: ArgbImage, mask: ByteArray, ms: Long) {
        if (sample == null) {
            synchronized(this) { if (sample == null) { sample = image to mask; sampleReady.countDown() } }
        } else {
            cpuMsTotal.addAndGet(ms); cpuFrames.incrementAndGet()
        }
    }

    private fun startNnapi(calibrate: Boolean) {
        thread(name = "nnapi-open", isDaemon = true) {
            if (calibrate) prefs.edit().putString(key, VERDICT_TESTING).commit()   // çökerse "testing" kalsın
            val remover = runCatching { BackgroundRemover.open(appContext, useNnapi = true) }.getOrNull()
            if (remover == null) {
                if (calibrate) verdict(VERDICT_SLOW, "açılamadı")
                return@thread
            }
            val ok: Boolean? = if (!calibrate) true else runCatching { calibrate(remover) }.getOrElse {
                verdict(VERDICT_SLOW, "hata: ${it.message}")
                false
            }
            if (ok == null) prefs.edit().remove(key).apply()   // video karşılaştırmadan önce bitti: sonraki videoda yeniden dene
            synchronized(lifecycle) {
                if (ok != true || closed) { remover.close(); return@thread }
                nnapi = remover
                // CPU boştaysa hemen kenara al; bir kare işliyorsa bitince release() kenara alır
                if (free.remove(cpu)) cpuParked = true
                free.put(remover)
            }
            Log.i(TAG, "NNAPI işe katıldı")
        }
    }

    /** NNAPI'yi ilk karede CPU'yla karşılaştırır ve sonucu saklar. null: iş karşılaştırmadan önce bitti. */
    private fun calibrate(fast: BackgroundRemover): Boolean? {
        while (!sampleReady.await(200, TimeUnit.MILLISECONDS)) if (closed) return null
        val (image, cpuMask) = sample ?: return null
        // CPU ortalaması için en az birkaç kare bekle
        while (cpuFrames.get() < MIN_CPU_FRAMES) { if (closed) return null; Thread.sleep(100) }
        val cpuMs = cpuMsTotal.get() / cpuFrames.get()
        // NNAPI tek başına ölçülsün (işe katılınca da tek başına çalışacak): CPU'yu sürmekte olan kare bitince kuyruktan al
        var parked: BackgroundRemover? = null
        while (parked == null) { if (closed) return null; parked = free.poll(200, TimeUnit.MILLISECONDS) }
        val fastMask: ByteArray
        val fastMs: Long
        try {
            fast.maskOf(image)   // ısınma: derlemeden sonraki ilk çıkarım hep yavaş
            fastMask = fast.maskOf(image)
            fastMs = fast.lastInferenceMs
        } finally {
            free.put(parked)
        }
        var inter = 0; var union = 0
        for (i in cpuMask.indices) {
            val a = (cpuMask[i].toInt() and 0xFF) >= 128; val b = (fastMask[i].toInt() and 0xFF) >= 128
            if (a && b) inter++
            if (a || b) union++
        }
        val iou = if (union == 0) 1.0 else inter.toDouble() / union
        val ok = iou >= MIN_IOU && fastMs <= cpuMs * MAX_TIME_RATIO
        verdict(if (ok) VERDICT_OK else VERDICT_SLOW, "NNAPI $fastMs ms, CPU ort. $cpuMs ms, IoU %.3f".format(iou))
        return ok
    }

    private fun verdict(value: String, why: String) {
        prefs.edit().putString(key, value).commit()
        Log.i(TAG, "NNAPI kararı: $value ($why)")
    }

    override fun close() {
        synchronized(lifecycle) {
            closed = true
            nnapi?.close()
        }
        cpu.close()
    }

    companion object {
        private const val TAG = "WSC_SEGMENT"
        private const val PREFS = "segmentation"
        private const val VERDICT_OK = "ok"
        private const val VERDICT_SLOW = "slow"
        private const val VERDICT_TESTING = "testing"
        private const val MIN_IOU = 0.97
        /** NNAPI bundan yavaşsa (CPU süresinin oranı) kullanılmaz: derleme süresi ve risk değmez. */
        private const val MAX_TIME_RATIO = 0.7
        private const val MIN_CPU_FRAMES = 3

        /** CPU oturumunu açar; NNAPI gerekiyorsa arka planda hazırlanır. Ana iş parçacığında çağırma. */
        fun open(context: Context) = VideoBackgroundRemover(context)
    }
}
