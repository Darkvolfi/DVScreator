# DVScreator

An open-source Android app for making WhatsApp stickers from your photos, videos and GIFs — while keeping as much image quality as possible.

- 🎯 **Quality first:** every sticker is encoded with a best-fit search — lossless when it fits, otherwise the highest quality that stays within WhatsApp's size limit.
- 🔒 **Private:** no internet permission, no data collection. Everything, including background removal, runs on your phone.
- 🆓 **Free software:** GPL-3.0, no closed-source or Google Play Services dependencies.

## Features

- **Photos, videos and GIFs** become animated stickers (512×512, up to 500 KB, up to 10 s). Long or busy clips are fitted automatically by lowering quality or frame rate, never by silently cutting the end.
- **Editor:** square crop or fit the whole image, choose the time range of a video/GIF, rotate, margin, white or black outline, text with outline.
- **Background removal** with an on-device AI model (IS-Net), for photos and frame by frame for videos. Erase / restore brush for fine touches.
- **Sticker packs:** add up to 30 stickers per pack, pick emojis, and add or update the pack in WhatsApp and WhatsApp Business. Packs are checked against WhatsApp's rules before sending, with a clear message if something is wrong.
- **Single sticker:** make one sticker without building a pack; it goes into a "Quick stickers" pack.
- **Sticker keyboard:** send your stickers straight into a chat as stickers, without adding a pack.
- **Import from WhatsApp:** copy stickers you received in chats (or whole shared packs) into your own packs, without recompressing them.
- **11 languages:** English, Turkish, Spanish, Portuguese, German, French, Italian, Russian, Arabic, Indonesian and Hindi. The app follows your phone's language and can be changed in Settings.

## Download

Get the latest APK from [Releases](https://github.com/Darkvolfi/DVScreator/releases). `arm64-v8a` works on almost every phone; `armeabi-v7a` is for older 32-bit phones and `x86_64` for emulators.

Requires Android 7.0 or newer.

## Building

Requirements: Android Studio with the Android SDK, **NDK** and **CMake** (SDK Manager → SDK Tools). The Gradle wrapper downloads everything else.

```bash
git clone --recurse-submodules https://github.com/Darkvolfi/DVScreator.git
```

Open the project in Android Studio and run it, or build from the command line:

```bash
./gradlew assembleDebug testDebugUnitTest
```

Notes:

- The first build downloads the background-removal model (`isnet-general-use.onnx`, ~170 MB) from the [rembg](https://github.com/danielgatis/rembg) releases and caches it in the Gradle user home.
- Release builds are signed only if a `keystore.properties` file exists in the project root (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`); otherwise the release APK is unsigned. This file is never committed.

### Project structure

| Module | Purpose |
|---|---|
| `app` | Entry point, settings, quality lab (developer screen) |
| `feature/packs` | Packs, single sticker, import, sticker keyboard, language setting |
| `feature/editor` | Sticker editor |
| `core/imaging` | Image/video/GIF loading, resizing, encoding pipeline |
| `core/webp` | libwebp via JNI (`third_party/libwebp` submodule) |
| `core/segmentation` | On-device background removal (ONNX Runtime) |
| `core/whatsapp` | WhatsApp integration: content provider, pack storage, validation |
| `core/model` | Shared data types |

## Translations

Strings live in `res/values*/strings.xml` in the `app`, `feature/packs`, `feature/editor` and `core/whatsapp` modules. Corrections and new languages are welcome — open an issue or a pull request.

## Support

Questions, bug reports or ideas: **support@darkvolfi.com**, or open an [issue](https://github.com/Darkvolfi/DVScreator/issues).

## License

[GPL-3.0](LICENSE). WhatsApp is a trademark of WhatsApp LLC; this project is not affiliated with or endorsed by WhatsApp.
