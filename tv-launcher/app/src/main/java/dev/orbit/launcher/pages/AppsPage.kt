package dev.orbit.launcher.pages

import android.view.View
import dev.orbit.launcher.ui.AppAdapter
import dev.orbit.launcher.ui.TvList
import dev.orbit.launcher.ui.WRAP
import dev.orbit.launcher.ui.frameParams
import dev.orbit.launcher.ui.u

/** Every app, A–Z, six banners across. */
class AppsPage(host: Host) : Page(host) {

    override val view = Canvas720(context)
    private val label = sectionLabel(context, "All apps")
    private val grid = TvList(context, horizontal = false, span = 6)
    private val adapter = AppAdapter(186.u, 104.u, 5.u, 7.u, host::launch, host::showOptions)

    init {
        view.addView(label, frameParams(WRAP, WRAP, 52.u, 18.u))
        grid.setPadding(47.u, 10.u, 47.u, 40.u)
        grid.setRecycledViewPool(host.cardPool)
        grid.adapter = adapter
        view.addView(grid, frameParams(1280.u, 656.u - 36.u, 0, 36.u))
        bind()
    }

    /** The grid itself: requesting focus on it lands on the remembered card. */
    override fun defaultFocus(): View = grid

    override fun reset() {
        grid.reset()
    }

    override fun onAppsChanged() = bind()

    private fun bind() {
        val list = host.apps.alphabetical()
        label.text = "ALL APPS  ·  ${list.size}"
        adapter.submitList(list)
    }
}
