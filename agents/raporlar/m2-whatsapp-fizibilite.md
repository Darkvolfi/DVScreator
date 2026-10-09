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

---

# M2 Hareketli sticker hattı — ölçümler (2026-10-10)

## Sentetik klipler (`AnimationEncoderDeviceTest`, logcat `WSC_ANIM`)

Zamansal sabitleme taraması (30 kare, ±3 video gürültüsü, q80):

| Blok ortalama eşiği | Boyut | Not |
|---|---|---|
| yok | 303 KB | Gürültü fark kodlamasını boşa çıkarıyor |
| 2 | 316 KB | Eşik gürültünün altında, etkisiz |
| 3 | 61 KB | 5 kat küçük |
| **4 (varsayılan)** | **51 KB** | **6 kat küçük** |

İlk denenen piksel bazlı eşik (4) yalnızca %0.5 kazandırdı → 8×8 blok kararına geçildi.
Not: Sabitlemenin orijinal karelere göre SSIM'i 0.93 çıkıyor; bu "bir gürültünün yerine başka gürültü"
geçmesinin SSIM'deki yanıltıcı cezası (düz alanlarda), gözle görülür bir fark değil.

BEST_FIT (hız iyileştirmesi sonrası):

| Klip | Sonuç | Boyut | SSIM | Süre (ilk sürüm → şimdi) |
|---|---|---|---|---|
| Gürültülü arka plan + top, 30 kare | Kayıpsız | 308 KB | 1.0 | 10.6 → 1.7 sn |
| Aynısı, 75 kare | Kayıpsız | 417 KB | 1.0 | 25 → 2.9 sn |
| Tam hareket (kaydırma + gren), 60 kare | Kayıplı q65 | 500 KB | 0.860 | 45.7 → 18 sn |
| Tam hareket, 120 kare | Kayıplı q65, 60 kareye (15 fps) düşürüldü | 498 KB | 0.860 | 84 → 26 sn |

Hız iyileştirmeleri: kayıpsızda SSIM ölçülmez; kayıplıda ≤10 kare örneklenir; ikili arama yerine
ln(boyut)–kalite interpolasyonu; önce en düşük kalite denenir (sığmazsa doğrudan FPS düşürme);
kayıpsız deneme ilk 8 kareden tahminle atlanabilir.

## Gerçek kaynaklar (proje sahibinin telefonu, Kalite laboratuvarı, logcat `WSC_LAB`)

| Kaynak | Sonuç | Boyut | SSIM | Süre |
|---|---|---|---|---|
| Fotoğraf 736×736 (maks. kalite modu) | Kayıpsız | 65.9 KB | 1.0000 | 0.3 sn |
| Hareketli, 11.5 sn @ 10 fps → 10 sn'ye kırpıldı, 100 kare | Kayıplı q73 | 442 KB | ort. 0.9979 / en kötü 0.9976 | 17 sn (kare çıkarma 6.3, kodlama 5.9, ölçüm 3.7) |

Proje sahibinin değerlendirmesi: "baya iyi gözüküyo".

İyileştirme fırsatları: arama 4 adımda durup bütçenin %88'ini kullandı (q biraz daha yükselebilir);
kare çıkarma `MediaMetadataRetriever` ile 63 ms/kare → `MediaCodec` ile hızlandırılabilir.
