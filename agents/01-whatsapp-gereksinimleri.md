# 01 — WhatsApp Sticker Gereksinimleri

Kaynak: WhatsApp'ın resmi örnek projesi — https://github.com/WhatsApp/stickers (Android klasörü,
BSD lisanslı). **Uygulamaya geçmeden önce bu repodaki güncel README ve doğrulama kodu
(`StickerPackValidator`) tekrar kontrol edilmeli**; WhatsApp sınırları zamanla değişebilir.

## Sticker dosyası

| Özellik | Statik | Hareketli |
|---|---|---|
| Format | WebP | Animated WebP |
| Boyut | **tam 512 × 512 px** | **tam 512 × 512 px** |
| Dosya boyutu | **≤ 100 KB** | **≤ 500 KB** |
| Kare süresi | — | her kare ≥ 8 ms |
| Toplam süre | — | ≤ 10 sn (öneri) |
| Arka plan | Şeffaf önerilir | Şeffaf önerilir |
| Kenar boşluğu | ~16 px önerilir (zorunlu değil) | aynı |

## Paket simgesi (tray icon)
- PNG, **96 × 96 px**, **≤ 50 KB**

## Paket
- **3 – 30** sticker
- Statik ve hareketli sticker **aynı pakette karışamaz** (`animated_sticker_pack` bayrağı)
- `identifier`: benzersiz, harf/rakam/`_`/`-`/`.`; ≤ 128 karakter
- `name`, `publisher`: ≤ 128 karakter
- Her sticker için **1–3 emoji — en az 1 zorunlu** (`EMOJI_MIN_LIMIT = 1`)
- Erişilebilirlik metni (isteğe bağlı): statikte ≤ 125, hareketlide ≤ 255 karakter
- Hareketli pakette her sticker **en az 2 kare** içermeli (`getFrameCount() > 1`); statik pakette tek kare
- `image_data_version`: paket içeriği değiştiğinde **artırılmalı**, yoksa WhatsApp önbellekten eski
  sticker'ı gösterir
- `avoid_cache`: geliştirme sırasında `true` yapılabilir

## Entegrasyon mekanizması
1. Uygulama bir **ContentProvider** sunar:
   - `exported="true"`, `readPermission="com.whatsapp.sticker.READ"`
   - URI'ler: `/metadata`, `/metadata/{id}`, `/stickers/{id}`, `/stickers_asset/{id}/{dosya}`
   - Kolon adları ve URI yapısı örnek projedeki `StickerContentProvider` ile **birebir aynı** olmalı.
2. Paketi eklemek için Intent:
   - Action: `com.whatsapp.intent.action.ENABLE_STICKER_PACK`
   - Extras: `sticker_pack_id`, `sticker_pack_authority`, `sticker_pack_name`
   - Paket `com.whatsapp` ve `com.whatsapp.w4b` (Business) için ayrı ayrı hedeflenebilir.
3. Paket zaten ekli mi? WhatsApp'ın kendi provider'ına sorgu (örnek projede
   `WhitelistCheck`) — Android 11+ için manifest'e `<queries>` eklenmeli.

## Doğrulama
Dışa aktarmadan önce uygulama içi bir `PackValidator`, yukarıdaki **tüm** kuralları kontrol eder
ve kullanıcıya anlaşılır hata gösterir ("Bu pakette 2 sticker var, en az 3 gerekli" vb.).
WhatsApp'ın sessizce reddetmesine asla güvenme.

## Doğrulama kodundan teyit edilenler (2026-10-09)
`StickerPackValidator.java` (WhatsApp/stickers, main dalı) okundu:
`STATIC_STICKER_FILE_LIMIT_KB = 100`, `ANIMATED_STICKER_FILE_LIMIT_KB = 500`, `KB_IN_BYTES = 1024`,
512×512 zorunlu, kare süresi ≥ 8 ms, toplam ≤ 10 000 ms, emoji 1–3.
Not: Bu, örnek projenin doğrulayıcısıdır; WhatsApp uygulamasının kendi kontrolleri M2'de gerçek cihazda doğrulanacak.

## Paket güncelleme davranışı (gerçek cihaz, 2026-10-10)
- Ekli bir pakete sticker eklenip `image_data_version` artırıldığında WhatsApp değişikliği **kendiliğinden
  hemen almıyor** (`notifyChange` de tek başına yetmedi).
- Aynı `ENABLE_STICKER_PACK` isteği ekli paket için tekrar gönderilince WhatsApp paketi yeniden okuyor,
  **hemen güncelliyor** ve `RESULT_OK` döndürüyor → uygulamada ekli paketler için "WhatsApp'ta güncelle" düğmesi.
- `avoid_cache` alanı WhatsApp 2.25.9.78'den beri yok sayılıyor (resmi README).
