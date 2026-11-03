package io.github.darkvolfi.dvscreator.core.segmentation

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import io.github.darkvolfi.dvscreator.core.imaging.ArgbImage

/**
 * Fotoğraf için arka plan modeli. Model 512 girdide ince, uzun parçaları (ör. tank namlusu) kaybediyor; eğitildiği
 * 1024 girdide tam buluyor ama ~4 kat yavaş (2026-11-03, SM-S731B: 1.0 → 4.6 sn). Bu yüzden:
 * - [Mode.AUTO]: cihaz yeterince hızlı ve belleği bolsa 1024, değilse 512. Karar cihaz başına bir kez verilir ve
 *   saklanır: 512'de boş bir kare ölçülür, [MAX_FAST_MS]'den kısaysa 1024 denenir.
 * - 1024'ün ilk çıkarımı sırasında uygulama kapanırsa (bellek) kayıt "pending" kalır → bir daha 512.
 * - Kullanıcı Ayarlar'dan hızlıyı ya da yüksek kaliteyi zorlayabilir.
 * Video her zaman 512'dir (VideoBackgroundRemover).
 */
object PhotoBackgroundRemover {
    enum class Mode { AUTO, FAST, QUALITY }

    fun mode(context: Context): Mode =
        prefs(context).getString(KEY_MODE, null)?.let { runCatching { Mode.valueOf(it) }.getOrNull() } ?: Mode.AUTO

    fun setMode(context: Context, mode: Mode) = prefs(context).edit().putString(KEY_MODE, mode.name).apply()

    /** Otomatikte bu cihaz için verilmiş karar: true yüksek kalite, false hızlı, null henüz ölçülmedi. */
    fun autoUsesQuality(context: Context): Boolean? = when (prefs(context).getString(verdictKey(), null)) {
        VERDICT_QUALITY, VERDICT_TRY -> true
        VERDICT_FAST, VERDICT_PENDING -> false
        else -> null
    }

    /** Modeli açar (birkaç saniye; ilk seferde ölçüm de yapılır — ana iş parçacığında çağırma). */
    fun open(context: Context): BackgroundRemover {
        val appContext = context.applicationContext
        val quality = when (mode(appContext)) {
            Mode.FAST -> false
            Mode.QUALITY -> true
            Mode.AUTO -> autoQuality(appContext)
        }
        if (!quality) return BackgroundRemover.open(appContext)
        val remover = runCatching { BackgroundRemover.open(appContext, inputSize = BackgroundRemover.QUALITY_INPUT) }
            .onFailure { Log.w(TAG, "1024 açılamadı, 512 kullanılıyor", it) }
            .getOrNull() ?: return BackgroundRemover.open(appContext).also { setVerdict(appContext, VERDICT_FAST) }
        if (prefs(appContext).getString(verdictKey(), null) == VERDICT_TRY) {
            remover.firstInference = object : BackgroundRemover.FirstInference {
                override fun started() { setVerdict(appContext, VERDICT_PENDING) }   // çökerse böyle kalır
                override fun finished() { setVerdict(appContext, VERDICT_QUALITY) }
            }
        }
        return remover
    }

    private fun autoQuality(context: Context): Boolean {
        when (prefs(context).getString(verdictKey(), null)) {
            VERDICT_QUALITY, VERDICT_TRY -> return true
            VERDICT_FAST -> return false
            VERDICT_PENDING -> {   // önceki 1024 çıkarımı bitmedi: uygulama kapanmış
                setVerdict(context, VERDICT_FAST)
                return false
            }
        }
        val fastMs = if (enoughMemory(context)) measureFastMs(context) else null
        val quality = fastMs != null && fastMs <= MAX_FAST_MS
        Log.i(TAG, "fotoğraf girdisi: 512'de $fastMs ms → ${if (quality) "1024" else "512"}")
        setVerdict(context, if (quality) VERDICT_TRY else VERDICT_FAST)
        return quality
    }

    /** 512 girdide bir çıkarımın süresi (ısınmadan sonra); içerik süreyi değiştirmez. */
    private fun measureFastMs(context: Context): Long? = runCatching {
        BackgroundRemover.open(context).use { r ->
            val blank = ArgbImage(BackgroundRemover.FAST_INPUT, BackgroundRemover.FAST_INPUT,
                IntArray(BackgroundRemover.FAST_INPUT * BackgroundRemover.FAST_INPUT) { 0xFF808080.toInt() })
            r.maskOf(blank)
            r.maskOf(blank)
            r.lastInferenceMs
        }
    }.getOrNull()

    /** 1024 girdi ~1 GB'a yakın ara bellek ister: düşük bellekli cihazlarda denenmez. */
    private fun enoughMemory(context: Context): Boolean {
        val am = context.getSystemService(ActivityManager::class.java) ?: return false
        if (am.isLowRamDevice) return false
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return info.totalMem >= MIN_TOTAL_MEMORY
    }

    private fun setVerdict(context: Context, value: String) = prefs(context).edit().putString(verdictKey(), value).commit()
    private fun verdictKey() = "photo_input_${Build.FINGERPRINT}"
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val TAG = "WSC_SEGMENT"
    private const val PREFS = "segmentation"
    private const val KEY_MODE = "photo_mode"
    private const val VERDICT_FAST = "fast"
    /** Ölçüm 1024 dedi, henüz hiç çalışmadı. */
    private const val VERDICT_TRY = "try"
    /** 1024'ün ilk çıkarımı sürüyor (ya da sırasında kapandı). */
    private const val VERDICT_PENDING = "pending"
    private const val VERDICT_QUALITY = "quality"
    /** 512'de bu süreden kısa (1024'te ~4 katı, ~5 sn) cihazlar yüksek kaliteyi kullanır. */
    private const val MAX_FAST_MS = 1300L
    /** 6 GB'lık telefonlar ~5.5 GB bildiriyor. */
    private const val MIN_TOTAL_MEMORY = 5_500_000_000L
}
