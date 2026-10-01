package app.alyo.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore

/** A file in a [Vault]. */
class VaultFile(val name: String, val uri: Uri)

/**
 * A folder outside the app where recordings are kept, so they outlive the app and can be reached
 * by a file manager or a computer. Files are created, renamed, listed and deleted through here;
 * reading and writing their contents goes through the content resolver with the URI.
 */
interface Vault {
    /** What to call the place, for the settings screen. */
    val label: String

    /** True if [uri] is a file in this vault. */
    fun owns(uri: Uri): Boolean

    /** Creates an empty file. The name may come back altered if one like it exists. */
    fun create(name: String, mime: String): Uri

    /** @return the file's URI afterwards, which some folders change on rename */
    fun rename(uri: Uri, name: String): Uri

    fun delete(uri: Uri)

    fun list(): List<VaultFile>
}

/**
 * The default: `Documents/Alyo` on the phone's own storage, through the media store. It needs no
 * permission and no question asked. Its limit is that Android only lets an app see files there
 * that it created itself, so a fresh install cannot read what an earlier one left; for that the
 * folder has to be chosen by hand, which makes it a [TreeVault].
 */
class MediaVault(context: Context) : Vault {
    private val resolver = context.contentResolver
    private val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    override val label = FOLDER

    override fun owns(uri: Uri) = uri.authority == MediaStore.AUTHORITY

    override fun create(name: String, mime: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
        }
        return resolver.insert(collection, values) ?: error("The phone's storage is not available")
    }

    override fun rename(uri: Uri, name: String): Uri {
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) }, null, null)
        return uri
    }

    override fun delete(uri: Uri) {
        resolver.delete(uri, null, null)
    }

    override fun list(): List<VaultFile> {
        val found = ArrayList<VaultFile>()
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf("$FOLDER/"),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) found += VaultFile(cursor.getString(1).orEmpty(), Uri.withAppendedPath(collection, cursor.getLong(0).toString()))
        }
        return found
    }

    companion object {
        const val FOLDER = "Documents/Alyo"
    }
}

/**
 * A folder the user picked in the system's folder picker: anywhere on the phone, an SD card, a
 * USB drive. The grant is remembered across restarts, and it covers files that were already in
 * the folder, which is what lets a fresh install pick up an earlier one's recordings.
 */
class TreeVault(context: Context, private val tree: Uri) : Vault {
    private val resolver = context.contentResolver
    private val root: Uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    // A tree id reads "primary:Recordings/Alyo" or "1A2B-3C4D:Alyo"; the part before the colon is the volume.
    override val label: String = DocumentsContract.getTreeDocumentId(tree).let { id ->
        val path = id.substringAfter(':', "").ifEmpty { "the top folder" }
        if (id.startsWith("primary:")) path else "$path on the card"
    }

    override fun owns(uri: Uri) = uri.toString().startsWith(tree.toString())

    override fun create(name: String, mime: String): Uri =
        DocumentsContract.createDocument(resolver, root, mime, name) ?: error("Could not create a file in the recordings folder")

    override fun rename(uri: Uri, name: String): Uri = DocumentsContract.renameDocument(resolver, uri, name) ?: uri

    override fun delete(uri: Uri) {
        DocumentsContract.deleteDocument(resolver, uri)
    }

    override fun list(): List<VaultFile> {
        val found = ArrayList<VaultFile>()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) found += VaultFile(cursor.getString(1).orEmpty(), DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)))
        }
        return found
    }
}
