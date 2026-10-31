package dev.orbit.launcher.pages

import android.view.View
import android.widget.GridLayout
import dev.orbit.launcher.R
import dev.orbit.launcher.data.Inputs
import dev.orbit.launcher.ui.Fonts
import dev.orbit.launcher.ui.InfoTile
import dev.orbit.launcher.ui.Palette
import dev.orbit.launcher.ui.WRAP
import dev.orbit.launcher.ui.frameParams
import dev.orbit.launcher.ui.label
import dev.orbit.launcher.ui.u

/** Every HDMI / AV port, including disconnected ones. */
class InputsPage(host: Host) : Page(host) {

    override val view = Canvas720(context)
    private val label = sectionLabel(context, "Inputs")
    private val grid = GridLayout(context)
    private val empty = context.label("", Fonts.regular, 14f, Palette.text(0.6f))

    init {
        view.addView(label, frameParams(WRAP, WRAP, 52.u, 18.u))
        grid.columnCount = 4
        grid.clipChildren = false
        grid.clipToPadding = false
        view.addView(grid, frameParams(1176.u, WRAP, 52.u, 46.u))
        empty.maxLines = 3
        view.addView(empty, frameParams(700.u, WRAP, 52.u, 46.u))
        bind()
    }

    override fun defaultFocus(): View? = grid.getChildAt(0)

    override fun onInputsChanged() = bind()

    private fun bind() {
        val list = host.inputs.all
        val focusedIndex = (0 until grid.childCount).firstOrNull { grid.getChildAt(it).isFocused } ?: -1
        grid.removeAllViews()
        empty.visibility = if (list.isEmpty() || !host.inputs.launchable) View.VISIBLE else View.GONE
        empty.text = when {
            list.isEmpty() -> "No HDMI or AV inputs were found on this TV."
            else -> "This TV has no app that opens an input directly, so inputs can't be switched from here."
        }
        if (!host.inputs.launchable) return
        list.forEachIndexed { i, input ->
            val tile = InfoTile(context)
            tile.bind(
                if (Inputs.isHdmiLike(input.type)) R.drawable.ic_hdmi else R.drawable.ic_composite,
                input.label,
                Inputs.stateLabel(input.state),
                input.connected,
            )
            tile.alpha = if (input.available) 1f else 0.55f
            tile.onClick = { host.launchInput(input, tile) }
            grid.addView(tile, GridLayout.LayoutParams().apply {
                width = 282.u; height = 88.u
                rightMargin = if (i % 4 == 3) 0 else 16.u
                bottomMargin = 16.u
            })
        }
        if (focusedIndex >= 0) grid.getChildAt(focusedIndex.coerceAtMost(grid.childCount - 1))?.requestFocus()
    }
}
