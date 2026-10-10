# JNI: native kodun çağırdığı Kotlin sınıfları korunur
-keep class io.github.darkvolfi.dvscreator.core.webp.** { *; }

# ONNX Runtime: Java sınıflarına native koddan (JNI) adla erişilir
-keep class ai.onnxruntime.** { *; }

# Paket JSON'u (kotlinx.serialization): eski paket dosyaları okunabilsin diye model sınıfları adıyla kalır
-keep class io.github.darkvolfi.dvscreator.core.model.** { *; }
-keep class io.github.darkvolfi.dvscreator.core.whatsapp.PackManifest { *; }
-keep class io.github.darkvolfi.dvscreator.core.whatsapp.StickerEntry { *; }
