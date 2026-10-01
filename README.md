# Sotto Recorder

A voice recorder for Android that writes down what is said while you are still talking. It runs
entirely on the phone: no account, no cloud, no Google services.

It is a free-software take on Google's Recorder app.

## Features

- Live transcript, with punctuation added a moment after each sentence ends
- Tap a word to jump to it; the word being played is highlighted
- Edit the transcript, during or after recording
- Crop or cut by selecting on the waveform or across the words
- Speaker labels you can rename
- Speech, music, applause and laughter shown in different colors
- Search across all recordings, or inside one
- Import audio files and transcribe them
- Export audio (M4A), text (TXT), subtitles (SRT) or word timings (JSON)
- Optional summaries written by a small language model on the phone
- Background playback, lock screen controls, widget and quick settings tile
- Backup and restore of the whole library
- Recordings appear in the system file picker

English only for now. Requires Android 10 or later on a 64-bit ARM phone.

## Install

Get the APK from the [releases page](https://github.com/PimpinPumpkin/sotto/releases), or point
[Obtainium](https://github.com/ImranR98/Obtainium) at this repository.

| Channel | What it is |
| --- | --- |
| Stable | The release marked Latest. Promoted weekly from a nightly. |
| Nightly | A prerelease for each day the code changed. |
| Canary | The `canary` release, rebuilt on every change. |

All three share a signing key, so you can switch between them without reinstalling.

## Models

Nothing is transcribed until a speech model is on the phone. The app offers to download one on
first launch.

| Choice | Second-pass model | Download |
| --- | --- | --- |
| Standard (default) | Parakeet TDT 110M | 275 MB |
| Best | Parakeet TDT 0.6B v2 | 828 MB, and about 1.1 GB of memory while recording |

Summaries need a separate model: Quick (Qwen2.5 0.5B, 676 MB) or Thorough (Qwen2.5 1.5B, 1.1 GB).
Any other GGUF chat model can be imported instead.

Model downloads are the only time the app uses the network. To avoid even that, download the
files on another device and use "Import from files" in Settings.

Each version of the app pins the exact files it uses by SHA-256. They are fetched from the
[`models` release](https://github.com/PimpinPumpkin/sotto/releases/tag/models) in this
repository, which holds unmodified copies, and from the original publisher if that fails. When a
later version moves to a newer model, the old one keeps working until the new one is downloaded.

## How it works

Transcription is two passes, both on the CPU through
[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx):

1. NVIDIA's streaming FastConformer produces the words you see appear. It is fast and rough:
   lower case, no punctuation.
2. At each pause, NVIDIA Parakeet TDT hears the whole sentence and rewrites it with punctuation,
   capitals and better word timings.

On a Pixel 4a 5G (2020) each pass runs at about 7x real time.

Someone who talks without pausing is split at the quietest moment after ten seconds, so a
sentence is never cut at a fixed length.

Other pieces:

- **Sounds**: CED tiny (6 MB, inside the APK) tags music, applause and laughter.
- **Speakers**: pyannote segmentation finds where the voice changes, and 3D-Speaker CAM++ groups
  the voices. Runs once when a recording is saved. Skipped for recordings over 45 minutes.
- **Summaries**: Qwen2.5 through [llama.cpp](https://github.com/ggml-org/llama.cpp), with the
  output held to a title and one to five sentences by a grammar. Long transcripts are read in
  pieces. Not offered for recordings under 80 words. Small models get facts wrong, so this is
  marked experimental.

### Storage

Recordings are in app-private storage, one folder each:

```
files/recordings/<id>/
  audio.m4a         AAC-LC, 48 kHz mono, 96 kbit/s
  peaks.bin         waveform, one byte per 20 ms
  transcript.json   words with start and end times in milliseconds
  meta.json         title, date, duration
```

Audio is written as an ADTS stream while recording and wrapped into an M4A afterwards, so a
recording survives the app being killed. Cropping copies AAC frames without re-encoding.

## Building

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

Needs JDK 17, Android SDK platform 37, the NDK and CMake. The first build downloads the
sherpa-onnx library and the two bundled models, and fetches llama.cpp at its pinned tag. Pass
`-PllamaSrc=<checkout>` to build llama.cpp from a local copy.

Debug builds can record from a WAV file (16-bit mono, 16 or 48 kHz) instead of the microphone:

```bash
adb push clip.wav /data/local/tmp/clip.wav
adb shell run-as app.sotto cp /data/local/tmp/clip.wav files/clip.wav
adb shell am start -n app.sotto/.MainActivity --es wav /data/user/0/app.sotto/files/clip.wav
```

## Known gaps

- English only. A language is one entry in
  [`ModelCatalog.kt`](app/src/main/java/app/sotto/asr/ModelCatalog.kt).
- A sentence shared by two people is labeled with whoever spoke most of it.
- The Bluetooth microphone option and pausing for phone calls have not been tested on real
  hardware.
- Not on F-Droid yet. See [docs/FDROID.md](docs/FDROID.md).

## License

GPL-3.0. See [LICENSE](LICENSE).

| Component | License |
| --- | --- |
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx), llama.cpp, ONNX Runtime | Apache 2.0, MIT |
| NVIDIA FastConformer and Parakeet TDT | CC BY 4.0 |
| CED tiny, 3D-Speaker CAM++, Qwen2.5 | Apache 2.0 |
| pyannote segmentation 3.0 | MIT |
| [Google Sans Flex](https://fonts.google.com/specimen/Google+Sans+Flex) | OFL 1.1, see [licenses/](licenses/GoogleSansFlex-OFL.txt) |
| AndroidX, Compose, Media3, Commons Compress | Apache 2.0 |

Not affiliated with or endorsed by Google or NVIDIA.
