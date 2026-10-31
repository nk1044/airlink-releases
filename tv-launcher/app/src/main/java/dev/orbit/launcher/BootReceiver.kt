package dev.orbit.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Enabled only by Settings › Open at TV startup. Brings Home up once the TV has booted. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.startActivity(
            Intent(context, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        )
    }
}
