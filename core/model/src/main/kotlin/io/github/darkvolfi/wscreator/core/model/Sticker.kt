package io.github.darkvolfi.wscreator.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Sticker(
    val id: String,
    val packId: String,
    val position: Int,
    /** 1..3 emoji (WhatsApp en az 1 istiyor); arama ve öneri için kullanılır. */
    val emojis: List<String> = emptyList(),
    /** Kullanıcının seçtiği orijinal dosya — asla değiştirilmez. */
    val sourcePath: String,
    val recipe: EditRecipe = EditRecipe(),
    val output: ExportResult? = null,
)
