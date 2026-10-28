import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.darkvolfi.dvscreator"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.darkvolfi.dvscreator"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 5
        // Sürüm: UYGULAMA.BÜYÜK.KÜÇÜK — 1 = uygulama; ortadaki büyük değişiklik / hata düzeltmesi turu; sonuncu küçük düzeltmeler
        versionName = "1.2.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Yayın imzası: keystore.properties (git dışında) varsa onunla; yoksa APK imzasız üretilir.
    // storeFile=…  storePassword=…  keyAlias=…  keyPassword=…
    val keystoreFile = rootProject.file("keystore.properties")
    signingConfigs {
        if (keystoreFile.exists()) {
            create("release") {
                val p = Properties().apply { keystoreFile.inputStream().use { load(it) } }
                storeFile = rootProject.file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    // İşlemci türüne göre ayrı APK: arka plan modeli (~170 MB) + ONNX Runtime her ABI için ~30 MB.
    // Tek "evrensel" APK ~330 MB olurdu; telefonların çoğu için arm64-v8a yeterli.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }
    // Model belleğe eşlenebilsin diye APK içinde sıkıştırılmadan durmalı
    androidResources {
        noCompress += "onnx"
        // Android 13+: telefonun Ayarlar → Uygulamalar → DVScreator → Dil ekranı (res/values-xx klasörlerinden)
        generateLocaleConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    // F-Droid / tekrarlanabilir derleme için Google'a özel bağımlılık meta verisi gömülmez
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:imaging"))
    implementation(project(":core:webp"))
    implementation(project(":core:whatsapp"))
    implementation(project(":feature:packs"))
    implementation(project(":feature:editor"))
    implementation(project(":feature:export"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

/** Depo kökündeki GPL-3.0 metnini uygulamaya koyar (Ayarlar → Lisans; GPL lisans metninin birlikte dağıtılmasını ister). */
abstract class LicenseAssetTask : DefaultTask() {
    @get:InputFile abstract val license: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val dir = outputDir.get().asFile.apply { mkdirs() }
        license.get().asFile.copyTo(File(dir, "LICENSE.txt"), overwrite = true)
    }
}

val licenseAsset = tasks.register<LicenseAssetTask>("licenseAsset") {
    license.set(rootProject.layout.projectDirectory.file("LICENSE"))
}
androidComponents {
    onVariants { variant -> variant.sources.assets?.addGeneratedSourceDirectory(licenseAsset, LicenseAssetTask::outputDir) }
}
