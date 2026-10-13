package io.github.darkvolfi.dvscreator.feature.editor

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
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
import io.github.darkvolfi.dvscreator.core.imaging.StickerPipeline
import io.github.darkvolfi.dvscreator.core.webp.NativeWebp
import io.github.darkvolfi.dvscreator.core.imaging.toBitmap
import io.github.darkvolfi.dvscreator.core.imaging.toArgbImage
import io.github.darkvolfi.dvscreator.core.model.EditRecipe
import io.github.darkvolfi.dvscreator.core.model.MaskStroke
import io.github.darkvolfi.dvscreator.core.model.TextLayer
import io.github.darkvolfi.dvscreator.core.model.TrimRange
import io.github.darkvolfi.dvscreator.core.segmentation.BackgroundRemover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/** Hareketli kaynağın zaman çizelgesi (video ve GIF ortak): süre kırpma arayüzü için. */
private class Timeline(val durationMs: Int, val fps: Double, val thumbnails: List<ImageBitmap>)

/** Düzenleme ekranının ihtiyaç duyduğu kaynak bilgisi. */
private sealed interface SourceMeta {
    /** Kaynağın görünen (döndürülmüş) çözünürlüğü — kırpma bu koordinatlarda. */
    val width: Int
    val height: Int

    /** [timeline]: GIF'lerde süre kırpma için; fotoğrafta null. */
    class Still(override val width: Int, override val height: Int, val preview: ImageBitmap, val previewImage: ArgbImage, val timeline: Timeline? = null) : SourceMeta
    class Video(
        override val width: Int, override val height: Int,
        val durationMs: Int, val fps: Double, val thumbnails: List<ImageBitmap>,
    ) : SourceMeta {
        val timeline = Timeline(durationMs, fps, thumbnails)
    }
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
        value = withContext(Dispatchers.IO) { runCatching { if (isVideo) readVideoMeta(context, uri) else readStillMeta(context, uri, mimeType) }.getOrNull() }
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
    var rotation by remember(uri) { mutableStateOf(0) }
    var margin by remember(uri) { mutableStateOf(EditRecipe().marginPx) }
    var outline by remember(uri) { mutableStateOf(0) }
    var outlineColor by remember(uri) { mutableStateOf(0xFFFFFFFF.toInt()) }
    var texts by remember(uri) { mutableStateOf<List<TextLayer>>(emptyList()) }
    var selectedText by remember(uri) { mutableStateOf<Int?>(null) }
    // Açık yazı penceresi: -1 yeni yazı, ≥0 düzenlenen yazı
    var textDialog by remember(uri) { mutableStateOf<Int?>(null) }
    val maxMs = AnimationLoader.MAX_DURATION_MS.toFloat()
    val video = m as? SourceMeta.Video
    val timeline = video?.timeline ?: (m as? SourceMeta.Still)?.timeline
    val currentRange = timeline?.let { range ?: (0f..min(it.durationMs.toFloat(), maxMs)) }
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
    // remember: her çizimde yeni nesne olursa aşağıdaki işler (arka plan kaldırma) durmadan yeniden başlar
    val frame: StillFrame? = remember(m, videoFrame) { (m as? SourceMeta.Still)?.let { StillFrame(it.previewImage, it.preview) } ?: videoFrame }
    // Arka plan kaldırmanın sonucu hemen gösterilir (önizleme çözünürlüğünde ~1 sn)
    val mattedFrame by produceState<StillFrame?>(null, removeBackground, frame) {
        value = null
        val f = frame
        if (!removeBackground || f == null) return@produceState
        value = withContext(Dispatchers.Default) {
            runCatching { BackgroundRemover.open(context).use { it.matte().apply(f.image, Matte.Region.FULL) }.let { StillFrame(it, it.toBitmap().asImageBitmap()) } }.getOrNull()
        }
    }
    val mattedPreview = mattedFrame?.bitmap

    // Hareketli önizleme: GIF her zaman, video yalnızca arka plan kaldırılınca (aksi hâlde video oynatıcı oynar).
    // Arka plan kaldırılınca kareler tek tek işlenir; hazır olanlar döngüde oynar.
    val animStart = ((currentRange?.start ?: 0f) / 100f).roundToInt() * 100
    val animEnd = ((currentRange?.endInclusive ?: 0f) / 100f).roundToInt() * 100
    val wantAnim = timeline != null && (video == null || removeBackground)
    val rawAnim by produceState<List<AnimFrame>?>(null, uri, wantAnim, removeBackground, animStart, animEnd) {
        if (!wantAnim) { value = null; return@produceState }
        delay(300)   // aralık sürüklenirken her adımda okuma
        val maxFrames = if (removeBackground) MATTED_PREVIEW_FRAMES else ANIM_PREVIEW_FRAMES
        value = withContext(Dispatchers.IO) {
            runCatching {
                val raw = if (video != null) readVideoPreview(context, uri, animStart, animEnd, maxFrames)
                else AnimationLoader(context).readGifPreview(uri, animStart, animEnd, ANIM_PREVIEW_SIDE, maxFrames)
                raw.map { (bmp, ms) -> bmp.asImageBitmap().let { AnimFrame(it, it, ms) } }
            }.getOrNull()
        }
    }
    var mattedAnim by remember(uri) { mutableStateOf<List<AnimFrame>>(emptyList()) }
    LaunchedEffect(rawAnim, removeBackground, mattedFrame != null) {
        mattedAnim = emptyList()
        val raw = rawAnim
        // İlk kare bitmeden başlama: aynı anda iki model işlemciyi paylaşmasın
        if (!removeBackground || raw == null || mattedFrame == null) return@LaunchedEffect
        val remover = withContext(Dispatchers.Default) { runCatching { BackgroundRemover.open(context) }.getOrNull() } ?: return@LaunchedEffect
        try {
            val matte = remover.matte()
            for (f in raw) {
                val done = withContext(Dispatchers.Default) {
                    runCatching {
                        val image = f.original.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false).toArgbImage()
                        AnimFrame(matte.apply(image, Matte.Region.FULL).toBitmap().asImageBitmap(), f.original, f.durationMs)
                    }.getOrNull()
                } ?: break
                mattedAnim = mattedAnim + done
            }
        } finally {
            remover.close()
        }
    }
    // Silgi araçlarında görüntü durur (hareketli kareye çizilmez); izler yine her kareye uygulanır
    val playing: List<AnimFrame>? = when {
        tool != EditorTool.CROP -> null
        removeBackground -> mattedAnim.takeIf { it.size >= 2 }
        else -> rawAnim?.takeIf { it.size >= 2 }
    }
    var animIndex by remember(uri) { mutableStateOf(0) }
    LaunchedEffect(playing) {
        val p = playing ?: return@LaunchedEffect
        while (true) {
            val i = animIndex % p.size
            delay(p[i].durationMs.toLong().coerceAtLeast(20))
            animIndex = (i + 1) % p.size
        }
    }
    val animFrame = playing?.let { it[animIndex % it.size] }
    // Yazı aracındaki "son hâl": sticker üretimiyle aynı kod (kırpma, maske, silgi, kenar boşluğu, döndürme, dış çizgi)
    val previewRecipe = EditRecipe(
        crop = if (squareMode) crop.toCropRect() else null, rotationDegrees = rotation, marginPx = margin,
        strokes = strokes, outlinePx = outline, outlineArgb = outlineColor,
    )
    val finalCanvas by produceState<ImageBitmap?>(null, tool == EditorTool.TEXT, frame, mattedFrame, previewRecipe) {
        val f = frame
        if (tool != EditorTool.TEXT || f == null || (removeBackground && mattedFrame == null)) return@produceState
        delay(80)   // kaydırıcı sürüklenirken her adımda yeniden çizme
        value = withContext(Dispatchers.Default) { runCatching { renderFinal(f.image, mattedFrame?.image, previewRecipe, m.width) }.getOrNull() }
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
            if (tool == EditorTool.TEXT) {
                // Yazı aracı: stickerın son hâli
                val canvas = finalCanvas
                if (canvas != null) {
                    StickerPreview(
                        canvas = canvas, texts = texts, selected = selectedText, onSelect = { selectedText = it },
                        onChange = { i, t -> texts = texts.toMutableList().also { l -> l[i] = t } },
                        modifier = Modifier.fillMaxSize().checkerboard(),
                    )
                } else {
                    CircularProgressIndicator()
                }
            } else {
            val ratio = m.width.toFloat() / m.height
            Box(Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 1f).graphicsLayer { rotationZ = rotation.toFloat() }) {
                when {
                    m is SourceMeta.Still || needFrame -> {
                        val shown = animFrame?.bitmap ?: if (removeBackground) mattedPreview else frame?.bitmap
                        val original = animFrame?.original ?: frame?.bitmap
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
        }

        rawAnim?.let { raw ->
            if (removeBackground && tool == EditorTool.CROP && mattedAnim.size < raw.size) {
                Text(
                    stringResource(R.string.editor_anim_preparing, mattedAnim.size, raw.size),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        run {
            // Araçlar: kırp / sil / geri getir. GIF ve videoda izler her kareye uygulanır
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = tool == EditorTool.CROP, onClick = { tool = EditorTool.CROP }, label = { Text(stringResource(R.string.editor_tool_crop)) })
                FilterChip(selected = tool == EditorTool.ERASE, onClick = { tool = EditorTool.ERASE }, label = { Text(stringResource(R.string.editor_tool_erase)) })
                FilterChip(selected = tool == EditorTool.RESTORE, onClick = { tool = EditorTool.RESTORE }, label = { Text(stringResource(R.string.editor_tool_restore)) })
                FilterChip(selected = tool == EditorTool.TEXT, onClick = { tool = EditorTool.TEXT }, label = { Text(stringResource(R.string.editor_tool_text)) })
            }
            if (tool == EditorTool.TEXT) {
                Text(stringResource(R.string.text_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { textDialog = -1 }) { Text(stringResource(R.string.text_add)) }
                    selectedText?.let { i ->
                        OutlinedButton(onClick = { textDialog = i }) { Text(stringResource(R.string.text_edit)) }
                        TextButton(onClick = { texts = texts.filterIndexed { j, _ -> j != i }; selectedText = null }) { Text(stringResource(R.string.text_delete)) }
                    }
                }
                selectedText?.let { i -> texts.getOrNull(i) }?.let { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.text_size), style = MaterialTheme.typography.bodySmall)
                        Slider(
                            value = t.sizeFraction, valueRange = MIN_TEXT_SIZE..MAX_TEXT_SIZE,
                            onValueChange = { v -> texts = texts.toMutableList().also { l -> l[selectedText!!] = t.copy(sizeFraction = v) } },
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        )
                    }
                }
            }
            textDialog?.let { index ->
                TextDialog(
                    initial = texts.getOrNull(index) ?: newTextLayer(),
                    onDone = { t ->
                        if (index >= 0) texts = texts.toMutableList().also { it[index] = t }
                        else { texts = texts + t; selectedText = texts.lastIndex }
                        textDialog = null
                    },
                    onDismiss = { textDialog = null },
                )
            }
            if (tool == EditorTool.ERASE || tool == EditorTool.RESTORE) {
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { rotation = (rotation + 90) % 360 }) { Text(stringResource(R.string.editor_rotate)) }
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.editor_margin), style = MaterialTheme.typography.bodySmall)
            Slider(value = margin.toFloat(), valueRange = 0f..MAX_MARGIN.toFloat(), steps = MAX_MARGIN / 4 - 1, onValueChange = { margin = it.roundToInt() }, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        }
        // Dış çizgi (klasik beyaz sticker kenarı)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.editor_outline), style = MaterialTheme.typography.bodySmall)
            Slider(value = outline.toFloat(), valueRange = 0f..MAX_OUTLINE.toFloat(), steps = MAX_OUTLINE - 1, onValueChange = { outline = it.roundToInt() }, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            for (c in listOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt())) {
                Box(
                    Modifier.padding(start = 6.dp).size(28.dp).clip(CircleShape).background(Color(c))
                        .border(if (c == outlineColor) 3.dp else 1.dp, if (c == outlineColor) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
                        .clickable { outlineColor = c },
                )
            }
        }
        if ((outline > 0 || margin != EditRecipe().marginPx || rotation != 0) && tool != EditorTool.TEXT) {
            Text(stringResource(R.string.editor_see_final), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                val hint = if (timeline != null && currentRange != null) {
                    val selMs = (currentRange.endInclusive - currentRange.start).roundToInt()
                    val frames = (selMs * plannedFps(selMs, timeline, video != null) / 1000).roundToInt()
                    stringResource(R.string.editor_remove_bg_hint_video, (frames * SECONDS_PER_MATTE_FRAME).roundToInt().coerceAtLeast(1))
                } else stringResource(R.string.editor_remove_bg_hint)
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = removeBackground, onCheckedChange = { removeBackground = it })
        }

        if (timeline != null && currentRange != null) {
            // Küçük resim şeridi: hangi anı seçtiğini görmek için
            Row(Modifier.fillMaxWidth().height(48.dp).clipToBounds()) {
                for (thumb in timeline.thumbnails) {
                    Image(thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).height(48.dp))
                }
            }
            RangeSlider(
                value = currentRange,
                valueRange = 0f..timeline.durationMs.toFloat(),
                onValueChange = { new ->
                    val next = constrain(currentRange, new, maxMs, MIN_MS.toFloat())
                    range = next
                    // Hangi tutamaç hareket ettiyse o ana atla (bitişte, bitişten hemen önceki an)
                    val seekTo = if (new.start != currentRange.start) next.start else next.endInclusive - 300f
                    player?.seekTo(seekTo.coerceAtLeast(next.start).roundToInt())
                },
            )
            val selMs = (currentRange.endInclusive - currentRange.start).roundToInt()
            val fps = plannedFps(selMs, timeline, video != null)
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
                        texts = texts,
                        rotationDegrees = rotation,
                        marginPx = margin,
                        outlinePx = outline,
                        outlineArgb = outlineColor,
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

/** Kenar boşluğu üst sınırı (px, 512 tuvalde) — StickerPipeline.MAX_MARGIN ile aynı. */
private const val MAX_MARGIN = 32
/** Dış çizgi üst sınırı (px, 512 tuvalde); varsayılan kenar boşluğu 16 olduğundan kesilmez. */
private const val MAX_OUTLINE = 14

/** Fırça yarıçapı, kaynak genişliğine oranla. */
private const val DEFAULT_BRUSH = 0.04f
private const val MIN_BRUSH = 0.008f
private const val MAX_BRUSH = 0.15f

/** Telefonda ölçülen: kare başına model ~0.7 sn + ön/son işlem (agents/raporlar). */
private const val SECONDS_PER_MATTE_FRAME = 0.8

/** Hareketli önizleme: uzun kenar (px) ve kare sınırı (bellek); arka plan kaldırılınca her kare ~0.7 sn. */
private const val ANIM_PREVIEW_SIDE = 360
private const val ANIM_PREVIEW_FRAMES = 60
private const val MATTED_PREVIEW_FRAMES = 16

/** Şeffaf alanları göstermek için dama deseni. */
private fun Modifier.checkerboard(): Modifier = drawBehind {
    val cell = 12.dp.toPx()
    val cols = (size.width / cell).toInt() + 1
    val rows = (size.height / cell).toInt() + 1
    drawRect(Color(0xFFFFFFFF))
    // Son satır / sütundaki kareler alanın dışına taşmasın
    clipRect {
        for (y in 0 until rows) for (x in 0 until cols) {
            if ((x + y) % 2 == 0) drawRect(Color(0xFFDDDDDD), Offset(x * cell, y * cell), Size(cell, cell))
        }
    }
}

private fun sec(ms: Float) = String.format(Locale.ROOT, "%.1f", ms / 1000f)

/**
 * Fotoğraf / GIF (ilk kare). Kırpma koordinatları ORİJİNAL (EXIF yönü uygulanmış) çözünürlükte —
 * kalite hattı ([io.github.darkvolfi.dvscreator.core.imaging.StickerPipeline]) aynı uzayı bekler.
 */
private fun readStillMeta(context: Context, uri: Uri, mimeType: String): SourceMeta.Still {
    val loaded = ImageLoader(context.contentResolver).load(uri, minLongSide = PREVIEW_LONG_SIDE)
    val w = (loaded.image.width / loaded.scale).roundToInt()
    val h = (loaded.image.height / loaded.scale).roundToInt()
    val timeline = if (mimeType == "image/gif") {
        runCatching { AnimationLoader(context).readGifInfo(uri) }.getOrNull()
            ?.takeIf { it.durationMs > 0 }
            ?.let { g -> Timeline(g.durationMs, g.fps, g.thumbnails.map { it.asImageBitmap() }) }
    } else null
    return SourceMeta.Still(w, h, loaded.image.toBitmap().asImageBitmap(), loaded.image, timeline)
}

/** Seçilen aralıkta çıkarılacak kare hızı: video planlanan hıza indirilir, GIF kendi kareleriyle kalır. */
private fun plannedFps(selMs: Int, timeline: Timeline, isVideo: Boolean): Double =
    if (isVideo) AnimationLoader.plannedFps(selMs, timeline.fps) else timeline.fps

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

/** Hareketli önizleme karesi: gösterilen ([bitmap], arka planı kaldırılmış olabilir), [original] ve süresi. */
private class AnimFrame(val bitmap: ImageBitmap, val original: ImageBitmap, val durationMs: Int)

/** Videonun [startMs]..[endMs] aralığından eşit aralıklı [count] kare (en fazla [ANIM_PREVIEW_SIDE] px) ve süreleri. */
private fun readVideoPreview(context: Context, uri: Uri, startMs: Int, endMs: Int, count: Int): List<Pair<Bitmap, Int>> {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, uri)
        val n = count.coerceAtMost(maxOf(2, (endMs - startMs) / 100))
        val step = (endMs - startMs).toDouble() / n
        return (0 until n).mapNotNull { k ->
            val raw = retriever.getFrameAtTime(((startMs + k * step) * 1000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST) ?: return@mapNotNull null
            val s = minOf(1f, ANIM_PREVIEW_SIDE.toFloat() / maxOf(raw.width, raw.height))
            val scaled = Bitmap.createScaledBitmap(raw, maxOf(1, (raw.width * s).roundToInt()), maxOf(1, (raw.height * s).roundToInt()), true)
            if (scaled !== raw) raw.recycle()
            val argb = if (scaled.config == Bitmap.Config.ARGB_8888) scaled else scaled.copy(Bitmap.Config.ARGB_8888, false).also { scaled.recycle() }
            argb to step.roundToInt()
        }
    } finally {
        retriever.release()
    }
}

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

/**
 * Önizleme karesinden stickerın son hâli (yazılar hariç — onlar canlı çizilir). [matted]: otomatik arka plan
 * kaldırma sonucu (aynı karenin tamamı için, önbellekten); maske yeniden hesaplanmaz.
 */
private fun renderFinal(frame: ArgbImage, matted: ArgbImage?, recipe: EditRecipe, sourceWidth: Int): ImageBitmap {
    val scale = frame.width.toDouble() / sourceWidth
    val auto = matted?.let { full ->
        Matte { image, _ ->
            val c = recipe.crop ?: return@Matte full
            fun s(v: Int) = (v * scale).roundToInt()
            full.crop(s(c.left), s(c.top), s(c.right), s(c.bottom)).takeIf { it.width == image.width && it.height == image.height } ?: image
        }
    }
    return StickerPipeline(NativeWebp).render(frame, recipe, scale, Matte.of(auto, recipe.strokes)).toBitmap().asImageBitmap()
}
