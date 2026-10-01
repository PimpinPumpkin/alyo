# Alyo

Voice typing for Android that writes while you are still talking. It runs entirely on the phone:
no account, no cloud, no Google services.

Alyo is a keyboard with no keys. Tap the microphone key on the keyboard you already use, talk,
and the words appear in the text field as you say them. When you pause, the sentence is rewritten
with punctuation and capitals.

## Features

- Words appear as you speak, not after you stop
- Punctuation and capitals added a moment after each sentence ends
- Spoken punctuation: comma, period, question mark, exclamation point, colon, semicolon, new line,
  new paragraph
- Undo takes back the last sentence; backspace right after a spoken mark puts the word back
- Stops by itself after a few seconds of silence, or when you tap Done
- Works with any keyboard that has a microphone key, and as the system speech recognizer for apps
  that ask for one

English only for now. Requires Android 10 or later on a 64-bit ARM phone.

## Install

Get the APK from the [releases page](https://github.com/PimpinPumpkin/alyo/releases), or point
[Obtainium](https://github.com/ImranR98/Obtainium) at this repository.

| Channel | What it is |
| --- | --- |
| Stable | The release marked Latest. Promoted weekly from a nightly. |
| Nightly | A prerelease for each day the code changed. |
| Canary | The `canary` release, rebuilt on every change. |

All three share a signing key, so you can switch between them without reinstalling.

Then open Alyo and follow its three steps: download the speech model, allow the microphone, and
turn on "Alyo voice typing" in the phone's keyboard list. Your own keyboard stays the default.

## Models

| Quality | Download | Notes |
| --- | --- | --- |
| Standard (default) | 244 MB | Right for most phones |
| Best | 797 MB | About 1 GB of memory while dictating |

The model download is the only time the app uses the network. To avoid even that, download the
archives on another device and use "Import a model from files" in Settings.

Each version of the app pins the exact files it uses by SHA-256. They are fetched from the
[`models` release](https://github.com/PimpinPumpkin/alyo/releases/tag/models) in this repository,
which holds unmodified copies, and from the original publisher if that fails.

## How it works

Two passes, both on the CPU through [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx):

1. A streaming model (NVIDIA FastConformer) produces the words you see appear. They are shown
   underlined, as text that may still change.
2. At each pause, a larger model (NVIDIA Parakeet TDT, 110M or 0.6B) hears the whole sentence and
   rewrites it with punctuation and capitals. That version replaces the underlined one.

Someone who talks without pausing is split at the quietest moment after eight seconds, so the
punctuation never waits long.

The microphone opens before the models have finished loading and its audio is queued, so nothing
said in the first second is lost. The models stay in memory for a few minutes after a dictation
(adjustable), so the next one starts at once. Audio is held in memory only until its sentence is
final. Nothing is written to storage.

### Spoken punctuation

The second pass punctuates by itself, so most of the time there is nothing to say. Marks said out
loud are converted too, unless the words around them make the word read as a noun: "that
period", "a comma", "period of time". A pause before the word tips it back to a mark. When it
guesses wrong, backspace straight afterwards puts the word back.

## Building

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

Needs JDK 17 and Android SDK platform 37. The first build downloads the sherpa-onnx library.

The APK carries a copy of ONNX Runtime built with only the operators these models use, which
halves its size. `scripts/build-onnxruntime.sh` rebuilds it, and has to be run again when a model
is added.

Debug builds dictate from a WAV file (16-bit mono, 16 kHz) in place of the microphone if one is
present:

```bash
adb push clip.wav /data/local/tmp/clip.wav
adb shell run-as app.alyo cp /data/local/tmp/clip.wav files/test.wav
```

## Known gaps

- English only.
- No personal dictionary for names and unusual words yet.
- No text to speech yet.
- Not tested with Bluetooth microphones.
- Not on F-Droid yet. See [docs/FDROID.md](docs/FDROID.md).

## License

GPL-3.0. See [LICENSE](LICENSE).

| Component | License |
| --- | --- |
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx), ONNX Runtime | Apache 2.0, MIT |
| NVIDIA FastConformer and Parakeet TDT | CC BY 4.0 |
| [Google Sans Flex](https://fonts.google.com/specimen/Google+Sans+Flex) | OFL 1.1, see [licenses/](licenses/GoogleSansFlex-OFL.txt) |
| AndroidX, Compose, Commons Compress | Apache 2.0 |

Not affiliated with or endorsed by Google or NVIDIA.
