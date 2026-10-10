package io.github.darkvolfi.dvscreator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.darkvolfi.dvscreator.feature.packs.PacksRoot
import io.github.darkvolfi.dvscreator.ui.theme.DVScreatorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DVScreatorTheme {
                // Kalite laboratuvarı geçici bir geliştirici sekmesidir; M4'te kaldırılacak
                var tab by rememberSaveable { mutableIntStateOf(0) }
                Scaffold { padding ->
                    Column(Modifier.padding(padding)) {
                        PrimaryTabRow(selectedTabIndex = tab) {
                            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.tab_packs)) })
                            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.tab_lab)) })
                        }
                        when (tab) {
                            0 -> PacksRoot()
                            else -> QualityLabScreen()
                        }
                    }
                }
            }
        }
    }
}
