package io.github.darkvolfi.dvscreator.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Açık / koyu: telefona göre ya da sabit. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Uygulamanın rengi. [PHONE]: Android 12+ telefonun duvar kâğıdı renkleri (eskiden tek seçenekti).
 * Diğerleri sabit paletler: [light] ve [dark] (ana renk, üstündeki yazı, kap, kabın yazısı, ikincil kap).
 */
enum class ThemeColor(val light: List<Long>, val dark: List<Long>) {
    PHONE(GREEN_LIGHT, GREEN_DARK),
    GREEN(GREEN_LIGHT, GREEN_DARK),
    BLUE(listOf(0xFF1F5FBF, 0xFFFFFFFF, 0xFFD8E2FF, 0xFF001A43, 0xFFDAE2F9), listOf(0xFFADC6FF, 0xFF002E69, 0xFF004494, 0xFFD8E2FF, 0xFF3E4759)),
    PURPLE(listOf(0xFF6B4FA0, 0xFFFFFFFF, 0xFFEADDFF, 0xFF25005A, 0xFFE8DEF8), listOf(0xFFD0BCFF, 0xFF3B1E6F, 0xFF523787, 0xFFEADDFF, 0xFF4A4458)),
    ORANGE(listOf(0xFFA04A00, 0xFFFFFFFF, 0xFFFFDBC8, 0xFF341100, 0xFFFFDCC6), listOf(0xFFFFB68A, 0xFF552100, 0xFF793100, 0xFFFFDBC8, 0xFF5A4236)),
    RED(listOf(0xFFB3261E, 0xFFFFFFFF, 0xFFFFDAD5, 0xFF410001, 0xFFF7DCD8), listOf(0xFFFFB4A9, 0xFF690002, 0xFF930A0A, 0xFFFFDAD5, 0xFF5D3F3B)),
    ;

    /** Ayarlarda gösterilen örnek renk. */
    val swatch: Color get() = Color(light[0])
}

private val GREEN_LIGHT = listOf(0xFF1B8A5A, 0xFFFFFFFF, 0xFFB6F2D0, 0xFF002113, 0xFFD0E8DA)
private val GREEN_DARK = listOf(0xFF6BDBA4, 0xFF003822, 0xFF005234, 0xFFB6F2D0, 0xFF354B3F)

/** Tema seçimi: Ayarlar'da değişir, hemen uygulanır ve saklanır. */
object ThemeSettings {
    var mode by mutableStateOf(ThemeMode.SYSTEM)
        private set
    var color by mutableStateOf(ThemeColor.PHONE)
        private set

    /** Telefon renkleri yalnızca Android 12+'da var; eskilerde [ThemeColor.PHONE] yeşil görünür. */
    val phoneColorsAvailable: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun load(context: Context) {
        val p = prefs(context)
        mode = runCatching { ThemeMode.valueOf(p.getString(KEY_MODE, null)!!) }.getOrDefault(ThemeMode.SYSTEM)
        color = runCatching { ThemeColor.valueOf(p.getString(KEY_COLOR, null)!!) }.getOrDefault(ThemeColor.PHONE)
    }

    fun setMode(context: Context, value: ThemeMode) {
        mode = value
        prefs(context).edit().putString(KEY_MODE, value.name).apply()
    }

    fun setColor(context: Context, value: ThemeColor) {
        color = value
        prefs(context).edit().putString(KEY_COLOR, value.name).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("theme", Context.MODE_PRIVATE)
    private const val KEY_MODE = "mode"
    private const val KEY_COLOR = "color"
}

private fun ColorScheme.withPalette(c: List<Long>) = copy(
    primary = Color(c[0]), onPrimary = Color(c[1]),
    primaryContainer = Color(c[2]), onPrimaryContainer = Color(c[3]),
    secondary = Color(c[0]), secondaryContainer = Color(c[4]), onSecondaryContainer = Color(c[3]),
)

/** Uygulama şu an koyu mu (Ayarlar'daki seçime göre). */
@Composable
fun isAppInDarkTheme(): Boolean = when (ThemeSettings.mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun DVScreatorTheme(content: @Composable () -> Unit) {
    val darkTheme = isAppInDarkTheme()
    val color = ThemeSettings.color
    val colors = if (color == ThemeColor.PHONE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (darkTheme) {
        darkColorScheme().withPalette(color.dark)
    } else {
        lightColorScheme().withPalette(color.light)
    }
    MaterialTheme(colorScheme = colors, content = content)
}
