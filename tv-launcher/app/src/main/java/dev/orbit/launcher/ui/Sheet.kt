package dev.orbit.launcher.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.orbit.launcher.data.Work

/** A row in a sheet or on the Settings page: icon, title, optional detail, optional switch or value. */
class ActionRow(context: Context) : FocusCard(context) {
    private val titlePaint = textPaint(Fonts.semibold, 14.5f, Palette.TEXT)
    private val subPaint = textPaint(Fonts.regular, 12f, Palette.text(0.55f))
    private val valuePaint = textPaint(Fonts.semibold, 12f, Palette.text(0.5f))
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f.u }
    private val r = RectF()
    private var icon: Drawable? = null
    private var title = ""
    private var sub = ""
    private var value = ""
    var danger = false
    var accentIcon = false

    /** null = no switch. */
    var toggle: Boolean? = null
        set(v) {
            val old = field
            field = v
            if (v == null) { knob = 0f; invalidate(); return }
            val to = if (v) 1f else 0f
            if (old == null || !isShown) { knob = to; invalidate(); return }
            knobAnim?.cancel()
            knobAnim = ValueAnimator.ofFloat(knob, to).apply {
                duration = 380
                interpolator = SpringInterpolator(0.72f)
                addUpdateListener { knob = it.animatedValue as Float; invalidate() }
                start()
            }
        }
    private var knob = 0f
    private var knobAnim: ValueAnimator? = null

    init {
        lift = 1.03f; radius = 14f.u; shadowBlur = 14f.u; shadowRest = 0f; shadowFocus = 0.22f; shadowDrop = 10f.u
    }

    fun set(iconRes: Int, title: String, sub: String = "", value: String = "") {
        icon = if (iconRes != 0) context.getDrawable(iconRes)!!.mutate() else null
        this.title = title
        this.sub = sub
        this.value = value
        contentDescription = if (sub.isEmpty()) title else "$title. $sub"
        invalidate()
    }

    override fun drawContent(c: Canvas, w: Float, h: Float, focus: Float) {
        fill.color = Palette.white(0.95f + 0.05f * focus)
        r.set(0f, 0f, w, h)
        c.drawRoundRect(r, radius, radius, fill)
        edge.color = Palette.line(Palette.EDGE * (1f - focus))
        r.set(0.5f.u, 0.5f.u, w - 0.5f.u, h - 0.5f.u)
        c.drawRoundRect(r, radius, radius, edge)

        var x = 18f.u
        icon?.let {
            val s = 18.u
            val tint = when {
                danger -> Palette.RED
                accentIcon -> Palette.BLUE
                else -> Palette.text(0.5f + 0.35f * focus)
            }
            it.setTint(tint)
            val iy = ((h - s) / 2).toInt()
            it.setBounds(x.toInt(), iy, x.toInt() + s, iy + s)
            it.draw(c)
            x += s + 14f.u
        }
        titlePaint.color = if (danger) Palette.RED else Palette.TEXT
        if (sub.isEmpty()) {
            c.drawText(title, x, h / 2 + 5f.u, titlePaint)
        } else {
            c.drawText(title, x, h / 2 - 3f.u, titlePaint)
            c.drawText(ellipsize(sub, subPaint, w - x - 90f.u), x, h / 2 + 14f.u, subPaint)
        }

        val t = toggle
        if (t != null) {
            // iOS-style switch.
            val sw = 40f.u; val sh = 24f.u
            val sx = w - 18f.u - sw; val sy = (h - sh) / 2
            fill.color = blend(Palette.line(0.12f), Palette.GREEN, knob)
            r.set(sx, sy, sx + sw, sy + sh)
            c.drawRoundRect(r, sh / 2, sh / 2, fill)
            fill.color = Palette.line(0.12f)
            c.drawCircle(sx + sh / 2 + (sw - sh) * knob, sy + sh / 2 + 1f.u, sh / 2 - 2.5f.u, fill)
            fill.color = Palette.white(1f)
            val kr = sh / 2 - 3f.u
            val kx = sx + sh / 2 + (sw - sh) * knob
            c.drawCircle(kx, sy + sh / 2, kr, fill)
        } else if (value.isNotEmpty()) {
            valuePaint.textAlign = Paint.Align.RIGHT
            valuePaint.color = Palette.BLUE
            c.drawText(value, w - 18f.u, h / 2 + 4.5f.u, valuePaint)
        }
    }

    /** ARGB blend, alpha included. */
    private fun blend(a: Int, b: Int, t: Float): Int {
        val ia = 1 - t
        val ca = (((a ushr 24) * ia + (b ushr 24) * t).toInt()) shl 24
        val cr = ((((a shr 16) and 255) * ia + ((b shr 16) and 255) * t).toInt()) shl 16
        val cg = ((((a shr 8) and 255) * ia + ((b shr 8) and 255) * t).toInt()) shl 8
        val cb = (((a and 255) * ia + (b and 255) * t).toInt())
        return ca or cr or cg or cb
    }
}

/**
 * The options sheet: slides in from the right on a spring while the page behind recedes and
 * dims, the way iOS presents a sheet. Everything animated is a RenderNode property.
 */
class Sheet(context: Context, private val behind: View) : FrameLayout(context) {

    class Action(val icon: Int, val title: String, val sub: String = "", val danger: Boolean = false, val run: () -> Unit)

    private val scrim = View(context)
    private val panel = LinearLayout(context)
    private val header = LinearLayout(context)
    private val list = LinearLayout(context)
    private val headerArt = object : View(context) {
        var bmp: Bitmap? = null
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        val dst = Rect()
        override fun onDraw(c: Canvas) {
            bmp?.let { dst.set(0, 0, width, height); c.drawBitmap(it, null, dst, p) }
        }
    }
    private val title = context.label("", Fonts.bold, 22f, Palette.TEXT, -0.3f)
    private val subtitle = context.label("", Fonts.regular, 13f, Palette.text(0.55f))
    private val body = TextView(context)
    private val panelW = 400.u
    private var returnFocus: View? = null
    var isOpen = false; private set
    var onClosed: (() -> Unit)? = null

    init {
        visibility = GONE
        clipChildren = false
        scrim.setBackgroundColor(0x47000000)
        scrim.alpha = 0f
        addView(scrim, LayoutParams(MATCH, MATCH))

        panel.orientation = LinearLayout.VERTICAL
        panel.background = GradientDrawable().apply {
            setColor(Palette.PANEL)
            cornerRadii = floatArrayOf(22f.u, 22f.u, 0f, 0f, 0f, 0f, 22f.u, 22f.u)
            setStroke(1.u, Palette.line(0.06f))
        }
        panel.setPadding(28.u, 36.u, 28.u, 28.u)
        panel.clipChildren = false
        panel.clipToPadding = false

        header.orientation = LinearLayout.VERTICAL
        header.addView(headerArt, LinearLayout.LayoutParams(192.u, 108.u).apply { bottomMargin = 18.u })
        header.addView(title, LinearLayout.LayoutParams(MATCH, WRAP))
        header.addView(subtitle, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = 6.u })
        panel.addView(header, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = 22.u })

        body.typeface = Fonts.regular
        body.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 12.5f.u)
        body.setTextColor(Palette.text(0.65f))
        body.setLineSpacing(4f.u, 1f)
        body.includeFontPadding = false
        body.isFocusable = false
        panel.addView(body, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = 18.u })

        list.orientation = LinearLayout.VERTICAL
        list.clipChildren = false
        list.clipToPadding = false
        panel.addView(list, LinearLayout.LayoutParams(MATCH, WRAP))

        addView(panel, LayoutParams(panelW, MATCH, Gravity.END))
        panel.translationX = panelW + 40f.u
        isClickable = true
    }

    fun open(
        anchor: View?,
        title: String,
        subtitle: String,
        art: Bitmap?,
        actions: List<Action>,
        body: String = "",
    ) {
        returnFocus = anchor
        this.title.text = title
        this.subtitle.text = subtitle
        this.subtitle.visibility = if (subtitle.isEmpty()) GONE else VISIBLE
        headerArt.bmp = art
        headerArt.visibility = if (art == null) GONE else VISIBLE
        headerArt.invalidate()
        this.body.text = body
        this.body.visibility = if (body.isEmpty()) GONE else VISIBLE

        list.removeAllViews()
        actions.forEachIndexed { i, a ->
            val row = ActionRow(context)
            row.set(a.icon, a.title, a.sub)
            row.danger = a.danger
            row.onClick = { close(); a.run() }
            list.addView(row, LinearLayout.LayoutParams(MATCH, (if (a.sub.isEmpty()) 52 else 60).u).apply { if (i > 0) topMargin = 8.u })
        }

        visibility = VISIBLE
        isOpen = true
        (behind as? android.view.ViewGroup)?.descendantFocusability = FOCUS_BLOCK_DESCENDANTS

        behind.pivotX = behind.width / 2f
        behind.pivotY = behind.height / 2f
        behind.animate().scaleX(0.955f).scaleY(0.955f).setInterpolator(Motion.sheet).setDuration(Motion.SHEET_MS).start()
        scrim.animate().alpha(1f).setInterpolator(Motion.linearOut).setDuration(260).start()
        panel.animate().translationX(0f).setInterpolator(Motion.sheet).setDuration(Motion.SHEET_MS).start()

        list.getChildAt(0)?.requestFocus()
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        behind.animate().scaleX(1f).scaleY(1f).setInterpolator(Motion.sheet).setDuration(Motion.SHEET_MS - 80).start()
        scrim.animate().alpha(0f).setInterpolator(Motion.easeOut).setDuration(260).start()
        panel.animate().translationX(panelW + 40f.u).setInterpolator(Motion.easeIn).setDuration(240)
            .withEndAction { if (!isOpen) { visibility = GONE; list.removeAllViews() } }.start()
        (behind as? android.view.ViewGroup)?.descendantFocusability = FOCUS_AFTER_DESCENDANTS
        val f = returnFocus
        returnFocus = null
        if (f != null && f.isAttachedToWindow && f.isShown) f.requestFocus()
        onClosed?.invoke()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isOpen && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MENU -> { close(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun focusSearch(focused: View, direction: Int): View? {
        val next = android.view.FocusFinder.getInstance().findNextFocus(this, focused, direction)
        if (next == null) {
            if (direction == FOCUS_UP || direction == FOCUS_DOWN) Motion.bump(focused, false, if (direction == FOCUS_UP) -1 else 1)
            return focused
        }
        return next
    }
}

/** A small pill that drops in from the top for confirmations ("Pinned to Home"). */
class Hud(context: Context) : FrameLayout(context) {
    private val text = context.label("", Fonts.semibold, 13f, Palette.TEXT)
    private val hide = Runnable {
        animate().translationY(-60f.u).alpha(0f).setInterpolator(Motion.easeIn).setDuration(220)
            .withEndAction { visibility = GONE }.start()
    }

    init {
        background = GradientDrawable().apply {
            setColor(Palette.white(0.97f))
            cornerRadius = 20f.u
            setStroke(1.u, Palette.line(0.08f))
        }
        setPadding(22.u, 11.u, 22.u, 11.u)
        addView(text, LayoutParams(WRAP, WRAP))
        visibility = GONE
        isFocusable = false
    }

    fun show(msg: String) {
        text.text = msg
        Work.main.removeCallbacks(hide)
        if (visibility != VISIBLE) {
            visibility = VISIBLE
            alpha = 0f
            translationY = -60f.u
        }
        animate().translationY(0f).alpha(1f).setInterpolator(Motion.sheet).setDuration(Motion.SHEET_MS).start()
        Work.main.postDelayed(hide, 1800)
    }
}

