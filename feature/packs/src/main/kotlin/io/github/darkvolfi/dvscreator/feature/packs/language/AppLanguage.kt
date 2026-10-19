package io.github.darkvolfi.dvscreator.feature.packs.language

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import java.util.Locale

/**
 * Uygulamanın dili. Varsayılan: cihazın dili (desteklenen dillerden biriyse otomatik o dil, değilse İngilizce).
 * Kullanıcı Ayarlar'dan başka bir dil seçebilir:
 * - Android 13+: sistemin "uygulama dili" ayarı ([LocaleManager]) — aynı seçim telefonun Ayarlar → Uygulamalar
 *   → DVScreator → Dil ekranında da görünür (app/build.gradle.kts: generateLocaleConfig).
 * - Android 12 ve öncesi: seçim burada saklanır; Activity / Service bağlamları [wrap] ile, uygulama bağlamı
 *   (ViewModel'deki mesajlar) [applyToApp] ile o dile çevrilir.
 */
object AppLanguage {

    /** Desteklenen diller (res/values-xx klasörleriyle aynı) ve her birinin kendi dilindeki adı. */
    val SUPPORTED: List<Pair<String, String>> = listOf(
        "en" to "English",
        "tr" to "Türkçe",
        "es" to "Español",
        "pt" to "Português",
        "de" to "Deutsch",
        "fr" to "Français",
        "it" to "Italiano",
        "ru" to "Русский",
        "ar" to "العربية",
        "id" to "Bahasa Indonesia",
        "hi" to "हिन्दी",
    )

    /** Seçili dilin etiketi ([SUPPORTED]'daki); null = cihazın dili. */
    fun current(context: Context): String? {
        val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            localeManager(context).applicationLocales.takeIf { !it.isEmpty }?.get(0)
        } else {
            prefs(context).getString(KEY, null)?.let(Locale::forLanguageTag)
        } ?: return null
        // "in" / "id" (Endonezce) gibi eski ve yeni kodlar Locale üzerinden eşleşir; bölge (pt-BR) yok sayılır
        return SUPPORTED.firstOrNull { Locale.forLanguageTag(it.first).language == locale.language }?.first
    }

    /** Dili değiştirir ve ekranı yeni dille yeniden çizer. [tag] null ise cihazın diline döner. */
    fun set(activity: Activity, tag: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Sistem Activity'yi kendisi yeniden oluşturur
            localeManager(activity).applicationLocales = if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            prefs(activity).edit().apply { if (tag == null) remove(KEY) else putString(KEY, tag) }.commit()
            applyToApp(activity.applicationContext)
            activity.recreate()
        }
    }

    /** Android 12 ve öncesi: Activity / Service'in `attachBaseContext`'inde. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val locales = chosenLocales(base) ?: return base
        val config = Configuration(base.resources.configuration).apply { setLocales(locales) }
        return base.createConfigurationContext(config)
    }

    /**
     * Android 12 ve öncesi: uygulama bağlamının kaynaklarını seçili dile çevirir (ViewModel ve depo mesajları).
     * Application.onCreate ve onConfigurationChanged'de çağrılır: sistem yapılandırması değişince sıfırlanır.
     */
    @Suppress("DEPRECATION")   // updateConfiguration: Android 12 ve öncesinde uygulama bağlamı için tek yol
    fun applyToApp(app: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        val locales = chosenLocales(app) ?: Resources.getSystem().configuration.locales
        Locale.setDefault(locales[0])
        val res = app.resources
        if (res.configuration.locales == locales) return
        val config = Configuration(res.configuration).apply { setLocales(locales) }
        res.updateConfiguration(config, res.displayMetrics)
    }

    private fun chosenLocales(context: Context): LocaleList? =
        prefs(context).getString(KEY, null)?.let { LocaleList.forLanguageTags(it) }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun localeManager(context: Context) = context.getSystemService(LocaleManager::class.java)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "app_language"
    private const val KEY = "tag"
}
