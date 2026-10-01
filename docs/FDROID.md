# Getting into F-Droid's main repository

Where this stands, checked against F-Droid's inclusion policy.

## Already fine

- License: GPL-3.0, and every dependency is under a free license (Apache 2.0, MIT, OFL, CC BY 4.0).
- No Google Play Services, Firebase, analytics, ads or tracking.
- No self-updater. Updates come from the store.
- llama.cpp is built from source as part of the Gradle build.
- Models are downloaded at run time from their publishers and are freely licensed. The offline
  flavor does not touch the network at all, and is the one to submit.
- Store text and screenshots are in `fastlane/metadata/android/`.

## The blocker

**sherpa-onnx is taken as a prebuilt AAR** (`app/build.gradle.kts` downloads it from the project's
GitHub release). F-Droid builds everything from source and does not accept prebuilt binaries, so
the recipe has to build sherpa-onnx, and ONNX Runtime under it, with the NDK. Both are open
source and other F-Droid apps do this, but it is real work: ONNX Runtime's Android build is long
and has its own toolchain needs.

The route: add sherpa-onnx as a source dependency (a submodule or an F-Droid srclib), build its
JNI library and ONNX Runtime for arm64-v8a in the recipe's `build:` step, and point the app at the
result instead of the downloaded AAR. llama.cpp should move from FetchContent to a submodule at
the same time, since F-Droid's build has no network after checkout.

## Also needed

- The repository has to be public.
- Reproducible builds are optional but worth aiming for, so F-Droid can ship the developer-signed APK.
- Until then, the releases here work with Obtainium, or a self-hosted F-Droid repo can serve the
  CI-built APKs as they are.
