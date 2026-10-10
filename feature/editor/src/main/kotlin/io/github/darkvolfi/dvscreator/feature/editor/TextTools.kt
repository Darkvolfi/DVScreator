package io.github.darkvolfi.dvscreator.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.darkvolfi.dvscreator.core.imaging.TextOverlay
import io.github.darkvolfi.dvscreator.core.model.CropRect
import io.github.darkvolfi.dvscreator.core.model.MaskStroke
import io.github.darkvolfi.dvscreator.core.model.TextLayer
import kotlin.math.max

/**
 * Stickerın son hâli ([canvas]: sticker üretimiyle aynı kodla — StickerPipeline.render — üretilmiş 512 tuval,
 * yazısız) ve üstünde yazılar ([TextOverlay], stickerla aynı çizim). Yazılar dokunarak seçilir,
 * sürüklenerek taşınır, iki parmakla büyütülür.
 */
@Composable
internal fun StickerPreview(
    canvas: ImageBitmap,
    texts: List<TextLayer>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    onChange: (Int, TextLayer) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestTexts by rememberUpdatedState(texts)
    val latestSelected by rememberUpdatedState(selected)
    BoxWithConstraints(modifier.aspectRatio(1f).clipToBounds()) {
        val s = constraints.maxWidth.toFloat()
        Image(canvas, contentDescription = null, modifier = Modifier.fillMaxSize(), filterQuality = FilterQuality.High)
        val textBitmap = remember(texts, s) { TextOverlay.render(texts, s.toInt())?.asImageBitmap() }
        textBitmap?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { p ->
                        val hit = latestTexts.indices.lastOrNull { TextOverlay.bounds(latestTexts[it], size.width).contains(p.x, p.y) }
                        onSelect(hit)
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val i = latestSelected ?: return@detectTransformGestures
                        val t = latestTexts.getOrNull(i) ?: return@detectTransformGestures
                        onChange(i, t.copy(
                            centerX = (t.centerX + pan.x / size.width).coerceIn(0f, 1f),
                            centerY = (t.centerY + pan.y / size.height).coerceIn(0f, 1f),
                            sizeFraction = (t.sizeFraction * zoom).coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE),
                        ))
                    }
                },
        ) {
            // Seçili yazının çerçevesi
            val i = selected ?: return@Canvas
            val t = texts.getOrNull(i) ?: return@Canvas
            val b = TextOverlay.bounds(t, size.width.toInt())
            drawRect(
                Color.White, topLeft = Offset(b.left, b.top), size = Size(b.width(), b.height()),
                style = Stroke(width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))),
            )
            drawRect(
                Color.Black, topLeft = Offset(b.left - 3f, b.top - 3f), size = Size(b.width() + 6f, b.height() + 6f),
                style = Stroke(width = 1f),
            )
        }
    }
}

/** Yeni ya da düzenlenen yazı: metin, renk, kenar çizgisi. */
@Composable
internal fun TextDialog(initial: TextLayer, onDone: (TextLayer) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial.text) }
    var color by remember { mutableStateOf(initial.colorArgb) }
    var outline by remember { mutableStateOf(initial.outlineArgb != null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.text_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = text, onValueChange = { text = it.take(MAX_TEXT_LENGTH) }, modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 3)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in TEXT_COLORS) {
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .border(if (c == color) 3.dp else 1.dp, if (c == color) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
                                .clickable { color = c },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.text_outline), modifier = Modifier.weight(1f))
                    Switch(checked = outline, onCheckedChange = { outline = it })
                }
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = {
                // Kenar çizgisi yazı rengine zıt: açık renkte siyah, koyu renkte beyaz
                val outlineColor = if (isLight(color)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                onDone(initial.copy(text = text.trim(), colorArgb = color, outlineArgb = if (outline) outlineColor else null))
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun isLight(argb: Int): Boolean {
    val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
    return r * 299 + g * 587 + b * 114 > 128_000
}

/** Yeni yazının varsayılanı: altta, beyaz, siyah kenarlı (klasik sticker yazısı). */
internal fun newTextLayer() = TextLayer(
    text = "", centerX = 0.5f, centerY = 0.84f, sizeFraction = 0.14f,
    colorArgb = 0xFFFFFFFF.toInt(), outlineArgb = 0xFF000000.toInt(),
)

internal const val MIN_TEXT_SIZE = 0.05f
internal const val MAX_TEXT_SIZE = 0.45f
private const val MAX_TEXT_LENGTH = 60
private val TEXT_COLORS = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFFFFD600, 0xFFFF3B30, 0xFF34C759, 0xFF007AFF, 0xFFFF2D95,
).map { it.toInt() }
