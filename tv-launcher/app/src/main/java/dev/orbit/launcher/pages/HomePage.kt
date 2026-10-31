package dev.orbit.launcher.pages

import android.text.format.DateUtils
import android.view.View
import android.widget.LinearLayout
import dev.orbit.launcher.R
import dev.orbit.launcher.data.AppEntry
import dev.orbit.launcher.data.Inputs
import dev.orbit.launcher.ui.AppAdapter
import dev.orbit.launcher.ui.HeroCard
import dev.orbit.launcher.ui.InfoTile
import dev.orbit.launcher.ui.RecentTile
import dev.orbit.launcher.ui.TvList
import dev.orbit.launcher.ui.WRAP
import dev.orbit.launcher.ui.frameParams
import dev.orbit.launcher.ui.u

/**
 * Home, laid out on the design's 1280×720 grid (page origin is y = 64, under the top bar):
 * hero row 298 high, "Your Apps" banner row, "Inputs" row.
 */
class HomePage(host: Host) : Page(host) {

    override val view = Canvas720(context)

    private val hero = HeroCard(context)
    private val recents = listOf(RecentTile(context), RecentTile(context))
    private val appsLabel = sectionLabel(context, "Your Apps")
    private val appsRow = TvList(context, horizontal = true)
    private val appsAdapter = AppAdapter(186.u, 104.u, 5.u, 7.u, host::launch, host::showOptions)
    private val inputsDivider = hairline(context)
    private val inputsLabel = sectionLabel(context, "Inputs")
    private val inputsRow = LinearLayout(context)

    init {
        val left = 52.u
        view.addView(hero, frameParams(608.u, 298.u, left, 16.u))
        val rx = left + 608.u + 14.u
        val rw = 1280.u - 52.u - rx
        view.addView(recents[0], frameParams(rw, 142.u, rx, 16.u))
        view.addView(recents[1], frameParams(rw, 142.u, rx, 16.u + 142.u + 14.u))

        view.addView(hairline(context), frameParams(1176.u, 1.u, left, 332.u))
        view.addView(appsLabel, frameParams(WRAP, WRAP, left, 346.u))

        appsRow.setPadding(47.u, 0, 47.u, 0)
        appsRow.setRecycledViewPool(host.cardPool)
        appsRow.adapter = appsAdapter
        // Cards carry 7 px vertical margins (shared pool with the grid), so the row starts 7 px higher.
        view.addView(appsRow, frameParams(1280.u, 118.u, 0, 363.u))

        view.addView(inputsDivider, frameParams(1176.u, 1.u, left, 488.u))
        view.addView(inputsLabel, frameParams(WRAP, WRAP, left, 502.u))
        inputsRow.orientation = LinearLayout.HORIZONTAL
        inputsRow.clipChildren = false
        inputsRow.clipToPadding = false
        view.addView(inputsRow, frameParams(1176.u, 68.u, left, 526.u))

        // Where focus goes when the TV leaves touch mode (a pointer remote, an injected tap).
        hero.isFocusedByDefault = true
        hero.onClick = { hero.entry?.let { host.launch(it, hero) } }
        hero.onLongClick = { hero.entry?.let { host.showOptions(it, hero) } }
        recents.forEach { t ->
            t.onClick = { t.entry?.let { host.launch(it, t) } }
            t.onLongClick = { t.entry?.let { host.showOptions(it, t) } }
        }

        bindApps()
        bindInputs()
    }

    override fun defaultFocus(): View = hero

    override fun reset() {
        appsRow.reset()
        hero.requestFocus()
    }

    override fun onAppsChanged() = bindApps()
    override fun onInputsChanged() = bindInputs()

    private fun bindApps() {
        val r = host.apps.recents(3)
        val now = System.currentTimeMillis()
        val first = r.getOrNull(0)
        hero.bind(
            first,
            subtitle = when {
                first == null -> "Your apps will appear here"
                first.lastUsed > 0 -> "Last opened ${ago(first, now)}"
                else -> if (first.isTv) "Made for TV" else "App"
            },
            eyebrow = if (first != null && first.lastUsed > 0) "Jump back in" else "Suggested",
        )
        for (i in 0..1) {
            val e = r.getOrNull(i + 1)
            recents[i].bind(e, if (e == null) "" else if (e.lastUsed > 0) "Opened ${ago(e, now)}" else "Not opened yet")
            recents[i].visibility = if (e == null) View.INVISIBLE else View.VISIBLE
        }
        appsAdapter.submitList(host.apps.homeOrder())
    }

    private fun ago(e: AppEntry, now: Long): String =
        if (now - e.lastUsed < DateUtils.MINUTE_IN_MILLIS) "just now"
        else DateUtils.getRelativeTimeSpanString(e.lastUsed, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

    private fun bindInputs() {
        val list = host.inputs.forHome()
        val show = list.isNotEmpty()
        inputsDivider.visibility = if (show) View.VISIBLE else View.GONE
        inputsLabel.visibility = inputsDivider.visibility
        inputsRow.visibility = inputsDivider.visibility

        // Reuse tiles; keep focus if one of them has it.
        while (inputsRow.childCount > list.size.coerceAtMost(5)) inputsRow.removeViewAt(inputsRow.childCount - 1)
        list.take(5).forEachIndexed { i, input ->
            val tile = (inputsRow.getChildAt(i) as? InfoTile) ?: InfoTile(context).also {
                inputsRow.addView(it, LinearLayout.LayoutParams(220.u, 68.u).apply { if (i > 0) leftMargin = 10.u })
            }
            tile.bind(
                if (Inputs.isHdmiLike(input.type)) R.drawable.ic_hdmi else R.drawable.ic_composite,
                input.label,
                Inputs.stateLabel(input.state),
                input.connected,
            )
            tile.onClick = { host.launchInput(input, tile) }
            tile.onLongClick = null
        }
    }
}
