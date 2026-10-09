package io.github.darkvolfi.wscreator.core.imaging

/**
 * Bellekteki görüntü: satır satır, non-premultiplied 0xAARRGGBB, sRGB.
 * Android `Bitmap.getPixels` ve [io.github.darkvolfi.wscreator.core.webp.WebpCodec] ile aynı biçim;
 * Android sınıflarına bağımlı olmadığı için JVM birim testlerinde kullanılabilir.
 */
class ArgbImage(val width: Int, val height: Int, val pixels: IntArray = IntArray(width * height)) {
    init {
        require(width > 0 && height > 0) { "geçersiz boyut: ${width}x$height" }
        require(pixels.size == width * height) { "piksel sayısı boyutla uyuşmuyor" }
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]
    operator fun set(x: Int, y: Int, argb: Int) {
        pixels[y * width + x] = argb
    }

    fun copy(): ArgbImage = ArgbImage(width, height, pixels.copyOf())

    /** [left, right) × [top, bottom) bölgesini keser; bölge görüntüye kırpılır. */
    fun crop(left: Int, top: Int, right: Int, bottom: Int): ArgbImage {
        val l = left.coerceIn(0, width - 1)
        val t = top.coerceIn(0, height - 1)
        val r = right.coerceIn(l + 1, width)
        val b = bottom.coerceIn(t + 1, height)
        if (l == 0 && t == 0 && r == width && b == height) return this
        val out = ArgbImage(r - l, b - t)
        for (y in t until b) System.arraycopy(pixels, y * width + l, out.pixels, (y - t) * out.width, out.width)
        return out
    }

    /** Saat yönünde 90'ın katları kadar döndürür. */
    fun rotate(degrees: Int): ArgbImage = when (((degrees % 360) + 360) % 360) {
        0 -> this
        90 -> transform(height, width) { x, y -> this[y, height - 1 - x] }
        180 -> transform(width, height) { x, y -> this[width - 1 - x, height - 1 - y] }
        270 -> transform(height, width) { x, y -> this[width - 1 - y, x] }
        else -> throw IllegalArgumentException("yalnızca 90'ın katları: $degrees")
    }

    fun flipHorizontal(): ArgbImage = transform(width, height) { x, y -> this[width - 1 - x, y] }

    private inline fun transform(w: Int, h: Int, source: (x: Int, y: Int) -> Int): ArgbImage {
        val out = ArgbImage(w, h)
        var i = 0
        for (y in 0 until h) for (x in 0 until w) out.pixels[i++] = source(x, y)
        return out
    }
}

internal fun alphaOf(argb: Int) = argb ushr 24
internal fun redOf(argb: Int) = (argb shr 16) and 0xff
internal fun greenOf(argb: Int) = (argb shr 8) and 0xff
internal fun blueOf(argb: Int) = argb and 0xff
internal fun argbOf(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
