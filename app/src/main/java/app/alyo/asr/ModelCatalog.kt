package app.alyo.asr

/** One file of a model, fetched on its own so an interrupted download can resume. */
data class ModelFile(val name: String, val url: String, val bytes: Long, val sha256: String? = null)

sealed interface ModelSource {
    val downloadBytes: Long

    data class Files(val files: List<ModelFile>) : ModelSource {
        override val downloadBytes: Long get() = files.sumOf { it.bytes }
    }

    /** A `.tar.bz2` from the sherpa-onnx model releases; only the entries named in [keep] are unpacked. */
    /**
     * @param keep the files to unpack, by name, wherever they sit in the archive
     * @param tree unpack everything instead, keeping the folders: a voice comes with a folder of
     * pronunciation data hundreds of files deep
     */
    data class Tarball(val url: String, override val downloadBytes: Long, val keep: Set<String> = emptySet(), val tree: Boolean = false) : ModelSource
}

enum class ModelKind {
    /** Emits words while audio is still arriving. Drives the live transcript. */
    STREAMING_TRANSDUCER,

    /** Needs a finished stretch of audio; slower to appear but far more accurate, with punctuation. */
    OFFLINE_NEMO_TRANSDUCER,

    /** Not a speech model: a small chat language model in GGUF form, used to write summaries. */
    CHAT_GGUF,

    /** Turns a stretch of speech into a fingerprint of the voice, for telling speakers apart. */
    SPEAKER_EMBEDDING,

    /** Goes the other way: a Piper voice, which turns text into speech. */
    VOICE,
}

data class ModelSpec(
    val id: String,
    val kind: ModelKind,
    val title: String,
    val summary: String,
    val source: ModelSource,
    val encoder: String = "encoder.int8.onnx",
    val decoder: String = "decoder.int8.onnx",
    val joiner: String = "joiner.int8.onnx",
    val tokens: String = "tokens.txt",
    /**
     * Name of this model's archive in the sherpa-onnx `asr-models` release, without `.tar.bz2`.
     * It is also the folder inside that archive, which is how an imported file is recognized.
     */
    val archive: String = "",
    /** For streaming models: audio consumed per decode step. Segment boundaries are placed with it. */
    val chunkMs: Int = 0,
    /**
     * For streaming models: how late a word is reported relative to when it was actually said. A
     * streaming model waits for look-ahead audio before committing, and stamps the word with the
     * moment it committed. Subtracted so tapping a live word lands on the word, not after it.
     */
    val lagMs: Int = 0,
    /**
     * Earlier models this one takes over from, newest first. A phone that has one of them keeps
     * transcribing with it until this one has been downloaded, at which point the old one is
     * deleted. So upgrading a model is: give the new one a new [id], and move the old spec here.
     */
    val replaces: List<ModelSpec> = emptyList(),
    /**
     * For voice models: how different two voices must be to count as two people. It is a distance
     * in the model's own terms, so each model has its own. Lower finds more speakers.
     */
    val speakerThreshold: Float = 0f,
    /** For voices: the language spoken, as a tag like `en-US`. */
    val locale: String = "",
    /** For voices: the license of the recordings the voice was trained on, shown where it is chosen. */
    val license: String = "",
    /** For voices: true when that license forbids commercial use, which also keeps it out of F-Droid's main repository. */
    val nonCommercial: Boolean = false,
    /** For voices: which engine speaks it. */
    val synth: Synth = Synth.PIPER,
    /** For voices that come several to a model: which of them this is. */
    val speaker: Int = 0,
    /** For voices that speak more than their own [locale]: the other languages, as two-letter codes. */
    val languages: List<String> = emptyList(),
    /**
     * For models that share one download, the name they share it under. They are installed and
     * removed together.
     */
    val store: String = "",
) {
    /** What the files are kept and fetched under: [store] if the download is shared, else [id]. */
    val key: String get() = store.ifEmpty { id }
}

/** The engines that turn text into speech. */
enum class Synth(val sampleRate: Int) {
    /** One small model per voice. Fast on any phone. */
    PIPER(22_050),
    /** One larger model for all its voices and languages. Sounds more natural and takes about twice as long. */
    SUPERTONIC(44_100),
}

/** How good the finished transcript is, traded against download size and memory. */
/**
 * @param refiner the second-pass model, or null where the live model's own text is the transcript
 */
data class Quality(val id: String, val title: String, val summary: String, val refiner: ModelSpec?)

/**
 * Everything needed to transcribe one language: a streaming model for the live text and a choice
 * of second-pass models that rewrite each sentence once it is finished. Adding a language is
 * adding one of these to [ModelCatalog.languages]; no other code changes.
 */
data class LanguagePack(
    val code: String,
    val name: String,
    val live: ModelSpec,
    val qualities: List<Quality>,
    /**
     * Words that usually continue a sentence rather than begin one. When long speech is split at
     * a breath, the second pass punctuates each piece as a sentence of its own; a piece opening
     * with one of these is stitched back onto the one before ("…dynasty. Where…" becomes
     * "…dynasty, where…"). Empty turns the stitching off.
     */
    val continuers: Set<String> = emptySet(),
)

object ModelCatalog {
    private const val HF = "https://huggingface.co/csukuangfj"
    private const val GH = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"

    private val enLive = ModelSpec(
        id = "en-live-fastconformer-480ms",
        archive = "sherpa-onnx-nemo-streaming-fast-conformer-transducer-en-480ms-int8",
        kind = ModelKind.STREAMING_TRANSDUCER,
        title = "Live English",
        summary = "NVIDIA FastConformer streaming transducer, 114M parameters",
        // "480ms" in the model's name is its look-ahead; each step it consumes 7 encoder frames of 80 ms.
        chunkMs = 560,
        // Measured against the second pass's timings on the same audio: 320 to 480 ms late.
        lagMs = 400,
        source = "$HF/sherpa-onnx-nemo-streaming-fast-conformer-transducer-en-480ms-int8/resolve/main".let { base ->
            ModelSource.Files(
                listOf(
                    ModelFile("encoder.int8.onnx", "$base/encoder.int8.onnx", 131_507_603, "100c5616929a131b5b3c8c8ab0d83aaba2cdcae163acd8d190b4e5ffa5f7d051"),
                    ModelFile("decoder.int8.onnx", "$base/decoder.int8.onnx", 3_955_864, "4f04431988472f8c7b815f942aa6901976929c9e22353029681fcdb262da0164"),
                    ModelFile("joiner.int8.onnx", "$base/joiner.int8.onnx", 1_408_182, "d2d8e7290a6a8245a83ed211aa0dd8cdf8effb8a7c64fe392399561111b52f30"),
                    ModelFile("tokens.txt", "$base/tokens.txt", 11_896, "618dc110fc2213886b52e063ff42329bbdf37a266ca7705184090fa5f39f3131"),
                ),
            )
        },
    )

    private val enParakeetLarge = ModelSpec(
        id = "en-parakeet-tdt-0.6b-v2",
        archive = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8",
        kind = ModelKind.OFFLINE_NEMO_TRANSDUCER,
        title = "Parakeet TDT 0.6B v2",
        summary = "NVIDIA Parakeet, 600M parameters",
        source = "$HF/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/resolve/main".let { base ->
            ModelSource.Files(
                listOf(
                    ModelFile("encoder.int8.onnx", "$base/encoder.int8.onnx", 652_184_296, "a32b12d17bbbc309d0686fbbcc2987b5e9b8333a7da83fa6b089f0a2acd651ab"),
                    ModelFile("decoder.int8.onnx", "$base/decoder.int8.onnx", 7_257_753, "b6bb64963457237b900e496ee9994b59294526439fbcc1fecf705b31a15c6b4e"),
                    ModelFile("joiner.int8.onnx", "$base/joiner.int8.onnx", 1_739_080, "7946164367946e7f9f29a122407c3252b680dbae9a51343eb2488d057c3c43d2"),
                    ModelFile("tokens.txt", "$base/tokens.txt", 9_384, "ec182b70dd42113aff6c5372c75cac58c952443eb22322f57bbd7f53977d497d"),
                ),
            )
        },
    )

    private val enParakeetSmall = ModelSpec(
        id = "en-parakeet-tdt-110m",
        archive = "sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8",
        kind = ModelKind.OFFLINE_NEMO_TRANSDUCER,
        title = "Parakeet TDT 110M",
        summary = "NVIDIA Parakeet, 110M parameters",
        source = ModelSource.Tarball(
            url = "$GH/sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8.tar.bz2",
            downloadBytes = 108_035_095,
            keep = setOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"),
        ),
    )

    val english = LanguagePack(
        code = "en",
        name = "English",
        live = enLive,
        continuers = setOf(
            "and", "but", "or", "so", "because", "which", "where", "when", "while", "since", "that",
            "although", "though", "if", "unless", "until", "whereas", "then", "who", "whose",
        ),
        qualities = listOf(
            Quality(
                id = "light",
                title = "Standard",
                summary = "Accurate and quick to download. Right for most phones.",
                refiner = enParakeetSmall,
            ),
            Quality(
                id = "best",
                title = "Best",
                summary = "Most accurate. Three times the download and about 1 GB of memory while recording.",
                refiner = enParakeetLarge,
            ),
        ),
    )

    /**
     * Banafo's Kroko streaming models. Unlike the English live model they write capitals and
     * punctuation as they go, so their text can stand as the transcript without a second pass.
     */
    private fun kroko(code: String, name: String, files: List<ModelFile>) = ModelSpec(
        id = "$code-live-kroko",
        archive = "sherpa-onnx-streaming-zipformer-$code-kroko-2025-08-06",
        kind = ModelKind.STREAMING_TRANSDUCER,
        title = "Live $name",
        summary = "Kroko streaming Zipformer",
        encoder = "encoder.onnx",
        decoder = "decoder.onnx",
        joiner = "joiner.onnx",
        // Each step takes 128 feature frames of 10 ms.
        chunkMs = 1280,
        source = ModelSource.Files(files),
    )

    private fun krokoFiles(code: String, encoderBytes: Long, encoder: String, decoderBytes: Long, decoder: String, joiner: String, tokensBytes: Long, tokens: String): List<ModelFile> {
        val base = "$HF/sherpa-onnx-streaming-zipformer-$code-kroko-2025-08-06/resolve/main"
        return listOf(
            ModelFile("encoder.onnx", "$base/encoder.onnx", encoderBytes, encoder),
            ModelFile("decoder.onnx", "$base/decoder.onnx", decoderBytes, decoder),
            ModelFile("joiner.onnx", "$base/joiner.onnx", 336_817, joiner),
            ModelFile("tokens.txt", "$base/tokens.txt", tokensBytes, tokens),
        )
    }

    /** The second pass for every European language but English: one model that covers 25 of them. */
    private val euParakeet = ModelSpec(
        id = "eu-parakeet-tdt-0.6b-v3",
        archive = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
        kind = ModelKind.OFFLINE_NEMO_TRANSDUCER,
        title = "Parakeet TDT 0.6B v3",
        summary = "NVIDIA Parakeet, 600M parameters, 25 European languages",
        source = "$HF/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main".let { base ->
            ModelSource.Files(
                listOf(
                    ModelFile("encoder.int8.onnx", "$base/encoder.int8.onnx", 652_184_281, "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"),
                    ModelFile("decoder.int8.onnx", "$base/decoder.int8.onnx", 11_845_275, "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"),
                    ModelFile("joiner.int8.onnx", "$base/joiner.int8.onnx", 6_355_277, "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"),
                    ModelFile("tokens.txt", "$base/tokens.txt", 93_939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
                ),
            )
        },
    )

    private fun european(code: String, name: String, live: ModelSpec, continuers: Set<String>) = LanguagePack(
        code = code,
        name = name,
        live = live,
        continuers = continuers,
        qualities = listOf(
            Quality("light", "Standard", "The live text is the transcript. Small and quick.", refiner = null),
            Quality("best", "Best", "Each sentence is rewritten by a larger model. About 1 GB of memory while recording.", refiner = euParakeet),
        ),
    )

    val german = european(
        "de", "Deutsch",
        kroko(
            "de", "German",
            krokoFiles(
                "de", 70_091_557, "6e83993d6967ec7a3498b055b7e85ace85b5d64d1b1e8773cb29a43a11f5edb5",
                617_489, "94a29592b403c53fa2231b478637da1ab4abcef7f5e46e432098416a4a3ed562",
                "28356bff070aea51ab1d725a3278e81d19f9300f860d3248a7014292264df15a",
                5_606, "86e8370994ff2c01149ba8c4f8709aa93cdc18914b27a717e291e96faf39a6eb",
            ),
        ),
        setOf("und", "aber", "oder", "weil", "dass", "wenn", "als", "obwohl", "während", "denn", "sondern", "wobei", "damit", "sodass", "bevor", "nachdem"),
    )

    val french = european(
        "fr", "Français",
        kroko(
            "fr", "French",
            krokoFiles(
                "fr", 70_092_599, "e02facae1daf6f1f13da67ea3ace7c722516d0868d1768d78c0580bc22cc0c5b",
                617_488, "6aed547570e3ab5afc05429a017cedd3a056c16df3baa5703f02461cefa25bac",
                "a51eec759bcdcaae2614686fa2a8b57417b2d420dd55a5a5558b388d35a9b2b6",
                5_415, "fedfb9c844bfb2bf14171f8184863e3d617b815a8667bdd9fc9a3149fde73298",
            ),
        ),
        setOf("et", "mais", "ou", "donc", "car", "parce", "que", "qui", "quand", "si", "puisque", "lorsque", "comme", "dont", "où", "alors", "tandis"),
    )

    val spanish = european(
        "es", "Español",
        kroko(
            "es", "Spanish",
            krokoFiles(
                "es", 154_878_102, "2d9f5ef87d1a5257f8a6687e21501c56f3aa2fcbfcfab9364dcc4ce4e06ae81b",
                617_488, "d4ce176b94b25f7acc88717bc3f704fcf5d6e131aaac2e0cabab3885541181ee",
                "dae35df88d676e320fcdb99217328e66dcf722bf11b0f2459e14ddb5b982ded5",
                6_385, "1be5e0a58e05d06d327df4c6b7b5e4f8aba01da6981eb016fcaceafc6a56680f",
            ),
        ),
        setOf("y", "e", "pero", "o", "u", "porque", "que", "cuando", "si", "aunque", "mientras", "donde", "como", "pues", "sino", "entonces"),
    )

    val languages: List<LanguagePack> = listOf(english, german, spanish, french)

    /** File name every summary model is stored under, whatever it was called when it arrived. */
    const val GGUF = "model.gguf"

    private fun gguf(id: String, title: String, summary: String, repo: String, file: String, bytes: Long, sha256: String) = ModelSpec(
        id = id,
        kind = ModelKind.CHAT_GGUF,
        title = title,
        summary = summary,
        source = ModelSource.Files(listOf(ModelFile(GGUF, "https://huggingface.co/$repo/resolve/main/$file", bytes, sha256))),
    )

    /**
     * Models that can write a summary, smallest first. Speeds are what a 2020 mid-range phone
     * (Pixel 4a 5G) measured: the small one reads about 50 words-pieces a second, the large one 11.
     */
    val summarizers: List<ModelSpec> = listOf(
        gguf(
            "llm-qwen2.5-0.5b", "Quick", "Four times faster. Gets the gist; can miss detail.",
            "Qwen/Qwen2.5-0.5B-Instruct-GGUF", "qwen2.5-0.5b-instruct-q8_0.gguf", 675_710_816,
            "ca59ca7f13d0e15a8cfa77bd17e65d24f6844b554a7b6c12e07a5f89ff76844e",
        ),
        gguf(
            "llm-qwen2.5-1.5b", "Thorough", "The better summaries. Slow on older phones.",
            "Qwen/Qwen2.5-1.5B-Instruct-GGUF", "qwen2.5-1.5b-instruct-q4_k_m.gguf", 1_117_320_736,
            "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e",
        ),
    )

    /** Any other chat model in GGUF form that the user has imported themselves. */
    val importedSummarizer = ModelSpec(
        id = "llm-imported",
        kind = ModelKind.CHAT_GGUF,
        title = "Imported model",
        summary = "A GGUF chat model you supplied",
        source = ModelSource.Files(emptyList()),
    )

    /**
     * The download behind speaker labels. Language does not matter to it: a voice is a voice.
     * Chosen by counting speakers in recordings where the answer is known: of the six models
     * tried, this was the only one right on all of them across a range of thresholds.
     */
    val speakers = ModelSpec(
        id = "speakers-titanet-large",
        kind = ModelKind.SPEAKER_EMBEDDING,
        title = "Speaker labels",
        summary = "NVIDIA TitaNet Large voice model",
        encoder = "speakers.onnx",
        speakerThreshold = 0.85f,
        source = ModelSource.Files(
            listOf(
                ModelFile(
                    "speakers.onnx",
                    "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/nemo_en_titanet_large.onnx",
                    101_405_493,
                    "d51abcf31717ef28162f26acb9d44dd4127c3d44c9b8624f699f3425daca8e77",
                ),
            ),
        ),
    )

    private const val TTS = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"

    /**
     * A Piper voice as sherpa-onnx packages it: the model, its tokens, and the eSpeak NG data
     * that turns spelling into sounds. The full-precision models are used on purpose: on a phone
     * the 8-bit ones are smaller but more than twice as slow.
     */
    private fun piper(name: String, title: String, summary: String, locale: String, bytes: Long, license: String, nonCommercial: Boolean = false) = ModelSpec(
        id = "voice-$name",
        archive = "vits-piper-$name-medium",
        kind = ModelKind.VOICE,
        title = title,
        summary = summary,
        encoder = "$name-medium.onnx",
        locale = locale,
        license = license,
        nonCommercial = nonCommercial,
        source = ModelSource.Tarball("$TTS/vits-piper-$name-medium.tar.bz2", bytes, tree = true),
    )

    /**
     * A Supertonic 3 voice. All ten are one download, and each speaks every language the model
     * knows. The 8-bit model is used: on a phone it is as fast as the full one at a third the size.
     */
    private fun supertonic(speaker: Int, title: String, summary: String) = ModelSpec(
        id = "voice-supertonic-3-${title.lowercase()}",
        store = "voice-supertonic-3",
        archive = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11",
        kind = ModelKind.VOICE,
        title = "Supertonic $title",
        summary = summary,
        encoder = "text_encoder.int8.onnx",
        locale = "en-US",
        languages = SUPERTONIC_LANGUAGES,
        license = "OpenRAIL-M",
        synth = Synth.SUPERTONIC,
        speaker = speaker,
        source = ModelSource.Tarball("$TTS/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2", 128_774_318, tree = true),
    )

    private val SUPERTONIC_LANGUAGES = listOf(
        "en", "ko", "ja", "ar", "bg", "cs", "da", "de", "el", "es", "et", "fi", "fr", "hi", "hr", "hu",
        "id", "it", "lt", "lv", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "tr", "uk", "vi",
    )

    /**
     * The voices offered for reading aloud, the default for each language first. Chosen for clear
     * speech at a speed an old phone keeps up with (about six times faster than real time on a
     * Pixel 4a 5G), and, with one labeled exception, for training data anyone may use.
     */
    val voices: List<ModelSpec> = listOf(
        piper("en_US-kristin", "Kristin", "American, female", "en-US", 67_259_230, "Public domain recordings"),
        piper("en_US-norman", "Norman", "American, male", "en-US", 67_203_672, "Public domain recordings"),
        piper("en_US-joe", "Joe", "American, male", "en-US", 67_169_394, "CC0"),
        piper("en_US-ljspeech", "Linda", "American, female", "en-US", 67_169_893, "Public domain recordings"),
        piper("en_US-hfc_female", "HFC", "American, female. The clearest of these.", "en-US", 67_228_166, "CC BY-NC-SA 4.0", nonCommercial = true),
        piper("en_GB-cori", "Cori", "British, female", "en-GB", 67_257_412, "Public domain recordings"),
        piper("en_GB-alba", "Alba", "Scottish, female", "en-GB", 67_212_349, "CC BY 4.0"),
        piper("de_DE-thorsten", "Thorsten", "Deutsch, male", "de-DE", 67_214_254, "CC0"),
        piper("fr_FR-siwis", "Siwis", "Français, female", "fr-FR", 67_207_459, "CC BY 4.0"),
        piper("es_ES-davefx", "Dave", "Español de España, male", "es-ES", 67_184_952, "CC0"),
        piper("es_MX-ald", "Ald", "Español de México, male", "es-MX", 67_196_497, "Unlicense"),
    ) + listOf(
        // In the order the model numbers them.
        supertonic(0, "F1", "Female"),
        supertonic(1, "F2", "Female"),
        supertonic(2, "F3", "Female"),
        supertonic(3, "F4", "Female"),
        supertonic(4, "F5", "Female"),
        supertonic(5, "M1", "Male"),
        supertonic(6, "M2", "Male"),
        supertonic(7, "M3", "Male"),
        supertonic(8, "M4", "Male"),
        supertonic(9, "M5", "Male"),
    )

    /** The voice to start with for [locale]: its country's first, else its language's first, else the first of all. */
    fun voiceFor(locale: java.util.Locale): ModelSpec {
        val tags = voices.map { java.util.Locale.forLanguageTag(it.locale) }
        val index = tags.indexOfFirst { it.language == locale.language && it.country == locale.country }
            .takeIf { it >= 0 } ?: tags.indexOfFirst { it.language == locale.language }.takeIf { it >= 0 } ?: 0
        return voices[index]
    }

    /** Every model this version offers. */
    val allModels: List<ModelSpec>
        get() = (languages.flatMap { pack -> listOf(pack.live) + pack.qualities.mapNotNull { it.refiner } } + summarizers + importedSummarizer + speakers + voices).distinct()

    /**
     * Alyo's own copies of the model files, tried before the upstream hosts. The models belong to
     * other projects, which may rename or remove them; copies here mean a release keeps working
     * for as long as it is installed. Files are named `<model id>.<file name>`, and nothing is
     * ever deleted from this release, since old versions of the app still point at it.
     */
    const val MIRROR = "https://github.com/PimpinPumpkin/alyo/releases/download/models"

    /** Where [file] of [spec] can be fetched from, in the order to try. */
    fun urls(spec: ModelSpec, file: ModelFile): List<String> = listOf("$MIRROR/${spec.id}.${file.name}", file.url)

    fun urls(spec: ModelSpec, tarball: ModelSource.Tarball): List<String> = listOf("$MIRROR/${spec.key}.tar.bz2", tarball.url)

    /** Where the archive for [spec] can be fetched by hand, for importing instead of downloading in the app. */
    fun archiveUrl(spec: ModelSpec): String = "$GH/${spec.archive}.tar.bz2"

    fun language(code: String): LanguagePack = languages.firstOrNull { it.code == code } ?: english

    /** The language to start in: the phone's own if Alyo can transcribe it, otherwise English. */
    fun languageFor(locale: java.util.Locale): LanguagePack = language(locale.language)
}
