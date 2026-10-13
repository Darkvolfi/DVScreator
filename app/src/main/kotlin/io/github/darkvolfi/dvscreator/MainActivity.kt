package io.github.darkvolfi.dvscreator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.darkvolfi.dvscreator.feature.packs.PacksRoot
import io.github.darkvolfi.dvscreator.ui.theme.DVScreatorTheme

/** Üst düzey ekranlar: ana ekran (paketler) ve sağ üstteki ayarlar düğmesinden açılanlar. */
private enum class Screen { HOME, SETTINGS, LICENSE, LAB }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DVScreatorTheme {
                var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
                Scaffold { padding ->
                    Box(Modifier.padding(padding)) {
                        when (screen) {
                            Screen.HOME -> PacksRoot(onOpenSettings = { screen = Screen.SETTINGS })
                            Screen.SETTINGS -> {
                                BackHandler { screen = Screen.HOME }
                                SettingsScreen(
                                    onBack = { screen = Screen.HOME },
                                    onOpenLicense = { screen = Screen.LICENSE },
                                    onOpenLab = { screen = Screen.LAB },
                                )
                            }
                            Screen.LICENSE -> {
                                BackHandler { screen = Screen.SETTINGS }
                                LicenseScreen(onBack = { screen = Screen.SETTINGS })
                            }
                            Screen.LAB -> {
                                BackHandler { screen = Screen.SETTINGS }
                                QualityLabScreen(onBack = { screen = Screen.SETTINGS })
                            }
                        }
                    }
                }
            }
        }
    }
}
