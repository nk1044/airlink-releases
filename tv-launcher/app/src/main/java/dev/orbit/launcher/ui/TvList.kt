package dev.orbit.launcher.ui

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.orbit.launcher.data.AppEntry

/** Set while a D-pad key is auto-repeating, so scrolls can shorten to keep up with focus. */
object Keys {
    var repeating = false
}

/**
 * A row (or grid) for the remote:
 * - entering it from outside lands on the item that last had focus, not the geometric nearest;
 * - focus never leaves sideways (or downward, for a grid) — it bumps instead, like tvOS;
 * - the focused item is kept clear of the edges so the next card always peeks in.
 */
class TvList(context: Context, val horizontal: Boolean, val span: Int = 1) : RecyclerView(context) {

    var lastFocusedPos = 0

    init {
        setHasFixedSize(true)
        clipChildren = false
        clipToPadding = false
        overScrollMode = OVER_SCROLL_NEVER
        isFocusable = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        itemAnimator = null
        isHorizontalScrollBarEnabled = false
        isVerticalScrollBarEnabled = false
        layoutManager = if (span > 1) Grid(context, span) else Row(context, horizontal)
    }

    override fun requestChildFocus(child: View?, focused: View?) {
        super.requestChildFocus(child, focused)
        if (child != null) {
            val p = getChildAdapterPosition(child)
            if (p != NO_POSITION) lastFocusedPos = p
        }
    }

    private fun rememberedChild(): View? {
        val lm = layoutManager ?: return null
        lm.findViewByPosition(lastFocusedPos)?.let { if (it.isShown) return it }
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.isFocusable && c.isShown && isFullyInside(c)) return c
        }
        return if (childCount > 0) getChildAt(0) else null
    }

    private fun isFullyInside(c: View): Boolean =
        if (horizontal) c.left >= paddingLeft - 1 else c.top >= paddingTop - 1

    override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) {
        if (hasFocus() || descendantFocusability == FOCUS_BLOCK_DESCENDANTS) {
            super.addFocusables(views, direction, focusableMode)
            return
        }
        val v = rememberedChild()
        if (v != null) views.add(v) else super.addFocusables(views, direction, focusableMode)
    }

    override fun onRequestFocusInDescendants(direction: Int, previouslyFocusedRect: Rect?): Boolean {
        val v = rememberedChild()
        if (v != null && v.requestFocus()) return true
        return super.onRequestFocusInDescendants(direction, previouslyFocusedRect)
    }

    override fun focusSearch(focused: View, direction: Int): View? {
        val next = super.focusSearch(focused, direction)
        if (next != null && next !== focused && next.isDescendantOf(this)) return next
        val blocked = when (direction) {
            FOCUS_LEFT, FOCUS_RIGHT -> true
            FOCUS_DOWN -> !horizontal
            else -> false
        }
        if (!blocked) return next
        // Nothing further this way inside the list: stay, and say so with a bump.
        val item = findContainingItemView(focused) ?: focused
        val sideways = direction == FOCUS_LEFT || direction == FOCUS_RIGHT
        Motion.bump(item, sideways, if (direction == FOCUS_LEFT || direction == FOCUS_UP) -1 else 1)
        return focused
    }

    /** Back to the first item without animation (HOME pressed while Home is showing). */
    fun reset() {
        lastFocusedPos = 0
        stopScroll()
        scrollToPosition(0)
    }

    // ---- scrolling ----

    /**
     * How far to scroll so [child] sits at least most of a card away from either edge.
     * At the very start or end of the content, align flush instead.
     */
    internal fun alignDelta(child: View): Int {
        val lm = layoutManager ?: return 0
        val lp = child.layoutParams as LayoutParams
        val start: Int; val end: Int; val cs: Int; val ce: Int
        if (horizontal) {
            start = paddingLeft; end = width - paddingRight
            cs = lm.getDecoratedLeft(child) - lp.leftMargin; ce = lm.getDecoratedRight(child) + lp.rightMargin
        } else {
            start = paddingTop; end = height - paddingBottom
            cs = lm.getDecoratedTop(child) - lp.topMargin; ce = lm.getDecoratedBottom(child) + lp.bottomMargin
        }
        val size = ce - cs
        val pos = getChildAdapterPosition(child)
        val count = adapter?.itemCount ?: 0
        val firstLine = pos in 0 until span
        val lastLine = pos >= 0 && pos >= count - ((count - 1) % span + 1)
        val peek = (size * if (horizontal) 0.62f else 0.5f).toInt()
        return when {
            firstLine && cs != start -> cs - start
            !firstLine && cs - peek < start -> cs - peek - start
            lastLine && ce > end -> ce - end
            !lastLine && ce + peek > end -> ce + peek - end
            else -> 0
        }
    }

    internal fun scrollToKeep(child: View, immediate: Boolean): Boolean {
        val d = alignDelta(child)
        if (d == 0) return false
        if (immediate) {
            if (horizontal) scrollBy(d, 0) else scrollBy(0, d)
        } else {
            val ms = if (Keys.repeating) 200 else 420
            if (horizontal) smoothScrollBy(d, 0, Motion.easeOut, ms) else smoothScrollBy(0, d, Motion.easeOut, ms)
        }
        return true
    }

    private class Row(context: Context, horizontal: Boolean) :
        LinearLayoutManager(context, if (horizontal) HORIZONTAL else VERTICAL, false) {
        init { initialPrefetchItemCount = 8; isItemPrefetchEnabled = true }

        override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: Rect, immediate: Boolean, focusedChildVisible: Boolean) =
            (parent as TvList).scrollToKeep(child, immediate)

        override fun calculateExtraLayoutSpace(state: State, extra: IntArray) {
            // Lay out one card beyond each edge so a held D-pad never outruns layout.
            val e = if (orientation == HORIZONTAL) 220.u else 140.u
            extra[0] = e; extra[1] = e
        }
    }

    private class Grid(context: Context, span: Int) : GridLayoutManager(context, span) {
        override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: Rect, immediate: Boolean, focusedChildVisible: Boolean) =
            (parent as TvList).scrollToKeep(child, immediate)

        override fun calculateExtraLayoutSpace(state: State, extra: IntArray) {
            extra[0] = 130.u; extra[1] = 130.u
        }
    }
}

// ---------------------------------------------------------------------------------------------

class AppAdapter(
    private val itemW: Int,
    private val itemH: Int,
    private val marginH: Int,
    private val marginV: Int,
    private val onOpen: (AppEntry, View) -> Unit,
    private val onOptions: (AppEntry, View) -> Unit,
) : ListAdapter<AppEntry, AppAdapter.VH>(Diff) {

    init { setHasStableIds(true) }

    class VH(val card: AppCard) : RecyclerView.ViewHolder(card)

    override fun getItemId(position: Int) = getItem(position).id
    override fun getItemViewType(position: Int) = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val card = AppCard(parent.context)
        card.layoutParams = RecyclerView.LayoutParams(itemW, itemH).apply {
            setMargins(marginH, marginV, marginH, marginV)
        }
        val vh = VH(card)
        card.onClick = { card.entry?.let { onOpen(it, card) } }
        card.onLongClick = { card.entry?.let { onOptions(it, card) } }
        return vh
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.card.bind(getItem(position))

    private object Diff : DiffUtil.ItemCallback<AppEntry>() {
        override fun areItemsTheSame(a: AppEntry, b: AppEntry) = a.key == b.key
        override fun areContentsTheSame(a: AppEntry, b: AppEntry) = a.label == b.label && a.stamp == b.stamp
    }
}
