package io.github.darkvolfi.dvscreator.feature.packs

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.darkvolfi.dvscreator.core.whatsapp.ReceivedSticker
import io.github.darkvolfi.dvscreator.core.whatsapp.ReceivedStickers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * WhatsApp'ta alınan stickerlardan seçip pakete ekleme (agents/04 — M4b).
 * İlk seferde Android'in klasör seçicisi doğrudan WhatsApp'ın sticker klasöründe açılır.
 * "Paketlere göre" görünümünde stickerlar geldikleri pakete göre gruplanır (EXIF'teki paket bilgisi).
 */
@Composable
internal fun ImportScreen(
    room: Int,
    onImport: (List<ReceivedSticker>) -> Unit,
    onImportAsNewPack: (name: String, stickers: List<ReceivedSticker>) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val received = remember { ReceivedStickers(context) }
    var folder by remember { mutableStateOf(received.folder) }
    var wrongFolder by remember { mutableStateOf(false) }
    var byPack by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(OpenDocumentTree()) { tree: Uri? ->
        if (tree != null) {
            wrongFolder = !received.saveFolder(tree)
            folder = received.folder
        }
    }
    var packsFolder by remember { mutableStateOf(received.packsFolder) }
    val packsPicker = rememberLauncherForActivityResult(OpenDocumentTree()) { tree: Uri? ->
        if (tree != null) {
            received.savePacksFolder(tree)
            packsFolder = received.packsFolder
        }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("‹ " + stringResource(R.string.back)) }
            Text(stringResource(R.string.import_title), style = MaterialTheme.typography.titleLarge)
        }

        if (folder == null) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.import_explain), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.import_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { picker.launch(received.initialFolderUri()) }) { Text(stringResource(R.string.import_grant)) }
            }
            return@Column
        }
        if (wrongFolder) {
            Text(stringResource(R.string.import_wrong_folder), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        val stickers by produceState<List<ReceivedSticker>?>(null, folder) {
            value = withContext(Dispatchers.IO) { runCatching { received.list() }.getOrDefault(emptyList()) }
        }
        val list = stickers
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        var selected by remember { mutableStateOf<List<ReceivedSticker>>(emptyList()) }
        fun toggle(item: ReceivedSticker) {
            selected = when {
                item in selected -> selected - item
                selected.size < room -> selected + item
                else -> selected
            }
        }

        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.import_selected, selected.size, room, list.size),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Button(enabled = selected.isNotEmpty(), onClick = { onImport(selected) }) { Text(stringResource(R.string.import_add)) }
        }
        Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !byPack, onClick = { byPack = false }, label = { Text(stringResource(R.string.import_view_all)) })
            FilterChip(selected = byPack, onClick = { byPack = true }, label = { Text(stringResource(R.string.import_view_packs)) })
        }
        if (list.isEmpty()) {
            Text(stringResource(R.string.import_empty), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { picker.launch(received.initialFolderUri()) }) { Text(stringResource(R.string.import_change_folder)) }
            return@Column
        }

        if (!byPack) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(96.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(list, key = { it.uri.toString() }) { item ->
                    StickerTile(received, item, selected.indexOf(item)) { toggle(item) }
                }
            }
            return@Column
        }

        // Paketlere göre: her dosyanın yalnızca sonu okunur (EXIF), paralel ve önbellekli
        var scanned by remember { mutableIntStateOf(0) }
        val groups by produceState<List<ImportGroup>?>(null, list, packsFolder) {
            value = withContext(Dispatchers.IO) {
                // Bütün olarak paylaşılan paketler önce: alınanlar klasöründe bunların yalnızca bir kısmı olur
                val shared = runCatching { received.sharedPacks() }.getOrDefault(emptyList())
                    .map { ImportGroup(it.info, it.stickers, full = true) }
                val sharedIds = shared.mapTo(HashSet()) { it.info?.id }
                val gate = Semaphore(8)
                val infos = coroutineScope {
                    list.map { s -> async { gate.withPermit { received.packOf(s).also { scanned++ } } } }.awaitAll()
                }
                shared + list.indices.groupBy { infos[it]?.id }
                    .filterKeys { it == null || it !in sharedIds }
                    .map { (_, idx) -> ImportGroup(infos[idx.first()], idx.map { list[it] }, full = false) }
                    .sortedWith(compareBy({ it.info == null }, { -it.members.size }))
            }
        }
        val g = groups
        if (g == null) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator()
                Text(stringResource(R.string.import_scanning, scanned, list.size), style = MaterialTheme.typography.bodySmall)
            }
            return@Column
        }
        val unnamed = stringResource(R.string.import_no_pack)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (packsFolder == null) {
                item(key = "shared-grant", span = { GridItemSpan(maxLineSpan) }) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.import_shared_title), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.import_shared_explain), style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { packsPicker.launch(received.initialPacksFolderUri()) }) { Text(stringResource(R.string.import_shared_grant)) }
                        }
                    }
                }
            }
            for ((info, members, full) in g) {
                val name = info?.name?.takeIf { it.isNotBlank() } ?: unnamed
                val groupKey = "${if (full) "f" else "r"}:${info?.id}"
                item(key = "h:$groupKey", span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.padding(top = 12.dp)) {
                        Text(
                            "$name · ${members.size}" + if (full) " · " + stringResource(R.string.import_shared_badge) else "",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        info?.publisher?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                val free = (room - selected.size).coerceAtLeast(0)
                                selected = selected + members.filter { it !in selected }.take(free)
                            }) { Text(stringResource(R.string.import_select_all)) }
                            // WhatsApp en az 3 sticker istiyor
                            if (info != null && members.size >= 3) {
                                OutlinedButton(onClick = { onImportAsNewPack(name, members) }) {
                                    Text(
                                        if (members.size > 30) stringResource(R.string.import_new_packs_split, (members.size + 29) / 30)
                                        else stringResource(R.string.import_new_pack),
                                    )
                                }
                            }
                        }
                    }
                }
                items(members, key = { "$groupKey:${it.uri}" }) { item ->
                    StickerTile(received, item, selected.indexOf(item)) { toggle(item) }
                }
            }
        }
    }
}

@Composable
private fun StickerTile(received: ReceivedStickers, item: ReceivedSticker, selectedIndex: Int, onClick: () -> Unit) {
    // Dosya yalnızca görünür olduğunda okunur (~2700 dosya)
    val bytes by produceState<ByteArray?>(null, item.uri) {
        value = withContext(Dispatchers.IO) { runCatching { received.read(item) }.getOrNull() }
    }
    val isSelected = selectedIndex >= 0
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else Modifier)
            .clickable(onClick = onClick),
    ) {
        StickerImage(bytes, Modifier.fillMaxSize().padding(4.dp))
        if (isSelected) {
            Text(
                "${selectedIndex + 1}",
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** "Paketlere göre" görünümünde bir grup; [full]: paylaşılan paket klasöründen gelen tam paket. */
private data class ImportGroup(val info: ReceivedStickers.PackInfo?, val members: List<ReceivedSticker>, val full: Boolean)
