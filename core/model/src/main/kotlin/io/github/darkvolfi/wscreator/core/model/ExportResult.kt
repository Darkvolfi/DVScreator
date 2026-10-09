package io.github.darkvolfi.wscreator.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ExportResult(
    val path: String,
    val sizeBytes: Int,
    val mode: EncodeMode,
    /** Kayıplı modda kullanılan WebP kalitesi (0..100), diğer modlarda near-lossless seviyesi. */
    val quality: Int,
    /** Kaynağın 512 px sürümüne göre yapısal benzerlik (1.0 = birebir aynı). */
    val ssim: Double,
)

@Serializable
enum class EncodeMode { LOSSLESS, NEAR_LOSSLESS, LOSSY }
