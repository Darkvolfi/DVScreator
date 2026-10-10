package io.github.darkvolfi.wscreator

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
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
import io.github.darkvolfi.wscreator.core.imaging.AnimationTooLargeException
import io.github.darkvolfi.wscreator.core.imaging.CreatedSticker
import io.github.darkvolfi.wscreator.core.imaging.StickerFactory
import io.github.darkvolfi.wscreator.core.imaging.StickerTooLargeException
import io.github.darkvolfi.wscreator.core.model.EncodeMode
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
                    describe(StickerFactory.create(context, uri, type))
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

private fun describe(s: CreatedSticker) = LabState.Done(
    webp = s.webp,
    lines = listOfNotNull(
        if (s.fromVideo) "Hareketli sticker" else "Maksimum kalite modu (statik → 2 kare, 500 KB bütçe)",
        modeText(s.mode, s.quality),
        "Boyut: ${kb(s.webp.size)} / 500 KB",
        if (s.fromVideo) "SSIM: ortalama ${f4(s.ssimMean)} · en kötü kare ${f4(s.ssimMin)}" else "SSIM: ${f4(s.ssimMean)}",
        if (s.fromVideo) String.format(Locale.ROOT, "Kareler: %d kare, %.1f fps, %.1f sn", s.frameCount, s.fps, s.durationMs / 1000.0) else null,
        if (s.framesReduced) "  (500 KB'a sığması için kare hızı düşürüldü)" else null,
        "Kaynak: ${s.sourceDescription}",
        if (s.trimmed) "  (10 sn / 150 kare sınırı için kırpıldı)" else null,
        "Süre: yükleme ${s.loadMs} ms · toplam ${s.totalMs} ms",
    ),
)

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
