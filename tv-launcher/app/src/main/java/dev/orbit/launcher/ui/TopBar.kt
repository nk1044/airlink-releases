package dev.orbit.launcher.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import dev.orbit.launcher.R

/** One nav item. Focused, it becomes a white pill that lifts on a soft shadow (tvOS style). */
class NavTab(context: Context, val title: String, val index: Int) : View(context) {
    private val paint = textPaint(Fonts.semibold, 14f, Palette.text(0.5f))
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.FILTER_BITMAP_FLAG)
    private val r = RectF()
    private var focusAmount = 0f
    private var anim: ValueAnimator? = null
    var active = false
        set(v) { field = v; invalidate() }
    var onFocused: ((NavTab) -> Unit)? = null

    init {
        isFocusable = true
        defaultFocusHighlightEnabled = false
        contentDescription = title
    }

    override fun hasOverlappingRendering() = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((paint.measureText(title) + 40f.u).toInt(), 36.u)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        anim?.cancel()
        anim = ValueAnimator.ofFloat(focusAmount, if (gainFocus) 1f else 0f).apply {
            duration = 220
            interpolator = Motion.linearOut
            addUpdateListener { focusAmount = it.animatedValue as Float; invalidate() }
            start()
        }
        val s = if (gainFocus) 1.08f else 1f
        animate().scaleX(s).scaleY(s).setInterpolator(Motion.focus).setDuration(Motion.FOCUS_MS).start()
        translationZ = if (gainFocus) 1f else 0f
        if (gainFocus) onFocused?.invoke(this)
    }

    override fun onDraw(c: Canvas) {
        val f = focusAmount
        if (f > 0.01f) {
            val g = Glow.get(width - 8.u, height, height / 2f, 10f.u)
            val pad = (g.height - height) / 2f
            shadow.color = Palette.SHADOW
            shadow.alpha = (255 * 0.22f * f).toInt()
            c.drawBitmap(g, 4f.u - pad, -pad + 6f.u * f, shadow)
            pill.color = Palette.white(f)
            r.set(0f, 0f, width.toFloat(), height.toFloat())
            c.drawRoundRect(r, height / 2f, height / 2f, pill)
        }
        val base = if (active) 0.92f else 0.5f
        paint.color = Palette.text(base + (1f - base) * f)
        c.drawText(title, 20f.u, height / 2f + 5f.u, paint)
    }
}

/**
 * The "selected tab" pill, drawn by the tab row behind its tabs. Its edges animate separately,
 * so it stretches as it slides, leading edge first.
 */
private class SelectionPill(private val host: View) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.line(0.075f) }
    private val r = RectF()
    private var l = 0f
    private var rt = 0f
    private var anim: ValueAnimator? = null

    fun moveTo(left: Float, right: Float, animate: Boolean) {
        anim?.cancel()
        if (!animate || rt == 0f) { l = left; rt = right; host.invalidate(); return }
        val l0 = l; val r0 = rt
        val forward = left > l0
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Motion.SHEET_MS
            interpolator = Motion.sheet
            addUpdateListener {
                val t = it.animatedValue as Float
                val lead = minOf(1f, t * 1.18f)
                l = l0 + (left - l0) * (if (forward) t else lead)
                rt = r0 + (right - r0) * (if (forward) lead else t)
                host.invalidate()
            }
            start()
        }
    }

    fun draw(c: Canvas) {
        if (rt <= l) return
        r.set(l, 0f, rt, host.height.toFloat())
        c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, p)
    }
}

/**
 * The 64 px bar: centred tabs (as on tvOS) and, on the right, network, date and time. The clock
 * is TextClock, which ticks on the system's minute broadcast — no per-second timers.
 */
class TopBar(context: Context, titles: List<String>) : FrameLayout(context) {

    val tabs: List<NavTab>
    private val network = ImageView(context)
    private val navRow: LinearLayout = object : LinearLayout(context) {
        override fun dispatchDraw(canvas: Canvas) {
            pill.draw(canvas)
            super.dispatchDraw(canvas)
        }
    }
    private val pill: SelectionPill = SelectionPill(navRow)
    var selectedIndex = 0; private set
    var onTabFocused: ((Int) -> Unit)? = null

    init {
        clipChildren = false
        clipToPadding = false

        navRow.setWillNotDraw(false)
        navRow.orientation = LinearLayout.HORIZONTAL
        navRow.clipChildren = false
        navRow.clipToPadding = false
        tabs = titles.mapIndexed { i, t ->
            NavTab(context, t, i).also { tab ->
                tab.onFocused = { onTabFocused?.invoke(it.index) }
                navRow.addView(tab, LinearLayout.LayoutParams(WRAP, 36.u).apply { if (i > 0) leftMargin = 6.u })
            }
        }
        addView(navRow, LayoutParams(WRAP, 36.u, Gravity.CENTER))

        val right = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        network.setImageResource(R.drawable.ic_wifi)
        network.imageTintList = android.content.res.ColorStateList.valueOf(Palette.text(0.5f))
        right.addView(network, LinearLayout.LayoutParams(16.u, 16.u).apply { rightMargin = 16.u })

        val date = TextClock(context).apply {
            format12Hour = "EEE d MMM"
            format24Hour = "EEE d MMM"
            typeface = Fonts.regular
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 13f.u)
            setTextColor(Palette.text(0.55f))
            includeFontPadding = false
        }
        right.addView(date, LinearLayout.LayoutParams(WRAP, WRAP))
        val time = TextClock(context).apply {
            format12Hour = "h:mm"
            format24Hour = "H:mm"
            typeface = Fonts.semibold
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f.u)
            setTextColor(Palette.text(0.88f))
            fontFeatureSettings = "tnum"
            includeFontPadding = false
        }
        right.addView(time, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = 12.u })
        addView(right, LayoutParams(WRAP, WRAP, Gravity.CENTER_VERTICAL or Gravity.END).apply { rightMargin = 52.u })

        tabs[0].active = true
        // Place the pill once the tabs have been measured.
        navRow.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> movePill(false) }
    }

    fun select(index: Int, animate: Boolean = true) {
        if (index == selectedIndex && animate) return
        tabs[selectedIndex].active = false
        selectedIndex = index
        tabs[index].active = true
        movePill(animate)
    }

    private fun movePill(animate: Boolean) {
        val tab = tabs[selectedIndex]
        if (tab.width == 0) return
        pill.moveTo(tab.left.toFloat(), tab.right.toFloat(), animate)
    }

    fun setNetwork(kind: Int) {
        network.setImageResource(
            when (kind) {
                NET_ETHERNET -> R.drawable.ic_ethernet
                NET_NONE -> R.drawable.ic_wifi_off
                else -> R.drawable.ic_wifi
            }
        )
        network.imageTintList = android.content.res.ColorStateList.valueOf(
            if (kind == NET_NONE) Palette.RED else Palette.text(0.5f)
        )
    }

    companion object {
        const val NET_WIFI = 0
        const val NET_ETHERNET = 1
        const val NET_NONE = 2
    }
}
