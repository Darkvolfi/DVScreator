# 02 — Kalite Stratejisi (Görüntü Hattı)

Projenin farkı bu dosyada. Her adımın amacı: **512×512 / 100 KB sınırında kaynağa en yakın sonuç.**

```
Kaynak ─► Decode ─► Renk uzayı (sRGB) ─► Düzenleme (tam çözünürlükte)
       ─► Kare tuval + kenar boşluğu ─► Yüksek kaliteli küçültme ─► (ops.) hafif keskinleştirme
       ─► Alfa temizliği ─► Akıllı WebP kodlama ─► Doğrulama + kalite skoru
```

## 1. Decode
- `ImageDecoder` (API 28+), altında `BitmapFactory`. Her zaman `ARGB_8888`.
- **EXIF yönü** uygulanır (yan dönmüş fotoğraf olmaz).
- Çok büyük görsellerde (ör. >4096 px) bellek için `inSampleSize` yalnızca **hedefin en az 4 katı**
  çözünürlük kalacak şekilde kullanılır (512 hedef → en az 2048 px kalır).
- HEIC, AVIF, PNG, JPEG, WebP, GIF desteklenir.

## 2. Renk
- Display P3 / HDR fotoğraflar **sRGB'ye dönüştürülür** (`ColorSpace.connect`). Aksi halde WhatsApp'ta
  renkler soluk görünür.

## 3. Düzenleme tahribatsızdır
- Kırpma, döndürme, silgi maskesi, metin → hepsi **orijinal çözünürlükte** bir "düzenleme tarifi"
  olarak saklanır (JSON + maske PNG). Küçültme en son yapılır.
- Önizleme için düşük çözünürlüklü kopya kullanılır; dışa aktarım her zaman orijinalden.

## 4. Kare tuval
- Görsel **asla uzatılmaz.** En-boy oranı korunur, şeffaf kare tuvale ortalanır.
- Varsayılan kenar boşluğu: 512'de 16 px (ayarlanabilir: 0–32).

## 5. Küçültme (en kritik adım)
`Bitmap.createScaledBitmap(filter=true)` büyük oranlarda **aliasing** (tırtık, moire) üretir. Bunun yerine:
- **Lanczos3** (veya alan-ortalaması + Lanczos) yeniden örnekleme, Kotlin'de ya da native (libwebp ile
  birlikte gelen `sharpyuv`/kendi C kodumuz) uygulanır.
- **Premultiplied alpha'da** ölçeklenir — böylece şeffaf kenarlarda siyah/beyaz hale (halo) oluşmaz.
- Doğrusal ışık (linear light) ölçekleme opsiyonu: gamma-doğru ortalama; ince detaylarda daha doğru.
- Küçültme sonrası **hafif unsharp mask** (varsayılan: kapalı, "Netlik" kaydırıcısı ile açılır).

## 6. Alfa temizliği
- Neredeyse tamamen şeffaf pikseller (alfa < 3) → tam şeffaf, RGB = 0. Bu hem boyutu düşürür hem de
  kodlayıcının görünmeyen renk gürültüsüne bayt harcamasını engeller.
- Tam şeffaf piksellerin RGB'si sıfırlanır (WebP `exact=false` davranışı).

## 7. Akıllı WebP kodlama
Android'in `Bitmap.compress` fonksiyonu yetersiz (method/alpha_q/sharp_yuv ayarları yok).
**libwebp JNI ile doğrudan kullanılır** (BSD lisansı, GPL uyumlu).

Arama stratejisi (`BEST_FIT`, uygulandı: `core/imaging/StickerEncoder.kt`):
1. **Kayıpsız** dene: `WebPConfigLosslessPreset(6)`. ≤ 100 KB → bitti. ✅ Piksel-piksel aynı.
2. **Neredeyse kayıpsız 80** sığarsa → bitti (gözle ayırt edilemez).
3. Sığmazsa near-lossless 60/40 ve **kayıplı ikili arama** birlikte değerlendirilir, **SSIM'i yüksek olan** seçilir.
   Kayıplı: `sharp_yuv=1, alpha_quality=100`; `quality` 0–100 ikili arama `method=4` ile,
   ardından `method=5` ile en fazla 3 adım yukarı yoklama.
   Boyut gerçek bayt olarak ölçüldüğü için güvenlik payına gerek yok: sınır = tam 100 × 1024 bayt.
4. Hâlâ sığmıyorsa (çok gürültülü fotoğraf): `StickerTooLargeException` → kullanıcıya "hafif gürültü azalt" önerisi (M3).

Ölçümle belirlenen ayarlar (ayrıntı: [raporlar/m1-kalite.md](raporlar/m1-kalite.md)):
- `method 6` **kullanılmaz**: m5'ten ~20 kat yavaş, kazanç ~%1.
- `alpha_filtering = 1`: 2 ("en iyi") 5 kat yavaş, boyut kazancı yok.
- Kayıpsız seviye 9 kullanılmaz: 4–8 sn, kazanç %5–10.
- Native kod debug derlemede de `-O2` (yoksa libwebp 20+ kat yavaş).

Her aday için kod çözülüp kaynakla karşılaştırılır; sonuç ekranında gösterilir:
- Mod (Kayıpsız / Neredeyse kayıpsız / Kayıplı q=87)
- Boyut (ör. 97.4 KB / 100 KB)
- **SSIM** skoru (ör. 0.991)

Performans: tüm deneme süreci 512×512'de bir orta seviye telefonda < 1.5 sn hedeflenir;
arama `Dispatchers.Default` üzerinde, iptal edilebilir.

## 7b. Maksimum kalite modu: statik görsel → "hareketli" sticker (500 KB bütçe)
Fikir proje sahibinden (hareketli sticker deneyimi). WhatsApp sınırları: statik **100 KB**, hareketli **500 KB**.
Doğrulayıcı hareketli pakette yalnızca `kare sayısı > 1` şartı arıyor, hareketin varlığına bakmıyor.

- Statik görsel, **2 aynı kareden** oluşan animated WebP olarak kodlanır (ör. 2 × 1000 ms, döngü).
  İkinci kare değişmediği için WebPAnimEncoder onu neredeyse sıfır baytla kodlar.
- Ekranda tamamen sabit görünür, ama BEST_FIT bütçesi **500 KB** olur → gerçek fotoğraflar da büyük
  ihtimalle kayıpsız / near-lossless sığar.
- Kısıt: paketin **tamamı** hareketli olmalı (karışık paket yok) → mod paket düzeyinde seçilir.
- **Risk:** Örnek projenin doğrulayıcısına dayanıyor. WhatsApp uygulamasının kabul ettiği M2'de gerçek
  cihazda doğrulanmalı; WhatsApp kuralı değiştirirse paket otomatik olarak normal (100 KB) moda
  yeniden üretilebilmeli — tahribatsız düzenleme sayesinde bu her zaman mümkün.
- Uygulama: `WebPAnimEncoder` JNI (M5'teki hareketli sticker işiyle aynı altyapı, öne çekilir).

## 8. Tray icon
- Aynı hat, 96×96, PNG; 50 KB'ı aşarsa `pngquant` benzeri renk azaltma (libimagequant **GPL-3** —
  lisans uyumlu) ya da paletli PNG.

## 9. Hareketli sticker (ANA ÖZELLİK — M2)
- Kaynak: GIF (`ImageDecoder` / kendi çözücümüz), video (`MediaExtractor` + `MediaCodec`).
- Kodlayıcı: libwebp `WebPAnimEncoder` (`kmin/kmax`, `allow_mixed`). **Fark kodlaması yerleşik:**
  her kare yalnızca önceki kareden değişen dikdörtgen bölge olarak kaydedilir (doğrulandı: 20 kare
  hareketli test 167–208 KB). Tamamen aynı ardışık kareler tek kareye birleştirilir.
- **Zamansal sabitleme (temporal stabilization) — kodlamadan önce:** Video sıkıştırma gürültüsü
  "sabit" arka planın piksellerini her karede biraz değiştirir → fark kodlaması işe yaramaz.
  Önceki kareden yalnızca gürültü eşiği kadar (ör. ΔE / kanal farkı < T) değişen pikseller önceki
  karenin değeriyle değiştirilir; gerçekten değişen bölgeler aynen kalır. Böylece sabit arka plan
  gerçekten sabit olur ve bütçe hareketli bölgenin kalitesine harcanır. T, kaliteyi düşürmeyecek
  şekilde ölçümle ayarlanır (kare bazında SSIM ile).
- **Sığdırma sırası (kare silme en son çare):**
  1. Sabitleme + kayıpsız / near-lossless dene (kısa, sabit arka planlı kliplerde sığabilir)
  2. Kayıplı kalite ikili arama (SSIM eşiğinin üstünde kaldığı sürece)
  3. Hâlâ sığmazsa FPS düşürme (30→25→20→15); **kare süreleri uzatılır**, hareket hızı değişmez
  4. Son olarak kullanıcıya süre kırpma önerisi ("4 sn'ye kırparsan kalite şu kadar artar")
- Kalite ölçümü kare bazında: ortalama SSIM + en kötü kare SSIM (tek kötü kare de göze batar).
- Toplam ≤ 500 KB, her kare ≥ 8 ms, ≤ 10 sn (fizibilite testinde daha büyüğü de deneniyor).

## Kalite testleri (CI'da zorunlu)
- `core/imaging` altında **altın görsel seti** (`testdata/`): fotoğraf, çizim, metinli, şeffaf kenarlı,
  P3 renkli, devasa (8000 px), minik (64 px) görseller.
- Her biri için: boyut ≤ sınır, 512×512, SSIM ≥ belirlenen eşik (ilk ölçümden sonra kilitlenir),
  halo testi (şeffaf kenar çevresinde koyu piksel yok).
- Herhangi bir PR SSIM'i **0.002'den fazla düşürürse** test başarısız olur.
