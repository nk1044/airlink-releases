package dev.clipsync

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** The Control Center tile. Tap: send the phone's clipboard to the PC. Long-press: settings. */
class ClipTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        if (!SyncService.isRunning && Prefs(this).master != null) SyncService.start(this)
        refresh()
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
        tile.state = if (s == SyncService.STATE_WIFI || s == SyncService.STATE_BT) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        val pc = SyncService.peer.ifEmpty { "PC" }
        tile.subtitle = when (s) {
            SyncService.STATE_WIFI -> "$pc · Wi-Fi"
            SyncService.STATE_BT -> "$pc · Bluetooth"
            SyncService.STATE_PAUSED -> "Paused"
            SyncService.STATE_SETUP -> "Set up"
            SyncService.STATE_BAD_PASSWORD -> "Wrong password"
            else -> "Not connected"
        }
        tile.updateTile()
    }
}
