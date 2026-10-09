package io.github.darkvolfi.wscreator.probe

import android.app.Activity
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.darkvolfi.wscreator.R
import io.github.darkvolfi.wscreator.core.whatsapp.PackStore
import io.github.darkvolfi.wscreator.core.whatsapp.WhatsAppIntents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "WSC_WA"

/** GEÇİCİ fizibilite ekranı: test paketlerini üret, tek tek WhatsApp'a eklemeyi dene, sonucu gör. */
@Composable
fun ProbeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { PackStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<String?>(null) }
    // Daha önce üretilip diske yazılmış paketler yeniden başlatmadan sonra da eklenebilsin
    val summaries = remember {
        mutableStateMapOf<String, String>().apply { store.packs().forEach { put(it.identifier, "✔") } }
    }
    val results = remember { mutableStateMapOf<String, String>() }
    // Sonuçlar uygulama kapansa da kaybolmasın: `adb shell run-as <paket> cat files/probe_results.txt`
    val resultsFile = remember { java.io.File(context.filesDir, "probe_results.txt") }
    var pending by remember { mutableStateOf<String?>(null) }
    val whatsApps = remember { WhatsAppIntents.installedWhatsApps(context) }

    val okText = stringResource(R.string.probe_result_added)
    val cancelText = stringResource(R.string.probe_result_cancelled)
    val launcher = rememberLauncherForActivityResult(StartActivityForResult()) { result ->
        val id = pending ?: return@rememberLauncherForActivityResult
        val error = WhatsAppIntents.validationError(result.data)
        val text = when {
            result.resultCode == Activity.RESULT_OK -> okText
            error != null -> "❌ $error"
            else -> cancelText
        }
        results[id] = text
        val line = "$id → resultCode=${result.resultCode} validation_error=$error"
        Log.i(TAG, line)
        resultsFile.appendText("${System.currentTimeMillis()} $line\n")
        pending = null
    }

    Column(
        modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.probe_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.probe_subtitle), style = MaterialTheme.typography.bodyMedium)
        if (whatsApps.isEmpty()) {
            Text(stringResource(R.string.probe_no_whatsapp), color = MaterialTheme.colorScheme.error)
        }
        Button(
            enabled = progress == null,
            onClick = {
                results.clear()
                scope.launch {
                    val generated = withContext(Dispatchers.Default) {
                        runCatching { WhatsAppProbe.generateAll(store) { title -> progress = title } }
                    }
                    generated.onSuccess { summaries.clear(); summaries.putAll(it); it.forEach { (id, s) -> Log.i(TAG, "$id üretildi: $s") } }
                        .onFailure { e -> Log.e(TAG, "üretim hatası", e); results["_"] = e.toString() }
                    progress = null
                }
            },
        ) { Text(progress?.let { stringResource(R.string.probe_generating, it) } ?: stringResource(R.string.probe_generate)) }
        results["_"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        for (spec in WhatsAppProbe.specs) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(spec.title, style = MaterialTheme.typography.titleMedium)
                    Text(spec.purpose, style = MaterialTheme.typography.bodySmall)
                    summaries[spec.id]?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        for (pkg in whatsApps) {
                            OutlinedButton(
                                enabled = summaries.containsKey(spec.id) && pending == null,
                                onClick = {
                                    pending = spec.id
                                    Log.i(TAG, "${spec.id} → $pkg'ye ekleniyor")
                                    launcher.launch(WhatsAppIntents.addPack(context, spec.id, "WSC Test ${spec.title}", pkg))
                                },
                            ) { Text(if (pkg.endsWith("w4b")) stringResource(R.string.probe_add_business) else stringResource(R.string.probe_add)) }
                        }
                    }
                    results[spec.id]?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}
