# 06 — Kararlar ve Açık Sorular

## ✅ Verilen kararlar (2026-10-09)

| # | Konu | Karar |
|---|---|---|
| S1 | Lisans | **GPL-3.0** |
| S2 | Otomatik arka plan kaldırma | **IS-Net general-use** (Apache-2.0, ~170 MB, proje sahibi 2026-10-10 kaliteyi seçti; U²-Netp 4.5 MB alternatifti) + ONNX Runtime, tamamen cihazda, APK içinde (internet izni yok). Google ML Kit / Play Services **kullanılmaz**. M4'e öne çekildi. |
| S3 | Dağıtım | Şimdilik **yalnızca GitHub** (Releases). Ticari hedef yok. İlgi görürse önce F-Droid, sonra Google Play. Kod her zaman F-Droid'e girebilecek durumda tutulur (kapalı kaynak bağımlılık yok). |
| S4 | Android sürümleri | **API 24 (Android 7.0)** ve üstü çalışır; en yeni sürüm hedeflenir — şu an **API 37 (Android 17)**. Her yeni Android sürümünde hedef güncellenir. |
| S5 | Uygulama ve paket adı | **DVScreator**, `io.github.darkvolfi.dvscreator` (debug: `.debug` son ekli). 2026-10-10: "WScreator" adlı başka uygulamalar olduğu için proje sahibinin lakabından (Darkvolfi) DV ön ekiyle değiştirildi. Not: yerel klasör adı hâlâ `WScreator`. |
| S6 | Hareketli sticker | **Ana özellik, MVP'de (M2).** Proje sahibinin birincil kullanımı: hareketli sticker, kalite düşmeden. |
| S7 | Geliştirme ortamı | Android Studio kurulu; test cihazı Samsung SM-S731B, Android 16 (USB hata ayıklama için Otomatik Engelleyici kapatıldı) |
| S8 | Maksimum kalite modu (statik → 2 kareli hareketli, 500 KB) | **Varsayılan açık** — WhatsApp kabul ettiği 2026-10-10 fizibilite testinde doğrulandı; ayarlardan kapatılabilir. |
| S9 | Hareketli sticker kaynakları | **Video (MP4 vb.) + GIF.** Süreler **karışık** → uygulama her kaynağa göre süre/FPS önerisi sunmalı. |
| S10 | Hareketli sticker arka planı | **İkisi de:** olduğu gibi bırakma VE şeffaf yapma seçilebilmeli → videoda kare kare otomatik arka plan silme gerekiyor (S2 modeli). M6'dan öne çekilmesi değerlendirilecek. |

## ❓ Açık sorular

Şu an açık soru yok.

> Not: Uygulama adında "WhatsApp" kelimesi kullanılmamalı (marka ihlali).
