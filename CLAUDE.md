# Alyo

Speech for Android without Google, in one app: a voice-only keyboard that types while you talk,
a recorder that transcribes as it records, and a text-to-speech engine. The first two share one
on-device engine and one set of models. README.md covers how it works.

## Rules

Enforced by `scripts/check-writing.sh` (git hooks and CI). Run `bash scripts/install-hooks.sh`
once per clone.

- No AI attribution anywhere: no `Co-Authored-By` trailer, no "generated with" line.
- US English.
- No em dashes.
- Commit subjects are the changelog. Release notes use them as written, so make each one a plain
  sentence about what changed for the user. Prefix documentation-only commits with `Docs:`.

## Layout

- `asr/` the shared engine: model catalog and downloads, live and second-pass transcription.
- `dictation/`, `ime/`, `recognition/`, `text/` voice typing.
- `audio/`, `data/`, `playback/`, `export/`, `llm/`, `service/` the recorder.
- `data/Library.kt` moves each finished recording's audio out to the recordings folder
  (`Documents/Alyo` through the media store, or a folder picked with the system picker) and keeps
  a sidecar file beside it. `RecordingStore.audio(id)` only exists until that move;
  use `source(id)` to play and `localAudio(id)` where a real file is needed.
- `llm-fast/` is a second build of `app/src/main/cpp` (the summary engine) for processors with
  dot-product instructions. `Llama.kt` picks one at load. A change to the CMake file affects both.
- `tts/` read aloud: the Piper engine, the Android `TextToSpeechService`, and the sheet that
  reads shared or selected text.
- `data/Settings.kt` holds `Feature`: each feature lists the manifest components it owns. They are
  disabled in the manifest and enabled when the user turns the feature on, so a new feature's
  components must be added there or they will never appear.

## Releases

- Work on `canary`. Every push rebuilds the `canary` release.
- Fast-forward `main` when it is ready. A daily job cuts a `v0.1.<run>` nightly if `main` moved.
- `promote-stable.yml` promotes the newest nightly to stable every Monday.
- The `models` release holds the model files every install downloads. Never delete it or anything
  in it. To change a model, upload the new files, add a `ModelSpec` with a new id, and list the
  old one under `replaces`.
- The APK ships a trimmed ONNX Runtime. After adding any model, rerun
  `scripts/build-onnxruntime.sh <dir with every model>` or the new model may fail to load.

## Building and testing

`./gradlew assembleDebug` with JDK 17 and Android SDK platform 37. Signing reads
`ALYO_KEYSTORE_PATH`, `ALYO_KEYSTORE_PASSWORD` and `ALYO_KEY_ALIAS`, and falls back to the debug
key. `./gradlew testDebugUnitTest` runs the unit tests.

Debug builds, and release builds made with `-PtestHooks`:

- `--es wav <path>` on MainActivity records from a WAV file, and `--es theme light|dark` shows
  the other theme.
- Voice typing dictates `test.wav` (16-bit mono, 16 kHz) in place of the microphone if that file
  is in the app's files folder or its external files folder. To drive the keyboard:
  `adb shell ime enable app.alyo/.ime.AlyoIme`, focus a text field, then
  `adb shell ime set app.alyo/.ime.AlyoIme`.
