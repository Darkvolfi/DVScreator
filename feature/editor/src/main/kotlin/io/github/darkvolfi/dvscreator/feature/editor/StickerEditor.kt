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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.darkvolfi.dvscreator.core.imaging.AnimationLoader
import io.github.darkvolfi.dvscreator.core.imaging.ImageLoader
import io.github.darkvolfi.dvscreator.core.imaging.toBitmap
import io.github.darkvolfi.dvscreator.core.model.EditRecipe
import io.github.darkvolfi.dvscreator.core.model.TrimRange
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

    class Still(override val width: Int, override val height: Int, val preview: ImageBitmap) : SourceMeta
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
    val maxMs = AnimationLoader.MAX_DURATION_MS.toFloat()
    val video = m as? SourceMeta.Video
    val currentRange = video?.let { range ?: (0f..min(it.durationMs.toFloat(), maxMs)) }
    val latestRange by rememberUpdatedState(currentRange)

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
                when (m) {
                    is SourceMeta.Still -> Image(m.preview, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                    is SourceMeta.Video -> AndroidView(
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
                if (squareMode) {
                    CropOverlay(crop, m.width, m.height, onChange = { crop = it }, modifier = Modifier.fillMaxSize())
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

private fun sec(ms: Float) = String.format(Locale.ROOT, "%.1f", ms / 1000f)

/**
 * Fotoğraf / GIF (ilk kare). Kırpma koordinatları ORİJİNAL (EXIF yönü uygulanmış) çözünürlükte —
 * kalite hattı ([io.github.darkvolfi.dvscreator.core.imaging.StickerPipeline]) aynı uzayı bekler.
 */
private fun readStillMeta(context: Context, uri: Uri): SourceMeta.Still {
    val loaded = ImageLoader(context.contentResolver).load(uri, minLongSide = PREVIEW_LONG_SIDE)
    val w = (loaded.image.width / loaded.scale).roundToInt()
    val h = (loaded.image.height / loaded.scale).roundToInt()
    return SourceMeta.Still(w, h, loaded.image.toBitmap().asImageBitmap())
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
