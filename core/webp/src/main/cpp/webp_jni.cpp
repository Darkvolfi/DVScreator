#include <jni.h>
#include <cstdio>
#include <cstring>
#include <vector>

#ifdef HAVE_LIBWEBP
#include "webp/decode.h"
#include "webp/demux.h"
#include "webp/encode.h"
#include "webp/mux.h"
#endif

// Pikseller Kotlin tarafında IntArray olarak, non-premultiplied 0xAARRGGBB biçimindedir.
// Android (little-endian) belleğinde bu bayt dizilimi B,G,R,A'dır → libwebp'nin BGRA fonksiyonları.

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_darkvolfi_wscreator_core_webp_NativeWebp_encoderVersion(JNIEnv *env, jobject) {
#ifdef HAVE_LIBWEBP
    const int v = WebPGetEncoderVersion();
    char buf[16];
    std::snprintf(buf, sizeof(buf), "%d.%d.%d", (v >> 16) & 0xff, (v >> 8) & 0xff, v & 0xff);
    return env->NewStringUTF(buf);
#else
    return env->NewStringUTF("yok");
#endif
}

#ifdef HAVE_LIBWEBP
namespace {

void throwIllegalState(JNIEnv *env, const char *msg) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls != nullptr) env->ThrowNew(cls, msg);
}

/** Kotlin WebpOptions → WebPConfig. Hata durumunda istisna atar ve false döner. */
bool buildConfig(JNIEnv *env, WebPConfig *config,
                 jboolean lossless, jint losslessLevel, jfloat quality, jint method,
                 jint nearLossless, jint alphaQuality, jint alphaFiltering, jboolean sharpYuv, jboolean exact) {
    if (!WebPConfigInit(config)) {
        throwIllegalState(env, "WebPConfigInit basarisiz (libwebp surum uyusmazligi)");
        return false;
    }
    if (lossless) {
        if (!WebPConfigLosslessPreset(config, losslessLevel)) {
            throwIllegalState(env, "gecersiz lossless seviyesi");
            return false;
        }
        config->near_lossless = nearLossless;
    } else {
        config->lossless = 0;
        config->quality = quality;
        config->method = method;
        config->use_sharp_yuv = sharpYuv ? 1 : 0;
    }
    config->alpha_quality = alphaQuality;
    config->alpha_compression = 1;            // alfa kanalı her zaman kayıpsız sıkıştırılır
    config->alpha_filtering = alphaFiltering;  // 0 yok, 1 hızlı, 2 en iyi (çok yavaş)
    config->exact = exact ? 1 : 0;
    if (!WebPValidateConfig(config)) {
        throwIllegalState(env, "gecersiz WebP ayarlari");
        return false;
    }
    return true;
}

/** IntArray ARGB → WebPPicture (ARGB modunda). Çağıran WebPPictureFree ile serbest bırakır. */
bool importPicture(JNIEnv *env, WebPPicture *picture, jintArray argb, jint width, jint height) {
    if (width <= 0 || height <= 0 || env->GetArrayLength(argb) < width * height) {
        throwIllegalState(env, "gecersiz goruntu boyutu");
        return false;
    }
    if (!WebPPictureInit(picture)) {
        throwIllegalState(env, "WebPPictureInit basarisiz");
        return false;
    }
    picture->width = width;
    picture->height = height;
    picture->use_argb = 1;
    jint *pixels = env->GetIntArrayElements(argb, nullptr);
    if (pixels == nullptr) return false;  // OutOfMemoryError zaten atıldı
    const int ok = WebPPictureImportBGRA(picture, reinterpret_cast<const uint8_t *>(pixels), width * 4);
    env->ReleaseIntArrayElements(argb, pixels, JNI_ABORT);  // salt okunur; geri kopyalama yok
    if (!ok) {
        WebPPictureFree(picture);
        throwIllegalState(env, "piksel aktarimi basarisiz (bellek?)");
        return false;
    }
    return true;
}

jbyteArray toByteArray(JNIEnv *env, const uint8_t *data, size_t size) {
    jbyteArray out = env->NewByteArray(static_cast<jsize>(size));
    if (out != nullptr) {
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte *>(data));
    }
    return out;
}

}  // namespace
#endif

/**
 * Tek kareli (statik) WebP.
 * @param lossless       1 = kayıpsız (VP8L), 0 = kayıplı (VP8)
 * @param losslessLevel  0..9, yalnızca kayıpsızda (WebPConfigLosslessPreset)
 * @param quality        kayıplıda 0..100 kalite
 * @param method         0..6, kayıplıda hız/sıkıştırma dengesi
 * @param nearLossless   0..100 (100 = kapalı), yalnızca kayıpsızda
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_github_darkvolfi_wscreator_core_webp_NativeWebp_nativeEncode(
        JNIEnv *env, jobject, jintArray argb, jint width, jint height,
        jboolean lossless, jint losslessLevel, jfloat quality, jint method,
        jint nearLossless, jint alphaQuality, jint alphaFiltering, jboolean sharpYuv, jboolean exact) {
#ifdef HAVE_LIBWEBP
    WebPConfig config;
    if (!buildConfig(env, &config, lossless, losslessLevel, quality, method,
                     nearLossless, alphaQuality, alphaFiltering, sharpYuv, exact)) {
        return nullptr;
    }
    WebPPicture picture;
    if (!importPicture(env, &picture, argb, width, height)) return nullptr;

    WebPMemoryWriter writer;
    WebPMemoryWriterInit(&writer);
    picture.writer = WebPMemoryWrite;
    picture.custom_ptr = &writer;

    const int ok = WebPEncode(&config, &picture);
    const WebPEncodingError error = picture.error_code;
    WebPPictureFree(&picture);
    if (!ok) {
        WebPMemoryWriterClear(&writer);
        char msg[48];
        std::snprintf(msg, sizeof(msg), "WebPEncode hatasi: %d", static_cast<int>(error));
        throwIllegalState(env, msg);
        return nullptr;
    }
    jbyteArray out = toByteArray(env, writer.mem, writer.size);
    WebPMemoryWriterClear(&writer);
    return out;
#else
    return nullptr;
#endif
}

/**
 * Hareketli WebP (WebPAnimEncoder). Kareler arası farkı kendisi bulur; değişmeyen bölgeler
 * neredeyse sıfır bayt tutar. DİKKAT: tamamen aynı ardışık kareleri tek kareye birleştirir.
 *
 * @param frames     her biri width*height ARGB
 * @param durations  her karenin süresi (ms)
 * @param loopCount  0 = sonsuz döngü
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_github_darkvolfi_wscreator_core_webp_NativeWebp_nativeEncodeAnimation(
        JNIEnv *env, jobject, jobjectArray frames, jintArray durations, jint width, jint height,
        jint loopCount, jboolean lossless, jint losslessLevel, jfloat quality, jint method,
        jint nearLossless, jint alphaQuality, jint alphaFiltering, jboolean sharpYuv, jboolean exact) {
#ifdef HAVE_LIBWEBP
    const jsize count = env->GetArrayLength(frames);
    if (count < 1 || env->GetArrayLength(durations) != count) {
        throwIllegalState(env, "kare ve sure sayisi uyusmuyor");
        return nullptr;
    }
    WebPConfig config;
    if (!buildConfig(env, &config, lossless, losslessLevel, quality, method,
                     nearLossless, alphaQuality, alphaFiltering, sharpYuv, exact)) {
        return nullptr;
    }

    WebPAnimEncoderOptions animOptions;
    if (!WebPAnimEncoderOptionsInit(&animOptions)) {
        throwIllegalState(env, "WebPAnimEncoderOptionsInit basarisiz");
        return nullptr;
    }
    animOptions.anim_params.loop_count = loopCount;
    animOptions.anim_params.bgcolor = 0x00000000;  // şeffaf
    WebPAnimEncoder *encoder = WebPAnimEncoderNew(width, height, &animOptions);
    if (encoder == nullptr) {
        throwIllegalState(env, "WebPAnimEncoderNew basarisiz");
        return nullptr;
    }

    std::vector<jint> durationValues(count);
    env->GetIntArrayRegion(durations, 0, count, durationValues.data());

    int timestamp = 0;
    for (jsize i = 0; i < count; ++i) {
        auto frame = static_cast<jintArray>(env->GetObjectArrayElement(frames, i));
        WebPPicture picture;
        const bool imported = importPicture(env, &picture, frame, width, height);
        env->DeleteLocalRef(frame);
        if (!imported) {
            WebPAnimEncoderDelete(encoder);
            return nullptr;
        }
        const int ok = WebPAnimEncoderAdd(encoder, &picture, timestamp, &config);
        WebPPictureFree(&picture);
        if (!ok) {
            throwIllegalState(env, WebPAnimEncoderGetError(encoder));
            WebPAnimEncoderDelete(encoder);
            return nullptr;
        }
        timestamp += durationValues[i];
    }
    // Son karenin süresini belirlemek için bitiş zaman damgası
    WebPData data;
    WebPDataInit(&data);
    if (!WebPAnimEncoderAdd(encoder, nullptr, timestamp, nullptr) ||
        !WebPAnimEncoderAssemble(encoder, &data)) {
        throwIllegalState(env, WebPAnimEncoderGetError(encoder));
        WebPAnimEncoderDelete(encoder);
        return nullptr;
    }
    WebPAnimEncoderDelete(encoder);
    jbyteArray out = toByteArray(env, data.bytes, data.size);
    WebPDataClear(&data);
    return out;
#else
    return nullptr;
#endif
}

/**
 * Statik bir WebP'yi, görünümü değişmeyen 2 kareli hareketli WebP'ye sarar (agents/02 — 7b).
 * 1. kare: verilen görüntü (tam tuval). 2. kare: sol üst köşede 2×2 tamamen şeffaf, alfa
 * karıştırmalı (blend) — tuvalde hiçbir pikseli değiştirmez. Mux seviyesinde yapılır çünkü
 * WebPAnimEncoder aynı kareleri tek kareye birleştirirdi.
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_github_darkvolfi_wscreator_core_webp_NativeWebp_nativeStaticAsAnimation(
        JNIEnv *env, jobject, jbyteArray staticWebp, jint canvasWidth, jint canvasHeight,
        jint firstDurationMs, jint secondDurationMs) {
#ifdef HAVE_LIBWEBP
    const jsize size = env->GetArrayLength(staticWebp);
    std::vector<uint8_t> first(static_cast<size_t>(size));
    env->GetByteArrayRegion(staticWebp, 0, size, reinterpret_cast<jbyte *>(first.data()));

    const uint8_t transparent[2 * 2 * 4] = {0};
    uint8_t *secondData = nullptr;
    const size_t secondSize = WebPEncodeLosslessBGRA(transparent, 2, 2, 2 * 4, &secondData);
    if (secondSize == 0) {
        throwIllegalState(env, "2x2 kare kodlanamadi");
        return nullptr;
    }

    WebPMux *mux = WebPMuxNew();
    WebPMuxFrameInfo frame;
    std::memset(&frame, 0, sizeof(frame));
    frame.id = WEBP_CHUNK_ANMF;
    frame.dispose_method = WEBP_MUX_DISPOSE_NONE;

    frame.bitstream = {first.data(), first.size()};
    frame.duration = firstDurationMs;
    frame.blend_method = WEBP_MUX_NO_BLEND;
    WebPMuxError err = WebPMuxPushFrame(mux, &frame, 1);

    if (err == WEBP_MUX_OK) {
        frame.bitstream = {secondData, secondSize};
        frame.duration = secondDurationMs;
        frame.blend_method = WEBP_MUX_BLEND;
        err = WebPMuxPushFrame(mux, &frame, 1);
    }
    WebPFree(secondData);

    WebPMuxAnimParams params = {0x00000000, 0};  // şeffaf zemin, sonsuz döngü
    if (err == WEBP_MUX_OK) err = WebPMuxSetAnimationParams(mux, &params);
    if (err == WEBP_MUX_OK) err = WebPMuxSetCanvasSize(mux, canvasWidth, canvasHeight);

    WebPData assembled;
    WebPDataInit(&assembled);
    if (err == WEBP_MUX_OK) err = WebPMuxAssemble(mux, &assembled);
    WebPMuxDelete(mux);
    if (err != WEBP_MUX_OK) {
        char msg[48];
        std::snprintf(msg, sizeof(msg), "WebPMux hatasi: %d", static_cast<int>(err));
        throwIllegalState(env, msg);
        return nullptr;
    }
    jbyteArray out = toByteArray(env, assembled.bytes, assembled.size);
    WebPDataClear(&assembled);
    return out;
#else
    return nullptr;
#endif
}

/** Dönüş: [genişlik, yükseklik, piksel0, piksel1, ...] — pikseller non-premultiplied ARGB. */
extern "C" JNIEXPORT jintArray JNICALL
Java_io_github_darkvolfi_wscreator_core_webp_NativeWebp_nativeDecode(
        JNIEnv *env, jobject, jbyteArray data) {
#ifdef HAVE_LIBWEBP
    const jsize size = env->GetArrayLength(data);
    jbyte *bytes = env->GetByteArrayElements(data, nullptr);
    if (bytes == nullptr) return nullptr;

    int width = 0, height = 0;
    uint8_t *bgra = WebPDecodeBGRA(reinterpret_cast<const uint8_t *>(bytes),
                                   static_cast<size_t>(size), &width, &height);
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    if (bgra == nullptr) {
        throwIllegalState(env, "WebP cozulemedi");
        return nullptr;
    }

    const jsize count = width * height;
    jintArray out = env->NewIntArray(count + 2);
    if (out != nullptr) {
        const jint header[2] = {width, height};
        env->SetIntArrayRegion(out, 0, 2, header);
        env->SetIntArrayRegion(out, 2, count, reinterpret_cast<const jint *>(bgra));
    }
    WebPFree(bgra);
    return out;
#else
    return nullptr;
#endif
}

/** Dönüş: [tuvalGenişlik, tuvalYükseklik, kareSayısı, döngüSayısı, süre1, süre2, ...] (ms). */
extern "C" JNIEXPORT jintArray JNICALL
Java_io_github_darkvolfi_wscreator_core_webp_NativeWebp_nativeInfo(
        JNIEnv *env, jobject, jbyteArray data) {
#ifdef HAVE_LIBWEBP
    const jsize size = env->GetArrayLength(data);
    std::vector<uint8_t> bytes(static_cast<size_t>(size));
    env->GetByteArrayRegion(data, 0, size, reinterpret_cast<jbyte *>(bytes.data()));
    WebPData webpData = {bytes.data(), bytes.size()};
    WebPDemuxer *demux = WebPDemux(&webpData);
    if (demux == nullptr) {
        throwIllegalState(env, "WebP ayristirilamadi");
        return nullptr;
    }
    std::vector<jint> info = {
            static_cast<jint>(WebPDemuxGetI(demux, WEBP_FF_CANVAS_WIDTH)),
            static_cast<jint>(WebPDemuxGetI(demux, WEBP_FF_CANVAS_HEIGHT)),
            static_cast<jint>(WebPDemuxGetI(demux, WEBP_FF_FRAME_COUNT)),
            static_cast<jint>(WebPDemuxGetI(demux, WEBP_FF_LOOP_COUNT)),
    };
    WebPIterator iter;
    if (WebPDemuxGetFrame(demux, 1, &iter)) {
        do {
            info.push_back(iter.duration);
        } while (WebPDemuxNextFrame(&iter));
        WebPDemuxReleaseIterator(&iter);
    }
    WebPDemuxDelete(demux);
    jintArray out = env->NewIntArray(static_cast<jsize>(info.size()));
    if (out != nullptr) env->SetIntArrayRegion(out, 0, static_cast<jsize>(info.size()), info.data());
    return out;
#else
    return nullptr;
#endif
}
