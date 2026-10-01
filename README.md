# Sotto

A voice recorder for Android that transcribes as you speak, entirely on the phone. It is a
free-software take on Google's Recorder: same idea, same feel, no Google services, no account,
no cloud.

- **Live transcript.** Words appear while you are still talking.
- **Linked audio and text.** The word being played is highlighted; tap any word to jump there.
- **Fix mistakes.** Tap a sentence to correct it. Words you leave alone stay locked to the audio.
- **Crop and remove.** Select a stretch on the waveform, the seek bar, or by tapping words, then
  keep only that or cut it out. Edits can be undone and listened to before saving a copy, and the
  transcript is trimmed to match.
- **Export.** Audio (M4A), transcript with timestamps (TXT), subtitles (SRT), or per-word timings (JSON),
  through the share sheet or straight into `Download/Sotto`.
- **Search** across titles and every word of every transcript, and within one transcript with
  jump-to-match.
- **Background playback** with lock screen and notification controls.
- **Offline.** The only network request the app ever makes is the one-time speech model download.

Requires Android 10 or later on a 64-bit ARM phone.

## How the transcription works

Google's Recorder is good because it uses a *streaming transducer*: a model that reads audio a
fraction of a second at a time and emits words as it goes, then quietly revises them once it has
heard the whole sentence. Sotto is built the same way, from open models:

1. **Live pass.** NVIDIA's streaming FastConformer transducer (114M parameters) consumes audio in
   560 ms steps and produces the text you see appear. It is fast and a little rough: lower case,
   no punctuation.
2. **Second pass.** Each time the speaker pauses, that sentence is handed to NVIDIA Parakeet TDT,
   which hears it whole and rewrites it with punctuation, capitals and tighter word timings. The
   rough text is replaced in place a moment later.

Both run on the CPU through [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx). On a Pixel 4a 5G
(Snapdragon 765G, 2020) each pass runs at roughly 7x real time, so the second pass keeps up with
the speaker and only the last sentence is still being polished when you press Save.

Two quality settings choose the second-pass model:

| Setting | Second pass | Download | Notes |
| --- | --- | --- | --- |
| Best | Parakeet TDT 0.6B v2 | 798 MB | Uses about 1.1 GB of memory while recording |
| Lighter | Parakeet TDT 110M | 245 MB | For phones with less than 6 GB |

Models are fetched from the sherpa-onnx releases (GitHub and Hugging Face) on first run, checked
against pinned SHA-256 hashes where the host publishes them, and stored in app-private storage.

English only for now. A language is one entry in
[`ModelCatalog.kt`](app/src/main/java/app/sotto/asr/ModelCatalog.kt): a streaming model plus one or
more second-pass models. Nothing else in the app is language-specific.

## Building

```bash
./gradlew assembleDebug
```

Needs JDK 17 and Android SDK platform 37. The first build downloads the sherpa-onnx AAR (50 MB)
from its GitHub release into `app/libs/`; it is not on Maven.

```bash
./gradlew testDebugUnitTest
```

runs the unit tests for the parts with real logic in them: token-to-word timing, re-timing a
sentence after an edit, re-timing a transcript after a cut, resampling, and the ADTS framing.

### Testing without talking

Debug builds can record from a WAV file as if it were the microphone, at real-time speed, which
exercises the whole capture, transcribe and save path:

```bash
adb push clip.wav /data/local/tmp/clip.wav
adb shell run-as app.sotto cp /data/local/tmp/clip.wav files/clip.wav
adb shell am start -n app.sotto/.MainActivity --es wav /data/user/0/app.sotto/files/clip.wav
```

The file must be 16-bit mono at 16 or 48 kHz. Recording stops and saves when the file ends.
Adding `--es theme light` or `--es theme dark` shows the other theme without changing the phone's
own setting.

## How recordings are stored

Everything lives in app-private storage, one folder per recording:

```
files/recordings/<id>/
  audio.m4a         AAC-LC, 48 kHz mono, 96 kbit/s
  peaks.bin         waveform, one byte per 20 ms
  transcript.json   sentences of words, each word with start and end in milliseconds
  meta.json         title, date, duration
```

While recording, audio is written as an ADTS stream (`audio.aac`) rather than an MP4. Every ADTS
frame is self-contained, so if the app is killed mid-recording the file is intact up to the last
frame; it is wrapped into an M4A at the next launch, along with the waveform and whatever had
been transcribed. An MP4 written directly would be unreadable
without the index that is only written at the end.

Cropping and cutting copy AAC frames between containers without decoding, so there is no quality
loss and it is near-instant. Cuts land on frame boundaries (21 ms).

Someone who talks for a long time without pausing is not cut off at a fixed length. Once a
sentence has run past ten seconds, the quietest quarter second in it (the breath) is found and
the split is made there, and if the next piece opens with a word like "because" or "which" the
two are punctuated as one sentence.

## Not there yet

- Transcribing a recording after the fact (imported audio, or one made before the model was downloaded).
- Languages other than English.
- Speaker labels.
- Summaries. That needs a second, general-purpose language model on the phone.
- Model downloads do not run as a foreground service, so a very long download can be interrupted
  if the app is swiped away. It resumes where it stopped the next time the app opens.

## Licences

Sotto is licensed under the GNU General Public License v3.0; see [LICENSE](LICENSE).

It builds on:

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) and ONNX Runtime: Apache 2.0 and MIT
- NVIDIA FastConformer and Parakeet TDT speech models: CC BY 4.0 (downloaded at run time, not bundled)
- [Google Sans Flex](https://fonts.google.com/specimen/Google+Sans+Flex): SIL Open Font License 1.1,
  see [licenses/GoogleSansFlex-OFL.txt](licenses/GoogleSansFlex-OFL.txt)
- AndroidX, Jetpack Compose, Media3, Apache Commons Compress: Apache 2.0

Sotto is not affiliated with or endorsed by Google.
