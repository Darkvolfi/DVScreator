package io.github.darkvolfi.dvscreator.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import io.github.darkvolfi.dvscreator.core.model.CropRect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Kırpma: kare (çerçeve hep kare), serbest (dikdörtgen) ya da resmin tamamı. */
enum class CropMode { SQUARE, FREE, FIT }

/**
 * Kırpma çerçevesi — kaynağın piksel koordinatlarında (görünen çözünürlük). Tüm işlemler sonucu
 * kaynak sınırları içinde ve en az [MIN_FRACTION] boyunda tutar.
 */
data class CropFrame(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top

    /** Aynı merkezde, kısa kenar boyunda kare. */
    fun squared(sourceW: Int, sourceH: Int): CropFrame = withSide(min(width, height), sourceW, sourceH)

    /** Aynı merkezde [side] boyunda kare. */
    fun withSide(side: Float, sourceW: Int, sourceH: Int): CropFrame {
        val s = side.coerceIn(minSide(sourceW, sourceH), min(sourceW, sourceH).toFloat())
        val cx = (left + right) / 2; val cy = (top + bottom) / 2
        return CropFrame(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2).inside(sourceW, sourceH)
    }

    /** Boyutu koruyarak kaydırır. */
    fun moved(dx: Float, dy: Float, sourceW: Int, sourceH: Int) =
        CropFrame(left + dx, top + dy, right + dx, bottom + dy).inside(sourceW, sourceH)

    /** Merkez sabit, iki parmakla büyütme / küçültme. */
    fun scaled(zoom: Float, sourceW: Int, sourceH: Int, square: Boolean): CropFrame {
        if (square) return withSide(width * zoom, sourceW, sourceH)
        val minS = minSide(sourceW, sourceH)
        val w = (width * zoom).coerceIn(minS, sourceW.toFloat())
        val h = (height * zoom).coerceIn(minS, sourceH.toFloat())
        val cx = (left + right) / 2; val cy = (top + bottom) / 2
        return CropFrame(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2).inside(sourceW, sourceH)
    }

    /**
     * Tutamaçla boyutlandırma: [handle] hangi kenarların ([LEFT], [TOP], [RIGHT], [BOTTOM]) hareket ettiği.
     * Karede karşı kenar / köşe sabit kalır; yalnızca bir kenar sürüklenirse diğer eksende ortalanır.
     */
    fun resized(handle: Int, dx: Float, dy: Float, sourceW: Int, sourceH: Int, square: Boolean): CropFrame {
        val minS = minSide(sourceW, sourceH)
        var l = left; var t = top; var r = right; var b = bottom
        if (handle and LEFT != 0) l = (l + dx).coerceIn(0f, max(0f, r - minS))
        if (handle and RIGHT != 0) r = (r + dx).coerceIn(min(sourceW.toFloat(), l + minS), sourceW.toFloat())
        if (handle and TOP != 0) t = (t + dy).coerceIn(0f, max(0f, b - minS))
        if (handle and BOTTOM != 0) b = (b + dy).coerceIn(min(sourceH.toFloat(), t + minS), sourceH.toFloat())
        if (!square) return CropFrame(l, t, r, b)

        val horizontal = handle and (LEFT or RIGHT) != 0
        val vertical = handle and (TOP or BOTTOM) != 0
        val wanted = when {
            horizontal && vertical -> max(r - l, b - t)
            horizontal -> r - l
            else -> b - t
        }
        // Sabit nokta: sürüklenen kenarın karşısı, sürüklenmeyen eksende merkez
        val ax = when { handle and LEFT != 0 -> right; handle and RIGHT != 0 -> left; else -> (left + right) / 2 }
        val ay = when { handle and TOP != 0 -> bottom; handle and BOTTOM != 0 -> top; else -> (top + bottom) / 2 }
        val roomX = when { handle and LEFT != 0 -> ax; handle and RIGHT != 0 -> sourceW - ax; else -> 2 * min(ax, sourceW - ax) }
        val roomY = when { handle and TOP != 0 -> ay; handle and BOTTOM != 0 -> sourceH - ay; else -> 2 * min(ay, sourceH - ay) }
        val s = wanted.coerceAtMost(min(roomX, roomY)).coerceAtLeast(minS)
        val nl = when { handle and LEFT != 0 -> ax - s; handle and RIGHT != 0 -> ax; else -> ax - s / 2 }
        val nt = when { handle and TOP != 0 -> ay - s; handle and BOTTOM != 0 -> ay; else -> ay - s / 2 }
        return CropFrame(nl, nt, nl + s, nt + s).inside(sourceW, sourceH)
    }

    fun toCropRect() = CropRect(left.roundToInt(), top.roundToInt(), right.roundToInt(), bottom.roundToInt())

    /** Boyutu koruyup kaynağın içine kaydırır. */
    private fun inside(sourceW: Int, sourceH: Int): CropFrame {
        val w = min(width, sourceW.toFloat()); val h = min(height, sourceH.toFloat())
        val l = left.coerceIn(0f, sourceW - w); val t = top.coerceIn(0f, sourceH - h)
        return CropFrame(l, t, l + w, t + h)
    }

    companion object {
        /** En küçük çerçeve: kaynağın kısa kenarının %15'i (daha küçüğü 512 px'e büyütülünce bulanıklaşır). */
        const val MIN_FRACTION = 0.15f
        const val LEFT = 1
        const val TOP = 2
        const val RIGHT = 4
        const val BOTTOM = 8
        /** Çerçevenin içinden sürükleme: taşı. */
        const val MOVE = 0

        fun minSide(sourceW: Int, sourceH: Int) = min(sourceW, sourceH) * MIN_FRACTION

        /** Varsayılan: ortalanmış en büyük kare. */
        fun largestSquare(sourceW: Int, sourceH: Int): CropFrame {
            val s = min(sourceW, sourceH).toFloat()
            return CropFrame((sourceW - s) / 2, (sourceH - s) / 2, (sourceW + s) / 2, (sourceH + s) / 2)
        }
    }
}

/**
 * Önizlemenin üzerine çizilen kırpma çerçevesi. Bu bileşen önizlemeyle AYNI boyutta (kaynağın en-boy
 * oranında) yerleştirilmeli: ekran koordinatı = kaynak koordinatı × (bileşen genişliği / kaynak genişliği).
 *
 * Köşelerden ve kenarlardan tutarak boyutlandırılır, içinden sürüklenerek taşınır, iki parmakla büyütülür.
 * [square]: çerçeve kare kalır.
 */
@Composable
fun CropOverlay(
    crop: CropFrame,
    sourceW: Int,
    sourceH: Int,
    square: Boolean,
    onChange: (CropFrame) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by rememberUpdatedState(crop)
    val isSquare by rememberUpdatedState(square)
    Canvas(
        modifier.pointerInput(sourceW, sourceH) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val k = sourceW / size.width.toFloat()   // ekran px → kaynak px
                val handle = hitHandle(current, down.position.x * k, down.position.y * k, HANDLE_TOUCH.toPx() * k)
                    ?: return@awaitEachGesture   // çerçevenin dışı
                down.consume()
                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break
                    val c = current
                    val next = if (pressed.size >= 2) {
                        val pan = event.calculatePan()
                        c.scaled(event.calculateZoom(), sourceW, sourceH, isSquare).moved(pan.x * k, pan.y * k, sourceW, sourceH)
                    } else {
                        val d = pressed[0].positionChange()
                        if (handle == CropFrame.MOVE) c.moved(d.x * k, d.y * k, sourceW, sourceH)
                        else c.resized(handle, d.x * k, d.y * k, sourceW, sourceH, isSquare)
                    }
                    if (next != c) onChange(next)
                    event.changes.forEach { it.consume() }
                }
            }
        },
    ) {
        val k = size.width / sourceW
        val left = crop.left * k; val top = crop.top * k
        val w = crop.width * k; val h = crop.height * k
        val dim = Color.Black.copy(alpha = 0.55f)
        // Çerçeve dışını karart
        drawRect(dim, Offset.Zero, Size(size.width, top))
        drawRect(dim, Offset(0f, top + h), Size(size.width, size.height - top - h))
        drawRect(dim, Offset(0f, top), Size(left, h))
        drawRect(dim, Offset(left + w, top), Size(size.width - left - w, h))
        // Çerçeve ve üçte bir çizgileri
        drawRect(Color.White, Offset(left, top), Size(w, h), style = Stroke(width = 1.5.dp.toPx()))
        for (i in 1..2) {
            drawLine(Color.White.copy(alpha = 0.5f), Offset(left + w * i / 3, top), Offset(left + w * i / 3, top + h))
            drawLine(Color.White.copy(alpha = 0.5f), Offset(left, top + h * i / 3), Offset(left + w, top + h * i / 3))
        }
        // Tutamaçlar: köşelerde L, kenar ortalarında kısa çizgi
        val thick = 4.dp.toPx()
        val arm = min(HANDLE_ARM.toPx(), min(w, h) / 3)
        fun bar(a: Offset, b: Offset) = drawLine(Color.White, a, b, strokeWidth = thick, cap = StrokeCap.Round)
        val r = left + w; val b = top + h
        for ((x, sx) in listOf(left to 1f, r to -1f)) for ((y, sy) in listOf(top to 1f, b to -1f)) {
            bar(Offset(x, y), Offset(x + arm * sx, y))
            bar(Offset(x, y), Offset(x, y + arm * sy))
        }
        val cx = left + w / 2; val cy = top + h / 2
        bar(Offset(cx - arm / 2, top), Offset(cx + arm / 2, top))
        bar(Offset(cx - arm / 2, b), Offset(cx + arm / 2, b))
        bar(Offset(left, cy - arm / 2), Offset(left, cy + arm / 2))
        bar(Offset(r, cy - arm / 2), Offset(r, cy + arm / 2))
    }
}

/**
 * Dokunulan nokta (kaynak px) hangi tutamaçta: kenarlara [touch] kadar yakınsa o kenarlar (köşede iki kenar),
 * çerçevenin içindeyse [CropFrame.MOVE], dışındaysa null.
 */
internal fun hitHandle(c: CropFrame, x: Float, y: Float, touch: Float): Int? {
    // Küçük çerçevede tutamaç alanları içeriyi kaplamasın: taşımak için ortada yer kalsın
    val t = min(touch, min(c.width, c.height) / 3)
    val withinX = x in (c.left - t)..(c.right + t)
    val withinY = y in (c.top - t)..(c.bottom + t)
    if (!withinX || !withinY) return null
    var h = 0
    if (abs(x - c.left) < t) h = h or CropFrame.LEFT else if (abs(x - c.right) < t) h = h or CropFrame.RIGHT
    if (abs(y - c.top) < t) h = h or CropFrame.TOP else if (abs(y - c.bottom) < t) h = h or CropFrame.BOTTOM
    if (h != 0) return h
    return if (x in c.left..c.right && y in c.top..c.bottom) CropFrame.MOVE else null
}

/** Parmağın tutamacı yakalayacağı uzaklık ve tutamaç çizgisinin uzunluğu. */
private val HANDLE_TOUCH = 28.dp
private val HANDLE_ARM = 22.dp
