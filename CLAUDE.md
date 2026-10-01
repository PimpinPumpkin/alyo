# Sotto Recorder

Android voice recorder with on-device transcription. README.md covers how it works.

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
`SOTTO_KEYSTORE_PATH`, `SOTTO_KEYSTORE_PASSWORD` and `SOTTO_KEY_ALIAS`, and falls back to the
debug key.

Debug builds take `--es wav <path>` to record from a file and `--es theme light|dark`.
