package io.github.darkvolfi.dvscreator.feature.editor

import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import io.github.darkvolfi.dvscreator.core.model.MaskStroke

/** Düzenleme ekranındaki araç. */
enum class EditorTool { CROP, ERASE, RESTORE }

/**
 * Silgi önizlemesi: [base] (otomatik arka plan kaldırılmış ya da orijinal) üzerine fırça izleri GPU'da
 * çizilir — sil: şeffaflaştır; geri getir: [original] pikselleri. Gerçek sticker aynı izlerle
 * core:imaging'de (StrokePainter) tam çözünürlükte üretilir.
 *
 * Bu bileşen kaynağın en-boy oranında yerleştirilmeli: iz koordinatları bileşene göre 0..1.
 */
@Composable
fun MaskCanvas(
    base: ImageBitmap,
    original: ImageBitmap,
    strokes: List<MaskStroke>,
    tool: EditorTool,
    /** Fırça yarıçapı, kaynak genişliğine oranla. */
    brush: Float,
    onStroke: (MaskStroke) -> Unit,
    modifier: Modifier = Modifier,
) {
    var current by remember { mutableStateOf<MaskStroke?>(null) }
    var cursor by remember { mutableStateOf<Offset?>(null) }
    val latestTool by rememberUpdatedState(tool)
    val latestBrush by rememberUpdatedState(brush)
    val latestOnStroke by rememberUpdatedState(onStroke)
    val drawing = tool != EditorTool.CROP

    val gestures = if (!drawing) Modifier else Modifier
        .pointerInput(Unit) {
            fun norm(o: Offset, size: IntSize) = listOf(o.x / size.width, o.y / size.height)
            detectDragGestures(
                onDragStart = { o ->
                    cursor = o
                    current = MaskStroke(latestTool == EditorTool.RESTORE, latestBrush, norm(o, size))
                },
                onDrag = { change, _ ->
                    cursor = change.position
                    current = current?.let { it.copy(points = it.points + norm(change.position, size)) }
                },
                onDragEnd = { current?.let(latestOnStroke); current = null; cursor = null },
                onDragCancel = { current?.let(latestOnStroke); current = null; cursor = null },
            )
        }
        .pointerInput(Unit) {
            detectTapGestures { o ->
                latestOnStroke(MaskStroke(latestTool == EditorTool.RESTORE, latestBrush, listOf(o.x / size.width, o.y / size.height)))
            }
        }

    val originalBitmap = remember(original) { original.asAndroidBitmap() }
    Canvas(
        modifier
            .then(gestures)
            // Silme (CLEAR) yalnızca bu katmanı etkilesin, arkadaki dama desenini değil
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
    ) {
        drawImage(base, dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()))
        val all = if (current != null) strokes + current!! else strokes
        if (all.isEmpty()) return@Canvas
        val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            shader = BitmapShader(originalBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(android.graphics.Matrix().apply { setScale(size.width / originalBitmap.width, size.height / originalBitmap.height) })
            }
        }
        val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        drawIntoCanvas { canvas ->
            for (s in all) {
                val paint = if (s.restore) shaderPaint else clearPaint
                paint.strokeWidth = 2 * s.radius * size.width
                val path = android.graphics.Path()
                val p = s.points
                path.moveTo(p[0] * size.width, p[1] * size.height)
                if (p.size == 2) path.lineTo(p[0] * size.width + 0.01f, p[1] * size.height)
                var i = 2
                while (i + 1 < p.size) { path.lineTo(p[i] * size.width, p[i + 1] * size.height); i += 2 }
                canvas.nativeCanvas.drawPath(path, paint)
            }
        }
        // Fırçanın boyutunu göster
        cursor?.let { c ->
            drawCircle(Color.White, radius = brush * size.width, center = c, style = Stroke(width = 2f))
            drawCircle(Color.Black, radius = brush * size.width + 2f, center = c, style = Stroke(width = 1f))
        }
    }
}
