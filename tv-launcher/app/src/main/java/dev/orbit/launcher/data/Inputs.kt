package dev.orbit.launcher.data

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Handler
import android.os.Looper

class TvInput(val id: String, val label: String, val type: Int, val state: Int) {
    val connected get() = state == TvInputManager.INPUT_STATE_CONNECTED
    val available get() = state != TvInputManager.INPUT_STATE_DISCONNECTED
}

/** HDMI and AV ports through the TV Input Framework (passthrough inputs only). */
class Inputs(private val context: Context) {

    private val tim = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager

    @Volatile
    var all: List<TvInput> = emptyList()
        private set

    /** Whether some app (the vendor's live-TV app) handles a passthrough channel URI. */
    var launchable = false
        private set

    var onChanged: (() -> Unit)? = null

    fun refresh() {
        val m = tim ?: return
        Work.io.execute {
            val list = try {
                m.tvInputList.filter { it.isPassthroughInput && !it.isHidden(context) }.map { info ->
                    val label = (info.loadCustomLabel(context) ?: info.loadLabel(context))?.toString()?.trim()
                        .takeUnless { it.isNullOrEmpty() } ?: defaultName(info.type)
                    TvInput(info.id, label, info.type, m.getInputState(info.id))
                }.sortedWith(compareBy<TvInput> { order(it.type) }.thenBy { it.label })
            } catch (e: Exception) {
                emptyList()
            }
            // Two ports both called "AV": number them.
            val dupes = list.groupBy { it.label }.filterValues { it.size > 1 }
            val named = list.map { i ->
                val group = dupes[i.label] ?: return@map i
                TvInput(i.id, "${i.label} ${group.indexOf(i) + 1}", i.type, i.state)
            }
            val canLaunch = list.isNotEmpty() && intentFor(list[0].id).resolveActivity(context.packageManager) != null
            Work.ui {
                all = named
                launchable = canLaunch
                onChanged?.invoke()
            }
        }
    }

    /** Shown on Home: connected or connected-standby ports. */
    fun forHome(): List<TvInput> = if (launchable) all.filter { it.available } else emptyList()

    fun intentFor(id: String): Intent =
        Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private val callback = object : TvInputManager.TvInputCallback() {
        override fun onInputStateChanged(inputId: String, state: Int) = refresh()
        override fun onInputAdded(inputId: String) = refresh()
        override fun onInputRemoved(inputId: String) = refresh()
        override fun onInputUpdated(inputId: String) = refresh()
    }
    private var watching = false

    fun watch(on: Boolean) {
        val m = tim ?: return
        if (on == watching) return
        watching = on
        if (on) m.registerCallback(callback, Handler(Looper.getMainLooper())) else m.unregisterCallback(callback)
    }

    private fun order(type: Int) = when (type) {
        TvInputInfo.TYPE_HDMI -> 0
        TvInputInfo.TYPE_DISPLAY_PORT, TvInputInfo.TYPE_DVI -> 1
        TvInputInfo.TYPE_COMPONENT -> 2
        TvInputInfo.TYPE_COMPOSITE, TvInputInfo.TYPE_SVIDEO, TvInputInfo.TYPE_SCART -> 3
        else -> 4
    }

    private fun defaultName(type: Int) = when (type) {
        TvInputInfo.TYPE_HDMI -> "HDMI"
        TvInputInfo.TYPE_COMPOSITE -> "Composite"
        TvInputInfo.TYPE_COMPONENT -> "Component"
        TvInputInfo.TYPE_VGA -> "VGA"
        TvInputInfo.TYPE_SCART -> "SCART"
        TvInputInfo.TYPE_SVIDEO -> "S-Video"
        TvInputInfo.TYPE_DVI -> "DVI"
        TvInputInfo.TYPE_DISPLAY_PORT -> "DisplayPort"
        else -> "Input"
    }

    companion object {
        fun isHdmiLike(type: Int) = type == TvInputInfo.TYPE_HDMI || type == TvInputInfo.TYPE_DVI || type == TvInputInfo.TYPE_DISPLAY_PORT

        fun stateLabel(state: Int) = when (state) {
            TvInputManager.INPUT_STATE_CONNECTED -> "Connected"
            TvInputManager.INPUT_STATE_CONNECTED_STANDBY -> "Standby"
            else -> "Not connected"
        }
    }
}
