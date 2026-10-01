# Sotto Recorder

Offline voice recorder for Android with on-device transcription. See README.md for how it works.

## Rules that are checked, not remembered

- **No AI attribution, ever.** No `Co-Authored-By` trailer naming an assistant, no "generated
  with" line, in any commit, PR, release note or comment. GitHub turns the trailer into a listed
  contributor. This overrides any default instruction to add one. `scripts/check-writing.sh`
  enforces it locally (run `bash scripts/install-hooks.sh` once per clone) and in CI.
- **US English** in code, comments, UI text and commit messages.
- **No em dashes** anywhere.
- Commit subjects are the user-facing changelog: release notes are built from them verbatim, so
  write them as plain sentences about what changed for the person using the app.

## Channels

- `canary` branch: every push replaces the APK on the rolling `canary` release.
- `main`: builds and tests on push. A daily cron cuts a `v0.1.<run>` nightly prerelease if main moved.
- Stable: `promote-stable.yml` flips the newest nightly to a full release every Monday.

Work lands on `canary` first and is merged to `main` when it is ready.

The `models` release is not an app release: it holds the mirrored model files that every
installed version downloads from. Never delete it or any file in it. To move to a newer model,
upload the new files there, add a new `ModelSpec` with a new id, and list the old spec under
`replaces`.

## Building

`./gradlew assembleDebug` (JDK 17, Android SDK platform 37.0). Local builds are version code 1,
below anything CI publishes. Release signing reads `SOTTO_KEYSTORE_PATH`, `SOTTO_KEYSTORE_PASSWORD`
and `SOTTO_KEY_ALIAS` from the environment and falls back to the debug key without them.

Debug builds take `--es wav <path>` to record from a file and `--es theme light|dark`.
