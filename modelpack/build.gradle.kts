plugins {
    id("com.android.asset-pack")
}

/**
 * Google Play için arka plan modeli (~179 MB) ayrı bir install-time asset pack'te: Play'in temel
 * indirme sınırı 200 MB. Play paketi uygulamayla birlikte kurar; model yine AssetManager'dan
 * aynı yoldan (models/…) okunur. GitHub APK'larında model doğrudan APK'nın içindedir (app/build.gradle.kts).
 */
assetPack {
    packName = "modelpack"
    dynamicDelivery {
        deliveryType = "install-time"
    }
}

// Eklenti varlıkları yalnızca src/main/assets'ten alır: indirilen model oraya kopyalanır (git dışı).
val syncModel = tasks.register<Sync>("syncModel") {
    from(project(":core:segmentation").tasks.named("downloadSegmentationModel"))
    into(layout.projectDirectory.dir("src/main/assets"))
    exclude("**/*.src")
}
