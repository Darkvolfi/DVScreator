package io.github.darkvolfi.wscreator.feature.packs

import android.app.Activity
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.darkvolfi.wscreator.core.imaging.CreatedSticker
import io.github.darkvolfi.wscreator.core.model.EncodeMode
import io.github.darkvolfi.wscreator.core.whatsapp.PackManifest
import io.github.darkvolfi.wscreator.core.whatsapp.StickerEntry
import io.github.darkvolfi.wscreator.core.whatsapp.WhatsAppIntents
import io.github.darkvolfi.wscreator.core.whatsapp.WhatsAppLimits
import java.util.Locale

private const val TAG = "WSC_PACKS"

/** WhatsApp ekleme sonucu (kullanıcıya gösterilecek). */
private sealed interface AddToWhatsAppResult {
    data object Added : AddToWhatsAppResult
    data object AlreadyAdded : AddToWhatsAppResult
    data object Cancelled : AddToWhatsAppResult
    data class Rejected(val reason: String) : AddToWhatsAppResult
    data class Invalid(val problems: List<String>) : AddToWhatsAppResult
}

@Composable
internal fun PackDetailScreen(pack: PackManifest, vm: PacksViewModel, onBack: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val addStates by vm.addState.collectAsState()
    val addState = addStates[pack.identifier] ?: AddState.Idle
    var editing by remember { mutableStateOf<StickerEntry?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var waResult by remember { mutableStateOf<AddToWhatsAppResult?>(null) }
    var pendingPackage by remember { mutableStateOf<String?>(null) }
    val whatsApps = remember { WhatsAppIntents.installedWhatsApps(context) }
    val room = WhatsAppLimits.PACK_MAX_STICKERS - pack.stickers.size

    val picker = rememberLauncherForActivityResult(PickMultipleVisualMedia(maxItems = WhatsAppLimits.PACK_MAX_STICKERS)) { uris ->
        val sources = uris.take(room).map { it to (context.contentResolver.getType(it) ?: "image/*") }
        vm.addStickers(pack.identifier, sources)
    }
    val waLauncher = rememberLauncherForActivityResult(StartActivityForResult()) { result ->
        val error = WhatsAppIntents.validationError(result.data)
        Log.i(TAG, "${pack.identifier} → resultCode=${result.resultCode} validation_error=$error")
        waResult = when {
            result.resultCode == Activity.RESULT_OK -> AddToWhatsAppResult.Added
            error != null -> AddToWhatsAppResult.Rejected(error)
            // WhatsApp zaten ekli pakette de RESULT_CANCELED döndürüyor (M2 fizibilite)
            pendingPackage?.let { WhatsAppIntents.isPackAdded(context, pack.identifier, it) } == true -> AddToWhatsAppResult.AlreadyAdded
            else -> AddToWhatsAppResult.Cancelled
        }
        pendingPackage = null
    }

    fun addToWhatsApp(pkg: String) {
        val problems = vm.repository.validate(pack.identifier)
        if (problems.isNotEmpty()) {
            waResult = AddToWhatsAppResult.Invalid(problems)
            return
        }
        if (WhatsAppIntents.isPackAdded(context, pack.identifier, pkg) == true) {
            waResult = AddToWhatsAppResult.AlreadyAdded
            return
        }
        pendingPackage = pkg
        waLauncher.launch(WhatsAppIntents.addPack(context, pack.identifier, pack.name, pkg))
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier.fillMaxSize().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text("‹ " + stringResource(R.string.back)) }
                    Text(
                        pack.name, style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).clickable { renaming = true },
                    )
                    TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.pack_delete)) }
                }
                Text(
                    stringResource(R.string.pack_rules, pack.stickers.size, WhatsAppLimits.PACK_MIN_STICKERS, WhatsAppLimits.PACK_MAX_STICKERS),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = room > 0 && addState !is AddState.Working,
                        onClick = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageAndVideo)) },
                    ) { Text(stringResource(R.string.pack_add_sticker)) }
                }
                AddStatusCard(addState, onDismiss = { vm.dismissAddState(pack.identifier) })
            }
        }

        items(pack.stickers, key = { it.imageFile }) { sticker ->
            val bytes = remember(sticker.imageFile) { vm.repository.stickerBytes(pack.identifier, sticker.imageFile) }
            Box(
                Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { editing = sticker },
            ) {
                StickerImage(bytes, Modifier.fillMaxSize().padding(4.dp))
                Text(
                    sticker.emojis.joinToString(""),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                )
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (whatsApps.isEmpty()) {
                    Text(stringResource(R.string.pack_no_whatsapp), color = MaterialTheme.colorScheme.error)
                }
                for (pkg in whatsApps) {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = addState !is AddState.Working && pendingPackage == null,
                        onClick = { addToWhatsApp(pkg) },
                    ) {
                        Text(stringResource(if (pkg == WhatsAppLimits.WHATSAPP_BUSINESS_PACKAGE) R.string.pack_add_business else R.string.pack_add_whatsapp))
                    }
                }
                waResult?.let { WhatsAppResultCard(it) }
            }
        }
    }

    editing?.let { sticker ->
        StickerDialog(
            sticker = sticker,
            onDismiss = { editing = null },
            onEmojis = { vm.setEmojis(pack.identifier, sticker.imageFile, it); editing = null },
            onDelete = { vm.removeSticker(pack.identifier, sticker.imageFile); editing = null },
        )
    }
    if (renaming) {
        NameDialog(stringResource(R.string.pack_rename), pack.name, onDismiss = { renaming = false }) {
            vm.rename(pack.identifier, it); renaming = false
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.pack_delete_title)) },
            text = { Text(stringResource(R.string.pack_delete_text)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deletePack(pack.identifier); onBack() }) {
                    Text(stringResource(R.string.pack_delete))
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun AddStatusCard(state: AddState, onDismiss: () -> Unit) {
    when (state) {
        AddState.Idle -> Unit
        is AddState.Working -> Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text(stringResource(R.string.pack_working, state.pending), style = MaterialTheme.typography.bodyMedium)
            }
        }
        is AddState.Done -> Card(Modifier.fillMaxWidth().clickable(onClick = onDismiss)) {
            Text(describe(state.sticker), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        }
        is AddState.Failed -> Card(
            Modifier.fillMaxWidth().clickable(onClick = onDismiss),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Text(stringResource(R.string.pack_add_failed, state.message), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun describe(s: CreatedSticker): String {
    val mode = when (s.mode) {
        EncodeMode.LOSSLESS -> stringResource(R.string.mode_lossless)
        EncodeMode.NEAR_LOSSLESS -> stringResource(R.string.mode_near_lossless, s.quality)
        EncodeMode.LOSSY -> stringResource(R.string.mode_lossy, s.quality)
    }
    val size = String.format(Locale.ROOT, "%.0f KB", s.webp.size / 1024.0)
    val ssim = String.format(Locale.ROOT, "%.3f", s.ssimMean)
    val seconds = String.format(Locale.ROOT, "%.1f", s.totalMs / 1000.0)
    return buildString {
        append(stringResource(R.string.pack_added_summary, mode, size, ssim, seconds))
        if (s.fromVideo) {
            append('\n').append(stringResource(R.string.pack_added_video, s.frameCount, String.format(Locale.ROOT, "%.1f", s.fps)))
            if (s.framesReduced) append(' ').append(stringResource(R.string.pack_added_reduced))
            if (s.trimmed) append(' ').append(stringResource(R.string.pack_added_trimmed))
        }
    }
}

@Composable
private fun WhatsAppResultCard(result: AddToWhatsAppResult) {
    val (text, isError) = when (result) {
        AddToWhatsAppResult.Added -> stringResource(R.string.wa_added) to false
        AddToWhatsAppResult.AlreadyAdded -> stringResource(R.string.wa_already_added) to false
        AddToWhatsAppResult.Cancelled -> stringResource(R.string.wa_cancelled) to false
        is AddToWhatsAppResult.Rejected -> stringResource(R.string.wa_rejected, result.reason) to true
        is AddToWhatsAppResult.Invalid -> (stringResource(R.string.wa_invalid) + "\n• " + result.problems.joinToString("\n• ")) to true
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = if (isError) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer) else CardDefaults.cardColors(),
    ) { Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) }
}

/** Sık kullanılan emojiler — WhatsApp sticker araması için her stickerda 1-3 tane gerekir. */
private val EMOJIS = listOf(
    "😀", "😂", "🤣", "😍", "🥰", "😘", "😎", "🤔", "😴", "😭", "😡", "😱",
    "👍", "👎", "👏", "🙏", "💪", "🎉", "❤️", "💔", "🔥", "✨", "😅", "🙃",
)

@Composable
private fun StickerDialog(sticker: StickerEntry, onDismiss: () -> Unit, onEmojis: (List<String>) -> Unit, onDelete: () -> Unit) {
    var selected by remember { mutableStateOf(sticker.emojis) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sticker_emojis_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.sticker_emojis_hint), style = MaterialTheme.typography.bodySmall)
                for (row in EMOJIS.chunked(6)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (e in row) {
                            val isOn = e in selected
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .then(if (isOn) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier)
                                    .clickable {
                                        selected = when {
                                            isOn -> selected - e
                                            selected.size < WhatsAppLimits.MAX_EMOJIS_PER_STICKER -> selected + e
                                            else -> selected.drop(1) + e
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) { Text(e, style = MaterialTheme.typography.titleLarge) }
                        }
                    }
                }
                OutlinedButton(onClick = onDelete) { Text(stringResource(R.string.sticker_delete)) }
            }
        },
        confirmButton = { TextButton(enabled = selected.isNotEmpty(), onClick = { onEmojis(selected) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
