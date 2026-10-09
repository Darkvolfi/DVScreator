package io.github.darkvolfi.wscreator

import android.app.Application
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class WScreatorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@WScreatorApp)
            modules(appModule)
        }
    }
}
