# DVScreator Privacy Policy

*Last updated: 4 November 2026*

DVScreator is a free, open-source app for making stickers. It is developed by Darkvolfi, an individual developer.

## Summary
From version 1.3.0, DVScreator does not collect, store on any server, share or sell any personal data. The app has no internet permission, so it cannot send anything off your device.

## Correction for versions 1.2.1 and earlier
Versions 0.1.0 to 1.2.1 were published with the claim that the app had no internet access. That was wrong. The ONNX Runtime library used for background removal added the internet permission and a telemetry component that contacts Microsoft's telemetry service. We measured a small amount of network traffic from version 1.3.0 test builds that still had this component: at least 41 KB sent over one day of heavy use.

The traffic is encrypted, so we could not inspect its contents. The library's telemetry includes usage data and a device identifier field. The amount is very small compared with the images processed, so we do not believe your photos were sent, but we cannot state this with certainty. DVScreator's own code never used the network.

Version 1.3.0 removes the permission and the component. Please update.

## What the app accesses and why
- **Photos, videos and GIFs you choose.** The app opens only the files you pick in the system picker, to turn them into stickers. They are processed on your device and never uploaded.
- **Stickers you received in WhatsApp**, only when you use the import feature and pick them yourself.
- **Your sticker packs** are saved in the app's private storage on your device. They are shared with WhatsApp or WhatsApp Business only when you tap "Add to WhatsApp", through WhatsApp's own sticker interface on your device.
- **Sticker keyboard.** If you enable it, it only shows your stickers and inserts the one you tap. It does not read, record or store anything you type.
- **Background removal** runs on your device with a built-in AI model. No image is sent anywhere.

## Data collection and sharing
- No personal data is collected.
- No data is shared with third parties.
- No analytics, advertising or crash-reporting services are used.
- No account is needed.

## Data deletion
All data stays on your device. Deleting a pack in the app, clearing the app's storage or uninstalling the app removes it. Stickers you already added to WhatsApp are then managed by WhatsApp.

## Children
The app is not directed at children under 13.

## Contact
If you email the support address shown in the app's Settings, your email and message are used only to answer you and are not shared.

Support: see the "Support" section in the app's Settings, or open an issue at https://github.com/Darkvolfi/DVScreator/issues

## Changes
Changes to this policy will be published on this page with a new date.

---

# DVScreator Gizlilik Politikası (Türkçe)

*Son güncelleme: 4 Kasım 2026*

DVScreator, çıkartma yapmak için ücretsiz ve açık kaynak bir uygulamadır. Bireysel geliştirici Darkvolfi tarafından geliştirilir.

**1.2.1 ve önceki sürümler için düzeltme:** 0.1.0–1.2.1 sürümleri "internete çıkmaz" denilerek yayımlandı; bu yanlıştı. Arka plan silmede kullanılan ONNX Runtime kütüphanesi internet iznini ve Microsoft'un telemetri servisine bağlanan bir bileşeni ekliyordu. Bu bileşeni hâlâ içeren 1.3.0 test sürümlerinde az miktarda trafik ölçtük: yoğun kullanılan bir günde en az 41 KB gönderim. Trafik şifreli olduğu için içeriğini göremedik. Kütüphanenin telemetrisinde kullanım verileri ve bir cihaz kimliği alanı var. Miktar işlenen görsellere göre çok küçük, bu yüzden fotoğrafların gönderildiğini düşünmüyoruz ama bunu kesin olarak söyleyemeyiz. DVScreator'ın kendi kodu ağı hiç kullanmadı. 1.3.0 bu izni ve bileşeni kaldırır; lütfen güncelle.

Aşağıdakiler 1.3.0 ve sonrası için geçerlidir:

- **Kişisel veri toplanmaz, paylaşılmaz, satılmaz.** Uygulamanın internet izni yoktur; cihazından dışarı hiçbir şey gönderemez.
- **Fotoğraf, video ve GIF'ler:** yalnızca senin seçtiğin dosyalar açılır, telefonda işlenir, hiçbir yere yüklenmez.
- **İçe aktarma:** WhatsApp'ta aldığın çıkartmalar yalnızca sen seçtiğinde kopyalanır.
- **Paketler:** uygulamanın cihazdaki özel alanında saklanır; yalnızca "WhatsApp'a ekle"ye bastığında WhatsApp'ın kendi çıkartma arayüzüyle WhatsApp'a verilir.
- **Çıkartma klavyesi:** yalnızca çıkartmalarını gösterir ve dokunduğunu ekler; yazdığın hiçbir şeyi okumaz, kaydetmez.
- **Arka plan silme:** cihazdaki yapay zekâ modeliyle çalışır, görsel gönderilmez.
- Analiz, reklam ve çökme raporlama servisi kullanılmaz; hesap gerekmez.
- **Silme:** her şey cihazındadır. Paketi silmek, uygulama verisini temizlemek ya da uygulamayı kaldırmak verileri siler.
- **İletişim:** uygulamanın Ayarlar bölümündeki destek adresi ya da GitHub Issues. E-postayla yazarsan e-postan yalnızca sana cevap vermek için kullanılır.
