package app.alyo.export

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import app.alyo.R
import app.alyo.AlyoApp
import app.alyo.data.RecordingMeta
import java.io.File
import java.io.FileNotFoundException

/**
 * Puts the library in the system file picker, so another app can be handed a recording: attach
 * one to an email, open one in an editor. Nothing is exposed wholesale. An app sees only the file
 * the user picks, when they pick it, and only to read.
 *
 * Each recording appears as its audio (`.m4a`) and, if it has one, its transcript (`.txt`).
 */
class LibraryProvider : DocumentsProvider() {
    private val app get() = context!!.applicationContext as AlyoApp

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_COLUMNS)
        if (!app.settings.shareWithPicker.value) return cursor
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT)
            add(Root.COLUMN_DOCUMENT_ID, ROOT)
            add(Root.COLUMN_TITLE, context!!.getString(R.string.app_name))
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY)
            add(Root.COLUMN_MIME_TYPES, "audio/*\ntext/plain")
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        if (documentId == ROOT) {
            cursor.newRow().apply {
                add(Document.COLUMN_DOCUMENT_ID, ROOT)
                add(Document.COLUMN_DISPLAY_NAME, context!!.getString(R.string.app_name))
                add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                add(Document.COLUMN_FLAGS, 0)
            }
        } else {
            val (meta, extension) = find(documentId)
            add(cursor, meta, extension)
        }
        return cursor
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        if (!app.settings.shareWithPicker.value) return cursor
        for (meta in app.store.recordings.value) {
            add(cursor, meta, AUDIO)
            if (!app.store.transcript(meta.id).value.isEmpty) add(cursor, meta, TEXT)
        }
        return cursor
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (!app.settings.shareWithPicker.value || 'w' in mode) throw FileNotFoundException("Read only")
        val (meta, extension) = find(documentId)
        val file = if (extension == AUDIO) app.store.localAudio(meta.id) else Exporter(context!!, app.store).textFile(meta)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun find(documentId: String): Pair<RecordingMeta, String> {
        val id = documentId.substringBeforeLast('.')
        val meta = app.store.meta(id) ?: throw FileNotFoundException(documentId)
        return meta to documentId.substringAfterLast('.')
    }

    private fun add(cursor: MatrixCursor, meta: RecordingMeta, extension: String) {
        val file: File? = if (extension == AUDIO) app.store.audio(meta.id).takeIf { it.exists() } else null
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, "${meta.id}.$extension")
            add(Document.COLUMN_DISPLAY_NAME, "${meta.title}.$extension")
            add(Document.COLUMN_MIME_TYPE, if (extension == AUDIO) "audio/mp4" else "text/plain")
            add(Document.COLUMN_LAST_MODIFIED, meta.createdAt)
            add(Document.COLUMN_SIZE, file?.length())
            add(Document.COLUMN_FLAGS, 0)
        }
    }

    companion object {
        const val AUTHORITY_SUFFIX = ".library"
        private const val ROOT = "library"
        private const val AUDIO = "m4a"
        private const val TEXT = "txt"
        private val ROOT_COLUMNS = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_ICON, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES)
        private val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE, Document.COLUMN_FLAGS,
        )
    }
}
