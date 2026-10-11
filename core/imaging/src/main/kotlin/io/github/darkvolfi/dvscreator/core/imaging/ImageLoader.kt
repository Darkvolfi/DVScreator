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

/** Yüklenen görüntü ve orijinal çözünürlüğe oranı (örnekleme yapıldıysa < 1). */
class LoadedImage(val image: ArgbImage, val scale: Double)

/**
 * Görseli kalite kaybı en az olacak şekilde çözer (agents/02 — madde 1, 2):
 * - Her zaman ARGB_8888, yazılım belleğinde, sRGB'ye dönüştürülmüş
 * - EXIF yönü uygulanmış
 * - Bellek için yalnızca uzun kenar [minLongSide]'ın altına düşmeyecek kadar örnekleme
 */
class ImageLoader(private val resolver: ContentResolver) {

    fun load(uri: Uri, minLongSide: Int = DEFAULT_MIN_LONG_SIDE): LoadedImage =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) loadWithImageDecoder(uri, minLongSide)
        else loadWithBitmapFactory(uri, minLongSide)

    @TargetApi(Build.VERSION_CODES.P)   // yalnızca load() içindeki sürüm kontrolünden çağrılır
    private fun loadWithImageDecoder(uri: Uri, minLongSide: Int): LoadedImage {
        var sample = 1
        var originalLongSide = 0
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            originalLongSide = maxOf(info.size.width, info.size.height)
            sample = sampleSizeFor(originalLongSide, minLongSide)
            decoder.setTargetSampleSize(sample)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isUnpremultipliedRequired = true   // düşük alfada renk hassasiyeti kaybolmasın
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        }
        // ImageDecoder EXIF yönünü kendisi uygular
        return LoadedImage(bitmap.toArgbImage(), maxOf(bitmap.width, bitmap.height).toDouble() / originalLongSide)
    }

    private fun loadWithBitmapFactory(uri: Uri, minLongSide: Int): LoadedImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0) { "Görsel çözülemedi: $uri" }
        val originalLongSide = maxOf(bounds.outWidth, bounds.outHeight)

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(originalLongSide, minLongSide)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            }
        }
        val bitmap = resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("Görsel çözülemedi: $uri")
        val orientation = resolver.openInputStream(uri).use { stream ->
            stream?.let { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        } ?: ExifInterface.ORIENTATION_NORMAL

        val image = applyExifOrientation(bitmap.toArgbImage(), orientation)
        return LoadedImage(image, maxOf(bitmap.width, bitmap.height).toDouble() / originalLongSide)
    }

    companion object {
        /** 512 px hedef için 4 kat çözünürlük: küçültme filtresi için yeterli bilgi, makul bellek. */
        const val DEFAULT_MIN_LONG_SIDE = 2048

        /** Uzun kenar [minLongSide]'ın altına düşmeyecek en büyük 2'nin kuvveti. */
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
