package app.sotto.asr

/** One file of a model, fetched on its own so an interrupted download can resume. */
data class ModelFile(val name: String, val url: String, val bytes: Long, val sha256: String? = null)

sealed interface ModelSource {
    val downloadBytes: Long

    data class Files(val files: List<ModelFile>) : ModelSource {
        override val downloadBytes: Long get() = files.sumOf { it.bytes }
    }

    /** A `.tar.bz2` from the sherpa-onnx model releases; only the entries named in [keep] are unpacked. */
    data class Tarball(val url: String, override val downloadBytes: Long, val keep: Set<String>) : ModelSource
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
)

/** How good the finished transcript is, traded against download size and memory. */
data class Quality(val id: String, val title: String, val summary: String, val refiner: ModelSpec)

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
                    ModelFile("tokens.txt", "$base/tokens.txt", 11_896),
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
                    ModelFile("tokens.txt", "$base/tokens.txt", 9_384),
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
                id = "best",
                title = "Best",
                summary = "Most accurate. For phones with 6 GB or more.",
                refiner = enParakeetLarge,
            ),
            Quality(
                id = "light",
                title = "Lighter",
                summary = "A third of the size, slightly less accurate.",
                refiner = enParakeetSmall,
            ),
        ),
    )

    val languages: List<LanguagePack> = listOf(english)

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
            "llm-qwen2.5-0.5b", "Quick", "Fast on any phone. Gets the gist; can miss detail.",
            "Qwen/Qwen2.5-0.5B-Instruct-GGUF", "qwen2.5-0.5b-instruct-q8_0.gguf", 675_710_816,
            "ca59ca7f13d0e15a8cfa77bd17e65d24f6844b554a7b6c12e07a5f89ff76844e",
        ),
        gguf(
            "llm-qwen2.5-1.5b", "Thorough", "Better summaries, four times slower. For recent phones.",
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

    /** The download behind speaker labels. Language does not matter to it: a voice is a voice. */
    val speakers = ModelSpec(
        id = "speakers-campplus-voxceleb",
        kind = ModelKind.SPEAKER_EMBEDDING,
        title = "Speaker labels",
        summary = "3D-Speaker CAM++ voice model",
        encoder = "speakers.onnx",
        source = ModelSource.Files(
            listOf(
                ModelFile(
                    "speakers.onnx",
                    "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx",
                    29_596_978,
                    "357a834f702b80161e5b981182c038e18553c1f2ca752ed6cec2052365d4129b",
                ),
            ),
        ),
    )

    val allModels: List<ModelSpec>
        get() = languages.flatMap { pack -> listOf(pack.live) + pack.qualities.map { it.refiner } } + summarizers + importedSummarizer + speakers

    /** Where the archive for [spec] can be fetched by hand, for importing into a build with no network. */
    fun archiveUrl(spec: ModelSpec): String = "$GH/${spec.archive}.tar.bz2"

    fun language(code: String): LanguagePack = languages.firstOrNull { it.code == code } ?: english
}
