package dev.clipsync

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** The Control Center tile. Tap: send the phone's clipboard to the PC. Long-press: settings. */
class ClipTileService : TileService() {

    companion object {
        @Volatile private var listening: ClipTileService? = null

        /** Refreshes the tile now if Control Center is open, else when it next opens. Main thread. */
        fun update(context: Context) {
            listening?.refresh()
                ?: TileService.requestListeningState(context, ComponentName(context, ClipTileService::class.java))
        }
    }

    override fun onTileAdded() {
        super.onTileAdded()
        Prefs(this).tileAdded = true
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
        Prefs(this).tileAdded = false
    }

    override fun onStartListening() {
        super.onStartListening()
        listening = this
        val prefs = Prefs(this)
        if (!prefs.tileAdded) prefs.tileAdded = true // added before this was tracked
        if (!SyncService.isRunning && prefs.master != null) SyncService.start(this)
        refresh()
    }

    override fun onStopListening() {
        if (listening === this) listening = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        // Android only lets the app on screen read the clipboard, so open an invisible activity for a moment.
        val intent = Intent(this, ClipReadActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val open = Runnable {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
        if (isLocked) unlockAndRun(open) else open.run()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val s = SyncService.state
        val pc = SyncService.peer.ifEmpty { "PC" }
        tile.state = if (s == SyncService.STATE_WIFI || s == SyncService.STATE_BT) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        val p = SyncService.progress
        tile.subtitle = when {
            p != null -> (if (p.sending) "↑ " else "↓ ") + "${p.percent}%"
            s == SyncService.STATE_WIFI -> "$pc · " + if (SyncService.viaHotspot) "Hotspot" else "Wi-Fi"
            s == SyncService.STATE_BT -> "$pc · Bluetooth"
            s == SyncService.STATE_PAUSED -> "Paused"
            s == SyncService.STATE_SETUP -> "Not linked"
            s == SyncService.STATE_BAD_PASSWORD -> "Link again"
            else -> "Not connected"
        }
        tile.updateTile()
    }
}
