# F-Droid

What stands between Alyo and F-Droid's main repository.

## Fine already

- GPL-3.0, and every dependency is under a free license.
- No Google Play Services, analytics, ads or tracking.
- No self-updater.
- llama.cpp is built from source.
- Models are freely licensed, downloaded at run time, and can be imported from files instead.
- Store text is in `fastlane/metadata/android/`.

## Blockers

**sherpa-onnx is a prebuilt AAR.** `app/build.gradle.kts` downloads it from the project's GitHub
release. F-Droid does not accept prebuilt binaries, so its recipe has to build sherpa-onnx and
ONNX Runtime with the NDK. Other F-Droid apps do this, but ONNX Runtime's Android build is long.

**The build uses the network.** llama.cpp comes in through CMake FetchContent, and the two bundled
models (CED tiny, pyannote segmentation) are fetched at configuration time. F-Droid builds have no
network after checkout, so llama.cpp and sherpa-onnx need to become submodules or srclibs, and the
two models need to be downloaded at run time like the rest or come from a source F-Droid accepts.

**One voice is not free.** The HFC voice was trained on recordings under CC BY-NC-SA 4.0. It is
downloaded only if the user picks it, but F-Droid may still want it removed or flagged
(NonFreeAssets). Every other voice and model is under a free license.

**The repository has to be public.**

## Until then

The releases here work with Obtainium.
