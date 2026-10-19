package io.github.darkvolfi.dvscreator

import android.app.Application
import android.content.res.Configuration
import io.github.darkvolfi.dvscreator.feature.packs.language.AppLanguage
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import java.io.File

class DVScreatorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLanguage.applyToApp(this)
        startKoin {
            androidContext(this@DVScreatorApp)
            modules(appModule)
        }
        removeFeasibilityTestPacks()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Android 12 ve öncesi: sistem yapılandırması değişince (döndürme, sistem dili) seçili uygulama dili sıfırlanmasın
        AppLanguage.applyToApp(this)
    }

    /** M2 fizibilite testinin (agents/raporlar/m2-whatsapp-fizibilite.md) geride kalan test paketleri. */
    private fun removeFeasibilityTestPacks() {
        File(filesDir, "wa_packs").listFiles { f -> f.name.startsWith("wsc_probe_") }?.forEach { it.deleteRecursively() }
        File(filesDir, "probe_results.txt").delete()
    }
}
