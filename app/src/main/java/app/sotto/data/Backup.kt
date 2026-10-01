package app.sotto.data

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The whole library as one zip: each recording's folder, exactly as it is stored. Restoring adds
 * what the phone does not already have and leaves everything else alone, so it is safe to restore
 * the same backup twice, or an old one over a newer library.
 */
object Backup {
    /** @return how many recordings were written */
    fun write(root: File, out: OutputStream, onProgress: (done: Int, total: Int) -> Unit): Int {
        val folders = root.listFiles { f -> f.isDirectory && File(f, "audio.m4a").exists() }.orEmpty()
        ZipOutputStream(out.buffered(1 shl 16)).use { zip ->
            // Audio is already compressed; deflating it again costs time and saves nothing.
            zip.setLevel(1)
            folders.forEachIndexed { index, folder ->
                for (file in folder.listFiles().orEmpty()) {
                    if (!file.isFile || file.name == "asr.pcm" || file.name.endsWith(".tmp.m4a")) continue
                    zip.putNextEntry(ZipEntry("${folder.name}/${file.name}"))
                    file.inputStream().use { it.copyTo(zip, 1 shl 16) }
                    zip.closeEntry()
                }
                onProgress(index + 1, folders.size)
            }
        }
        return folders.size
    }

    /** @return how many recordings were added */
    fun restore(root: File, input: InputStream): Int {
        val added = HashSet<String>()
        val skipped = HashSet<String>()
        ZipInputStream(input.buffered(1 shl 16)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val parts = entry.name.split('/')
                // Exactly "<recording>/<file>", nothing that could climb out of the library folder.
                if (entry.isDirectory || parts.size != 2 || parts.any { it.isEmpty() || it == "." || it == ".." || '\\' in it }) continue
                val (id, name) = parts
                if (id in skipped) continue
                val folder = File(root, id)
                if (id !in added) {
                    if (folder.exists()) {
                        skipped += id
                        continue
                    }
                    folder.mkdirs()
                    added += id
                }
                File(folder, name).outputStream().use { zip.copyTo(it, 1 shl 16) }
            }
        }
        // A folder without audio is not a recording: a truncated archive, or not one of ours.
        return added.count { id -> File(File(root, id), "audio.m4a").exists().also { ok -> if (!ok) File(root, id).deleteRecursively() } }
    }
}
