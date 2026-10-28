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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import io.github.darkvolfi.dvscreator.core.model.MaskStroke

/** Düzenleme ekranındaki araç. */
enum class EditorTool { CROP, LASSO, WAND, ERASE, RESTORE, TEXT }

/**
 * Kesim önizlemesi ve araçları: [base] (kesilmiş ya da orijinal; [appliedStrokes] kadar iz zaten işlenmiş) üzerine
 * henüz işlenmemiş fırça izleri GPU'da çizilir — sil: şeffaflaştır; geri getir: [original] pikselleri. Gerçek sticker
 * aynı izlerle core:imaging'de (Matte.of) tam çözünürlükte üretilir.
 *
 * - Silgi / geri getir: parmakla çiz ([onStroke])
 * - Kement: konunun etrafını çiz, bırakınca seçim olur ([onSelection]); dışı silinir
 * - Sihirli değnek: dokun ([onWand]); benzer renkli bitişik bölge silinir / geri gelir
 * İki parmak her araçta yakınlaştırır ve kaydırır.
 *
 * Bu bileşen kaynağın en-boy oranında yerleştirilmeli: koordinatlar bileşene göre 0..1.
 */
@Composable
fun MaskCanvas(
    base: ImageBitmap,
    original: ImageBitmap,
    strokes: List<MaskStroke>,
    /** [strokes]'un ilk bu kadarı [base]'e zaten işlenmiş. */
    appliedStrokes: Int,
    /** Kement seçimi (0..1 çokgen); kement aracında çizgisi gösterilir. */
    selection: List<Float>?,
    tool: EditorTool,
    /** Fırça yarıçapı, kaynak genişliğine oranla. */
    brush: Float,
    onStroke: (MaskStroke) -> Unit,
    onWand: (x: Float, y: Float) -> Unit,
    onSelection: (List<Float>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var current by remember { mutableStateOf<MaskStroke?>(null) }
    var lasso by remember { mutableStateOf<List<Float>?>(null) }
    var cursor by remember { mutableStateOf<Offset?>(null) }
    val latestTool by rememberUpdatedState(tool)
    val latestBrush by rememberUpdatedState(brush)
    val latestOnStroke by rememberUpdatedState(onStroke)
    val latestOnWand by rememberUpdatedState(onWand)
    val latestOnSelection by rememberUpdatedState(onSelection)
    val active = tool == EditorTool.ERASE || tool == EditorTool.RESTORE || tool == EditorTool.LASSO || tool == EditorTool.WAND

    // Yakınlaştırma (yalnızca kesim araçlarında): içerik = (ekran - offset) / scale
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(active) { if (!active) { scale = 1f; offset = Offset.Zero } }

    val gestures = if (!active) Modifier else Modifier
        .pointerInput(Unit) {
            fun content(o: Offset) = (o - offset) / scale
            fun norm(o: Offset) = content(o).let { listOf(it.x / size.width, it.y / size.height) }
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                val mode = latestTool
                // Fırça ekranda aynı boyutta kalsın: yakınlaştırınca kaynakta incelir
                var stroke = MaskStroke(mode == EditorTool.RESTORE, latestBrush / scale, norm(down.position))
                var path = norm(down.position)
                var lastPoint = down.position
                when (mode) {
                    EditorTool.LASSO -> lasso = path
                    EditorTool.WAND -> Unit
                    else -> { current = stroke; cursor = content(down.position) }
                }
                var multi = false
                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break
                    if (pressed.size >= 2) {
                        // İki parmak: çizimi iptal et, yakınlaştır / kaydır
                        multi = true
                        current = null
                        lasso = null
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
                        when (mode) {
                            EditorTool.LASSO -> {
                                // Çok sık nokta saklama: tarif (pack.json) şişmesin
                                if ((c.position - lastPoint).getDistance() >= LASSO_STEP_PX) {
                                    path = path + norm(c.position)
                                    lastPoint = c.position
                                    lasso = path
                                }
                            }
                            EditorTool.WAND -> Unit
                            else -> {
                                stroke = stroke.copy(points = stroke.points + norm(c.position))
                                current = stroke
                                cursor = content(c.position)
                            }
                        }
                    }
                    event.changes.forEach { it.consume() }
                }
                if (!multi) when (mode) {
                    EditorTool.LASSO -> if (path.size >= 6) latestOnSelection(path)   // en az üç nokta
                    EditorTool.WAND -> norm(down.position).let { (x, y) -> latestOnWand(x, y) }
                    else -> latestOnStroke(stroke)   // dokunma da tek noktalık iz
                }
                current = null
                lasso = null
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
        // Değnek bölge doldurur: GPU'da çizilemez, işlenmiş önizlemeyi bekler
        val pending = strokes.drop(appliedStrokes).filter { it.wandTolerance == null }
        val all = if (current != null) pending + current!! else pending
        if (all.isNotEmpty()) drawStrokes(all, originalBitmap)
        // Kement: çizilen yol ya da mevcut seçim
        val outline = lasso ?: selection.takeIf { tool == EditorTool.LASSO }
        outline?.let { drawPolygon(it, closed = lasso == null, scale = scale) }
        // Fırçanın boyutunu göster
        cursor?.let { c ->
            val r = brush / scale * size.width
            drawCircle(Color.White, radius = r, center = c, style = Stroke(width = 2f / scale))
            drawCircle(Color.Black, radius = r + 2f / scale, center = c, style = Stroke(width = 1f / scale))
        }
    }
    }
}

private fun DrawScope.drawStrokes(strokes: List<MaskStroke>, originalBitmap: android.graphics.Bitmap) {
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
        for (s in strokes) {
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
}

/** Kement çizgisi: koyu ve açık kesikli çizgi üst üste (her zeminde görünsün). */
private fun DrawScope.drawPolygon(points: List<Float>, closed: Boolean, scale: Float) {
    if (points.size < 4) return
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(points[0] * size.width, points[1] * size.height)
        var i = 2
        while (i + 1 < points.size) { lineTo(points[i] * size.width, points[i + 1] * size.height); i += 2 }
        if (closed) close()
    }
    drawPath(path, Color.Black, style = Stroke(width = 4f / scale))
    drawPath(path, Color.White, style = Stroke(width = 2f / scale, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f / scale, 8f / scale))))
}

private const val MAX_ZOOM = 8f
/** Kement noktaları arasındaki en kısa mesafe (ekran pikseli). */
private const val LASSO_STEP_PX = 6f
