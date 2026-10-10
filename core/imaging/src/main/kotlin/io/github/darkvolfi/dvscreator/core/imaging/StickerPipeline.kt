package io.github.darkvolfi.dvscreator.core.imaging

import io.github.darkvolfi.dvscreator.core.model.EditRecipe
import io.github.darkvolfi.dvscreator.core.webp.WebpCodec
import kotlin.math.roundToInt

/**
 * Kaynak görüntü + düzenleme tarifi → WhatsApp'a hazır sticker (agents/02 hattının tamamı).
 *
 * Kaynak, yükleyicinin örneklediği görüntüdür; tarifteki koordinatlar ise orijinal çözünürlükte
 * olduğundan [sourceScale] (= örneklenmiş / orijinal) ile ölçeklenir.
 */
class StickerPipeline(
    codec: WebpCodec,
    private val size: Int = STICKER_SIZE,
    limitBytes: Int = STATIC_LIMIT_BYTES,
) {
    private val encoder = StickerEncoder(codec, limitBytes)

    fun render(source: ArgbImage, recipe: EditRecipe, sourceScale: Double = 1.0, matte: Matte? = null): ArgbImage {
        var image = source
        recipe.crop?.let { c ->
            fun s(v: Int) = (v * sourceScale).roundToInt()
            image = image.crop(s(c.left), s(c.top), s(c.right), s(c.bottom))
        }
        image = image.rotate(recipe.rotationDegrees)
        matte?.let { image = it.apply(image, Matte.Region.of(recipe.crop, (source.width / sourceScale).roundToInt(), (source.height / sourceScale).roundToInt())) }
        // TODO(M3): silgi maskesi ve metin katmanları burada, küçültmeden ÖNCE uygulanacak
        return StickerCanvas.fit(image, size, recipe.marginPx.coerceIn(0, MAX_MARGIN))
    }

    suspend fun export(source: ArgbImage, recipe: EditRecipe, sourceScale: Double = 1.0, matte: Matte? = null): EncodedSticker =
        encoder.encode(render(source, recipe, sourceScale, matte))

    companion object {
        // Değerler core:whatsapp/WhatsAppLimits ile aynı; imaging modülü whatsapp'a bağımlı olmasın diye tekrar
        const val STICKER_SIZE = 512
        const val STATIC_LIMIT_BYTES = 100 * 1024
        const val MAX_MARGIN = 32
    }
}
