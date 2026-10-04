#!/usr/bin/env python3
"""Makes the short clip each voice is previewed with in the voice list.

    pip install sherpa-onnx soundfile        (and ffmpeg with libopus on the PATH)
    scripts/voice-samples.py <dir>

<dir> holds the voice archives unpacked as sherpa-onnx publishes them: one
vits-piper-<name>-medium folder per Piper voice and the Supertonic folder. The clips go to
app/src/main/assets/voices/<model id>.ogg. Run it again when a voice is added to ModelCatalog.kt.
"""
import pathlib, subprocess, sys, tempfile

import sherpa_onnx
import soundfile

LINES = {
    "en": "Hello. This is how I sound when I read to you.",
    "de": "Hallo. So klinge ich, wenn ich dir vorlese.",
    "fr": "Bonjour. Voici ma voix quand je vous fais la lecture.",
    "es": "Hola. Así sueno cuando te leo en voz alta.",
}
PIPER = ["en_US-kristin", "en_US-norman", "en_US-joe", "en_US-ljspeech", "en_US-hfc_female", "en_GB-cori",
         "en_GB-alba", "de_DE-thorsten", "fr_FR-siwis", "es_ES-davefx", "es_MX-ald"]
SUPERTONIC = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
SUPERTONIC_VOICES = ["f1", "f2", "f3", "f4", "f5", "m1", "m2", "m3", "m4", "m5"]

models = pathlib.Path(sys.argv[1])
out = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/assets/voices"
out.mkdir(parents=True, exist_ok=True)


def save(name, audio):
    with tempfile.NamedTemporaryFile(suffix=".wav") as wav:
        soundfile.write(wav.name, audio.samples, audio.sample_rate)
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav.name, "-ac", "1", "-c:a", "libopus",
                        "-b:a", "32k", str(out / f"{name}.ogg")], check=True)
    print(name, f"{len(audio.samples) / audio.sample_rate:.1f} s")


for name in PIPER:
    folder = models / f"vits-piper-{name}-medium"
    tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
        vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=str(folder / f"{name}-medium.onnx"), tokens=str(folder / "tokens.txt"),
                                                   data_dir=str(folder / "espeak-ng-data")),
        num_threads=4)))
    save(f"voice-{name}", tts.generate(LINES[name[:2]], sid=0, speed=1.0))

folder = models / SUPERTONIC
tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
    supertonic=sherpa_onnx.OfflineTtsSupertonicModelConfig(
        duration_predictor=str(folder / "duration_predictor.int8.onnx"), text_encoder=str(folder / "text_encoder.int8.onnx"),
        vector_estimator=str(folder / "vector_estimator.int8.onnx"), vocoder=str(folder / "vocoder.int8.onnx"),
        tts_json=str(folder / "tts.json"), unicode_indexer=str(folder / "unicode_indexer.bin"), voice_style=str(folder / "voice.bin")),
    num_threads=4)))
for sid, voice in enumerate(SUPERTONIC_VOICES):
    config = sherpa_onnx.GenerationConfig()
    config.sid = sid
    config.num_steps = 5
    config.speed = 1.0
    config.extra = {"lang": "en"}
    save(f"voice-supertonic-3-{voice}", tts.generate(LINES["en"], config))
