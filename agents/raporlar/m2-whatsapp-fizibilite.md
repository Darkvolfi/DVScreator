# M2 Fizibilite — WhatsApp hangi paketleri kabul ediyor? (2026-10-10)

Cihaz: Samsung SM-S731B, Android 16, WhatsApp (com.whatsapp, güncel sürüm).
Uygulama: "WhatsApp testi" sekmesi (`app/.../probe/`). Her paket 3 sticker, 1 emoji, 96×96 PNG tray.
Sonuçlar `files/probe_results.txt` ve logcat `WSC_WA` etiketinden okundu.

| # | Paket | Sticker boyutları | Sonuç |
|---|---|---|---|
| 1 | Statik ≤ 100 KB | 95 · 97 · 100 KB, 1 kare | ✅ Kabul (`RESULT_OK`) |
| 2 | Hareketli ≤ 500 KB | 167 · 187 · 208 KB, 20 kare | ✅ Kabul (`RESULT_OK`) |
| 3 | **Statik → 2 kareli hareketli** | 185 · 189 · 187 KB, 2 kare, kayıpsız | ✅ **Kabul** — proje sahibi ekledi; sonraki denemelerde WhatsApp "zaten ekli" penceresi gösterdi (`RESULT_CANCELED`, hata yok) |
| 4 | Hareketli ~700 KB | 716–722 KB | ❌ `validation_error=handleStickerPackPreviewResult/failed`, ~0.1 sn'de |
| 5 | Hareketli ~1.5 MB | 1523–1527 KB | ❌ aynı hata |
| 6 | Statik ~200 KB | 201–202 KB | ❌ aynı hata |

## Sonuçlar

1. **Sınırlar WhatsApp uygulamasında da uygulanıyor:** statik 100 KB, hareketli 500 KB. Aşılamaz.
   Reddedilme UI açılmadan, anında oluyor; hata metni genel (`handleStickerPackPreviewResult/failed`),
   gerekçe söylemiyor → uygulama içi `PackValidator` şart (kullanıcıya anlaşılır neden göstermek için).
2. **Maksimum kalite modu çalışıyor:** 2 kareli (2. kare 2×2 şeffaf, blend) hareketli WebP kabul edildi.
   Statik görseller için bütçe 100 KB → 500 KB. Bu testte görseller 100 KB'ta kayıplı olacakken 500 KB
   modunda **tamamen kayıpsız** sığdı.
3. "Zaten ekli" paket için WhatsApp `RESULT_CANCELED` + hata yok döndürüyor → M3'te eklemeden önce
   `is_whitelisted` sorgusu yapılmalı (örnek projedeki `WhitelistCheck`).

## Açık kalan
- Gönderim testi: sticker sohbete gönderildiğinde WhatsApp yeniden sıkıştırıyor mu? Alıcı tarafta
  (özellikle 2 kareli sticker) doğru ve hareketsiz görünüyor mu? Eski WhatsApp sürümleri / iOS alıcı?
- WhatsApp Business ile test edilmedi (cihazda yüklü değil).
