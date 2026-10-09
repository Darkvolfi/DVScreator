# 00 — Vizyon ve Kapsam

## Problem
Mevcut sticker uygulamalarının çoğu:
- Görseli kaba şekilde küçültür (bulanık, tırtıklı kenarlar),
- Sabit ve düşük bir WebP kalitesiyle sıkıştırır (blok/renk bantlanması),
- Kare olmayan görseli **uzatır** ya da kenarlarını keser,
- Reklam, takip ve gereksiz izinlerle doludur.

## Hedef
"Görüntü kalitesini silmeden" sticker yapmak. Teknik olarak dürüst tanım:

> WhatsApp sticker'ı **512×512 px ve en fazla 100 KB** olmak zorundadır. Bu sınır içinde,
> kaynağa **en yakın** sonucu üretmek: önce kayıpsız (lossless) dene, sığmazsa
> 100 KB'a sığan **en yüksek** kaliteyi otomatik bul. Kaynağın kendisi asla değiştirilmez.

Yani 4000×3000'lik bir fotoğraf 512×512'ye inecek — bu kaçınılmaz. Ama inişin kendisi
(ölçekleme, renk, şeffaflık, sıkıştırma) mümkün olan en kaliteli şekilde yapılacak.

## Kapsam — MVP (v1.0)
- **Hareketli sticker — ana özellik** (proje sahibinin birincil kullanımı): video / GIF → animated WebP,
  500 KB içinde en yüksek kalite (bkz. 02 — madde 9)
- **Maksimum kalite modu:** statik görseller de 2 kareli hareketli sticker olarak 500 KB bütçeyle (02 — 7b)
- Galeriden görsel seç (Android Photo Picker — depolama izni gerekmez)
- Kırp / sığdır (uzatma yok; şeffaf boşlukla ortalama), döndür
- Manuel silgi / geri al (arka plan temizleme için)
- Metin ekleme (basit)
- Kalite-öncelikli dışa aktarım (bkz. 02)
- Sticker paketleri oluştur / düzenle / sil (3–30 sticker)
- Paketi tek tuşla WhatsApp'a ekle (WhatsApp ve WhatsApp Business)
- Önizleme: dosya boyutu, kalite skoru (SSIM), kayıpsız mı değil mi

## Kapsam — Sonraki sürümler
- Otomatik arka plan kaldırma (cihaz içi model, bkz. 06 açık sorular)
- Kenar çizgisi (beyaz outline), gölge
- Paket içe/dışa aktarma (yedekleme, paylaşım için `.wastickers` benzeri zip)
- Çoklu dil (TR, EN başta)

## Kapsam dışı
- Bulut, hesap, sunucu, analitik, reklam
- Hazır sticker mağazası
- iOS

## İlkeler
1. **Kalite önce** — boyut sınırına sığdıktan sonra her bayt kaliteye harcanır.
2. **Tahribatsız düzenleme** — orijinal görsel + düzenleme adımları saklanır; her dışa aktarım
   orijinalden yeniden üretilir (sıkıştır-tekrar-sıkıştır kaybı yok).
3. **Gizlilik** — internet izni yok, her şey cihazda.
4. **Özgür yazılım** — F-Droid'e girebilecek şekilde: kapalı kaynak bağımlılık yok.
5. **Sade arayüz** — 3 dokunuşta sticker.
