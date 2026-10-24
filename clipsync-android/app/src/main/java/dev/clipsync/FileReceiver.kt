package dev.clipsync

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.OutputStream

/** Writes incoming FILE_* frames into Download/ClipSync through MediaStore (no storage permission needed). */
class FileReceiver(private val context: Context) {
    private var uri: Uri? = null
    private var out: OutputStream? = null
    private var size = 0L
    private var got = 0L
    private var count = 0

    fun begin(f: Frame) {
        cancel()
        if (f.len < 10) return
        val nameLen = Proto.readBe16(f.buf, 9)
        if (10 + nameLen > f.len) return
        size = Proto.readBe64(f.buf, 1)
        got = 0
        val name = String(f.buf, 11, nameLen, Charsets.UTF_8)
            .replace('/', '_').replace('\\', '_').trim().ifEmpty { "file" }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ClipSync")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val u = runCatching { context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }.getOrNull()
            ?: return
        uri = u
        out = runCatching { context.contentResolver.openOutputStream(u)?.buffered(Proto.CHUNK) }.getOrNull()
        if (out == null) cancel()
    }

    fun data(f: Frame) {
        val o = out ?: return
        if (got + f.len > size) {
            cancel()
            return
        }
        try {
            o.write(f.buf, 1, f.len)
            got += f.len
        } catch (e: Exception) {
            cancel()
        }
    }

    fun end() {
        val o = out ?: return
        val u = uri ?: return
        out = null
        uri = null
        val ok = runCatching { o.close() }.isSuccess && got == size
        if (ok) {
            context.contentResolver.update(u, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            count++
        } else {
            runCatching { context.contentResolver.delete(u, null, null) }
        }
    }

    fun cancel() {
        runCatching { out?.close() }
        out = null
        uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
        uri = null
    }

    /** Number of files completed in this batch; resets the count. */
    fun done(): Int = count.also { count = 0 }
}
