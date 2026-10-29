package dev.clipsync

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** Serves the latest image received from the PC, so apps can paste it from the clipboard. Read-only. */
class ClipProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "dev.clipsync.clip"
        const val DIR = "clip"
        fun uriFor(name: String): Uri = Uri.parse("content://$AUTHORITY/$name")
    }

    private fun fileFor(uri: Uri): File {
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        val file = File(File(context!!.cacheDir, DIR), name)
        if (name.contains('/') || !file.isFile) throw FileNotFoundException()
        return file
    }

    override fun onCreate() = true

    override fun getType(uri: Uri): String? = when (uri.lastPathSegment?.substringAfterLast('.')) {
        "png" -> "image/png"
        "jpg" -> "image/jpeg"
        else -> null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = fileFor(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols, 1).apply {
            addRow(cols.map { if (it == OpenableColumns.SIZE) file.length() else if (it == OpenableColumns.DISPLAY_NAME) file.name else null })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
