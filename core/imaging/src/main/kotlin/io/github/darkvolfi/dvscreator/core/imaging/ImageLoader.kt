package io.github.darkvolfi.dvscreator.core.imaging

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.annotation.TargetApi
import androidx.exifinterface.media.ExifInterface
import kotlin.math.roundToInt

/** Yüklenen görüntü ve orijinal çözünürlüğe oranı (örnekleme yapıldıysa < 1). */
class LoadedImage(val image: ArgbImage, val scale: Double)

/**
 * Görseli kalite kaybı en az olacak şekilde çözer (agents/02 — madde 1, 2):
 * - Her zaman ARGB_8888, yazılım belleğinde, sRGB'ye dönüştürülmüş
 * - EXIF yönü uygulanmış
 * - Bellek için uzun kenar en fazla [longSide] (büyükse tam ona küçültülür). Yalnızca 2'nin kuvvetiyle örneklemek
 *   10–12 MP fotoğrafları tam çözünürlükte bırakıyordu; arka plan kaldırmada 256 MB yığın doluyordu.
 */
class ImageLoader(private val resolver: ContentResolver) {

    fun load(uri: Uri, longSide: Int = DEFAULT_LONG_SIDE): LoadedImage =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) loadWithImageDecoder(uri, longSide)
        else loadWithBitmapFactory(uri, longSide)

    @TargetApi(Build.VERSION_CODES.P)   // yalnızca load() içindeki sürüm kontrolünden çağrılır
    private fun loadWithImageDecoder(uri: Uri, longSide: Int): LoadedImage {
        var originalLongSide = 0
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            val w = info.size.width; val h = info.size.height
            originalLongSide = maxOf(w, h)
            val scale = originalLongSide > longSide
            if (scale) {
                val f = longSide.toDouble() / originalLongSide
                decoder.setTargetSize(maxOf(1, (w * f).roundToInt()), maxOf(1, (h * f).roundToInt()))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            // Düşük alfada renk hassasiyeti kaybolmasın. ImageDecoder ön-çarpılmamış pikselleri ölçekleyemiyor
            // ("Cannot scale unpremultiplied pixels"); küçültülünce ön-çarpılmış çözülür, getPixels yine düz renk verir.
            decoder.isUnpremultipliedRequired = !scale
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        }
        // ImageDecoder EXIF yönünü kendisi uygular
        return LoadedImage(bitmap.toArgbImage(), maxOf(bitmap.width, bitmap.height).toDouble() / originalLongSide)
    }

    private fun loadWithBitmapFactory(uri: Uri, longSide: Int): LoadedImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0) { "Görsel çözülemedi: $uri" }
        val originalLongSide = maxOf(bounds.outWidth, bounds.outHeight)

        val sample = sampleSizeFor(originalLongSide, longSide)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            // Ölçeklenecekse ön-çarpılmış olmalı (Canvas ön-çarpılmamış bitmap çizmez); bkz. loadWithImageDecoder
            inPremultiplied = originalLongSide / sample > longSide
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            }
        }
        val sampled = resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("Görsel çözülemedi: $uri")
        val sampledLong = maxOf(sampled.width, sampled.height)
        val bitmap = if (sampledLong <= longSide) sampled else {
            val f = longSide.toDouble() / sampledLong
            Bitmap.createScaledBitmap(sampled, maxOf(1, (sampled.width * f).roundToInt()), maxOf(1, (sampled.height * f).roundToInt()), true)
                .also { sampled.recycle() }
        }
        val orientation = resolver.openInputStream(uri).use { stream ->
            stream?.let { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        } ?: ExifInterface.ORIENTATION_NORMAL

        val image = applyExifOrientation(bitmap.toArgbImage(), orientation)
        return LoadedImage(image, maxOf(bitmap.width, bitmap.height).toDouble() / originalLongSide)
    }

    companion object {
        /** 512 px hedef için 4 kat çözünürlük: küçültme filtresi için yeterli bilgi, makul bellek. */
        const val DEFAULT_LONG_SIDE = 2048

        /** Uzun kenar [minLongSide]'ın altına düşmeyecek en büyük 2'nin kuvveti (BitmapFactory'de ucuz ilk adım). */
        fun sampleSizeFor(longSide: Int, minLongSide: Int): Int {
            var sample = 1
            while (longSide / (sample * 2) >= minLongSide) sample *= 2
            return sample
        }

        fun applyExifOrientation(image: ArgbImage, orientation: Int): ArgbImage = when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> image.flipHorizontal()
            ExifInterface.ORIENTATION_ROTATE_180 -> image.rotate(180)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> image.rotate(180).flipHorizontal()
            ExifInterface.ORIENTATION_TRANSPOSE -> image.rotate(90).flipHorizontal()
            ExifInterface.ORIENTATION_ROTATE_90 -> image.rotate(90)
            ExifInterface.ORIENTATION_TRANSVERSE -> image.rotate(270).flipHorizontal()
            ExifInterface.ORIENTATION_ROTATE_270 -> image.rotate(270)
            else -> image
        }
    }
}

/** `getPixels` non-premultiplied sRGB ARGB döndürür — [ArgbImage] ile aynı biçim. */
fun Bitmap.toArgbImage(): ArgbImage {
    val image = ArgbImage(width, height)
    getPixels(image.pixels, 0, width, 0, 0, width, height)
    return image
}

fun ArgbImage.toBitmap(): Bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
