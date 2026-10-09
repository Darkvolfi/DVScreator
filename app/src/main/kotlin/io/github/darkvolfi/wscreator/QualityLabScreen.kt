package io.github.darkvolfi.wscreator

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.widget.ImageView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.darkvolfi.wscreator.core.imaging.AnimationEncoder
import io.github.darkvolfi.wscreator.core.imaging.AnimationLoader
import io.github.darkvolfi.wscreator.core.imaging.AnimationTooLargeException
import io.github.darkvolfi.wscreator.core.imaging.ImageLoader
import io.github.darkvolfi.wscreator.core.imaging.StickerPipeline
import io.github.darkvolfi.wscreator.core.imaging.StickerTooLargeException
import io.github.darkvolfi.wscreator.core.model.EditRecipe
import io.github.darkvolfi.wscreator.core.model.EncodeMode
import io.github.darkvolfi.wscreator.core.webp.NativeWebp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.Locale

private const val TAG = "WSC_LAB"

/**
 * GEÇİCİ geliştirici ekranı: galeriden fotoğraf / video / GIF seç → kalite hattından geçir →
 * sonucu ve ölçümleri göster. Statik görseller "maksimum kalite modu" (2 kareli, 500 KB) ile işlenir.
 * M4'te paket ekranıyla değiştirilecek.
 */
@Composable
fun QualityLabScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<LabState>(LabState.Idle) }

    fun process(uri: Uri) {
        state = LabState.Working
        scope.launch {
            state = withContext(Dispatchers.Default) {
                runCatching {
                    val type = context.contentResolver.getType(uri).orEmpty()
                    if (type.startsWith("video/") || type == "image/gif") animated(context, uri) else still(context, uri)
                }.getOrElse { e ->
                    Log.e(TAG, "işlem hatası", e)
                    LabState.Failed(
                        if (e is StickerTooLargeException || e is AnimationTooLargeException) e.message.orEmpty() else e.toString(),
                    )
                }
            }
            (state as? LabState.Done)?.let { Log.i(TAG, it.lines.joinToString(" | ")) }
        }
    }

    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri -> uri?.let(::process) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.lab_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.lab_subtitle), style = MaterialTheme.typography.bodyMedium)
        Button(
            onClick = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageAndVideo)) },
            enabled = state !is LabState.Working,
        ) { Text(stringResource(R.string.lab_pick)) }

        when (val s = state) {
            LabState.Idle -> Unit
            LabState.Working -> {
                CircularProgressIndicator()
                Text(stringResource(R.string.lab_working), style = MaterialTheme.typography.bodySmall)
            }
            is LabState.Failed -> Text(stringResource(R.string.lab_error, s.message), color = MaterialTheme.colorScheme.error)
            is LabState.Done -> LabResult(s)
        }
    }
}

/** Statik görsel → maksimum kalite modu: 500 KB bütçe, 2 kareli hareketli WebP. */
private suspend fun still(context: android.content.Context, uri: Uri): LabState.Done {
    val start = SystemClock.elapsedRealtime()
    val loaded = ImageLoader(context.contentResolver).load(uri)
    val loadMs = SystemClock.elapsedRealtime() - start
    val pipeline = StickerPipeline(NativeWebp, limitBytes = AnimationEncoder.ANIMATED_LIMIT_BYTES - 512)
    val result = pipeline.export(loaded.image, EditRecipe(), loaded.scale)
    val bytes = NativeWebp.staticAsAnimation(result.bytes, 512, 512)
    val total = SystemClock.elapsedRealtime() - start
    return LabState.Done(
        webp = bytes,
        lines = listOf(
            "Maksimum kalite modu (statik → 2 kare, 500 KB bütçe)",
            modeText(result.mode, result.quality),
            "Boyut: ${kb(bytes.size)} / 500 KB",
            "SSIM: ${f4(result.ssim)}",
            "Kaynak: ${loaded.image.width}×${loaded.image.height} px",
            "Süre: yükleme $loadMs ms · toplam $total ms",
        ),
    )
}

private suspend fun animated(context: android.content.Context, uri: Uri): LabState.Done {
    val start = SystemClock.elapsedRealtime()
    val loaded = AnimationLoader(context).load(uri)
    val loadMs = SystemClock.elapsedRealtime() - start
    val sourceFrames = loaded.clip.frames.size
    val sourceFps = loaded.clip.fps
    val r = AnimationEncoder().encode(loaded.clip)
    val total = SystemClock.elapsedRealtime() - start
    return LabState.Done(
        webp = r.bytes,
        lines = listOfNotNull(
            "Hareketli sticker",
            modeText(r.mode, r.quality),
            "Boyut: ${kb(r.bytes.size)} / 500 KB",
            "SSIM: ortalama ${f4(r.ssimMean)} · en kötü kare ${f4(r.ssimMin)}",
            String.format(Locale.ROOT, "Kareler: %d kare, %.1f fps, %.1f sn", r.frameCount, r.fps, r.durationMs / 1000.0),
            if (r.frameCount < sourceFrames) String.format(Locale.ROOT, "  (sığdırmak için %d kare / %.1f fps'ten düşürüldü)", sourceFrames, sourceFps) else null,
            String.format(Locale.ROOT, "Kaynak: %.1f sn, %.1f fps", loaded.sourceDurationMs / 1000.0, loaded.sourceFps),
            if (loaded.trimmed) "  (10 sn / 150 kare sınırı için kırpıldı)" else null,
            "Süre: kare çıkarma $loadMs ms · kodlama ${r.encodeMs} ms (${r.attempts} deneme) · ölçüm ${r.measureMs} ms · toplam $total ms",
        ),
    )
}

private fun modeText(mode: EncodeMode, quality: Int) = when (mode) {
    EncodeMode.LOSSLESS -> "Kayıpsız — piksel piksel aynı"
    EncodeMode.NEAR_LOSSLESS -> "Neredeyse kayıpsız (seviye $quality)"
    EncodeMode.LOSSY -> "Kayıplı (kalite $quality)"
}

private fun kb(bytes: Int) = String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
private fun f4(v: Double) = String.format(Locale.ROOT, "%.4f", v)

@Composable
private fun LabResult(s: LabState.Done) {
    // Şeffaflığı görebilmek için dama tahtası zemin
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
        // Hareketli WebP'yi Android'in kendi çözücüsüyle oynat (WhatsApp'taki görünüme en yakın)
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
            update = { view ->
                val drawable: Drawable? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(s.webp)))
                } else {
                    android.graphics.drawable.BitmapDrawable(view.resources, android.graphics.BitmapFactory.decodeByteArray(s.webp, 0, s.webp.size))
                }
                view.setImageDrawable(drawable)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && drawable is AnimatedImageDrawable) drawable.start()
            },
        )
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        s.lines.forEachIndexed { i, line ->
            Text(line, style = if (i <= 1) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium)
        }
    }
}

private sealed interface LabState {
    data object Idle : LabState
    data object Working : LabState
    data class Failed(val message: String) : LabState
    class Done(val webp: ByteArray, val lines: List<String>) : LabState
}
