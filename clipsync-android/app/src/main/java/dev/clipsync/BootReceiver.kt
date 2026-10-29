package dev.clipsync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Reconnects after a reboot or an app update, without opening the app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            if (Prefs(context).master != null) SyncService.start(context)
        }
    }
}
