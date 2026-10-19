package io.github.darkvolfi.dvscreator

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Resources
import androidx.core.net.toUri
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import io.github.darkvolfi.dvscreator.feature.packs.language.AppLanguage
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.darkvolfi.dvscreator.feature.packs.keyboard.KeyboardCard
import io.github.darkvolfi.dvscreator.feature.packs.R as PacksR

/** Uygulamanın kullandığı açık kaynak bileşenler ve lisansları (Ayarlar → Hakkında). */
private val COMPONENTS = listOf(
    "libwebp" to "BSD-3-Clause · Google",
    "ONNX Runtime" to "MIT · Microsoft",
    ISNET_MODEL to "Apache-2.0",
    "Glide GIF decoder" to "BSD, MIT, Apache-2.0",
    "Jetpack Compose, AndroidX, Room" to "Apache-2.0",
    "Kotlin, kotlinx.coroutines, kotlinx.serialization" to "Apache-2.0",
    "Koin" to "Apache-2.0",
    "Material Icons" to "Apache-2.0",
)

private const val SOURCE_URL = "https://github.com/Darkvolfi/DVScreator"
private const val SUPPORT_EMAIL = "support@darkvolfi.com"

/** Bileşen listesinde adı çevrilen tek satır (bkz. R.string.component_isnet). */
private const val ISNET_MODEL = "isnet"

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenLicense: () -> Unit, onOpenLab: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { TitleBar(stringResource(PacksR.string.settings_title), onBack) }

        item { SectionTitle(stringResource(R.string.settings_language)) }
        item { LanguageCard() }

        item { SectionTitle(stringResource(R.string.settings_keyboard)) }
        item { KeyboardCard() }

        item { SectionTitle(stringResource(R.string.settings_privacy)) }
        item { InfoCard { Text(stringResource(R.string.settings_privacy_text), style = MaterialTheme.typography.bodyMedium) } }

        item { SectionTitle(stringResource(R.string.settings_about)) }
        item {
            InfoCard {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.settings_version, version), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.settings_license_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                Text(stringResource(R.string.settings_license_text), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    FilledTonalButton(onClick = onOpenLicense) { Text(stringResource(R.string.settings_license_read)) }
                    OutlinedButton(onClick = { openUrl(context, SOURCE_URL) }) { Text(stringResource(R.string.settings_source)) }
                }
            }
        }

        item { SectionTitle(stringResource(R.string.settings_support)) }
        item {
            InfoCard {
                Text(stringResource(R.string.settings_support_text), style = MaterialTheme.typography.bodyMedium)
                // Seçilebilir: e-posta uygulaması yoksa adres kopyalanabilsin
                SelectionContainer { Text(SUPPORT_EMAIL, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
                FilledTonalButton(onClick = { sendEmail(context, SUPPORT_EMAIL, "DVScreator $version") }, modifier = Modifier.padding(top = 4.dp)) {
                    Text(stringResource(R.string.settings_support_send))
                }
            }
        }

        item { SectionTitle(stringResource(R.string.settings_components)) }
        item {
            InfoCard {
                COMPONENTS.forEachIndexed { i, (name, license) ->
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(if (name == ISNET_MODEL) stringResource(R.string.component_isnet) else name, style = MaterialTheme.typography.bodyMedium)
                    Text(license, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item { SectionTitle(stringResource(R.string.settings_developer)) }
        item {
            Card(
                onClick = onOpenLab,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.lab_title), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.settings_lab_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(painterResource(PacksR.drawable.ic_chevron_right), contentDescription = null)
                }
            }
        }
    }
}

/** GPL-3.0 metni (uygulamanın içinde, internetsiz okunur). */
@Composable
fun LicenseScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val text = remember { runCatching { context.assets.open("LICENSE.txt").bufferedReader().use { it.readText() } }.getOrDefault("") }
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TitleBar(stringResource(R.string.settings_license_title), onBack)
        // Lisans metni sabit satırlarla yazılmış: kaydırılabilir, satırlar bozulmadan
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
        )
    }
}

@Composable
internal fun TitleBar(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(painterResource(PacksR.drawable.ic_arrow_back), contentDescription = stringResource(PacksR.string.back))
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

@Composable
private fun InfoCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

/** Uygulama dili: telefonun dili (varsayılan) ya da desteklenen dillerden biri. */
@Composable
private fun LanguageCard() {
    val context = LocalContext.current
    val current = remember { AppLanguage.current(context) }
    var choosing by remember { mutableStateOf(false) }
    val systemLanguage = remember {
        Resources.getSystem().configuration.locales[0].let { it.getDisplayLanguage(it).replaceFirstChar { c -> c.titlecase(it) } }
    }
    val systemLabel = stringResource(R.string.settings_language_system_detail, systemLanguage)
    val label = AppLanguage.SUPPORTED.firstOrNull { it.first == current }?.second ?: systemLabel

    Card(
        onClick = { choosing = true },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(painterResource(PacksR.drawable.ic_chevron_right), contentDescription = null)
        }
    }

    if (choosing) {
        fun choose(tag: String?) {
            choosing = false
            if (tag != current) context.findActivity()?.let { AppLanguage.set(it, tag) }
        }
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(stringResource(R.string.settings_language)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    LanguageOption(systemLabel, selected = current == null) { choose(null) }
                    for ((tag, name) in AppLanguage.SUPPORTED) LanguageOption(name, selected = current == tag) { choose(tag) }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(PacksR.string.cancel)) } },
        )
    }
}

@Composable
private fun LanguageOption(name: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick, role = Role.RadioButton).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** E-posta uygulamasını adres ve konu dolu olarak açar (uygulamanın kendisi hiçbir şey göndermez). */
private fun sendEmail(context: Context, address: String, subject: String) {
    val intent = Intent(Intent.ACTION_SENDTO, "mailto:".toUri()).apply {
        putExtra(Intent.EXTRA_EMAIL, arrayOf(address))
        putExtra(Intent.EXTRA_SUBJECT, subject)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // E-posta uygulaması yok: adres ekranda yazılı, kullanıcı kopyalayabilir
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (_: ActivityNotFoundException) {
        // Tarayıcı yoksa yapılacak bir şey yok
    }
}
