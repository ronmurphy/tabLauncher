package dev.tabdesk

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * Lets another app open a file from a Folder View: TabDesk hands it a content:// link to that one file, read only,
 * when the user opens, shares or drags the file out. Nothing else can use it (it isn't exported; Android only lets
 * through the apps TabDesk gave a link to). Only files in shared storage (/storage/…), never TabDesk's own data.
 */
class FilesProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "dev.tabdesk.files"

        fun uriFor(f: File): Uri = Uri.Builder().scheme("content").authority(AUTHORITY)
            .encodedPath(Uri.encode(f.absolutePath, "/")).build()

        /** The file a link stands for, if it's one this provider may hand out. */
        fun fileOf(uri: Uri): File? {
            val p = uri.path ?: return null
            val f = try { File(p).canonicalFile } catch (_: Exception) { return null }
            return f.takeIf { it.path.startsWith("/storage/") && it.isFile }
        }

        fun mimeOf(f: File): String = mimeOf(f.name)
        fun mimeOf(name: String): String =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                ?: "application/octet-stream"
    }

    override fun onCreate() = true

    override fun getType(uri: Uri): String? = fileOf(uri)?.let { mimeOf(it) }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        val f = fileOf(uri) ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** The name and size, which apps ask for before reading (e.g. to show the file's name). */
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val f = fileOf(uri) ?: return null
        val cols = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols, 1).apply {
            addRow(cols.map { if (it == OpenableColumns.DISPLAY_NAME) f.name else f.length() })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
}
