package io.github.darkvolfi.dvscreator.feature.packs

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.darkvolfi.dvscreator.core.imaging.StickerFactory
import io.github.darkvolfi.dvscreator.core.whatsapp.PackManifest
import io.github.darkvolfi.dvscreator.core.whatsapp.StickerShare
import io.github.darkvolfi.dvscreator.core.whatsapp.WhatsAppIntents
import io.github.darkvolfi.dvscreator.core.whatsapp.WhatsAppLimits
import io.github.darkvolfi.dvscreator.feature.editor.StickerEditor

/** Paketsiz tek sticker: düzenle → üret → WhatsApp'ta sohbete gönder. */
@Composable
internal fun QuickStickerScreen(state: QuickState, vm: PacksViewModel, modifier: Modifier) {
    BackHandler { vm.dismissQuick() }
    if (state is QuickState.Editing) {
        key(state.source.uri) {
            StickerEditor(
                uri = state.source.uri,
                mimeType = state.source.mimeType,
                remaining = 1,
                onConfirm = { vm.onQuickEdited(it) },
                onSkip = { vm.dismissQuick() },
                onAddRestAsIs = {},
                modifier = modifier,
            )
        }
        return
    }
    Column(
        modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.quick_title), style = MaterialTheme.typography.headlineSmall)
        when (state) {
            is QuickState.Working -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text(
                    when (val p = state.progress) {
                        is StickerFactory.Progress.Extracting -> stringResource(R.string.progress_extracting, p.done, p.total)
                        is StickerFactory.Progress.Encoding -> stringResource(R.string.progress_encoding, p.attempt, p.frames)
                        StickerFactory.Progress.Measuring -> stringResource(R.string.progress_measuring)
                        null -> stringResource(R.string.quick_working)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            is QuickState.Done -> QuickResult(state, vm)
            is QuickState.Failed -> {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(stringResource(R.string.pack_add_failed, state.error), Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedButton(onClick = { vm.dismissQuick() }) { Text(stringResource(R.string.back)) }
            }
            QuickState.Idle, is QuickState.Editing -> Unit
        }
    }
}

/** Hızlı paketin WhatsApp'a ekleme / güncelleme sonucu. */
private enum class QuickWaResult { OK, ALREADY, CANCELLED }

@Composable
private fun QuickResult(state: QuickState.Done, vm: PacksViewModel) {
    val context = LocalContext.current
    val whatsApps = remember { WhatsAppIntents.installedWhatsApps(context) }
    val packs by vm.packs.collectAsState()
    val pack = state.packId?.let { id -> packs.firstOrNull { it.identifier == id } }
    var waResult by remember { mutableStateOf<QuickWaResult?>(null) }
    var waError by remember { mutableStateOf<String?>(null) }
    var pendingPackage by remember { mutableStateOf<String?>(null) }
    val waLauncher = rememberLauncherForActivityResult(StartActivityForResult()) { result ->
        waError = WhatsAppIntents.validationError(result.data)
        val id = state.packId
        waResult = when {
            result.resultCode == Activity.RESULT_OK -> QuickWaResult.OK
            waError == null && id != null && pendingPackage?.let { WhatsAppIntents.isPackAdded(context, id, it) } == true -> QuickWaResult.ALREADY
            else -> QuickWaResult.CANCELLED
        }
        pendingPackage = null
    }
    val quickName = stringResource(R.string.quick_pack_name)

    fun sendToWhatsApp(target: PackManifest, pkg: String) {
        if (target.stickers.size < WhatsAppLimits.PACK_MIN_STICKERS) return
        val problems = vm.repository.validate(target.identifier)
        if (problems.isNotEmpty()) { waError = problems.joinToString("\n"); return }
        pendingPackage = pkg
        waResult = null
        waLauncher.launch(WhatsAppIntents.addPack(context, target.identifier, target.name, pkg))
    }

    val sticker = state.sticker
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 48.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        StickerImage(sticker.webp, Modifier.size(240.dp).padding(8.dp))
    }
    Card(
        colors = if (sticker.degraded) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer) else CardDefaults.cardColors(),
    ) { Text(describe(sticker), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }

    if (whatsApps.isEmpty()) Text(stringResource(R.string.pack_no_whatsapp), color = MaterialTheme.colorScheme.error)
    if (pack == null) {
        Text(stringResource(R.string.quick_explain), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (pkg in whatsApps) {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { vm.addQuickToPack(quickName) { added -> sendToWhatsApp(added, pkg) } },
            ) {
                Text(stringResource(if (pkg == WhatsAppLimits.WHATSAPP_BUSINESS_PACKAGE) R.string.quick_add_business else R.string.quick_add_whatsapp))
            }
        }
    } else {
        val missing = WhatsAppLimits.PACK_MIN_STICKERS - pack.stickers.size
        Card(Modifier.fillMaxWidth()) {
            Text(
                if (missing > 0) stringResource(R.string.quick_need_more, pack.name, pack.stickers.size, missing)
                else stringResource(R.string.quick_added, pack.name, pack.stickers.size, WhatsAppLimits.PACK_MAX_STICKERS),
                Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium,
            )
        }
        waResult?.let { r ->
            Text(
                stringResource(
                    when (r) {
                        QuickWaResult.OK -> R.string.quick_wa_ok
                        QuickWaResult.ALREADY -> R.string.quick_wa_already
                        QuickWaResult.CANCELLED -> R.string.quick_wa_cancelled
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        // Eklendikten sonra yeniden deneme (ör. güncelleme iptal edildiyse)
        if (missing <= 0) for (pkg in whatsApps) {
            OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = pendingPackage == null, onClick = { sendToWhatsApp(pack, pkg) }) {
                Text(stringResource(if (pkg == WhatsAppLimits.WHATSAPP_BUSINESS_PACKAGE) R.string.pack_update_business else R.string.pack_update_whatsapp))
            }
        }
    }
    waError?.let { Text(stringResource(R.string.wa_rejected, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    OutlinedButton(
        modifier = Modifier.fillMaxWidth(),
        onClick = { context.startActivity(StickerShare.intent(context, sticker.webp, null)) },
    ) { Text(stringResource(R.string.quick_share_other)) }
    if (StickerShare.canSaveToGallery) {
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { saveToGallery(context, sticker.webp) }) {
            Text(stringResource(R.string.save_to_gallery))
        }
    }
    Button(modifier = Modifier.fillMaxWidth(), onClick = { vm.dismissQuick() }) { Text(stringResource(R.string.quick_done)) }
}

/** Galeriye kaydet ve sonucu kısa bildirimle göster (Samsung Klavye'de özel sticker yapmak için). */
internal fun saveToGallery(context: Context, webp: ByteArray) {
    val ok = StickerShare.saveToGallery(context, webp)
    Toast.makeText(context, if (ok) R.string.saved_to_gallery else R.string.save_failed, Toast.LENGTH_LONG).show()
}
