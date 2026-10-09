package io.github.darkvolfi.wscreator

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.darkvolfi.wscreator.core.imaging.ImageLoader
import io.github.darkvolfi.wscreator.core.imaging.StickerPipeline
import io.github.darkvolfi.wscreator.core.imaging.StickerTooLargeException
import io.github.darkvolfi.wscreator.core.model.EditRecipe
import io.github.darkvolfi.wscreator.core.model.EncodeMode
import io.github.darkvolfi.wscreator.core.webp.NativeWebp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * GEÇİCİ geliştirici ekranı (M1): galeriden görsel seç → kalite hattından geçir → sonucu ve
 * ölçümleri göster. Gerçek fotoğraflarla kalite ölçmek için. M3'te paket ekranıyla değiştirilecek.
 */
@Composable
fun QualityLabScreen() {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<LabState>(LabState.Idle) }

    fun process(uri: Uri) {
        state = LabState.Working
        scope.launch {
            state = withContext(Dispatchers.Default) {
                runCatching {
                    val start = SystemClock.elapsedRealtime()
                    val loaded = ImageLoader(resolver).load(uri)
                    val loadMs = SystemClock.elapsedRealtime() - start
                    val result = StickerPipeline(NativeWebp).export(loaded.image, EditRecipe(), loaded.scale)
                    LabState.Done(
                        preview = BitmapFactory.decodeByteArray(result.bytes, 0, result.bytes.size),
                        mode = result.mode,
                        quality = result.quality,
                        sizeBytes = result.bytes.size,
                        ssim = result.ssim,
                        sourceSize = "${loaded.image.width}×${loaded.image.height}",
                        loadMs = loadMs,
                        totalMs = SystemClock.elapsedRealtime() - start,
                    )
                }.getOrElse { e ->
                    LabState.Failed(if (e is StickerTooLargeException) e.message.orEmpty() else e.toString())
                }
            }
        }
    }

    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri -> uri?.let(::process) }

    run {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.lab_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.lab_subtitle), style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
                enabled = state !is LabState.Working,
            ) { Text(stringResource(R.string.lab_pick)) }

            when (val s = state) {
                LabState.Idle -> Unit
                LabState.Working -> CircularProgressIndicator()
                is LabState.Failed -> Text(stringResource(R.string.lab_error, s.message), color = MaterialTheme.colorScheme.error)
                is LabState.Done -> LabResult(s)
            }
        }
    }
}

@Composable
private fun LabResult(s: LabState.Done) {
    // Şeffaflığı görebilmek için dama tahtası zemin; piksel kayıplarını görmek için filtresiz büyütme
    Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
        Canvas(Modifier.fillMaxSize()) {
            val cell = size.width / 32
            for (y in 0 until 32) for (x in 0 until 32) {
                drawRect(
                    color = if ((x + y) % 2 == 0) Color(0xFFDDDDDD) else Color(0xFFFFFFFF),
                    topLeft = Offset(x * cell, y * cell), size = Size(cell, cell),
                )
            }
        }
        Image(
            bitmap = s.preview.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            filterQuality = FilterQuality.None,
        )
    }
    val modeText = when (s.mode) {
        EncodeMode.LOSSLESS -> stringResource(R.string.lab_mode_lossless)
        EncodeMode.NEAR_LOSSLESS -> stringResource(R.string.lab_mode_near_lossless, s.quality)
        EncodeMode.LOSSY -> stringResource(R.string.lab_mode_lossy, s.quality)
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(modeText, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.lab_size, String.format(Locale.ROOT, "%.1f", s.sizeBytes / 1024.0)))
        Text(stringResource(R.string.lab_ssim, String.format(Locale.ROOT, "%.4f", s.ssim)))
        Text(stringResource(R.string.lab_source, s.sourceSize))
        Text(stringResource(R.string.lab_time, s.loadMs, s.totalMs))
    }
}

private sealed interface LabState {
    data object Idle : LabState
    data object Working : LabState
    data class Failed(val message: String) : LabState
    data class Done(
        val preview: Bitmap,
        val mode: EncodeMode,
        val quality: Int,
        val sizeBytes: Int,
        val ssim: Double,
        val sourceSize: String,
        val loadMs: Long,
        val totalMs: Long,
    ) : LabState
}
