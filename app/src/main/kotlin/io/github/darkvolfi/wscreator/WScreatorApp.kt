package io.github.darkvolfi.wscreator

import android.app.Application
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import java.io.File

class WScreatorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@WScreatorApp)
            modules(appModule)
        }
        removeFeasibilityTestPacks()
    }

    /** M2 fizibilite testinin (agents/raporlar/m2-whatsapp-fizibilite.md) geride kalan test paketleri. */
    private fun removeFeasibilityTestPacks() {
        File(filesDir, "wa_packs").listFiles { f -> f.name.startsWith("wsc_probe_") }?.forEach { it.deleteRecursively() }
        File(filesDir, "probe_results.txt").delete()
    }
}
