package io.github.darkvolfi.dvscreator.feature.packs

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.nio.ByteBuffer

/**
 * WebP sticker'ı Android'in kendi çözücüsüyle gösterir; hareketliyse oynatır
 * (WhatsApp'taki görünüme en yakın sonuç).
 */
@Composable
fun StickerImage(bytes: ByteArray?, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx -> ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
        update = { view ->
            if (view.tag === bytes) return@AndroidView   // aynı içerik: yeniden çözme
            view.tag = bytes
            if (bytes == null) {
                view.setImageDrawable(null)
                return@AndroidView
            }
            val drawable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) }.getOrNull()
            } else {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { BitmapDrawable(view.resources, it) }
            }
            view.setImageDrawable(drawable)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && drawable is AnimatedImageDrawable) drawable.start()
        },
    )
}
