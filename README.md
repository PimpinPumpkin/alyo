<div align="center">

<img src="docs/logo.svg" width="110" alt="Alyo logo">

# Alyo

**Speech for your phone, without Google: voice typing, a recorder that transcribes as you talk, and a voice that reads aloud.**

[<img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="54">](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/PimpinPumpkin/alyo)&nbsp;&nbsp;[<img src="https://img.shields.io/badge/DOWNLOAD%20APK-1A73E8?style=for-the-badge&logo=android&logoColor=white" alt="Download the APK" height="54">](https://github.com/PimpinPumpkin/alyo/releases/latest)

Android 10 or later, 64-bit. No account, no cloud, no Google services.

</div>

<p align="center"><img src="docs/demo.gif" width="270" alt="Words appearing in the transcript while a recording is in progress"></p>

| Pick your features | Voice typing in any app | Select, then Read aloud | Reading a web page | Recordings |
|:-:|:-:|:-:|:-:|:-:|
| <picture><source media="(prefers-color-scheme: dark)" srcset="docs/screenshots/dark/00-alyo.png"><img src="docs/screenshots/light/00-alyo.png" width="150" alt="Choose what Alyo is for"></picture> | <picture><source media="(prefers-color-scheme: dark)" srcset="docs/screenshots/dark/11-voice-typing.png"><img src="docs/screenshots/light/11-voice-typing.png" width="150" alt="Dictating a text message with Alyo"></picture> | <picture><source media="(prefers-color-scheme: dark)" srcset="docs/screenshots/dark/12-read-aloud-select.png"><img src="docs/screenshots/light/12-read-aloud-select.png" width="150" alt="Read aloud in the menu over selected text"></picture> | <picture><source media="(prefers-color-scheme: dark)" srcset="docs/screenshots/dark/13-read-aloud-sheet.png"><img src="docs/screenshots/light/13-read-aloud-sheet.png" width="150" alt="Alyo reading selected text from a web page"></picture> | <picture><source media="(prefers-color-scheme: dark)" srcset="docs/screenshots/dark/01-home.png"><img src="docs/screenshots/light/01-home.png" width="150" alt="The recordings list"></picture> |

| Live transcript | Speakers | Summary | Music and speech | Search |
|:-:|:-:|:-:|:-:|:-:|
| <picture><source media="(prefers-color-scheme: dark)" srcset="docs/screenshots/dark/02-live-transcript.png"><img src="docs/screenshots/light/02-live-transcript.png" width="150" alt="Words appearing while recording"></picture> | <picture><source media="(prefers-color-scheme: dark)" srcset="docs/screenshots/dark/04-speakers.png"><img src="docs/screenshots/light/04-speakers.png" width="150" alt="A transcript with speaker labels"></picture> | <picture><source media="(prefers-color-scheme: dark)" srcset="docs/screenshots/dark/07-summary.png"><img src="docs/screenshots/light/07-summary.png" width="150" alt="A summary above a transcript"></picture> | <picture><source media="(prefers-color-scheme: dark)" srcset="docs/screenshots/dark/05-playback.png"><img src="docs/screenshots/light/05-playback.png" width="150" alt="Playback with the music label"></picture> | <picture><source media="(prefers-color-scheme: dark)" srcset="docs/screenshots/dark/06-search.png"><img src="docs/screenshots/light/06-search.png" width="150" alt="Searching inside a recording"></picture> |

Screenshots follow your GitHub theme: light or dark.

Alyo is one app with three jobs. Turn on any of them; what you leave off stays out of your way.
Voice typing and the recorder use the same speech models, so those are downloaded and kept once.

## Voice typing

- **Type by talking, in any app.** The microphone key on your keyboard opens it.
- **Words appear as you speak**, not after you stop.
- **Punctuation and capitals** are added for you, or say "comma", "period", "new line".
- **Undo the last sentence** or backspace a word, without leaving the panel.
- **Hands the keyboard back** after a few seconds of silence.
- **Works for apps that ask Android for speech**, such as a search box with a microphone.

## Recorder

- **Live transcript.** Words appear while you talk, and each sentence is tidied up when you pause.
- **Tap a word to hear it.** The word being played is highlighted.
- **Speaker labels** you can rename, merge, or correct from any word.
- **Fix the text**, during or after recording.
- **Crop and cut** by selecting on the waveform or across the words.
- **Music, applause and laughter** shown in their own colors.
- **Search** every recording at once, or inside one.
- **Summaries**, written on the phone, ready to copy or share. Optional.
- **Import audio or video files** and transcribe them, with the + button or by sharing them to Alyo from another app. From a video only the sound is kept; the video file stays where it was.
- **Export** audio, text, subtitles or word timings.
- **Background playback**, lock screen controls, a widget and a quick settings tile.
- **Recordings are ordinary files** in `Documents/Alyo`, or any folder you choose, including an
  SD card. They stay if the app is uninstalled.
- **Back up and restore** the whole library as one file.
- **One icon.** With the recorder on, Alyo opens straight into your recordings, and the other
  features are a tap away in Settings. A separate "Recorder" icon is there if you want one.

## Read aloud

- **A voice for the whole phone.** Choose Alyo as the speech engine and screen readers, navigation
  and read-aloud buttons in other apps all use it.
- **Read any text.** Select it and pick "Read aloud", or share it to Alyo.
- **Eleven quick voices** in English, German, French and Spanish, each a 67 MB download, and
  **ten Supertonic voices** that sound more natural and speak 31 languages, 129 MB for all ten.
  Every voice has a sample you can hear before downloading it.
- **Adjustable speed.**

## All of it

- **Private.** Nothing you say, and nothing read to you, leaves the phone.
- **English, German, French and Spanish** in the recorder and in read aloud. Voice typing is
  English for now.

The recorder is a free-software take on Google's Recorder app. Alyo is not affiliated with Google.
It is new, so expect a bug or two; [open an issue](https://github.com/PimpinPumpkin/alyo/issues)
if you find one.

---

## More

<details>
<summary><b>Installing and updates</b></summary>

<br>

**Obtainium** (recommended) installs the app and keeps it updated. **Download APK** takes you to
the latest release: download the `.apk` file and open it.

| Channel | What it is | How often |
| --- | --- | --- |
| **Stable** | The newest nightly, promoted | Weekly |
| **Nightly** | Built from `main` | Every day `main` changes |
| **Canary** | Built from the `canary` branch | Every push |

All three share one signing key and one rising version code, so moving between them is a plain
update.

</details>

<details>
<summary><b>Check you got the real thing</b></summary>

<br>

Every Alyo APK, from any channel, is signed with the same key. Its certificate fingerprint is:

```
SHA-256  CB:9E:B9:4D:CD:F0:3B:A7:D6:B4:52:FE:64:E5:4C:DA:39:FB:DF:90:AE:94:72:02:FE:3D:1A:E9:BF:F2:FA:EB
```

Check a downloaded file against it before installing:

```bash
apksigner verify --print-certs alyo-*.apk
```

On the phone, [App Verifier](https://github.com/soupslurpr/AppVerifier) checks the same thing. It
wants the package name on the first line and the fingerprint on the second:

```
app.alyo
CB:9E:B9:4D:CD:F0:3B:A7:D6:B4:52:FE:64:E5:4C:DA:39:FB:DF:90:AE:94:72:02:FE:3D:1A:E9:BF:F2:FA:EB
```

Android enforces this for you after the first install: an update signed with a different key is
refused.

</details>

<details>
<summary><b>Setting up voice typing</b></summary>

<br>

Alyo is a keyboard with no keys: it only listens. Your own keyboard stays as it is, and its
microphone key switches to Alyo.

1. Download the speech model.
2. Allow the microphone.
3. Turn on "Alyo voice typing" in the phone's keyboard list.

The app walks you through these and has a box to try it in. Two things can get in the way, and the
app tells you when it sees them:

- **Another voice typing app is also turned on.** The microphone key goes to whichever one the
  system picks. Switch the other off in the keyboard list.
- **Your keyboard answers its own microphone key.** FUTO Keyboard does this until its built-in
  voice input is turned off in its settings.

</details>

<details>
<summary><b>Models and download sizes</b></summary>

<br>

Nothing is turned into text until a speech model is on the phone. The app offers to download one
on first launch.

| Language | Standard (default) | Best |
| --- | --- | --- |
| English | 249 MB | 802 MB |
| German, French | 75 MB | 745 MB |
| Spanish | 160 MB | 830 MB |

- Best uses about 1.1 GB of memory while it is working.
- With the recorder on and speaker labels left on, add 97 MB for the voice model.
- Read aloud needs one voice: 67 MB each, or 129 MB for the ten Supertonic voices together. It
  does not need the speech models at all.
- Summaries need a separate model: Quick (676 MB) or Thorough (1.1 GB). Any other GGUF chat model
  can be imported instead.

To skip the in-app download, fetch the files on another device and use "Import from files" in
Settings.

When a later version moves to a newer model, the one you have keeps working until you update it.

</details>

<details>
<summary><b>Privacy</b></summary>

<br>

| What you do | Who hears about it |
| --- | --- |
| Dictate, record, transcribe, label speakers, summarize, search, have text read to you | **Nobody.** All of it runs on the phone |
| Download a model | **GitHub or Hugging Face**, which see a file download and nothing else |
| Import a model from files instead | **Nobody** |
| Export or share a recording | **Whichever app you send it to** |

No analytics, no crash reporting, no accounts. Model downloads are the only time the app uses the
network. On a system that lets you withhold the network permission from an app, Alyo runs fine
without it once its models are in place.

Voice typing listens only while its panel is open.

Recordings are files in `Documents/Alyo` unless you choose another folder, so a file manager, a
backup app or a computer can reach them, and so can any app you have given access to your files.
To keep them where no other app can look, choose "Inside the app" in Settings; they are then
deleted if Alyo is uninstalled.

</details>

<details>
<summary><b>How it works</b></summary>

<br>

Speech is turned into text in two passes, both on the CPU through
[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx):

1. A streaming model produces the words you see appear.
2. At each pause, a larger model hears the whole sentence and rewrites it with punctuation,
   capitals and better word timings.

| Language | Live | Second pass |
| --- | --- | --- |
| English | NVIDIA FastConformer | Parakeet TDT 110M (Standard) or 0.6B v2 (Best) |
| German, French, Spanish | Kroko streaming Zipformer | None (Standard) or Parakeet TDT 0.6B v3 (Best) |

The recorder and the keyboard share one engine and one copy of the models. Once loaded, the models
stay in memory until the phone needs the room, so the next dictation or recording starts at once.
The first load is shortened too: after a model is downloaded it is saved in the form the runtime
would otherwise have to work out on every load, which cuts the wait roughly in half (3.6 to 2.1
seconds for English on a Pixel 4a 5G). The microphone is open from the first moment either way,
so nothing said while a model loads is lost.

- **Sounds**: CED tiny, inside the APK, tags music, applause and laughter.
- **Speakers**: pyannote segmentation finds where the voice changes, and NVIDIA TitaNet groups
  the voices. Runs once when a recording is saved. Skipped for recordings over 45 minutes.
- **Summaries**: Qwen2.5 through [llama.cpp](https://github.com/ggml-org/llama.cpp), held by a
  grammar to a title and one to five sentences. Small models get facts wrong, so this is marked
  experimental. The engine ships in two builds, and phones from about 2018 on get the one that
  uses newer processor instructions: on a Pixel 4a 5G it reads a transcript about three times
  as fast (161 tokens a second against 57 for the Quick model, 39 against 12 for Thorough).
- **Audio**: AAC, 48 kHz mono, written so that a recording survives the app being killed.
  Cropping copies audio frames without re-encoding.
- **Storage**: when a recording is saved its audio is moved to the recordings folder, with a
  `.alyo.json` file beside it holding the transcript, speakers, summary and waveform. The app
  keeps only an index. After a reinstall, Alyo notices the folder and offers to open it; one
  confirmation in the system's folder picker brings every recording back.
- **Read aloud**: [Piper](https://github.com/OHF-Voice/piper1-gpl) voices, with eSpeak NG turning
  spelling into sounds. Alyo registers as an Android text-to-speech engine, so it works wherever
  the phone speaks. On a Pixel 4a 5G a sentence is ready about six times faster than it is spoken.
  The [Supertonic 3](https://github.com/supertone-inc/supertonic) voices are one model for all
  ten voices and every language; on the same phone a sentence is ready about twice as fast as it
  is spoken.

Each version of the app pins the exact model files it uses by SHA-256. They are fetched from the
[`models` release](https://github.com/PimpinPumpkin/alyo/releases/tag/models) here, which holds
unmodified copies, and from the original publisher if that fails.

</details>

<details>
<summary><b>Fixing a word a voice says wrong</b></summary>

<br>

The Piper voices work out how to say a word from its spelling, which goes wrong for names that
are not said the way they are written: Tucson, Schenectady, Sequim. Alyo keeps a table of such
words in [`app/src/main/assets/pronunciation/en.tsv`](app/src/main/assets/pronunciation/en.tsv):
the word, a tab, and a spelling that reads the way the word sounds.

To add one, edit that file and send a pull request, or
[open an issue](https://github.com/PimpinPumpkin/alyo/issues/new?template=pronunciation.yml) with the word and
how it should sound. `scripts/pronunciation-check.sh` shows how the voices read the word and
your spelling, so an entry can be checked without a phone. The first entries are place names
from around the United States and a few French ones from Quebec. They were checked against what
the voices' text reader produces, not all by ear, so corrections are welcome. The Supertonic
voices read by their own rules and do not use the table.

</details>

<details>
<summary><b>Known gaps</b></summary>

<br>

- Read aloud has no pause or skip yet, and does not highlight the words as it goes.
- One voice, HFC, was trained on recordings licensed for non-commercial use only. It is labeled
  where you choose it. The Supertonic voices come under OpenRAIL-M, which allows any use except a
  list of harmful ones, so it is not a free software license. The other voices are free to use
  for anything.
- Voice typing is English only. The recorder has four languages; adding one needs a good open
  streaming model, and few languages have one.
- The app's own text is English only.
- The Bluetooth microphone option and pausing a recording for phone calls have not been tested on
  real hardware.
- Not on F-Droid yet. See [docs/FDROID.md](docs/FDROID.md).

</details>

<details>
<summary><b>Building and contributing</b></summary>

<br>

```bash
./gradlew assembleDebug
```

JDK 17, Android SDK platform 37, the NDK and CMake. The first build downloads the sherpa-onnx
library, a trimmed ONNX Runtime and two small bundled models, and fetches llama.cpp at its pinned
tag. Pass `-PllamaSrc=<checkout>` to build llama.cpp from a local copy.

```bash
./gradlew testDebugUnitTest
```

runs the tests: word timing, transcript edits and cuts, speaker changes, the text logic of voice
typing (spacing, capitals, spoken punctuation, undo), resampling and audio framing.

Debug builds can work from audio files instead of the microphone:

```bash
# Record from a WAV file (16-bit mono, 16 or 48 kHz)
adb push clip.wav /data/local/tmp/clip.wav
adb shell run-as app.alyo cp /data/local/tmp/clip.wav files/clip.wav
adb shell am start -n app.alyo/.MainActivity --es wav /data/user/0/app.alyo/files/clip.wav

# Dictate files/test.wav (16-bit mono, 16 kHz) whenever the voice typing panel opens
adb shell run-as app.alyo cp /data/local/tmp/clip.wav files/test.wav
adb shell ime enable app.alyo/.ime.AlyoIme
adb shell ime set app.alyo/.ime.AlyoIme
```

After adding a model, rerun `scripts/build-onnxruntime.sh`: the bundled runtime only carries the
operators the current models use.

Issues and pull requests are welcome. Two house rules, both checked by `scripts/check-writing.sh`
and by CI: US English, and no em dashes. Commit subjects are used verbatim as release notes, so
write them as plain sentences about what changed for the person using the app.

</details>

## License

GPL-3.0. See [LICENSE](LICENSE).

| Component | License |
| --- | --- |
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx), llama.cpp, ONNX Runtime | Apache 2.0, MIT |
| NVIDIA FastConformer, Parakeet TDT and TitaNet | CC BY 4.0 |
| Banafo Kroko ASR | CC BY-SA 4.0 |
| CED tiny, Qwen2.5 | Apache 2.0 |
| pyannote segmentation 3.0 | MIT |
| Piper voices | Per voice: public domain, CC0, CC BY 4.0, and one CC BY-NC-SA 4.0 |
| Supertonic 3 voices | OpenRAIL-M, which allows any use but harmful ones |
| eSpeak NG data (inside each voice) | GPL 3.0 |
| [Google Sans Flex](https://fonts.google.com/specimen/Google+Sans+Flex) | OFL 1.1, see [licenses/](licenses/GoogleSansFlex-OFL.txt) |
| AndroidX, Compose, Media3, Commons Compress | Apache 2.0 |
