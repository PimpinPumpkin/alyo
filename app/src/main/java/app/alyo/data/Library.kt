package app.alyo.data

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Keeps recordings in a folder outside the app. A finished recording's audio is moved there, and
 * a small file with its transcript and details is kept next to it and rewritten when they change.
 * The app holds on to an index of its own (waveform, transcript, title) so the library opens at
 * once and searches without touching the folder.
 *
 * Everything here is safe to repeat. A recording whose audio is still inside the app has not been
 * moved yet; one whose files sit somewhere other than the current folder has not been moved over
 * yet. [sync] puts right whichever it finds, so an interrupted move simply carries on next time.
 */
class Library(private val context: Context, private val store: RecordingStore, private val settings: Settings, private val scope: CoroutineScope) {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "alyo-library") }.asCoroutineDispatcher()
    private val resolver = context.contentResolver
    private val json = Json { ignoreUnknownKeys = true }
    private val pending = HashMap<String, Job>()

    private val _status = MutableStateFlow<String?>(null)
    /** What is being done to the folder right now, in words, or null when nothing is. */
    val status: StateFlow<String?> = _status

    init {
        store.onChange = ::schedule
        store.onDelete = { meta -> scope.launch(worker) { remove(meta) } }
    }

    /** The folder recordings go to, or null when they are kept inside the app. */
    fun vault(location: String = settings.libraryLocation.value): Vault? = when (location) {
        Settings.LIBRARY_PRIVATE -> null
        Settings.LIBRARY_DEFAULT -> MediaVault(context)
        else -> TreeVault(context, Uri.parse(location))
    }

    /** Where recordings are kept, for showing in Settings. */
    val label: String get() = vault()?.label ?: "Inside the app"

    /**
     * True when the default folder exists but holds nothing this install made, and the library is
     * empty: the mark of an earlier install's recordings. Android will not show them to a new
     * install until the user picks the folder, so this is the cue to ask.
     */
    fun hasFolderFromBefore(): Boolean {
        if (settings.libraryLocation.value != Settings.LIBRARY_DEFAULT || store.recordings.value.isNotEmpty()) return false
        val folder = java.io.File(android.os.Environment.getExternalStorageDirectory(), MediaVault.FOLDER)
        return folder.isDirectory && runCatching { MediaVault(context).list().isEmpty() }.getOrDefault(false)
    }

    /** The default folder as the system's folder picker knows it, to open the picker right there. */
    val defaultFolderHint: Uri
        get() = android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:" + MediaVault.FOLDER)

    /**
     * Brings every recording in line with the current folder: after the folder is changed, and at
     * launch to finish any move that was cut short (the app was closed, a card was out).
     */
    fun syncAll() = scope.launch(worker) {
        val all = store.recordings.value
        val behind = all.filter { needsMove(it) }
        behind.forEachIndexed { index, meta ->
            _status.value = "Moving ${index + 1} of ${behind.size}"
            sync(meta.id)
        }
        (all - behind.toSet()).forEach { if (it.sidecarUri == null && vault() != null) sync(it.id) }
        _status.value = null
    }

    /**
     * Changes where recordings are kept and moves them there. Recordings already in the new
     * folder from an earlier install are added to the library first.
     *
     * @return how many recordings were found in the folder and added
     */
    suspend fun moveTo(location: String): Int {
        settings.setLibraryLocation(location)
        val found = kotlinx.coroutines.withContext(worker) { scan() }
        syncAll().join()
        return found
    }

    /** Adds recordings that are in the folder but not in the library: a fresh install pointed at an old folder. */
    private fun scan(): Int {
        val vault = vault() ?: return 0
        _status.value = "Looking in the folder"
        val files = runCatching { vault.list() }.getOrElse { return 0.also { _ -> _status.value = null } }
        var added = 0
        for (file in files.filter { it.name.endsWith(SIDECAR) }) {
            val base = file.name.removeSuffix(SIDECAR)
            val audio = files.firstOrNull { it.name == base + AUDIO } ?: continue
            val sidecar = runCatching { resolver.openInputStream(file.uri)!!.use { json.decodeFromString(Sidecar.serializer(), it.readBytes().decodeToString()) } }.getOrNull() ?: continue
            if (store.adopt(sidecar, audio.uri.toString(), file.uri.toString(), base)) added++
        }
        _status.value = null
        return added
    }

    private fun needsMove(meta: RecordingMeta): Boolean {
        val vault = vault()
        val home = meta.audioUri?.let(Uri::parse)
        return when {
            store.audio(meta.id).exists() -> vault != null
            home == null -> false
            else -> vault == null || !vault.owns(home)
        }
    }

    /** A change waits a moment before it is written out, since changes come in bursts (the second pass, typing a correction). */
    private fun schedule(id: String) {
        synchronized(pending) {
            pending.remove(id)?.cancel()
            pending[id] = scope.launch(worker) {
                delay(DEBOUNCE_MS)
                sync(id)
            }
        }
    }

    private fun sync(id: String) {
        try {
            val meta = store.meta(id) ?: return
            val vault = vault()
            val inside = store.audio(id)
            val home = meta.audioUri?.let(Uri::parse)
            if (vault == null) {
                // Kept inside the app: bring the audio back if it is out, and tidy up after it.
                if (home != null) {
                    if (!inside.exists()) copy(home, inside)
                    delete(home, meta.sidecarUri)
                    store.setHome(id, null, null, null)
                }
                return
            }
            val name = fileName(meta)
            var audio = home?.takeIf { vault.owns(it) }
            var sidecar = meta.sidecarUri?.let(Uri::parse)?.takeIf { vault.owns(it) }
            var base = meta.fileName?.takeIf { audio != null }

            if (inside.exists()) {
                // New or edited audio. An edit goes over the file already in the folder; if that
                // file cannot be written (it was on another phone, in a restored backup), a new
                // one is made instead.
                val existing = audio
                val written = existing?.let { runCatching { write(inside, it) }.getOrNull() }
                if (written == null) {
                    val fresh = vault.create(name + AUDIO, "audio/mp4")
                    val count = runCatching { write(inside, fresh) }.getOrElse {
                        runCatching { vault.delete(fresh) }
                        throw it
                    }
                    if (count != inside.length()) error("Only part of the audio reached the recordings folder")
                    audio = fresh
                    base = name
                    sidecar = null
                } else if (written != inside.length()) {
                    error("Only part of the audio reached the recordings folder")
                }
            } else if (audio == null && home != null) {
                // The audio is in a folder that is no longer the chosen one: move it over.
                val target = vault.create(name + AUDIO, "audio/mp4")
                resolver.openInputStream(home)!!.use { input -> resolver.openOutputStream(target, "wt")!!.use { input.copyTo(it, 1 shl 16) } }
                audio = target
                base = name
            }
            if (audio == null) return

            if (sidecar == null) sidecar = vault.create((base ?: name) + SIDECAR, "application/json")
            val details = store.sidecar(id) ?: return
            resolver.openOutputStream(sidecar, "wt")!!.use { it.write(json.encodeToString(Sidecar.serializer(), details).encodeToByteArray()) }

            // The files carry the title, so a new title renames them.
            if (base != name) {
                audio = runCatching { vault.rename(audio!!, name + AUDIO) }.getOrDefault(audio!!)
                sidecar = runCatching { vault.rename(sidecar!!, name + SIDECAR) }.getOrDefault(sidecar!!)
                base = name
            }
            store.setHome(id, audio.toString(), sidecar.toString(), base)
            // Only now is it safe to let go of what was here before.
            if (inside.exists()) inside.delete()
            if (home != null && home != audio && !vault.owns(home)) delete(home, meta.sidecarUri)
        } catch (e: Throwable) {
            // The folder may be on a card that has been taken out. The recording stays as it is
            // and is tried again the next time it changes or the app starts.
            Log.e(TAG, "Could not sync $id with the recordings folder", e)
        }
    }

    private fun remove(meta: RecordingMeta) {
        meta.audioUri?.let { delete(Uri.parse(it), meta.sidecarUri) }
    }

    private fun delete(audio: Uri, sidecar: String?) {
        for (uri in listOfNotNull(audio, sidecar?.let(Uri::parse))) {
            runCatching {
                when {
                    uri.authority == android.provider.MediaStore.AUTHORITY -> resolver.delete(uri, null, null)
                    else -> android.provider.DocumentsContract.deleteDocument(resolver, uri)
                }
            }.onFailure { Log.e(TAG, "Could not delete $uri", it) }
        }
    }

    private fun write(from: java.io.File, to: Uri): Long =
        from.inputStream().use { input -> resolver.openOutputStream(to, "wt")!!.use { input.copyTo(it, 1 shl 16) } }

    private fun copy(from: Uri, to: java.io.File) {
        val part = java.io.File(to.parentFile, to.name + ".part")
        resolver.openInputStream(from)!!.use { input -> part.outputStream().use { input.copyTo(it, 1 shl 16) } }
        if (!part.renameTo(to)) error("Could not bring the audio back into the app")
    }

    /** "2026-10-01 11.59 Interview about early computers": sorts by date in a file manager and says what it is. */
    private fun fileName(meta: RecordingMeta): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(Date(meta.createdAt))
        // A title that is only the date adds nothing to the stamp.
        val title = meta.title.takeIf { it != Titles.forDate(meta.createdAt) }.orEmpty()
            .replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), " ").replace(Regex("\\s+"), " ").trim().take(60)
        return if (title.isEmpty()) stamp else "$stamp $title"
    }

    private companion object {
        const val TAG = "Library"
        const val AUDIO = ".m4a"
        const val SIDECAR = ".alyo.json"
        const val DEBOUNCE_MS = 1500L
    }
}
