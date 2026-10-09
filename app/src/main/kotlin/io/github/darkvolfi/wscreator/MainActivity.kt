package io.github.darkvolfi.wscreator

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
import io.github.darkvolfi.wscreator.probe.ProbeScreen
import io.github.darkvolfi.wscreator.ui.theme.WScreatorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WScreatorTheme {
                // GEÇİCİ geliştirici sekmeleri — M4'te paket listesi ekranıyla değiştirilecek
                var tab by rememberSaveable { mutableIntStateOf(0) }
                Scaffold { padding ->
                    Column(Modifier.padding(padding)) {
                        PrimaryTabRow(selectedTabIndex = tab) {
                            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.tab_lab)) })
                            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.tab_probe)) })
                        }
                        when (tab) {
                            0 -> QualityLabScreen()
                            else -> ProbeScreen()
                        }
                    }
                }
            }
        }
    }
}
