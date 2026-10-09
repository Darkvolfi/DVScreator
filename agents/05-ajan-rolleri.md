# 05 — Ajan Rolleri

Her rol bir yapay zekâ ajanına (veya insana) verilebilir. Bir ajana görev verirken:
"`agents/05-ajan-rolleri.md` içindeki **<Rol>** rolündesin, `agents/04-yol-haritasi.md`'deki
**<görev>** maddesini yap" demek yeterlidir.

## Ortak kurallar (tüm roller)
- Başlamadan önce: `README.md`, `00`, ilgili teknik dosya (01/02/03) okunur.
- Sadece kendi sahip olduğu modüllere yazar; başka modülde değişiklik gerekiyorsa açıkça belirtir.
- Plan dışı bir karar gerekirse **kod yazmadan önce** `06-acik-sorular.md`'ye eklenir ve proje
  sahibine sorulur.
- Görev bitince: testler geçer, `04-yol-haritasi.md`'de madde `[x]` yapılır, kısa özet yazılır.
- Yeni bağımlılık → lisans GPL-3.0 uyumlu mu, Google Play Services'e bağlı mı? kontrol edilir.

---

## 🏛️ Mimar
**Sahip:** Gradle yapısı, `:app`, `:core:model`, modül sınırları, CI iskeleti
**Sorumluluk:** Modül iskeleti, bağımlılık yönlerinin korunması, kod stili (ktlint/detekt).
**Bitti tanımı:** `./gradlew build` temiz geçer, modül grafiği 03-mimari.md ile aynı.

## 🖼️ Görüntü İşleme
**Sahip:** `:core:imaging`, `:core:webp` (C/C++ dahil)
**Sorumluluk:** 02-kalite-stratejisi.md'deki hattın tamamı; JNI bellek güvenliği.
**Kurallar:**
- Kalite ile hız çeliştiğinde **kalite** kazanır (ama < 1.5 sn hedefi raporlanır).
- Her algoritma değişikliği altın set üzerinde önce/sonra SSIM tablosu ile gelir.
- JNI'de her `AndroidBitmap_lockPixels` için `unlock` garantisi; native bellek sızıntısı testi.
**Bitti tanımı:** Kalite testleri yeşil, eşikler gerilemedi.

## 📱 Android / UI
**Sahip:** `:feature:*`, `:core:data`
**Sorumluluk:** Ekranlar, navigasyon, Room, depolama, çeviriler, erişilebilirlik.
**Kurallar:** Ağır iş UI thread'de yapılmaz; tüm metinler `strings.xml`'de; koyu tema desteklenir.
**Bitti tanımı:** Compose UI ve ekran görüntüsü testleri yeşil; TalkBack ile temel akış çalışır.

## 💬 WhatsApp Entegrasyon
**Sahip:** `:core:whatsapp`
**Sorumluluk:** ContentProvider, PackValidator, intent'ler, WhatsApp sürüm uyumluluğu.
**Kurallar:** Resmi örnek proje (github.com/WhatsApp/stickers) referans alınır; URI/kolon adları
oradan birebir. Sınır değerleri tek bir `WhatsAppLimits` nesnesinde tutulur.
**Bitti tanımı:** Validator için tüm sınır durumlarını kapsayan unit testler; gerçek cihazda paket eklenir.

## 🧪 Test / QA
**Sahip:** `testdata/`, test altyapısı, kıyas raporları
**Sorumluluk:** Altın görsel seti, kalite eşikleri, uçtan uca test senaryoları, gerileme takibi.
**Bitti tanımı:** Her kilometre taşı sonunda kısa test raporu (`agents/raporlar/` altında).

## 🚀 Release
**Sahip:** `.github/workflows/`, `fastlane/`, imzalama yapılandırması
**Sorumluluk:** CI, sürümleme (SemVer), F-Droid uyumu, GitHub Releases.
**Kurallar:** İmzalama anahtarı **asla** repoya girmez (GitHub Secrets).
