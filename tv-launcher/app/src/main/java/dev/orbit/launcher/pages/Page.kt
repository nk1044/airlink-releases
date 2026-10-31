package dev.orbit.launcher.pages

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import dev.orbit.launcher.data.AppEntry
import dev.orbit.launcher.data.AppRepository
import dev.orbit.launcher.data.Inputs
import dev.orbit.launcher.data.TvInput
import dev.orbit.launcher.ui.Fonts
import dev.orbit.launcher.ui.Palette
import dev.orbit.launcher.ui.label
import dev.orbit.launcher.ui.Sheet

/** What pages need from the activity. */
interface Host {
    val activity: Activity
    val apps: AppRepository
    val inputs: Inputs
    val sheet: Sheet
    /** One pool for every banner card on every page. */
    val cardPool: RecyclerView.RecycledViewPool
    fun launch(e: AppEntry, from: View)
    fun launchInput(input: TvInput, from: View)
    fun launchIntent(intent: android.content.Intent, from: View)
    fun showOptions(e: AppEntry, from: View)
    fun hud(msg: String)
}

abstract class Page(val host: Host) {
    val context: Context get() = host.activity
    abstract val view: View
    /** Where focus lands when entering the page from the nav bar. */
    abstract fun defaultFocus(): View?
    /** HOME pressed while showing, or Back at the top: return to the start. */
    open fun reset() {}
    open fun onAppsChanged() {}
    open fun onInputsChanged() {}
    open fun onShown() {}
}

/** Small caps section heading, as in the design ("YOUR APPS"). */
fun sectionLabel(context: Context, text: String) =
    context.label(text.uppercase(), Fonts.bold, 10.5f, Palette.text(0.5f), 1.8f)

fun hairline(context: Context) = View(context).apply { setBackgroundColor(Palette.line(0.06f)) }

/** A FrameLayout that lets focused children scale past its edges. */
open class Canvas720(context: Context) : FrameLayout(context) {
    init {
        clipChildren = false
        clipToPadding = false
    }
}
