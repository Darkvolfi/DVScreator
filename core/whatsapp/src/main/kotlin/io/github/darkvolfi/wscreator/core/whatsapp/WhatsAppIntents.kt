package io.github.darkvolfi.wscreator.core.whatsapp

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** WhatsApp'a paket ekleme isteği (resmi örnek projedeki `AddStickerPackActivity` ile aynı sözleşme). */
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

    private fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
