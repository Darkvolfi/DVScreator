package io.github.darkvolfi.dvscreator.feature.editor

import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
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
enum class EditorTool { CROP, ERASE, RESTORE, TEXT }

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
    val drawing = tool == EditorTool.ERASE || tool == EditorTool.RESTORE

    // Yakınlaştırma (yalnızca silgi araçlarında): içerik = (ekran - offset) / scale
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(drawing) { if (!drawing) { scale = 1f; offset = Offset.Zero } }

    val gestures = if (!drawing) Modifier else Modifier
        .pointerInput(Unit) {
            fun content(o: Offset) = (o - offset) / scale
            fun norm(o: Offset) = content(o).let { listOf(it.x / size.width, it.y / size.height) }
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                // Fırça ekranda aynı boyutta kalsın: yakınlaştırınca kaynakta incelir
                var stroke = MaskStroke(latestTool == EditorTool.RESTORE, latestBrush / scale, norm(down.position))
                current = stroke
                cursor = content(down.position)
                var multi = false
                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break
                    if (pressed.size >= 2) {
                        // İki parmak: çizimi iptal et, yakınlaştır / kaydır
                        multi = true
                        current = null
                        cursor = null
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        val centroid = event.calculateCentroid(useCurrent = true)
                        val newScale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                        val k = newScale / scale
                        val o = (offset - centroid) * k + centroid + pan
                        scale = newScale
                        offset = Offset(
                            o.x.coerceIn(size.width * (1 - scale), 0f),
                            o.y.coerceIn(size.height * (1 - scale), 0f),
                        )
                    } else if (!multi) {
                        val c = pressed[0]
                        stroke = stroke.copy(points = stroke.points + norm(c.position))
                        current = stroke
                        cursor = content(c.position)
                    }
                    event.changes.forEach { it.consume() }
                }
                if (!multi) latestOnStroke(stroke)   // dokunma da tek noktalık iz
                current = null
                cursor = null
            }
        }

    val originalBitmap = remember(original) { original.asAndroidBitmap() }
    Box(modifier.clipToBounds().then(gestures)) {
    Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                // Silme (CLEAR) yalnızca bu katmanı etkilesin, arkadaki dama desenini değil
                compositingStrategy = CompositingStrategy.Offscreen
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = scale; scaleY = scale
                translationX = offset.x; translationY = offset.y
            },
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
            val r = brush / scale * size.width
            drawCircle(Color.White, radius = r, center = c, style = Stroke(width = 2f / scale))
            drawCircle(Color.Black, radius = r + 2f / scale, center = c, style = Stroke(width = 1f / scale))
        }
    }
    }
}

private const val MAX_ZOOM = 8f
