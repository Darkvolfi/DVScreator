package io.github.darkvolfi.dvscreator.core.webp

/**
 * libwebp JNI köprüsü. Pikseller her yerde non-premultiplied 0xAARRGGBB IntArray'dir
 * (Android `Bitmap.getPixels` ile aynı biçim).
 */
object NativeWebp : WebpCodec {
    init {
        System.loadLibrary("dvscreator_webp")
    }

    /** Bağlı libwebp kodlayıcı sürümü, ör. "1.6.0"; libwebp derlemeye eklenmediyse "yok". */
    external fun encoderVersion(): String

    override fun encode(argb: IntArray, width: Int, height: Int, options: WebpOptions): ByteArray =
        nativeEncode(
            argb, width, height,
            options.lossless, options.losslessLevel, options.quality, options.method,
            options.nearLossless, options.alphaQuality, options.alphaFiltering, options.sharpYuv, options.exact,
        ) ?: error("libwebp derlemeye eklenmemiş")

    override fun decode(webp: ByteArray): DecodedImage {
        val raw = nativeDecode(webp) ?: error("libwebp derlemeye eklenmemiş")
        return DecodedImage(width = raw[0], height = raw[1], argb = raw.copyOfRange(2, raw.size))
    }

    /**
     * Hareketli WebP. Değişmeyen bölgeler kareler arasında yeniden kodlanmaz.
     * Dikkat: tamamen aynı ardışık kareler tek kareye birleştirilir — bkz. [staticAsAnimation].
     */
    fun encodeAnimation(
        frames: List<IntArray>,
        durationsMs: IntArray,
        width: Int,
        height: Int,
        options: WebpOptions,
        loopCount: Int = 0,
    ): ByteArray = nativeEncodeAnimation(
        frames.toTypedArray(), durationsMs, width, height, loopCount,
        options.lossless, options.losslessLevel, options.quality, options.method,
        options.nearLossless, options.alphaQuality, options.alphaFiltering, options.sharpYuv, options.exact,
    ) ?: error("libwebp derlemeye eklenmemiş")

    /**
     * Statik WebP'yi görünümü hiç değişmeyen 2 kareli hareketli WebP'ye sarar
     * (agents/02 — 7b, "maksimum kalite modu"). Ek maliyet birkaç düzine bayt.
     */
    fun staticAsAnimation(
        staticWebp: ByteArray,
        canvasWidth: Int,
        canvasHeight: Int,
        firstDurationMs: Int = 1000,
        secondDurationMs: Int = 1000,
    ): ByteArray = nativeStaticAsAnimation(staticWebp, canvasWidth, canvasHeight, firstDurationMs, secondDurationMs)
        ?: error("libwebp derlemeye eklenmemiş")

    fun info(webp: ByteArray): WebpInfo {
        val raw = nativeInfo(webp) ?: error("libwebp derlemeye eklenmemiş")
        return WebpInfo(
            canvasWidth = raw[0],
            canvasHeight = raw[1],
            frameCount = raw[2],
            loopCount = raw[3],
            frameDurationsMs = raw.copyOfRange(4, raw.size),
        )
    }

    /**
     * Hareketli WebP'nin karelerini sırayla, tam tuval olarak verir (bellekte aynı anda tek kare).
     * [onFrame]'e verilen dizi her karede yeniden kullanılır — saklamak gerekiyorsa kopyalanmalı.
     */
    inline fun forEachFrame(webp: ByteArray, onFrame: (index: Int, argb: IntArray, endTimestampMs: Int) -> Unit): WebpAnimHeader {
        val info = IntArray(3)
        val handle = animDecoderOpen(webp, info)
        try {
            val buffer = IntArray(info[0] * info[1])
            var index = 0
            while (true) {
                val ts = animDecoderNext(handle, buffer)
                if (ts < 0) break
                onFrame(index++, buffer, ts)
            }
        } finally {
            animDecoderClose(handle)
        }
        return WebpAnimHeader(info[0], info[1], info[2])
    }

    @PublishedApi internal fun animDecoderOpen(webp: ByteArray, info: IntArray): Long = nativeAnimDecoderOpen(webp, info)
    @PublishedApi internal fun animDecoderNext(handle: Long, out: IntArray): Int = nativeAnimDecoderNext(handle, out)
    @PublishedApi internal fun animDecoderClose(handle: Long) = nativeAnimDecoderClose(handle)

    private external fun nativeAnimDecoderOpen(webp: ByteArray, info: IntArray): Long
    private external fun nativeAnimDecoderNext(handle: Long, out: IntArray): Int
    private external fun nativeAnimDecoderClose(handle: Long)

    private external fun nativeEncode(
        argb: IntArray, width: Int, height: Int,
        lossless: Boolean, losslessLevel: Int, quality: Float, method: Int,
        nearLossless: Int, alphaQuality: Int, alphaFiltering: Int, sharpYuv: Boolean, exact: Boolean,
    ): ByteArray?

    private external fun nativeEncodeAnimation(
        frames: Array<IntArray>, durations: IntArray, width: Int, height: Int, loopCount: Int,
        lossless: Boolean, losslessLevel: Int, quality: Float, method: Int,
        nearLossless: Int, alphaQuality: Int, alphaFiltering: Int, sharpYuv: Boolean, exact: Boolean,
    ): ByteArray?

    private external fun nativeStaticAsAnimation(
        staticWebp: ByteArray, canvasWidth: Int, canvasHeight: Int, firstDurationMs: Int, secondDurationMs: Int,
    ): ByteArray?

    private external fun nativeDecode(webp: ByteArray): IntArray?
    private external fun nativeInfo(webp: ByteArray): IntArray?
}

class WebpAnimHeader(val canvasWidth: Int, val canvasHeight: Int, val frameCount: Int)

class WebpInfo(
    val canvasWidth: Int,
    val canvasHeight: Int,
    val frameCount: Int,
    /** 0 = sonsuz döngü. */
    val loopCount: Int,
    val frameDurationsMs: IntArray,
) {
    val totalDurationMs: Int get() = frameDurationsMs.sum()
}
