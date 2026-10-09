# M1 Kalite Raporu — 2026-10-09

Cihaz: Samsung SM-S731B, Android 16. libwebp 1.6.0. Testler:
`core/imaging/src/androidTest/.../QualityPipelineDeviceTest.kt` (kalite) ve
`EncoderTimingDeviceTest.kt` (ayar bazında süre).

```bash
./gradlew :core:imaging:testDebugUnitTest :core:imaging:connectedDebugAndroidTest
adb logcat -s WSC_QUALITY WSC_TIMING
```

## Altın set sonuçları (BEST_FIT, 100 KB sınırı)

| Görsel | Mod | Boyut | SSIM | Süre* |
|---|---|---|---|---|
| photo 3000×2000 | Kayıpsız | 87.9 KB | 1.0000 | 0.85 sn |
| grainy_photo 3000×2000 | Near-lossless 80 | 87.6 KB | 0.9960 | 1.37 sn |
| cartoon 2048 (şeffaf) | Kayıpsız | 10.6 KB | 1.0000 | 0.38 sn |
| text 1600×1200 | Kayıpsız | 30.3 KB | 1.0000 | 0.22 sn |
| soft_edge 2000 (şeffaf) | Kayıpsız | 7.0 KB | 1.0000 | 0.49 sn |
| zone_plate 2048 | Near-lossless 80 | 88.6 KB | 0.9993 | 1.04 sn |
| noise 1024 (en kötü durum) | Kayıplı q=75 | 97.8 KB | 0.6962 | 1.36 sn |
| tiny 64×48 (büyütme) | Kayıpsız | 86.3 KB | 1.0000 | 0.27 sn |

\* Hazırlık (küçültme) + kodlama, debug derleme. Hepsi 1.5 sn hedefinin altında.

## Kıyas: bizim hat vs "basit yöntem"

Basit yöntem = çoğu uygulamanın yaptığı: `Bitmap.createScaledBitmap(filter=true)` + `Bitmap.compress(WEBP_LOSSY)`
(100 KB'a sığan en yüksek kalite).

| Görsel | Bizim SSIM | Basit SSIM |
|---|---|---|
| photo | 1.0000 | 0.9000 |
| grainy_photo | 0.9960 | 0.5520 |
| cartoon | 1.0000 | 0.9841 |
| text | 1.0000 | 0.9279 |
| soft_edge | 1.0000 | 0.9998 |
| zone_plate | 0.9993 | 0.4764 |
| tiny | 1.0000 | 0.9886 |

⚠️ **Yöntem notu (dürüstlük için):** Referans, bizim kodlama öncesi Lanczos 512 px görüntümüzdür.
Bu, bizim hattı doğal olarak avantajlı kılar. Ancak farkın büyük kısmı küçültmeden gelir:
bilinear küçültme ince dokuda **aliasing** (moire) üretir (zone_plate 0.48, grainy 0.55) — bu
gözle de görülen gerçek bir bozulmadır. Bağımsız bir referansla (ör. çok yüksek örneklemeli kutu filtresi)
ölçüm ileride eklenebilir.

## Performans bulguları (ayar seçimini belirleyen ölçümler)

| Bulgu | Ölçüm | Karar |
|---|---|---|
| Native kod debug'da `-O0` derleniyordu | Kayıplı m6: 22 sn → 1.2 sn (`-O2`) | libwebp debug'da da `-O2` |
| `alpha_filtering = 2` (en iyi) | m6'da 1.2 sn → 5.9 sn, boyut kazancı **0** | `alpha_filtering = 1` |
| `method 6` vs `method 5` | 1.0–1.5 sn vs ~60 ms, boyut farkı ~%1 | Arama m4, son ayar m5 |
| Kayıpsız seviye 9 vs 6 | 4–8 sn vs 0.1–0.3 sn, %5–10 küçük | Seviye 6 |

## Bilinen eksikler

1. **Gerçek kamera fotoğrafı yok.** Sentetik görseller (grainy dahil) küçültme sonrası kayıpsız/near-lossless'a
   sığıyor; gerçek fotoğraflarda kayıplı yolun daha sık çalışması beklenir. Kayıplı yol şimdilik yalnızca
   `noise` ile test edildi → geliştirici test ekranında gerçek fotoğraflarla ölçülecek; lisansı uygun (CC0)
   fotoğraflar `testdata/` altına eklenecek.
2. Doğrusal ışık (linear light) küçültme uygulandı ama varsayılan kapalı; görsel karşılaştırma yapılmadı.
3. Keskinleştirme (unsharp mask) henüz yok (`EditRecipe.sharpen` yok sayılıyor).
