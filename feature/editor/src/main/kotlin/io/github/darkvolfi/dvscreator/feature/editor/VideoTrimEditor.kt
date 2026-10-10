package io.github.darkvolfi.dvscreator.feature.editor

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
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
import io.github.darkvolfi.dvscreator.core.model.TrimRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

private class VideoMeta(val durationMs: Int, val fps: Double, val thumbnails: List<ImageBitmap>)

/**
 * Video kırpma ekranı: önizleme seçili aralıkta döngüyle oynar; iki tutamaçlı kaydırıcıyla
 * başlangıç ve bitiş seçilir (en fazla 10 sn — WhatsApp sınırı). Seçime göre elde edilecek
 * kare hızı/sayısı anında gösterilir; kısa seçim = 500 KB'ta daha yüksek kalite.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoTrimEditor(
    uri: Uri,
    onConfirm: (TrimRange) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val meta by produceState<VideoMeta?>(null, uri) {
        value = withContext(Dispatchers.IO) { runCatching { readMeta(context, uri) }.getOrNull() }
    }
    var range by remember(uri) { mutableStateOf<ClosedFloatingPointRange<Float>?>(null) }
    var player by remember { mutableStateOf<VideoView?>(null) }

    val m = meta
    if (m == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val maxMs = AnimationLoader.MAX_DURATION_MS.toFloat()
    val current = range ?: (0f..minOf(m.durationMs.toFloat(), maxMs))
    val latestRange by rememberUpdatedState(current)

    // Önizlemeyi seçili aralıkta döngüde tut
    LaunchedEffect(player) {
        val p = player ?: return@LaunchedEffect
        while (true) {
            delay(40)
            val r = latestRange
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
        Text(stringResource(R.string.trim_title), style = MaterialTheme.typography.titleLarge)

        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Color.Black), contentAlignment = Alignment.Center) {
            AndroidView(
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

        // Küçük resim şeridi: hangi anı seçtiğini görmek için
        Row(Modifier.fillMaxWidth().height(48.dp).clipToBounds()) {
            for (thumb in m.thumbnails) {
                Image(thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).height(48.dp))
            }
        }

        RangeSlider(
            value = current,
            valueRange = 0f..m.durationMs.toFloat(),
            onValueChange = { new ->
                val next = constrain(current, new, maxMs, MIN_MS.toFloat())
                range = next
                // Hangi tutamaç hareket ettiyse o ana atla (bitişte, bitişten hemen önceki an)
                val seekTo = if (new.start != current.start) next.start else next.endInclusive - 300f
                player?.seekTo(seekTo.coerceAtLeast(next.start).roundToInt())
            },
        )

        val selMs = (current.endInclusive - current.start).roundToInt()
        val fps = AnimationLoader.plannedFps(selMs, m.fps)
        val frames = (selMs * fps / 1000).roundToInt()
        Text(
            stringResource(R.string.trim_selection, sec(current.start), sec(current.endInclusive), sec(selMs.toFloat())),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(stringResource(R.string.trim_estimate, String.format(Locale.ROOT, "%.0f", fps), frames), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(if (selMs > 6000) R.string.trim_hint_long else R.string.trim_hint_short),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.trim_skip)) }
            Button(onClick = { onConfirm(TrimRange(current.start.roundToInt(), current.endInclusive.roundToInt())) }) {
                Text(stringResource(R.string.trim_confirm))
            }
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

private fun sec(ms: Float) = String.format(Locale.ROOT, "%.1f", ms / 1000f)

private fun readMeta(context: android.content.Context, uri: Uri): VideoMeta {
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
        return VideoMeta(info.durationMs, info.fps, thumbs)
    } finally {
        retriever.release()
    }
}
