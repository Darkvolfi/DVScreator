package io.github.darkvolfi.dvscreator.feature.packs

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.darkvolfi.dvscreator.core.whatsapp.PackManifest
import io.github.darkvolfi.dvscreator.core.whatsapp.WhatsAppLimits
import io.github.darkvolfi.dvscreator.feature.packs.keyboard.KeyboardCard
import io.github.darkvolfi.dvscreator.feature.packs.keyboard.isStickerKeyboardEnabled

/** Ana ekran kökü: paket listesi ↔ paket detayı ↔ tek sticker. [onOpenSettings]: sağ üstteki ayarlar düğmesi. */
@Composable
fun PacksRoot(onOpenSettings: () -> Unit, modifier: Modifier = Modifier, vm: PacksViewModel = viewModel()) {
    var openPackId by rememberSaveable { mutableStateOf<String?>(null) }
    val packs by vm.packs.collectAsState()
    val open = openPackId?.let { id -> packs.firstOrNull { it.identifier == id } }
    val quick by vm.quick.collectAsState()
    if (quick != QuickState.Idle) {
        QuickStickerScreen(quick, vm, modifier)
    } else if (open != null) {
        BackHandler { openPackId = null }
        PackDetailScreen(open, vm, onBack = { openPackId = null }, modifier = modifier)
    } else {
        PackListScreen(packs, vm, onOpen = { openPackId = it }, onOpenSettings = onOpenSettings, modifier = modifier)
    }
}

@Composable
private fun PackListScreen(
    packs: List<PackManifest>,
    vm: PacksViewModel,
    onOpen: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier,
) {
    var creating by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val quickPicker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) vm.onQuickPicked(StickerSource(uri, context.contentResolver.getType(uri) ?: "image/*"))
    }
    // Klavye kurulmuşsa ana ekranda yer kaplamasın (ayarlarda her zaman var)
    val keyboardReady = remember { isStickerKeyboardEnabled(context) }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.home_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onOpenSettings) {
                    Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.settings_title))
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionCard(
                    icon = painterResource(R.drawable.ic_send),
                    title = stringResource(R.string.quick_start),
                    subtitle = stringResource(R.string.home_quick_subtitle),
                    container = MaterialTheme.colorScheme.primaryContainer,
                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                    onClick = { quickPicker.launch(PickVisualMediaRequest(PickVisualMedia.ImageAndVideo)) },
                    modifier = Modifier.weight(1f),
                )
                ActionCard(
                    icon = painterResource(R.drawable.ic_add),
                    title = stringResource(R.string.packs_new),
                    subtitle = stringResource(R.string.home_new_pack_subtitle),
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                    onClick = { creating = true },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (!keyboardReady) item { KeyboardCard() }
        item {
            Text(
                stringResource(R.string.packs_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (packs.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.packs_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // En yeni paket üstte
        items(packs.asReversed(), key = { it.identifier }) { pack -> PackRow(vm, pack, onClick = { onOpen(pack.identifier) }) }
    }
    if (creating) {
        NameDialog(
            title = stringResource(R.string.packs_new),
            initial = "",
            onDismiss = { creating = false },
            onConfirm = { name -> creating = false; vm.createPack(name, onOpen) },
        )
    }
}

/** Ana ekrandaki büyük eylem kartı. */
@Composable
private fun ActionCard(
    icon: Painter,
    title: String,
    subtitle: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.height(140.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(content.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp)) }
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PackRow(vm: PacksViewModel, pack: PackManifest, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) { TrayIcon(vm, pack) }
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(pack.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.packs_count, pack.stickers.size, WhatsAppLimits.PACK_MAX_STICKERS),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(
                    progress = { pack.stickers.size / WhatsAppLimits.PACK_MAX_STICKERS.toFloat() },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    drawStopIndicator = {},
                )
            }
            Icon(
                painterResource(R.drawable.ic_chevron_right), contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun TrayIcon(vm: PacksViewModel, pack: PackManifest) {
    val bitmap = remember(pack.identifier, pack.imageDataVersion) {
        vm.repository.store().readFile(pack.identifier, pack.trayImageFile)
            ?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
    if (bitmap != null) Image(bitmap, contentDescription = null, modifier = Modifier.size(52.dp))
    else Text("📦", style = MaterialTheme.typography.headlineMedium)
}

@Composable
internal fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(WhatsAppLimits.MAX_TEXT_LENGTH) },
                label = { Text(stringResource(R.string.packs_name)) },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
