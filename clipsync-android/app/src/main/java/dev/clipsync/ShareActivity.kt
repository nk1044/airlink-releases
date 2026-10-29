package dev.clipsync

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * "Send to PC" in the Share menu: one image goes to the PC clipboard, shared text goes to the clipboard,
 * anything else (or several items) is saved as files in Downloads\ClipSync on the PC.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Prefs(this).master == null) {
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
            return
        }
        val type = intent.type.orEmpty()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                when {
                    uri == null && !text.isNullOrEmpty() -> SyncService.sendText(this, text)
                    uri != null -> SyncService.sendUris(this, listOf(uri), asImage = type.startsWith("image/"))
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                if (!uris.isNullOrEmpty()) SyncService.sendUris(this, uris, asImage = false)
            }
        }
        finish()
    }
}
