package io.github.darkvolfi.dvscreator.feature.editor

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.darkvolfi.dvscreator.core.imaging.ArgbImage
import io.github.darkvolfi.dvscreator.core.imaging.AnimationLoader
import io.github.darkvolfi.dvscreator.core.imaging.ImageLoader
import io.github.darkvolfi.dvscreator.core.imaging.Matte
import io.github.darkvolfi.dvscreator.core.imaging.toBitmap
import io.github.darkvolfi.dvscreator.core.imaging.toArgbImage
import io.github.darkvolfi.dvscreator.core.model.EditRecipe
import io.github.darkvolfi.dvscreator.core.model.MaskStroke
import io.github.darkvolfi.dvscreator.core.model.TrimRange
import io.github.darkvolfi.dvscreator.core.segmentation.BackgroundRemover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/** Düzenleme ekranının ihtiyaç duyduğu kaynak bilgisi. */
private sealed interface SourceMeta {
    /** Kaynağın görünen (döndürülmüş) çözünürlüğü — kırpma bu koordinatlarda. */
    val width: Int
    val height: Int

    class Still(override val width: Int, override val height: Int, val preview: ImageBitmap, val previewImage: ArgbImage) : SourceMeta
    class Video(
        override val width: Int, override val height: Int,
        val durationMs: Int, val fps: Double, val thumbnails: List<ImageBitmap>,
    ) : SourceMeta
}

/**
 * Bir kaynağı sticker yapmadan önceki düzenleme ekranı:
 * - Kare kırpma (sürükle / iki parmakla büyüt / kaydırıcı) ya da "tamamını sığdır"
 * - Videolarda süre aralığı (en fazla 10 sn), seçili aralık döngüde oynar
 *
 * @param remaining sırada bekleyen kaynak sayısı (bu dahil); >1 ise "kalanları olduğu gibi ekle" gösterilir
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickerEditor(
    uri: Uri,
    mimeType: String,
    remaining: Int,
    onConfirm: (EditRecipe) -> Unit,
    onSkip: () -> Unit,
    onAddRestAsIs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val isVideo = mimeType.startsWith("video/")
    val meta by produceState<SourceMeta?>(null, uri) {
        value = withContext(Dispatchers.IO) { runCatching { if (isVideo) readVideoMeta(context, uri) else readStillMeta(context, uri) }.getOrNull() }
    }
    val m = meta
    if (m == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    var squareMode by remember(uri) { mutableStateOf(true) }
    var crop by remember(uri) { mutableStateOf(SquareCrop.largest(m.width, m.height)) }
    var range by remember(uri) { mutableStateOf<ClosedFloatingPointRange<Float>?>(null) }
    var player by remember(uri) { mutableStateOf<VideoView?>(null) }
    var removeBackground by remember(uri) { mutableStateOf(false) }
    var tool by remember(uri) { mutableStateOf(EditorTool.CROP) }
    var strokes by remember(uri) { mutableStateOf<List<MaskStroke>>(emptyList()) }
    var redo by remember(uri) { mutableStateOf<List<MaskStroke>>(emptyList()) }
    var brush by remember(uri) { mutableStateOf(DEFAULT_BRUSH) }
    val maxMs = AnimationLoader.MAX_DURATION_MS.toFloat()
    val video = m as? SourceMeta.Video
    val currentRange = video?.let { range ?: (0f..min(it.durationMs.toFloat(), maxMs)) }
    val latestRange by rememberUpdatedState(currentRange)

    // Videoda silgi / arka plan önizlemesi oynayan video üzerinde değil, seçilen aralığın ilk karesinde yapılır;
    // izler sticker üretilirken her kareye uygulanır
    val needFrame = video != null && (tool != EditorTool.CROP || removeBackground)
    val frameMs = ((currentRange?.start ?: 0f) / 100f).roundToInt() * 100
    val videoFrame by produceState<StillFrame?>(null, uri, needFrame, frameMs) {
        if (!needFrame) return@produceState
        delay(300)   // aralık sürüklenirken her adımda kare okuma
        value = withContext(Dispatchers.IO) { runCatching { readVideoFrame(context, uri, frameMs) }.getOrNull() } ?: value
    }
    val frame: StillFrame? = (m as? SourceMeta.Still)?.let { StillFrame(it.previewImage, it.preview) } ?: videoFrame
    // Arka plan kaldırmanın sonucu hemen gösterilir (önizleme çözünürlüğünde ~1 sn)
    val mattedPreview by produceState<ImageBitmap?>(null, removeBackground, frame) {
        value = null
        val f = frame
        if (!removeBackground || f == null) return@produceState
        value = withContext(Dispatchers.Default) {
            runCatching { BackgroundRemover.open(context).use { it.matte().apply(f.image, Matte.Region.FULL).toBitmap().asImageBitmap() } }.getOrNull()
        }
    }

    // Videoda önizlemeyi seçili aralıkta döngüde tut
    LaunchedEffect(player) {
        val p = player ?: return@LaunchedEffect
        while (true) {
            delay(40)
            val r = latestRange ?: continue
            val pos = p.currentPosition
            if (pos >= r.endInclusive || pos < r.start - 250) {
                p.seekTo(r.start.roundToInt())
                if (!p.isPlaying) p.start()
            }
        }
    }

    Column(
        modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.editor_title), style = MaterialTheme.typography.titleLarge)

        // Önizleme: kare alan içinde kaynağın en-boy oranında kutu; kırpma çerçevesi aynı kutunun üzerinde
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Color.Black), contentAlignment = Alignment.Center) {
            val ratio = m.width.toFloat() / m.height
            Box(Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 1f)) {
                when {
                    m is SourceMeta.Still || needFrame -> {
                        val shown = if (removeBackground) mattedPreview else frame?.bitmap
                        val original = frame?.bitmap
                        if (shown != null && original != null) {
                            val transparent = removeBackground || strokes.isNotEmpty()
                            MaskCanvas(
                                base = shown, original = original, strokes = strokes, tool = tool, brush = brush,
                                onStroke = { strokes = strokes + it; redo = emptyList() },
                                modifier = Modifier.fillMaxSize().then(if (transparent) Modifier.checkerboard() else Modifier),
                            )
                        } else {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        }
                    }
                    else -> AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                setVideoURI(uri)
                                setOnPreparedListener { mp ->
                                    mp.setVolume(0f, 0f)   // sticker'da ses yok
                                    start()
                                    player = this
                                }
                            }
                        },
                        onRelease = { it.stopPlayback() },
                    )
                }
                if (squareMode && tool == EditorTool.CROP) {
                    CropOverlay(crop, m.width, m.height, onChange = { crop = it }, modifier = Modifier.fillMaxSize())
                }
            }
        }

        run {
            // Araçlar: kırp / sil / geri getir. GIF ve videoda izler her kareye uygulanır
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = tool == EditorTool.CROP, onClick = { tool = EditorTool.CROP }, label = { Text(stringResource(R.string.editor_tool_crop)) })
                FilterChip(selected = tool == EditorTool.ERASE, onClick = { tool = EditorTool.ERASE }, label = { Text(stringResource(R.string.editor_tool_erase)) })
                FilterChip(selected = tool == EditorTool.RESTORE, onClick = { tool = EditorTool.RESTORE }, label = { Text(stringResource(R.string.editor_tool_restore)) })
            }
            if (tool != EditorTool.CROP) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.editor_brush), style = MaterialTheme.typography.bodySmall)
                    Slider(value = brush, valueRange = MIN_BRUSH..MAX_BRUSH, onValueChange = { brush = it }, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                }
                Text(stringResource(R.string.editor_erase_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (strokes.isNotEmpty() || redo.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = strokes.isNotEmpty(), onClick = { redo = redo + strokes.last(); strokes = strokes.dropLast(1) }) { Text(stringResource(R.string.editor_undo)) }
                    TextButton(enabled = redo.isNotEmpty(), onClick = { strokes = strokes + redo.last(); redo = redo.dropLast(1) }) { Text(stringResource(R.string.editor_redo)) }
                    TextButton(enabled = strokes.isNotEmpty(), onClick = { redo = emptyList(); strokes = emptyList() }) { Text(stringResource(R.string.editor_clear_strokes)) }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = squareMode, onClick = { squareMode = true }, label = { Text(stringResource(R.string.editor_mode_square)) })
            FilterChip(selected = !squareMode, onClick = { squareMode = false }, label = { Text(stringResource(R.string.editor_mode_fit)) })
        }
        if (squareMode) {
            val maxSide = min(m.width, m.height).toFloat()
            Text(stringResource(R.string.editor_crop_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = crop.side / maxSide,
                valueRange = SquareCrop.MIN_FRACTION..1f,
                onValueChange = { crop = crop.copy(side = it * maxSide).clamped(m.width, m.height) },
            )
        } else {
            Text(stringResource(R.string.editor_fit_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.editor_remove_bg), style = MaterialTheme.typography.titleSmall)
                val hint = if (video != null && currentRange != null) {
                    val selMs = (currentRange.endInclusive - currentRange.start).roundToInt()
                    val frames = (selMs * AnimationLoader.plannedFps(selMs, video.fps) / 1000).roundToInt()
                    stringResource(R.string.editor_remove_bg_hint_video, (frames * SECONDS_PER_MATTE_FRAME).roundToInt().coerceAtLeast(1))
                } else stringResource(R.string.editor_remove_bg_hint)
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = removeBackground, onCheckedChange = { removeBackground = it })
        }

        if (video != null && currentRange != null) {
            // Küçük resim şeridi: hangi anı seçtiğini görmek için
            Row(Modifier.fillMaxWidth().height(48.dp).clipToBounds()) {
                for (thumb in video.thumbnails) {
                    Image(thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).height(48.dp))
                }
            }
            RangeSlider(
                value = currentRange,
                valueRange = 0f..video.durationMs.toFloat(),
                onValueChange = { new ->
                    val next = constrain(currentRange, new, maxMs, MIN_MS.toFloat())
                    range = next
                    // Hangi tutamaç hareket ettiyse o ana atla (bitişte, bitişten hemen önceki an)
                    val seekTo = if (new.start != currentRange.start) next.start else next.endInclusive - 300f
                    player?.seekTo(seekTo.coerceAtLeast(next.start).roundToInt())
                },
            )
            val selMs = (currentRange.endInclusive - currentRange.start).roundToInt()
            val fps = AnimationLoader.plannedFps(selMs, video.fps)
            val frames = (selMs * fps / 1000).roundToInt()
            Text(
                stringResource(R.string.trim_selection, sec(currentRange.start), sec(currentRange.endInclusive), sec(selMs.toFloat())),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(stringResource(R.string.trim_estimate, String.format(Locale.ROOT, "%.0f", fps), frames), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(if (selMs > 6000) R.string.trim_hint_long else R.string.trim_hint_short),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onSkip) { Text(stringResource(R.string.editor_skip)) }
            Button(onClick = {
                onConfirm(
                    EditRecipe(
                        crop = if (squareMode) crop.toCropRect() else null,
                        trim = currentRange?.let { TrimRange(it.start.roundToInt(), it.endInclusive.roundToInt()) },
                        removeBackground = removeBackground,
                        strokes = strokes,
                    ),
                )
            }) { Text(stringResource(R.string.editor_confirm)) }
        }
        if (remaining > 1) {
            TextButton(onClick = onAddRestAsIs) { Text(stringResource(R.string.editor_add_rest, remaining)) }
        }
    }
}

/**
 * Seçimi [minMs]..[maxMs] uzunlukta tutar. Seçim çok uzarsa diğer tutamaç sürükleneni takip eder;
 * çok kısalırsa sürüklenen tutamaç en kısa uzunlukta durur.
 */
internal fun constrain(
    old: ClosedFloatingPointRange<Float>,
    new: ClosedFloatingPointRange<Float>,
    maxMs: Float,
    minMs: Float,
): ClosedFloatingPointRange<Float> {
    var start = new.start
    var end = new.endInclusive
    val startMoved = start != old.start
    if (end - start > maxMs) {
        if (startMoved) end = start + maxMs else start = end - maxMs
    }
    if (end - start < minMs) {
        if (startMoved) start = end - minMs else end = start + minMs
    }
    return start..end
}

private const val MIN_MS = 500
private const val THUMBNAILS = 8
private const val PREVIEW_LONG_SIDE = 1080

/** Fırça yarıçapı, kaynak genişliğine oranla. */
private const val DEFAULT_BRUSH = 0.04f
private const val MIN_BRUSH = 0.008f
private const val MAX_BRUSH = 0.15f

/** Telefonda ölçülen: kare başına model ~0.7 sn + ön/son işlem (agents/raporlar). */
private const val SECONDS_PER_MATTE_FRAME = 0.8

/** Şeffaf alanları göstermek için dama deseni. */
private fun Modifier.checkerboard(): Modifier = drawBehind {
    val cell = 12.dp.toPx()
    val cols = (size.width / cell).toInt() + 1
    val rows = (size.height / cell).toInt() + 1
    drawRect(Color(0xFFFFFFFF))
    for (y in 0 until rows) for (x in 0 until cols) {
        if ((x + y) % 2 == 0) drawRect(Color(0xFFDDDDDD), Offset(x * cell, y * cell), Size(cell, cell))
    }
}

private fun sec(ms: Float) = String.format(Locale.ROOT, "%.1f", ms / 1000f)

/**
 * Fotoğraf / GIF (ilk kare). Kırpma koordinatları ORİJİNAL (EXIF yönü uygulanmış) çözünürlükte —
 * kalite hattı ([io.github.darkvolfi.dvscreator.core.imaging.StickerPipeline]) aynı uzayı bekler.
 */
private fun readStillMeta(context: Context, uri: Uri): SourceMeta.Still {
    val loaded = ImageLoader(context.contentResolver).load(uri, minLongSide = PREVIEW_LONG_SIDE)
    val w = (loaded.image.width / loaded.scale).roundToInt()
    val h = (loaded.image.height / loaded.scale).roundToInt()
    return SourceMeta.Still(w, h, loaded.image.toBitmap().asImageBitmap(), loaded.image)
}

private fun readVideoMeta(context: Context, uri: Uri): SourceMeta.Video {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, uri)
        val info = AnimationLoader.readVideoInfo(retriever)
        val thumbs = (0 until THUMBNAILS).mapNotNull { i ->
            val us = (info.durationMs * (i + 0.5) / THUMBNAILS * 1000).toLong()
            val bmp: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 96, 96)
            } else {
                retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { Bitmap.createScaledBitmap(it, 96, 96 * it.height / it.width, true) }
            }
            bmp?.asImageBitmap()
        }
        return SourceMeta.Video(info.displayWidth, info.displayHeight, info.durationMs, info.fps, thumbs)
    } finally {
        retriever.release()
    }
}

/** Silgi / arka plan önizlemesi için hareketsiz kare: işlenecek pikseller ve gösterilecek bitmap. */
private class StillFrame(val image: ArgbImage, val bitmap: ImageBitmap)

/** Videonun [ms] anındaki karesi (döndürme uygulanmış), en fazla [PREVIEW_LONG_SIDE] px. */
private fun readVideoFrame(context: Context, uri: Uri, ms: Int): StillFrame? {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, uri)
        val raw = retriever.getFrameAtTime(ms * 1000L, MediaMetadataRetriever.OPTION_CLOSEST) ?: return null
        val k = minOf(1f, PREVIEW_LONG_SIDE.toFloat() / maxOf(raw.width, raw.height))
        val bmp = if (k < 1f) Bitmap.createScaledBitmap(raw, (raw.width * k).roundToInt(), (raw.height * k).roundToInt(), true).also { raw.recycle() } else raw
        val image = bmp.copy(Bitmap.Config.ARGB_8888, false).toArgbImage()
        return StillFrame(image, image.toBitmap().asImageBitmap())
    } finally {
        retriever.release()
    }
}
