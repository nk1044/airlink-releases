package dev.clipsync

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast

/** Invisible: reads the clipboard once it has focus (Android's rule), hands it to the service, and closes. */
class ClipReadActivity : Activity() {
    private var handled = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true

        if (Prefs(this).master == null) {
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
            return
        }
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val item = if (clip != null && clip.itemCount > 0) clip.getItemAt(0) else null
        val uri = item?.uri
        val isImage = uri != null && (
            clip?.description?.let { d -> (0 until d.mimeTypeCount).any { d.getMimeType(it).startsWith("image/") } } == true ||
                contentResolver.getType(uri)?.startsWith("image/") == true
            )
        if (uri != null && isImage) {
            SyncService.sendUris(this, listOf(uri), asImage = true)
        } else {
            val text = item?.coerceToText(this)?.toString().orEmpty()
            if (text.isEmpty()) {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            } else {
                SyncService.sendText(this, text)
            }
        }
        finish()
    }
}
