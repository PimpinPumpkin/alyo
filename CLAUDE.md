# Alyo

Voice typing for Android: a voice-only keyboard with on-device speech recognition that types
while you talk. README.md covers how it works.

## Rules

Enforced by `scripts/check-writing.sh` (git hooks and CI). Run `bash scripts/install-hooks.sh`
once per clone.

- No AI attribution anywhere: no `Co-Authored-By` trailer, no "generated with" line.
- US English.
- No em dashes.
- Commit subjects are the changelog. Release notes use them as written, so make each one a plain
  sentence about what changed for the user. Prefix documentation-only commits with `Docs:`.

## Releases

- Work on `canary`. Every push rebuilds the `canary` release.
- Fast-forward `main` when it is ready. A daily job cuts a `v0.1.<run>` nightly if `main` moved.
- `promote-stable.yml` promotes the newest nightly to stable every Monday.
- The `models` release holds the model files every install downloads. Never delete it or anything
  in it. To change a model, upload the new files, add a `ModelSpec` with a new id, and list the
  old one under `replaces`.
- The APK ships a trimmed ONNX Runtime. After adding any model, rerun
  `scripts/build-onnxruntime.sh <dir with every model>` or the new model may fail to load.

## Building

`./gradlew assembleDebug` with JDK 17 and Android SDK platform 37. Signing reads
`ALYO_KEYSTORE_PATH`, `ALYO_KEYSTORE_PASSWORD` and `ALYO_KEY_ALIAS`, and falls back to the
debug key.

## Testing

The text logic (spacing, capitals, spoken punctuation, undo) is plain Kotlin with unit tests:
`./gradlew testDebugUnitTest`.

Debug builds, and release builds made with `-PtestHooks`, dictate from `test.wav` (16-bit mono,
16 kHz) in place of the microphone if that file exists in the app's files folder or its external
files folder. `--es theme light|dark` on MainActivity shows the other theme.

To drive the keyboard on an emulator: `adb shell ime enable app.alyo/.ime.AlyoIme`, focus a text
field, then `adb shell ime set app.alyo/.ime.AlyoIme`. Dictation starts as soon as the panel shows.
