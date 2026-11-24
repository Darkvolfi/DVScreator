package io.github.darkvolfi.dvscreator.feature.packs

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.darkvolfi.dvscreator.core.whatsapp.PackManifest
import io.github.darkvolfi.dvscreator.core.whatsapp.PackRepository
import io.github.darkvolfi.dvscreator.core.whatsapp.StickerShare
import io.github.darkvolfi.dvscreator.core.whatsapp.WhatsAppIntents
import io.github.darkvolfi.dvscreator.core.whatsapp.WhatsAppLimits

/** Hızlı (tek stickerlık) paket mi: ana ekranda paket olarak değil, "Hızlı stickerlar" sekmesinde sticker olarak görünür. */
internal val PackManifest.isQuick: Boolean get() = identifier.startsWith(PackRepository.QUICK_PREFIX)

/**
 * Tek stickerlık paketi WhatsApp'a ekleyen işlev; sonucu kısa bildirimle gösterir.
 * Çağrı: (paket, WhatsApp paket adı).
 */
@Composable
internal fun rememberQuickAdder(vm: PacksViewModel): (PackManifest, String) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Pair<String, String>?>(null) }
    val launcher = rememberLauncherForActivityResult(StartActivityForResult()) { result ->
        val (id, pkg) = pending ?: return@rememberLauncherForActivityResult
        pending = null
        val error = WhatsAppIntents.validationError(result.data)
        val message = when {
            result.resultCode == Activity.RESULT_OK -> context.getString(R.string.quick_wa_ok)
            error != null -> context.getString(R.string.wa_rejected, error)
            WhatsAppIntents.isPackAdded(context, id, pkg) == true -> context.getString(R.string.quick_wa_already)
            else -> context.getString(R.string.quick_wa_cancelled)
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
    return { pack, pkg ->
        val problems = vm.repository.validate(pack.identifier)
        if (problems.isNotEmpty()) {
            Toast.makeText(context, context.getString(R.string.wa_rejected, problems.joinToString("\n")), Toast.LENGTH_LONG).show()
        } else {
            pending = pack.identifier to pkg
            launcher.launch(WhatsAppIntents.addPack(context, pack.identifier, pack.name, pkg))
        }
    }
}

/** Hızlı stickerlar ızgarası (en yeni üstte); dokununca sticker penceresi açılır. */
@Composable
internal fun QuickStickerRow(vm: PacksViewModel, row: List<PackManifest>, onSelect: (PackManifest) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (pack in row) {
            val file = pack.stickers.firstOrNull()?.imageFile
            val bytes = remember(pack.identifier, file) { file?.let { vm.repository.stickerBytes(pack.identifier, it) } }
            Box(
                Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onSelect(pack) },
            ) { StickerImage(bytes, Modifier.fillMaxSize().padding(6.dp)) }
        }
        repeat(QUICK_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
    }
}

internal const val QUICK_COLUMNS = 3

/** Hızlı stickerın penceresi: büyük önizleme, WhatsApp'a (yeniden) ekle, galeriye kaydet, sil. */
@Composable
internal fun QuickStickerDialog(pack: PackManifest, vm: PacksViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val whatsApps = remember { WhatsAppIntents.installedWhatsApps(context) }
    val add = rememberQuickAdder(vm)
    val file = pack.stickers.firstOrNull()?.imageFile
    val bytes = remember(pack.identifier, file) { file?.let { vm.repository.stickerBytes(pack.identifier, it) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StickerImage(
                    bytes,
                    Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp),
                )
                for (pkg in whatsApps) {
                    Button(modifier = Modifier.fillMaxWidth(), onClick = { add(pack, pkg) }) {
                        Text(stringResource(if (pkg == WhatsAppLimits.WHATSAPP_BUSINESS_PACKAGE) R.string.quick_add_business else R.string.quick_add_whatsapp))
                    }
                }
                if (StickerShare.canSaveToGallery && bytes != null) {
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { saveToGallery(context, bytes) }) {
                        Text(stringResource(R.string.save_to_gallery))
                    }
                }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    onClick = { vm.deletePack(pack.identifier); onDismiss() },
                ) { Text(stringResource(R.string.sticker_delete)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
    )
}
