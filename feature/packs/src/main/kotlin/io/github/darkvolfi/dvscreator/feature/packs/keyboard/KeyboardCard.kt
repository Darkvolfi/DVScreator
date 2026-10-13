package io.github.darkvolfi.dvscreator.feature.packs.keyboard

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.darkvolfi.dvscreator.feature.packs.R

/** Sticker klavyesinin kurulumu: ayarlardan açma ve seçme (ikisi de kullanıcının kendi onayıyla). */
@Composable
fun KeyboardCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Ayarlardan dönünce durumu yeniden oku
    var resumes by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumes++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val enabled = remember(resumes) { isStickerKeyboardEnabled(context) }
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.keyboard_card_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(if (enabled) R.string.keyboard_ready else R.string.keyboard_card_text),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!enabled) {
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text(stringResource(R.string.keyboard_enable)) }
                }
                OutlinedButton(enabled = enabled, onClick = { imm(context).showInputMethodPicker() }) {
                    Text(stringResource(R.string.keyboard_pick))
                }
            }
        }
    }
}

private fun imm(context: Context) = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

/** Sticker klavyesi sistem ayarlarında açık mı? */
fun isStickerKeyboardEnabled(context: Context): Boolean =
    imm(context).enabledInputMethodList.any { it.packageName == context.packageName }
