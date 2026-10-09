# 03 — Mimari

## Teknoloji
| Alan | Seçim | Not |
|---|---|---|
| Dil | Kotlin 2.x | |
| UI | Jetpack Compose + Material 3 | Dinamik renk (Material You) |
| Mimari | MVVM + tek yönlü veri akışı (UiState / Event) | |
| DI | Koin | Hafif; Hilt'e geçiş gerekirse kolay |
| Veritabanı | Room | Paket ve sticker meta verisi |
| Asenkron | Coroutines + Flow | |
| Görsel gösterimi | Coil 3 | |
| Native | CMake + NDK, **libwebp** (git submodule) | Kodlama, Lanczos |
| Test | JUnit5, Robolectric, Compose UI test, Roborazzi (ekran görüntüsü testi) | |
| Build | Gradle Kotlin DSL + version catalog (`libs.versions.toml`) | |
| minSdk / compileSdk / targetSdk | 24 / 37 / 37 (Android 7.0 → Android 17) | Photo Picker 24+'da backport ile çalışır |
| Araç zinciri | AGP 9.4, Gradle 9.8, Kotlin 2.4, NDK 28 | AGP 9 Kotlin'i kendi içinde getirir → Android modüllerinde `kotlin-android` eklentisi **yok** |
| Paket adı | `io.github.darkvolfi.wscreator` | |

## Modüller
```
:app                      → Activity, navigation, DI kurulumu
:core:model               → Saf Kotlin veri sınıfları (StickerPack, Sticker, EditRecipe)
:core:imaging             → Decode, renk, kırpma, ölçekleme, alfa, SSIM (saf Kotlin + JNI çağrıları)
:core:webp                → libwebp JNI köprüsü (encode, animEncode, decode)
:core:data                → Room, dosya deposu (StickerStorage), repository'ler
:core:whatsapp            → ContentProvider, PackValidator, WhatsApp'a ekleme intent'leri
:feature:packs            → Paket listesi, paket detay ekranı
:feature:editor           → Sticker düzenleyici (kırp, silgi, metin, önizleme)
:feature:export           → Kalite sonuç ekranı, dışa aktarma akışı
```
Bağımlılık yönü: `feature → core`, `core` modülleri birbirine yalnızca aşağı doğru
(`imaging → webp`, `data → model`, `whatsapp → data`).

## Dosya yerleşimi (uygulama içi depolama)
```
files/
  sources/{stickerId}/original.<ext>     ← kullanıcının seçtiği orijinal, dokunulmaz
  sources/{stickerId}/mask.png           ← silgi maskesi (orijinal çözünürlük)
  sources/{stickerId}/recipe.json        ← kırpma, döndürme, metin, kenar boşluğu
  packs/{packId}/{stickerId}.webp        ← WhatsApp'a sunulan çıktı
  packs/{packId}/tray.png
```

## Veri modeli (özet)
```kotlin
data class StickerPack(
    val id: String, val name: String, val publisher: String,
    val animated: Boolean, val imageDataVersion: Int,
    val trayStickerId: String?, val createdAt: Long
)
data class Sticker(
    val id: String, val packId: String, val position: Int,
    val emojis: List<String>,          // 0..3
    val sourcePath: String, val recipe: EditRecipe,
    val output: ExportResult?          // dosya yolu, boyut, mod, ssim
)
```

## Ana akış
1. Paketler ekranı → "+" → Photo Picker (çoklu seçim)
2. Her görsel için Editör → Kaydet → arka planda `ExportWorker` kalite hattını çalıştırır
3. Paket detayında her sticker'ın kalite rozeti (Kayıpsız / q=92 …)
4. "WhatsApp'a ekle" → `PackValidator` → `imageDataVersion++` → intent

## İzinler
- **Yok.** (Photo Picker izin gerektirmez; INTERNET yok.)
- Manifest: `<queries>` içinde `com.whatsapp`, `com.whatsapp.w4b`.
