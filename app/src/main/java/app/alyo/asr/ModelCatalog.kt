package app.alyo.asr

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
    /** Emits words while audio is still arriving. Drives the text that appears while you talk. */
    STREAMING_TRANSDUCER,

    /** Needs a finished stretch of audio; slower to appear but far more accurate, with punctuation. */
    OFFLINE_NEMO_TRANSDUCER,
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
     * moment it committed. Subtracted so pauses are measured between the words themselves.
     */
    val lagMs: Int = 0,
    /**
     * Earlier models this one takes over from, newest first. A phone that has one of them keeps
     * transcribing with it until this one has been downloaded, at which point the old one is
     * deleted. So upgrading a model is: give the new one a new [id], and move the old spec here.
     */
    val replaces: List<ModelSpec> = emptyList(),
)

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
                summary = "Most accurate. Three times the download and about 1 GB of memory while dictating.",
                refiner = enParakeetLarge,
            ),
        ),
    )

    val languages: List<LanguagePack> = listOf(english)

    /** Every model this version offers. */
    val allModels: List<ModelSpec>
        get() = languages.flatMap { pack -> listOf(pack.live) + pack.qualities.mapNotNull { it.refiner } }.distinct()

    /**
     * Alyo's own copies of the model files, tried before the upstream hosts. The models belong to
     * other projects, which may rename or remove them; copies here mean a release keeps working
     * for as long as it is installed. Files are named `<model id>.<file name>`, and nothing is
     * ever deleted from this release, since old versions of the app still point at it.
     */
    const val MIRROR = "https://github.com/PimpinPumpkin/alyo/releases/download/models"

    /** Where [file] of [spec] can be fetched from, in the order to try. */
    fun urls(spec: ModelSpec, file: ModelFile): List<String> = listOf("$MIRROR/${spec.id}.${file.name}", file.url)

    fun urls(spec: ModelSpec, tarball: ModelSource.Tarball): List<String> = listOf("$MIRROR/${spec.id}.tar.bz2", tarball.url)

    /** Where the archive for [spec] can be fetched by hand, for importing instead of downloading in the app. */
    fun archiveUrl(spec: ModelSpec): String = "$GH/${spec.archive}.tar.bz2"

    fun language(code: String): LanguagePack = languages.firstOrNull { it.code == code } ?: english

}
