# 04 — Yol Haritası

Durum: `[ ]` yapılacak · `[~]` sürüyor · `[x]` bitti. Sorumlu rol parantez içinde (bkz. 05).

> **Öncelik (2026-10-09):** Proje sahibinin ana kullanımı **hareketli sticker, kalite düşmeden**.
> Bu yüzden hareketli sticker v1.x'ten MVP'ye (M2) taşındı.

## M0 — Proje iskeleti
- [x] 06-acik-sorular.md'deki kararların netleşmesi (Proje sahibi)
- [x] Gradle çok modüllü iskelet, version catalog, Compose, Koin (Mimar) — `assembleDebug` başarılı
- [x] `git init`, `.gitignore`, LICENSE, README (Mimar)
- [x] libwebp submodule (v1.6.0) + CMake + boş JNI "merhaba" (Görüntü) — Samsung SM-S731B (Android 16) üzerinde "libwebp: 1.6.0" doğrulandı
- [x] GitHub Actions: derleme + birim testleri + lint (bilgi amaçlı) + debug APK (Release) — ilk çalıştırma başarılı (7.5 dk)

## M1 — Statik kalite hattı (UI'sız, test ile)
- [~] Decode + EXIF + sRGB dönüşümü (Görüntü) — `ImageLoader` yazıldı, gerçek dosyalarla cihazda henüz denenmedi
- [x] Kare tuval + kenar boşluğu (Görüntü)
- [x] Lanczos3 premultiplied ölçekleme (Görüntü)
- [x] Alfa temizliği (Görüntü)
- [x] libwebp JNI: lossless / near-lossless / lossy encode + decode (Görüntü)
- [x] `BEST_FIT` arama stratejisi (Görüntü)
- [x] SSIM hesaplayıcı (Görüntü)
- [x] Altın görsel seti + kalite testleri; eşikleri kilitle (QA) — sentetik set; gerçek CC0 fotoğraflar eksik
- [~] Kıyas raporu: bizim hat vs `Bitmap.compress` (QA) — [raporlar/m1-kalite.md](raporlar/m1-kalite.md); popüler uygulama kıyası yapılmadı
- [x] Geliştirici test ekranı "Kalite laboratuvarı": galeriden fotoğraf seç → sticker + mod/boyut/SSIM/süre (Android)
- [ ] Gerçek fotoğraflarla ölçüm (QA) — laboratuvar ekranıyla, proje sahibinin cihazında

## M2 — Hareketli sticker hattı  ← ana özellik
- [x] **Fizibilite:** ContentProvider + 6 test paketi → gerçek hareketli ✅, 2 kareli "hareketli" ✅, 500 KB üstü ❌, statik 100 KB üstü ❌ (WhatsApp + QA) — [raporlar/m2-whatsapp-fizibilite.md](raporlar/m2-whatsapp-fizibilite.md)
- [ ] Gönderim testi: 2 kareli ve gerçek hareketli sticker sohbete gönderildiğinde kalite ve görünüm korunuyor mu? (QA)
- [x] libwebp `WebPAnimEncoder` + mux (statik→2 kare) + demux + akışlı `WebPAnimDecoder` JNI (Görüntü)
- [x] Video kaynağı: `MediaCodec` tek geçiş + yerel (C) YUV→RGB, desteklenmeyende `MediaMetadataRetriever`e dönüş, ≤150 kare (Görüntü) — test videosunda 6.6 kat hızlı, SSIM 0.9997
- [~] GIF kaynağı (glide gifdecoder) (Görüntü) — yazıldı, gerçek GIF ile denenmedi; hareketli WebP kaynağı eksik
- [x] Zamansal sabitleme: 8×8 blok bazlı (Görüntü) — ±3 gürültüde 6 kat küçültme (303 → 50 KB)
- [x] Animasyon BEST_FIT: sabitleme → kayıpsız (tahminli) → kayıplı interpolasyonlu arama → FPS düşürme (süre korunarak) (Görüntü)
- [x] Animasyon kalite ölçümü: örneklenmiş kare SSIM (ortalama + en kötü kare) (Görüntü)
- [x] "Maksimum kalite modu": statik görsel → 2 kareli animated WebP, 500 KB bütçe (Görüntü) — WhatsApp kabul ediyor
- [~] Animasyon altın seti + cihaz testleri (QA) — sentetik klipler; eşikler gerçek videolardan sonra kilitlenecek
- [x] Kalite laboratuvarına video / GIF desteği (Android)
- [~] Gerçek video / GIF ile ölçüm ve hız iyileştirmesi (QA) — 81 sn videoda 91 → 43 sn; sıkıştırma (8 deneme, 34 sn) açık iş

## M3 — WhatsApp entegrasyonu
- [x] Paket deposu (JSON, Room yerine — bkz. 03) + `PackRepository`; kaynak dosyalar `sources/` altına kopyalanır (Android)
- [x] ContentProvider (örnek projeyle birebir uyumlu) (WhatsApp)
- [x] PackValidator + Türkçe, anlaşılır hata mesajları (WhatsApp) — cihazda doğrulandı
- [x] ENABLE_STICKER_PACK intent'i, WhatsApp / Business seçimi, "zaten ekli" kontrolü, ekli paketi "WhatsApp'ta güncelle" (WhatsApp) — güncelleme cihazda doğrulandı
- [x] Tray icon üretimi: ilk stickerın ilk karesi, 96×96 PNG (Görüntü)
- [x] Gerçek cihazda uçtan uca test: proje sahibi kendi paketini oluşturup WhatsApp'a ekledi (2026-10-10) (QA)
- [x] "Paketlerim" ekranı (erken, basit sürüm): paket oluştur/sil/adlandır, çoklu seçimle sticker ekle, emoji seç, sticker sil (Android)

## M4 — Arayüz (MVP)
- [ ] Paket listesi / paket detay ekranları (Android)
- [ ] Photo Picker (görsel + video), çoklu seçim (Android)
- [~] Editör: kare kırpma (sürükle / iki parmakla / kaydırıcı) veya "tamamını sığdır" — fotoğraf, video, GIF ✅ (kırpma tam çözünürlükte, küçültmeden önce); döndürme ve kenar boşluğu ayarı eksik (Android)
- [~] Video editörü: başlangıç/bitiş kırpma (≤ 10 sn) + döngülü önizleme + küçük resim şeridi + kare hızı tahmini ✅ (aralık tarife kaydedilir); FPS / hız seçimi ve GIF kırpma arayüzü eksik (Android) — cihazda proje sahibinin testi bekleniyor
- [ ] Editör: silgi + geri al/yinele (maske orijinal çözünürlükte) (Android + Görüntü)
- [ ] Editör: metin ekleme (Android)
- [ ] Kalite sonuç ekranı (mod, boyut, SSIM, önce/sonra büyüteç) (Android)
- [ ] Emoji atama — sticker başına en az 1 zorunlu (Android)
- [ ] TR + EN çeviriler (Android)
- [ ] Ekran görüntüsü testleri (QA)

## M4b — WhatsApp'tan içe aktar (proje sahibi isteği, 2026-10-10)
- [x] SAF klasör erişimi: `Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers` (klasörde `.nomedia` var → galeri izniyle görünmüyor; kalıcı izin) (Android)
- [x] Alınan stickerları ızgarada göster (tembel yükleme, ~2700 dosya; hareketliler oynar), çoklu seçim; "Paketlere göre" gruplama (EXIF, dosya sonundan 8 KB) (Android)
- [x] Seçilenleri pakete **yeniden kodlamadan** ekle; alınan bir paketten yeni paket(ler) — 30 üstü eşit bölünür: geçerli hareketli → birebir kopya; statik → kayıpsız 2 kareli sarma; geçersizler (boyut / çözünürlük) için açıklama (Görüntü + WhatsApp)
- [x] Paylaşılan tam paketler: `WhatsApp Sticker Packs` klasöründe her paket bir zip (`.zip`/`.enc`, ikisi de düz zip: stickerlar + tepsi PNG). Ayrı SAF izni; önbelleğe bir kez çıkarılır; aynı paket birden çok kez varsa en kalabalığı. Alınanlar klasöründe aynı paketin yalnızca bir kısmı olur → tam paket onun yerine gösterilir (Android)
- [x] 500 KB üstü ya da kare süreleri bozuk stickerlar atlanmaz, `AnimationEncoder` ile yeniden sıkıştırılır (WhatsApp'ın kendi oluşturucusu sınırı uygulamıyor: paylaşılan paketlerde 15 tane 500 KB+, en büyüğü 827 KB) (Görüntü)
- Not: `WhatsApp Backup Excluded Stickers` = WhatsApp mağaza paketleri (çoğu Lottie) → içe aktarılmaz. `.was` dosyaları da Lottie → atlanır.
- Not: WhatsApp'ın kendi verisi (favoriler, son kullanılanlar, başka uygulamaların paketleri) Android güvenliği gereği erişilemez.
- Not: Paket başına 30 sınırı gerçek WhatsApp'ta doğrulandı (60 reddedildi). Proje sahibi cihazda onayladı (2026-10-10).

## M5 — Yayın v1.0
- [~] Uygulama simgesi: sticker + ":3" yüzü (dikey), temalı ikon için tek renkli sürüm ✅; mağaza görselleri eksik (Android)
- [ ] İmzalı release build, reproducible build kontrolü (Release)
- [ ] GitHub Releases (Release)
- [ ] F-Droid metadata (`fastlane/metadata`) — ertelendi, ilgi görürse (Release)
- [ ] Gizlilik politikası ("hiçbir veri toplanmaz") (Release)

## M6 — v1.x
- [ ] Otomatik arka plan kaldırma (06'daki karara göre) — videoda kare kare (Görüntü)
- [ ] Beyaz kenar çizgisi / gölge (Görüntü)
- [ ] Paket yedekleme / içe aktarma (Android)
