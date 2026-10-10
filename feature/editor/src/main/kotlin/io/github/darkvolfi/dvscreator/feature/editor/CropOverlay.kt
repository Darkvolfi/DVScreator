package io.github.darkvolfi.dvscreator.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import io.github.darkvolfi.dvscreator.core.model.CropRect
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Kare kırpma çerçevesi — kaynağın piksel koordinatlarında (görünen çözünürlük).
 * [centerX]/[centerY] merkez, [side] kenar uzunluğu.
 */
data class SquareCrop(val centerX: Float, val centerY: Float, val side: Float) {

    /** Çerçeveyi kaynak sınırları içinde tutar. */
    fun clamped(sourceW: Int, sourceH: Int): SquareCrop {
        val maxSide = min(sourceW, sourceH).toFloat()
        val s = side.coerceIn(maxSide * MIN_FRACTION, maxSide)
        return SquareCrop(
            centerX.coerceIn(s / 2, sourceW - s / 2),
            centerY.coerceIn(s / 2, sourceH - s / 2),
            s,
        )
    }

    fun toCropRect(): CropRect {
        val half = side / 2
        return CropRect(
            (centerX - half).roundToInt(), (centerY - half).roundToInt(),
            (centerX + half).roundToInt(), (centerY + half).roundToInt(),
        )
    }

    companion object {
        /** En küçük çerçeve: kaynağın kısa kenarının %15'i (daha küçüğü 512 px'e büyütülünce bulanıklaşır). */
        const val MIN_FRACTION = 0.15f

        /** Varsayılan: ortalanmış en büyük kare. */
        fun largest(sourceW: Int, sourceH: Int) = SquareCrop(sourceW / 2f, sourceH / 2f, min(sourceW, sourceH).toFloat())
    }
}

/**
 * Önizlemenin üzerine çizilen kırpma çerçevesi. Bu bileşen önizlemeyle AYNI boyutta (kaynağın en-boy
 * oranında) yerleştirilmeli: ekran koordinatı = kaynak koordinatı × (bileşen genişliği / kaynak genişliği).
 *
 * Tek parmakla sürükleme çerçeveyi taşır, iki parmakla sıkıştırma büyütür / küçültür.
 */
@Composable
fun CropOverlay(
    crop: SquareCrop,
    sourceW: Int,
    sourceH: Int,
    onChange: (SquareCrop) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by rememberUpdatedState(crop)
    Canvas(
        modifier.pointerInput(sourceW, sourceH) {
            detectTransformGestures { _, pan, zoom, _ ->
                val scale = sourceW / size.width.toFloat()   // ekran px → kaynak px
                val c = current
                onChange(
                    SquareCrop(c.centerX + pan.x * scale, c.centerY + pan.y * scale, c.side * zoom)
                        .clamped(sourceW, sourceH),
                )
            }
        },
    ) {
        val k = size.width / sourceW
        val left = (crop.centerX - crop.side / 2) * k
        val top = (crop.centerY - crop.side / 2) * k
        val side = crop.side * k
        val dim = Color.Black.copy(alpha = 0.55f)
        // Çerçeve dışını karart
        drawRect(dim, Offset.Zero, Size(size.width, top))
        drawRect(dim, Offset(0f, top + side), Size(size.width, size.height - top - side))
        drawRect(dim, Offset(0f, top), Size(left, side))
        drawRect(dim, Offset(left + side, top), Size(size.width - left - side, side))
        // Çerçeve ve üçte bir çizgileri
        drawRect(Color.White, Offset(left, top), Size(side, side), style = Stroke(width = 2.dp.toPx()))
        val third = side / 3
        for (i in 1..2) {
            drawLine(Color.White.copy(alpha = 0.5f), Offset(left + third * i, top), Offset(left + third * i, top + side))
            drawLine(Color.White.copy(alpha = 0.5f), Offset(left, top + third * i), Offset(left + side, top + third * i))
        }
    }
}

