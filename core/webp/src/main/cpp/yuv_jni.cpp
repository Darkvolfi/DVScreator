#include <jni.h>
#include <cstdint>
#include <cmath>

// YUV_420_888 (MediaCodec çıkışı) → 0xAARRGGBB, f×f alan ortalamasıyla küçülterek.
// Kotlin karşılığı: core/imaging VideoFrameExtractor. Kotlin döngüsü debug derlemede kare başına
// ~200 ms sürüyordu (1280×720); burada aynı iş birkaç ms.

namespace {
inline int clamp255(float v) { return v < 0.f ? 0 : (v > 255.f ? 255 : static_cast<int>(v + 0.5f)); }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_darkvolfi_dvscreator_core_webp_NativeImage_nativeYuvToArgb(
        JNIEnv *env, jobject,
        jobject yBuf, jint yRow, jint yPix,
        jobject uBuf, jint uRow, jint uPix,
        jobject vBuf, jint vRow, jint vPix,
        jint cropLeft, jint cropTop, jint outW, jint outH, jint factor,
        jboolean bt709, jboolean fullRange, jintArray out) {
    auto *y = static_cast<const uint8_t *>(env->GetDirectBufferAddress(yBuf));
    auto *u = static_cast<const uint8_t *>(env->GetDirectBufferAddress(uBuf));
    auto *v = static_cast<const uint8_t *>(env->GetDirectBufferAddress(vBuf));
    if (y == nullptr || u == nullptr || v == nullptr) return JNI_FALSE;   // doğrudan tampon değil
    if (env->GetArrayLength(out) < outW * outH) return JNI_FALSE;

    const float yScale = fullRange ? 1.f : 255.f / 219.f;
    const float yOffset = fullRange ? 0.f : 16.f;
    const float cScale = fullRange ? 1.f : 255.f / 224.f;
    const float kRv = bt709 ? 1.5748f : 1.402f;
    const float kGu = bt709 ? 0.1873f : 0.344136f;
    const float kGv = bt709 ? 0.4681f : 0.714136f;
    const float kBu = bt709 ? 1.8556f : 1.772f;
    const float invN = 1.f / static_cast<float>(factor * factor);

    jint *dst = env->GetIntArrayElements(out, nullptr);
    if (dst == nullptr) return JNI_FALSE;
    for (int oy = 0; oy < outH; ++oy) {
        const int sy0 = cropTop + oy * factor;
        const int cy = (sy0 + factor / 2) / 2;
        jint *row = dst + oy * outW;
        for (int ox = 0; ox < outW; ++ox) {
            const int sx0 = cropLeft + ox * factor;
            int sum = 0;
            for (int dy = 0; dy < factor; ++dy) {
                const uint8_t *p = y + (sy0 + dy) * yRow + sx0 * yPix;
                for (int dx = 0; dx < factor; ++dx) { sum += *p; p += yPix; }
            }
            const int cx = (sx0 + factor / 2) / 2;
            const float uu = (static_cast<float>(u[cy * uRow + cx * uPix]) - 128.f) * cScale;
            const float vv = (static_cast<float>(v[cy * vRow + cx * vPix]) - 128.f) * cScale;
            const float yy = (static_cast<float>(sum) * invN - yOffset) * yScale;
            const int r = clamp255(yy + kRv * vv);
            const int g = clamp255(yy - kGu * uu - kGv * vv);
            const int b = clamp255(yy + kBu * uu);
            row[ox] = static_cast<jint>(0xFF000000u | (r << 16) | (g << 8) | b);
        }
    }
    env->ReleaseIntArrayElements(out, dst, 0);
    return JNI_TRUE;
}
