package io.github.darkvolfi.dvscreator.core.whatsapp

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/** WhatsApp'a paket ekleme (resmi örnek projedeki `AddStickerPackActivity` / `WhitelistCheck` ile aynı sözleşme). */
object WhatsAppIntents {

    /** Yüklü WhatsApp paketleri: önce normal WhatsApp, sonra Business. */
    fun installedWhatsApps(context: Context): List<String> =
        listOf(WhatsAppLimits.WHATSAPP_PACKAGE, WhatsAppLimits.WHATSAPP_BUSINESS_PACKAGE).filter { isInstalled(context, it) }

    fun addPack(context: Context, identifier: String, name: String, whatsAppPackage: String): Intent =
        Intent(WhatsAppLimits.ENABLE_STICKER_PACK_ACTION).apply {
            putExtra("sticker_pack_id", identifier)
            putExtra("sticker_pack_authority", StickerContentProvider.authorityFor(context.packageName))
            putExtra("sticker_pack_name", name)
            setPackage(whatsAppPackage)
        }

    /** WhatsApp paketi reddederse sonuç Intent'inde gerekçe döner. */
    fun validationError(result: Intent?): String? = result?.getStringExtra("validation_error")

    /**
     * Paket bu WhatsApp'a zaten ekli mi? WhatsApp'ın kendi provider'ı sorgulanır. Zaten ekli pakette
     * WhatsApp RESULT_CANCELED döndürüyor (M2 fizibilite) — kullanıcıya doğru mesaj için gerekli.
     * Sorgu başarısız olursa null (bilinmiyor).
     */
    fun isPackAdded(context: Context, identifier: String, whatsAppPackage: String): Boolean? = runCatching {
        val uri = Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority("$whatsAppPackage.provider.sticker_whitelist_check")
            .appendPath("is_whitelisted")
            .appendQueryParameter("authority", StickerContentProvider.authorityFor(context.packageName))
            .appendQueryParameter("identifier", identifier)
            .build()
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getInt(c.getColumnIndexOrThrow("result")) == 1 else null
        }
    }.getOrNull()

    private fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
