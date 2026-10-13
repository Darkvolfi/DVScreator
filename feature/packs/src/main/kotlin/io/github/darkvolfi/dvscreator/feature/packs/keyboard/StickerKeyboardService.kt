package io.github.darkvolfi.dvscreator.feature.packs.keyboard

import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import io.github.darkvolfi.dvscreator.core.whatsapp.PackManifest
import io.github.darkvolfi.dvscreator.core.whatsapp.PackStore
import io.github.darkvolfi.dvscreator.core.whatsapp.StickerShare
import io.github.darkvolfi.dvscreator.feature.packs.R

/**
 * Sticker klavyesi: uygulamada yapılan stickerları paket eklemeden sohbete gönderir.
 *
 * WhatsApp'ın yazı kutusu klavyelerden `image/webp.wasticker` içeriği kabul eder ve onu sticker olarak
 * gönderir (Gboard stickerları da bu yolla gider). Paylaşım menüsü ise fotoğraf olarak gönderiyor.
 * Klavye yazı yazmaz ve hiçbir şey okumaz: yalnızca seçilen stickerı yazı kutusuna verir.
 */
class StickerKeyboardService : InputMethodService() {

    private lateinit var store: PackStore
    private val thumbs = object : LruCache<String, Bitmap>(THUMB_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private var grid: GridView? = null
    private var tabs: LinearLayout? = null
    private var hint: TextView? = null
    private var selected: String = RECENT
    private var bottomBar: View? = null
    private var abcButton: View? = null
    private var insetBottom = 0

    override fun onCreate() {
        super.onCreate()
        store = PackStore(this)
    }

    override fun onCreateInputView(): View {
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val bg = if (dark) 0xFF1B1B1F.toInt() else 0xFFF1F0F4.toInt()
        val fg = if (dark) Color.WHITE else Color.BLACK

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        // Üst şerit: paket sekmeleri
        tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tabs) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(TAB_DP)))

        hint = TextView(this).apply {
            setTextColor(fg)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
            visibility = View.GONE
        }
        // Sticker alanı sabit yükseklikte: boşken açıklama yazısı aynı yerde görünür
        val content = FrameLayout(this)
        content.addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        grid = GridView(this).apply {
            numColumns = COLUMNS
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            verticalSpacing = dp(4)
            horizontalSpacing = dp(4)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        content.addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(GRID_DP)))

        // Alt çubuk. Android 15+ klavye penceresi sistem şeridinin altına uzanır ve sistem kendi "klavye değiştir" /
        // "gizle" düğmelerini buraya çizer: çubuk o düğmelerin zemini olur. Sistem düğmesi yoksa kendi düğmemiz görünür.
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(if (dark) 0xFF26262B.toInt() else 0xFFE2E1E6.toInt())
            setPadding(dp(8), 0, dp(8), 0)
        }
        val abc = TextView(this).apply {
            text = getString(R.string.keyboard_abc)
            textSize = 16f
            setTextColor(fg)
            gravity = Gravity.CENTER
            setPadding(dp(20), 0, dp(20), 0)
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(if (dark) 0xFF3A3A40.toInt() else Color.WHITE) }
            contentDescription = getString(R.string.keyboard_switch_back)
            setOnClickListener { switchBack() }
        }
        bottom.addView(abc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)))
        root.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(BOTTOM_BAR_DP)))
        bottomBar = bottom
        abcButton = abc
        ViewCompat.setOnApplyWindowInsetsListener(bottom) { _, insets ->
            insetBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            fitBottomBar()
            insets
        }
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        bottomBar?.post { fitBottomBar() }
        // Paketler uygulamada değişmiş olabilir: her açılışta yeniden oku
        val packs = store.packs().filter { it.stickers.isNotEmpty() }
        if (selected != RECENT && packs.none { it.identifier == selected }) selected = RECENT
        if (selected == RECENT && recent().isEmpty()) packs.lastOrNull()?.let { selected = it.identifier }
        renderTabs(packs)
        renderGrid(packs)
    }

    private fun renderTabs(packs: List<PackManifest>) {
        val tabs = tabs ?: return
        tabs.removeAllViews()
        // Son gönderilenler sekmesi (simge kaynağı eklememek için emoji)
        tabs.addView(TextView(this).apply {
            text = "🕘"
            textSize = 20f
            gravity = Gravity.CENTER
            background = if (selected == RECENT) selectedBackground() else null
            contentDescription = getString(R.string.keyboard_recent)
            setOnClickListener { selected = RECENT; onStartInputView(currentInputEditorInfo, true) }
        }, LinearLayout.LayoutParams(dp(TAB_DP), dp(TAB_DP)))
        for (pack in packs.asReversed()) {
            tabs.addView(tab(selected == pack.identifier) {
                setImageBitmap(thumb(pack.identifier, pack.trayImageFile))
                contentDescription = pack.name
            }.also { it.setOnClickListener { selected = pack.identifier; onStartInputView(currentInputEditorInfo, true) } })
        }
    }

    private fun tab(isSelected: Boolean, setup: ImageView.() -> Unit): ImageView = ImageView(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(TAB_DP), dp(TAB_DP))
        setPadding(dp(8), dp(8), dp(8), dp(8))
        scaleType = ImageView.ScaleType.FIT_CENTER
        background = if (isSelected) selectedBackground() else null
        setup()
    }

    private fun selectedBackground() = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(0x33808080) }

    private fun renderGrid(packs: List<PackManifest>) {
        val items: List<Pair<String, String>> = if (selected == RECENT) recent()
        else packs.firstOrNull { it.identifier == selected }?.stickers?.map { selected to it.imageFile }.orEmpty()
        hint?.apply {
            visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            text = getString(if (packs.isEmpty()) R.string.keyboard_empty else R.string.keyboard_recent_empty)
        }
        grid?.adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(position: Int) = items[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val view = (convertView as? ImageView) ?: ImageView(this@StickerKeyboardService).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(CELL_DP))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                }
                val (packId, file) = items[position]
                view.setImageBitmap(thumb(packId, file))
                view.setOnClickListener { send(packId, file) }
                return view
            }
        }
    }

    /** Stickerın küçük önizlemesi (hareketlide ilk kare). */
    private fun thumb(packId: String, file: String): Bitmap? {
        val key = "$packId/$file"
        thumbs.get(key)?.let { return it }
        val bytes = runCatching { store.readFile(packId, file) }.getOrNull() ?: return null
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 4 }) ?: return null
        thumbs.put(key, bmp)
        return bmp
    }

    private fun send(packId: String, file: String) {
        val editor = currentInputEditorInfo ?: return
        val accepted = EditorInfoCompat.getContentMimeTypes(editor)
        val mime = when {
            accepted.any { it == STICKER_MIME } -> STICKER_MIME
            accepted.any { ClipDescription.compareMimeTypes("image/webp", it) } -> "image/webp"
            else -> null
        }
        if (mime == null) {
            Toast.makeText(this, R.string.keyboard_not_supported, Toast.LENGTH_SHORT).show()
            return
        }
        val bytes = runCatching { store.readFile(packId, file) }.getOrNull() ?: return
        val uri = StickerShare.keyboardUri(this, "${packId}_$file", bytes)
        // Android 7.1 öncesinde izin bayrağı yok: alıcıya elle izin ver
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
            grantUriPermission(editor.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val content = InputContentInfoCompat(uri, ClipDescription("sticker", arrayOf(mime)), null)
        val ok = InputConnectionCompat.commitContent(
            currentInputConnection ?: return, editor, content, InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null,
        )
        if (ok) remember(packId, file)
        else Toast.makeText(this, R.string.keyboard_not_supported, Toast.LENGTH_SHORT).show()
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Son gönderilenler, en yenisi önce. */
    private fun recent(): List<Pair<String, String>> =
        prefs().getString(RECENT, "").orEmpty().split('\n').filter { it.contains('/') }
            .map { it.substringBefore('/') to it.substringAfter('/') }
            .filter { (p, f) -> runCatching { store.assetFile(p, f) != null }.getOrDefault(false) }

    private fun remember(packId: String, file: String) {
        val list = (listOf(packId to file) + recent().filter { it != packId to file }).take(MAX_RECENT)
        prefs().edit().putString(RECENT, list.joinToString("\n") { "${it.first}/${it.second}" }).apply()
    }

    /**
     * Sistem kendi gezinme şeridini (klavye değiştir / gizle) klavye penceresinin içine çiziyorsa
     * (Android 13+, `imeDrawsImeNavBar`) alt çubuk o şeridin zemini olur ve kendi düğmemiz gizlenir.
     * Bu durumda şeridin yüksekliği pencere kenar boşluklarında bildirilmiyor (Samsung, Android 16): şerit
     * görünümü pencerede aranır.
     */
    private fun fitBottomBar() {
        val bar = bottomBar ?: return
        val system = maxOf(insetBottom, imeNavBarHeight(window.window?.decorView))
        abcButton?.visibility = if (system > 0) View.GONE else View.VISIBLE
        val height = if (system > 0) system else dp(BOTTOM_BAR_DP)
        if (bar.layoutParams.height != height) bar.layoutParams = bar.layoutParams.apply { this.height = height }
    }

    private fun imeNavBarHeight(view: View?): Int {
        if (view == null || view === bottomBar?.parent) return 0
        if (view.javaClass.name.endsWith(".NavigationBarFrame") && view.isShown) return view.height
        if (view is ViewGroup) for (i in 0 until view.childCount) imeNavBarHeight(view.getChildAt(i)).let { if (it > 0) return it }
        return 0
    }

    private fun switchBack() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (!switchToPreviousInputMethod()) (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        } else {
            @Suppress("DEPRECATION")
            val token = window.window?.attributes?.token
            @Suppress("DEPRECATION")
            if (token == null || !(getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).switchToLastInputMethod(token)) {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }
        }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        /** WhatsApp'ın sticker olarak kabul ettiği içerik türü. */
        const val STICKER_MIME = "image/webp.wasticker"
        private const val PREFS = "sticker_keyboard"
        private const val RECENT = "recent"
        private const val MAX_RECENT = 24
        private const val COLUMNS = 4
        /** Sticker alanı; sekmeler ve alt çubukla birlikte ~300 dp (+ sistem gezinme şeridi). */
        private const val GRID_DP = 200
        private const val TAB_DP = 48
        private const val BOTTOM_BAR_DP = 52
        private const val CELL_DP = 80
        private const val THUMB_CACHE_BYTES = 16 * 1024 * 1024
    }
}
