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
    /** Yamanın sürümü: değişince model yeniden yamalanır (Gradle girdisi). */
    @get:Input abstract val patchVersion: Property<String>
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
        if (!target.exists() || !marker.exists() || marker.readText() != sha256.get() + patchVersion.get()) {
            target.writeBytes(OnnxDynamicSize.patch(cached.readBytes()) { logger.lifecycle(it) })
            marker.writeText(sha256.get() + patchVersion.get())
        }
    }
}

/**
 * Yayınlanan IS-Net modeli 1024×1024 girdiye SABİT. Uygulama aynı modeli iki boyutta çalıştırır: videoda 512
 * (~4 kat hızlı), fotoğrafta hızlı cihazlarda 1024 (ince parçalar kaybolmaz). Bu yüzden girdi/çıktının yükseklik
 * ve genişliği "h"/"w" adlı serbest boyut olur; boyutu çalışırken ORT sabitler (SessionOptions.setSymbolicDimensionValue).
 * Sabit ara şekil bilgisi (graph.value_info) atılır. Ağırlıklar aynen kalır.
 * Protobuf alanları (onnx.proto): ModelProto.graph=7; GraphProto.node=1, input=11, output=12, value_info=13;
 * NodeProto.input=1, output=2, name=3, op_type=4, attribute=5; AttributeProto.name=1, i=3, t=5, type=20;
 * TensorProto.dims=1, data_type=2, raw_data=9; ValueInfoProto.name=1, type=2; TypeProto.tensor_type=1;
 * Tensor.shape=2; TensorShapeProto.dim=1; Dimension.dim_value=1, dim_param=2.
 */
object OnnxDynamicSize {
    /**
     * Ayrıca: dışa aktarımda her büyütme `Resize(x, sizes = Concat(Shape(x)[0:2], Constant[h, w]))` olarak
     * 1024 girdiye göre SABİT hedef boyutla kaydedilmiş. Her sabit `Div(Shape(girdi)[2:4], [1024/h, 1024/w])`
     * ile değiştirilir: hedef boyut girdinin boyutundan hesaplanır.
     */
    fun patch(model: ByteArray, log: (String) -> Unit): ByteArray {
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
        val inputName = fields(model).filter { it.first == 7 }.flatMap { (_, g) -> fields(g).filter { it.first == 11 } }
            .first().let { (_, vi) -> fields(vi).first { it.first == 1 }.second.decodeToString() }
        val patched = ArrayList<String>()
        var sharedAdded = false
        val out = rewrite(model) { field, value ->
            if (field == 7) listOf(field to rewrite(value) { f, v ->
                when (f) {
                    1 -> {
                        // Ortak düğümler en başta: girdinin [h, w]'si (düğümler topolojik sırada olmalı)
                        val shared = if (sharedAdded) emptyList() else {
                            sharedAdded = true
                            listOf(
                                1 to node("Shape", listOf(inputName), DYN_SHAPE),
                                1 to constant(DYN_IDX, longArrayOf(2, 3)),
                                1 to node("Gather", listOf(DYN_SHAPE, DYN_IDX), DYN_HW, attribute("axis", intValue = 0)),
                            )
                        }
                        shared + dynamicSizeNodes(v, sizeConstants, patched).map { 1 to it }
                    }
                    11, 12 -> listOf(f to rewriteShape(v))
                    13 -> emptyList()
                    else -> listOf(f to v)
                }
            }) else listOf(field to value)
        }
        log("girdi: $inputName, değiştirilen sabitler: ${patched.distinct()}")
        if (patched.size != sizeConstants.size) throw GradleException("beklenmeyen model yapısı: ${patched.size}/${sizeConstants.size} sabit değişti")
        return out
    }

    private const val DYN_SHAPE = "dvs_input_shape"
    private const val DYN_IDX = "dvs_hw_index"
    private const val DYN_HW = "dvs_input_hw"

    /**
     * Hedef boyut sabiti ise (çıktısı [targets]'ta) Constant [h, w] → Constant [1024/h, 1024/w] + Div(girdi [h, w], o);
     * çıktı adı aynı kalır, onu kullanan Concat değişmez. Diğer düğümler aynen.
     */
    private fun dynamicSizeNodes(node: ByteArray, targets: Set<String>, patched: MutableList<String>): List<ByteArray> {
        val output = fields(node).firstOrNull { it.first == 2 }?.second?.decodeToString()
        if (output == null || output !in targets) return listOf(node)
        var values: LongArray? = null
        fields(node).filter { it.first == 5 }.forEach { (_, attr) ->
            fields(attr).filter { it.first == 5 }.forEach { (_, tensor) ->
                fields(tensor).filter { it.first == 9 }.forEach { (_, raw) ->
                    val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                    values = LongArray(raw.size / 8) { buf.getLong() }
                }
            }
        }
        val v = values ?: throw GradleException("$output sabitinin değeri okunamadı")
        val divisors = LongArray(v.size) { i ->
            if (v[i] <= 0 || 1024 % v[i] != 0L) throw GradleException("$output: ${v[i]} 1024'ü bölmüyor")
            1024 / v[i]
        }
        patched += "${v.toList()}→girdi/${divisors.toList()}"
        return listOf(constant("${output}_div", divisors), node("Div", listOf(DYN_HW, "${output}_div"), output))
    }

    private fun node(op: String, inputs: List<String>, output: String, vararg attributes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        inputs.forEach { out.write(bytesField(1, it.encodeToByteArray())) }
        out.write(bytesField(2, output.encodeToByteArray()))
        out.write(bytesField(3, output.encodeToByteArray()))
        out.write(bytesField(4, op.encodeToByteArray()))
        attributes.forEach { out.write(bytesField(5, it)) }
        return out.toByteArray()
    }

    /** int64 vektör sabiti. */
    private fun constant(output: String, values: LongArray): ByteArray {
        val raw = ByteBuffer.allocate(values.size * 8).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { putLong(it) } }.array()
        val tensor = intField(1, values.size.toLong()) + intField(2, 7) + bytesField(9, raw)   // dims, INT64, raw_data
        return node("Constant", emptyList(), output, attribute("value", tensor = tensor))
    }

    private fun attribute(name: String, intValue: Long? = null, tensor: ByteArray? = null): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(bytesField(1, name.encodeToByteArray()))
        if (intValue != null) { out.write(intField(3, intValue)); out.write(intField(20, 2)) }   // INT
        if (tensor != null) { out.write(bytesField(5, tensor)); out.write(intField(20, 4)) }    // TENSOR
        return out.toByteArray()
    }

    private fun bytesField(field: Int, value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarint(out, ((field shl 3) or 2).toLong()); writeVarint(out, value.size.toLong()); out.write(value)
        return out.toByteArray()
    }

    private fun fields(msg: ByteArray): List<Pair<Int, ByteArray>> {
        val result = ArrayList<Pair<Int, ByteArray>>()
        rewrite(msg) { f, v -> result += f to v; emptyList() }
        return result
    }

    /** ValueInfo → type → tensor_type → shape: 4 boyutlu şeklin 3. ve 4. boyutu "h"/"w" adlı serbest boyut olur. */
    private fun rewriteShape(valueInfo: ByteArray): ByteArray = rewrite(valueInfo) { f, v ->
        if (f != 2) return@rewrite listOf(f to v)
        listOf(f to rewrite(v) { tf, tv ->
            if (tf != 1) return@rewrite listOf(tf to tv)
            listOf(tf to rewrite(tv) { sf, sv ->
                if (sf != 2) return@rewrite listOf(sf to sv)
                var index = 0
                listOf(sf to rewrite(sv) { df, dv ->
                    val i = if (df == 1) index++ else -1
                    when (i) {
                        2 -> listOf(1 to bytesField(2, "h".encodeToByteArray()))
                        3 -> listOf(1 to bytesField(2, "w".encodeToByteArray()))
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
    patchVersion = "dinamik-hw"
    cacheDir = File(gradle.gradleUserHomeDir, "dvscreator-models")
    outputDir = layout.buildDirectory.dir("generated/models")
}

// Model kütüphanenin kendi varlıklarında değil: uygulama onu APK'ya (app) ya da Play asset pack'ine
// (:modelpack) kendisi koyar. Burada yalnızca cihaz testlerinin APK'sına eklenir.
androidComponents {
    onVariants { variant ->
        variant.androidTest?.sources?.assets?.addGeneratedSourceDirectory(downloadModel, DownloadModel::outputDir)
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
