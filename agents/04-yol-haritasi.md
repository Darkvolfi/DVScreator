# 04 — Yol Haritası

Durum: `[ ]` yapılacak · `[~]` sürüyor · `[x]` bitti. Sorumlu rol parantez içinde (bkz. 05).

> **Öncelik (2026-10-09):** Proje sahibinin ana kullanımı **hareketli sticker, kalite düşmeden**.
> Bu yüzden hareketli sticker v1.x'ten MVP'ye (M2) taşındı.

## M0 — Proje iskeleti
- [x] 06-acik-sorular.md'deki kararların netleşmesi (Proje sahibi)
- [x] Gradle çok modüllü iskelet, version catalog, Compose, Koin (Mimar) — `assembleDebug` başarılı
- [x] `git init`, `.gitignore`, LICENSE, README (Mimar)
- [x] libwebp submodule (v1.6.0) + CMake + boş JNI "merhaba" (Görüntü) — Samsung SM-S731B (Android 16) üzerinde "libwebp: 1.6.0" doğrulandı
- [ ] GitHub Actions: build + unit test + lint (Release) — proje GitHub'a yüklenince

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
- [~] libwebp `WebPAnimEncoder` JNI ✅ + mux (statik→2 kare) ✅ + demux bilgi ✅; `WebPAnimDecoder` (hareketli WebP kaynağı için) eksik (Görüntü)
- [ ] Video kaynağı: `MediaExtractor` + `MediaCodec` ile zaman damgalı kare çıkarma (Görüntü)
- [ ] GIF / animated WebP kaynağı: kare + süre çıkarma (Görüntü)
- [ ] Zamansal sabitleme: gürültü eşiği altındaki değişimleri önceki kareden kopyala → fark kodlaması tam verimli (Görüntü) — bkz. 02 madde 9
- [ ] Animasyon BEST_FIT: sabitleme → kalite ikili arama → FPS düşürme (süre korunarak) → süre önerisi; ≤ 500 KB, kare ≥ 8 ms, ≤ 10 sn (Görüntü)
- [ ] Animasyon kalite ölçümü: kare bazında SSIM (ortalama + en kötü kare) (Görüntü)
- [ ] "Maksimum kalite modu": statik görsel → 2 kareli animated WebP, 500 KB bütçe (Görüntü) — bkz. 02 madde 7b
- [ ] Animasyon altın seti + cihaz testleri, eşik kilitleme (QA)
- [ ] Kalite laboratuvarına video / GIF desteği (Android)

## M3 — WhatsApp entegrasyonu
- [ ] Room şeması, StickerStorage, repository'ler (Android)
- [ ] ContentProvider (örnek projeyle birebir uyumlu; M2 fizibilite kodundan büyütülür) (WhatsApp)
- [ ] PackValidator + kullanıcı dostu hata mesajları (WhatsApp)
- [ ] ENABLE_STICKER_PACK intent'i, WhatsApp / Business seçimi, "zaten ekli" kontrolü (WhatsApp)
- [ ] Tray icon üretimi (Görüntü)
- [ ] Gerçek cihazda uçtan uca test: uygulamada oluşturulan paket WhatsApp'ta görünüyor ve gönderilebiliyor (QA)

## M4 — Arayüz (MVP)
- [ ] Paket listesi / paket detay ekranları (Android)
- [ ] Photo Picker (görsel + video), çoklu seçim (Android)
- [ ] Editör: kırp, döndür, kenar boşluğu (Android)
- [ ] Video editörü: başlangıç/bitiş kırpma (≤ 10 sn), FPS / hız seçimi, önizleme (Android)
- [ ] Editör: silgi + geri al/yinele (maske orijinal çözünürlükte) (Android + Görüntü)
- [ ] Editör: metin ekleme (Android)
- [ ] Kalite sonuç ekranı (mod, boyut, SSIM, önce/sonra büyüteç) (Android)
- [ ] Emoji atama — sticker başına en az 1 zorunlu (Android)
- [ ] TR + EN çeviriler (Android)
- [ ] Ekran görüntüsü testleri (QA)

## M5 — Yayın v1.0
- [ ] Uygulama simgesi, mağaza görselleri (Android)
- [ ] İmzalı release build, reproducible build kontrolü (Release)
- [ ] GitHub Releases (Release)
- [ ] F-Droid metadata (`fastlane/metadata`) — ertelendi, ilgi görürse (Release)
- [ ] Gizlilik politikası ("hiçbir veri toplanmaz") (Release)

## M6 — v1.x
- [ ] Otomatik arka plan kaldırma (06'daki karara göre) — videoda kare kare (Görüntü)
- [ ] Beyaz kenar çizgisi / gölge (Görüntü)
- [ ] Paket yedekleme / içe aktarma (Android)
