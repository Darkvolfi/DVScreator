package io.github.darkvolfi.dvscreator.core.webp

import java.nio.ByteBuffer

/** Yerel (C) görüntü yardımcıları — Kotlin döngüsünün yavaş kaldığı piksel işleri. */
object NativeImage {
    init {
        NativeWebp.encoderVersion()   // aynı .so; yüklenmesini garanti eder
    }

    /**
     * YUV_420_888 düzlemleri → ARGB, `factor × factor` alan ortalamasıyla küçülterek.
     * Tamponlar doğrudan (direct) olmalı — `Image.Plane.buffer` öyledir. Başarısızsa false.
     */
    fun yuvToArgb(
        y: ByteBuffer, yRow: Int, yPix: Int,
        u: ByteBuffer, uRow: Int, uPix: Int,
        v: ByteBuffer, vRow: Int, vPix: Int,
        cropLeft: Int, cropTop: Int, outW: Int, outH: Int, factor: Int,
        bt709: Boolean, fullRange: Boolean, out: IntArray,
    ): Boolean = nativeYuvToArgb(y, yRow, yPix, u, uRow, uPix, v, vRow, vPix, cropLeft, cropTop, outW, outH, factor, bt709, fullRange, out)

    private external fun nativeYuvToArgb(
        y: ByteBuffer, yRow: Int, yPix: Int,
        u: ByteBuffer, uRow: Int, uPix: Int,
        v: ByteBuffer, vRow: Int, vPix: Int,
        cropLeft: Int, cropTop: Int, outW: Int, outH: Int, factor: Int,
        bt709: Boolean, fullRange: Boolean, out: IntArray,
    ): Boolean
}
