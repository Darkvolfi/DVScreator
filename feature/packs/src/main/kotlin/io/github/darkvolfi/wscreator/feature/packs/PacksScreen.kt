package io.github.darkvolfi.wscreator.feature.packs

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.darkvolfi.wscreator.core.whatsapp.PackManifest
import io.github.darkvolfi.wscreator.core.whatsapp.WhatsAppLimits

/** "Paketlerim" kökü: liste ↔ paket detayı. */
@Composable
fun PacksRoot(modifier: Modifier = Modifier, vm: PacksViewModel = viewModel()) {
    var openPackId by rememberSaveable { mutableStateOf<String?>(null) }
    val packs by vm.packs.collectAsState()
    val open = openPackId?.let { id -> packs.firstOrNull { it.identifier == id } }
    if (open != null) {
        BackHandler { openPackId = null }
        PackDetailScreen(open, vm, onBack = { openPackId = null }, modifier = modifier)
    } else {
        PackListScreen(packs, vm, onOpen = { openPackId = it }, modifier = modifier)
    }
}

@Composable
private fun PackListScreen(packs: List<PackManifest>, vm: PacksViewModel, onOpen: (String) -> Unit, modifier: Modifier) {
    var creating by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.packs_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Button(onClick = { creating = true }) { Text(stringResource(R.string.packs_new)) }
        }
        if (packs.isEmpty()) {
            Text(stringResource(R.string.packs_empty), style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(packs, key = { it.identifier }) { pack ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(pack.identifier) }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        TrayIcon(vm, pack)
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(pack.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.packs_count, pack.stickers.size, WhatsAppLimits.PACK_MAX_STICKERS),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
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

@Composable
private fun TrayIcon(vm: PacksViewModel, pack: PackManifest) {
    val bitmap = remember(pack.identifier, pack.imageDataVersion) {
        vm.repository.store().readFile(pack.identifier, pack.trayImageFile)
            ?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap, contentDescription = null, modifier = Modifier.size(56.dp))
        else Text("📦", style = MaterialTheme.typography.headlineMedium)
    }
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
