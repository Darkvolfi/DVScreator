import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.darkvolfi.dvscreator.core.segmentation"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // Model belleğe eşlenebilsin diye APK içinde sıkıştırılmadan durmalı
    androidResources { noCompress += "onnx" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

/**
 * Arka plan kaldırma modeli (agents/06 — S2): IS-Net general-use, Apache-2.0, ~170 MB.
 * GitHub 100 MB üstü dosya kabul etmediği için depoda DEĞİL: derlemede indirilir, SHA-256 doğrulanır,
 * kullanıcının Gradle klasöründe önbelleklenir (clean sonrası yeniden indirilmez).
 */
abstract class DownloadModel : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:Input abstract val fileName: Property<String>
    /** Modelin çalışacağı girdi boyutu (1024'ün böleni olmalı: 512, 768 …). */
    @get:Input abstract val inputSize: Property<Int>
    @get:Internal abstract val cacheDir: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val cached = cacheDir.get().asFile.resolve(fileName.get())
        if (!cached.exists() || Sha256.of(cached) != sha256.get()) {
            cached.parentFile.mkdirs()
            logger.lifecycle("Model indiriliyor: ${url.get()}")
            val tmp = File(cached.path + ".part")
            URI(url.get()).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            val actual = Sha256.of(tmp)
            if (actual != sha256.get()) {
                tmp.delete()
                throw GradleException("Model SHA-256 uyuşmuyor: $actual (beklenen ${sha256.get()})")
            }
            tmp.renameTo(cached)
        }
        val target = outputDir.get().asFile.resolve("models").apply { mkdirs() }.resolve(fileName.get())
        val marker = File(target.path + ".src")
        if (!target.exists() || !marker.exists() || marker.readText() != sha256.get() + inputSize.get()) {
            target.writeBytes(OnnxDynamicSize.patch(cached.readBytes(), inputSize.get()) { logger.lifecycle(it) })
            marker.writeText(sha256.get() + inputSize.get())
        }
    }
}

/**
 * Yayınlanan IS-Net modeli 1024×1024 girdiye SABİT ve telefonda kare başına ~3 sn sürüyor. Stickerlar en fazla
 * 512 px olduğu için daha küçük girdi yeterli (512 → ~4 kat hızlı): girdi/çıktı şekli hedef boyuta çevrilir,
 * sabit ara şekil bilgisi (graph.value_info) atılır. Ağırlıklar aynen kalır.
 * Protobuf alanları (onnx.proto): ModelProto.graph=7; GraphProto.input=11, output=12, value_info=13;
 * ValueInfoProto.type=2; TypeProto.tensor_type=1; Tensor.shape=2; TensorShapeProto.dim=1;
 * Dimension.dim_value=1, dim_param=2.
 */
object OnnxDynamicSize {
    /**
     * Ayrıca: dışa aktarımda her büyütme `Resize(x, sizes = Concat(Shape(x)[0:2], Constant[h, w]))` olarak
     * 1024 girdiye göre SABİT hedef boyutla kaydedilmiş. Bu sabitler [targetSize]/1024 oranında küçültülür —
     * model yalnızca [targetSize] girdiyle doğru çalışır.
     */
    fun patch(model: ByteArray, targetSize: Int, log: (String) -> Unit): ByteArray {
        // 1. geçiş: Resize'ların sizes girdisini üreten Concat'in sabit (Constant) girdilerini bul
        val nodes = ArrayList<Triple<String, List<String>, List<String>>>()   // op, girdiler, çıktılar
        fields(model).filter { it.first == 7 }.forEach { (_, graph) ->
            fields(graph).filter { it.first == 1 }.forEach { (_, node) ->
                val fs = fields(node)
                nodes += Triple(
                    fs.firstOrNull { it.first == 4 }?.second?.decodeToString().orEmpty(),
                    fs.filter { it.first == 1 }.map { it.second.decodeToString() },
                    fs.filter { it.first == 2 }.map { it.second.decodeToString() },
                )
            }
        }
        val producer = nodes.flatMap { n -> n.third.map { it to n } }.toMap()
        val sizeConstants = nodes.filter { it.first == "Resize" }.mapNotNull { it.second.getOrNull(3) }
            .mapNotNull { producer[it]?.takeIf { p -> p.first == "Concat" } }
            .flatMap { concat -> concat.second.filter { producer[it]?.first == "Constant" } }
            .toSet()
        log("Resize: ${nodes.count { it.first == "Resize" }}, sabit hedef boyut: ${sizeConstants.size}")
        val patched = ArrayList<String>()
        val out = rewrite(model) { field, value ->
            if (field == 7) listOf(field to rewrite(value) { f, v ->
                when (f) {
                    1 -> listOf(f to scaleConstantNode(v, sizeConstants, targetSize, patched))
                    11, 12 -> listOf(f to rewriteShape(v, targetSize))
                    13 -> emptyList()
                    else -> listOf(f to v)
                }
            }) else listOf(field to value)
        }
        log("değiştirilen sabitler: ${patched.distinct()}")
        if (patched.size != sizeConstants.size) throw GradleException("beklenmeyen model yapısı: ${patched.size}/${sizeConstants.size} sabit değişti")
        return out
    }

    /** Constant düğümünün değerindeki int64 [h, w] → [h·s/1024, w·s/1024]. */
    private fun scaleConstantNode(node: ByteArray, targets: Set<String>, size: Int, patched: MutableList<String>): ByteArray {
        val fs = fields(node)
        if (fs.none { it.first == 2 && it.second.decodeToString() in targets }) return node
        return rewrite(node) { f, v ->
            if (f != 5) return@rewrite listOf(f to v)                        // attribute
            listOf(f to rewrite(v) { af, av ->
                if (af != 5) return@rewrite listOf(af to av)                 // AttributeProto.t
                listOf(af to rewrite(av) { tf, tv ->
                    if (tf != 9) return@rewrite listOf(tf to tv)             // TensorProto.raw_data (int64 LE)
                    val buf = ByteBuffer.wrap(tv).order(ByteOrder.LITTLE_ENDIAN)
                    val values = LongArray(tv.size / 8) { buf.getLong() }
                    val scaled = values.map { v0 ->
                        if (v0 * size % 1024 != 0L) throw GradleException("$v0 boyutu $size için tam bölünmüyor")
                        v0 * size / 1024
                    }
                    patched += "${values.toList()}→$scaled"
                    val ob = ByteBuffer.allocate(tv.size).order(ByteOrder.LITTLE_ENDIAN)
                    scaled.forEach { ob.putLong(it) }
                    listOf(tf to ob.array())
                })
            })
        }
    }

    private fun fields(msg: ByteArray): List<Pair<Int, ByteArray>> {
        val result = ArrayList<Pair<Int, ByteArray>>()
        rewrite(msg) { f, v -> result += f to v; emptyList() }
        return result
    }

    /** ValueInfo → type → tensor_type → shape: 4 boyutlu şeklin 3. ve 4. boyutu "h"/"w" olur. */
    private fun rewriteShape(valueInfo: ByteArray, size: Int): ByteArray = rewrite(valueInfo) { f, v ->
        if (f != 2) return@rewrite listOf(f to v)
        listOf(f to rewrite(v) { tf, tv ->
            if (tf != 1) return@rewrite listOf(tf to tv)
            listOf(tf to rewrite(tv) { sf, sv ->
                if (sf != 2) return@rewrite listOf(sf to sv)
                var index = 0
                listOf(sf to rewrite(sv) { df, dv ->
                    val i = if (df == 1) index++ else -1
                    when (i) {
                        2, 3 -> listOf(1 to intField(1, size.toLong()))
                        else -> listOf(df to dv)
                    }
                })
            })
        })
    }

    private fun intField(field: Int, value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarint(out, (field shl 3).toLong()); writeVarint(out, value)
        return out.toByteArray()
    }

    /**
     * Mesajın alanlarını dolaşır; length-delimited (tip 2) alanlar [transform]'a verilir, diğerleri aynen kopyalanır.
     * transform yeni (alan, içerik) listesi döndürür — boş liste alanı siler.
     */
    private fun rewrite(msg: ByteArray, transform: (Int, ByteArray) -> List<Pair<Int, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream(msg.size)
        var pos = 0
        fun varint(): Long {
            var result = 0L; var shift = 0
            while (true) {
                val b = msg[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
        while (pos < msg.size) {
            val start = pos
            val key = varint()
            val field = (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> { varint(); out.write(msg, start, pos - start) }
                1 -> { pos += 8; out.write(msg, start, pos - start) }
                5 -> { pos += 4; out.write(msg, start, pos - start) }
                2 -> {
                    val len = varint().toInt()
                    val value = msg.copyOfRange(pos, pos + len)
                    pos += len
                    for ((f, v) in transform(field, value)) {
                        writeVarint(out, (f.toLong() shl 3) or 2); writeVarint(out, v.size.toLong()); out.write(v)
                    }
                }
                else -> throw GradleException("desteklenmeyen protobuf tipi: $key")
            }
        }
        return out.toByteArray()
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) { out.write(((v and 0x7F) or 0x80).toInt()); v = v ushr 7 }
        out.write(v.toInt())
    }
}

object Sha256 {
    fun of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

val downloadModel = tasks.register<DownloadModel>("downloadSegmentationModel") {
    url = "https://github.com/danielgatis/rembg/releases/download/v0.0.0/isnet-general-use.onnx"
    sha256 = "60920e99c45464f2ba57bee2ad08c919a52bbf852739e96947fbb4358c0d964a"
    fileName = "isnet-general-use.onnx"
    inputSize = 512
    cacheDir = File(gradle.gradleUserHomeDir, "dvscreator-models")
    outputDir = layout.buildDirectory.dir("generated/models")
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(downloadModel, DownloadModel::outputDir)
    }
}

dependencies {
    api(project(":core:imaging"))
    implementation(libs.onnxruntime.android)   // MIT
    implementation(libs.kotlinx.coroutines.android)

    androidTestImplementation(project(":core:webp"))
    androidTestImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
