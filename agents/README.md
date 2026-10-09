# WScreator — Proje Planı (agents/)

Android için **açık kaynaklı**, görüntü kalitesini mümkün olan en yüksek seviyede koruyarak
WhatsApp çıkartması (sticker) üreten uygulama.

Bu klasör, projeyi geliştirecek insanlar ve yapay zekâ ajanları için **tek doğruluk kaynağıdır**.
Bir işe başlamadan önce ilgili dosyayı oku; bir karar değişirse önce buradaki dosyayı güncelle.

## Dosyalar

| Dosya | İçerik |
|---|---|
| [00-vizyon-ve-kapsam.md](00-vizyon-ve-kapsam.md) | Ne yapıyoruz, ne yapmıyoruz, ilkeler |
| [01-whatsapp-gereksinimleri.md](01-whatsapp-gereksinimleri.md) | WhatsApp'ın sticker için koyduğu teknik sınırlar |
| [02-kalite-stratejisi.md](02-kalite-stratejisi.md) | **Projenin kalbi:** kaliteyi koruyan görüntü hattı |
| [03-mimari.md](03-mimari.md) | Modüller, teknoloji seçimi, veri modeli |
| [04-yol-haritasi.md](04-yol-haritasi.md) | Kilometre taşları ve görev listesi |
| [05-ajan-rolleri.md](05-ajan-rolleri.md) | Hangi ajan neyden sorumlu, çalışma kuralları |
| [06-acik-sorular.md](06-acik-sorular.md) | Proje sahibinin karar vermesi gereken konular |

## Çalışma kuralları (özet)

1. Her görev [04-yol-haritasi.md](04-yol-haritasi.md) içindeki bir maddeye bağlıdır; bitince işaretlenir.
2. Görüntü hattına dokunan her değişiklik, [02-kalite-stratejisi.md](02-kalite-stratejisi.md)'deki
   kalite testlerinden geçmelidir (SSIM eşikleri gerilemez).
3. WhatsApp sınırları ([01](01-whatsapp-gereksinimleri.md)) asla aşılmaz — aşılırsa WhatsApp paketi reddeder.
4. Uygulama **internet izni istemez**; tüm işlem cihazda yapılır.
5. Kod Kotlin, arayüz Jetpack Compose. Yeni bağımlılık eklemeden önce lisansı kontrol edilir
   (GPL-3.0 ile uyumlu olmalı, kapalı kaynak/Google Play Services bağımlılığı olmamalı).
