package dev.orbit.launcher.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.KeyEvent
import android.view.View

/**
 * Soft shadows as ALPHA_8 masks, blurred once in software and tinted by the paint at draw time.
 * HWUI draws them as a single textured quad: no runtime blur, no shadow passes.
 */
object Glow {
    private val cache = HashMap<Long, Bitmap>()

    fun get(w: Int, h: Int, radius: Float, blur: Float): Bitmap {
        val key = (w.toLong() shl 40) or (h.toLong() shl 20) or (radius.toLong() shl 10) or blur.toLong()
        return cache.getOrPut(key) {
            val pad = Math.ceil(blur * 2.0).toInt()
            val b = Bitmap.createBitmap(w + pad * 2, h + pad * 2, Bitmap.Config.ALPHA_8)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL) }
            Canvas(b).drawRoundRect(RectF(pad.toFloat(), pad.toFloat(), (pad + w).toFloat(), (pad + h).toFloat()), radius, radius, p)
            b
        }
    }

    fun clear() = cache.clear()
}

/**
 * Base for every focusable tile. As on tvOS, a focused tile lifts: it scales up and its shadow
 * deepens and drops. Only RenderNode properties animate (scale, translationZ for draw order),
 * plus a 0→1 progress value for the shadow in this one view's display list. Size never changes,
 * so focus never triggers a layout. The shadow is a pre-blurred mask, not an elevation shadow.
 */
abstract class FocusCard(context: Context) : View(context) {

    /** Focus scale. Large cards lift less so they don't crowd their neighbours. */
    var lift = 1.10f
    var radius = 12f.u
    var shadowBlur = 18f.u
    /** Shadow opacity at rest and when focused. */
    var shadowRest = 0f
    var shadowFocus = 0.32f
    var shadowDrop = 14f.u
    /** Optional outline when focused (off by default: the lift carries focus). */
    var ringColor = Palette.BLUE
    var ringAlpha = 0f

    /** 0 = resting, 1 = focused. Drives the shadow. */
    protected var focusAmount = 0f
        private set

    private var focusAnim: ValueAnimator? = null
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f.u }
    private val glowPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()

    var onClick: (() -> Unit)? = null
    var onLongClick: (() -> Unit)? = null

    init {
        isFocusable = true
        isClickable = true
        isLongClickable = true
        setOnClickListener { onClick?.invoke() }
        setOnLongClickListener { onLongClick?.let { it(); true } ?: false }
        defaultFocusHighlightEnabled = false
        // No outline means translationZ only reorders drawing; no shadow is ever rendered.
        outlineProvider = null
        setWillNotDraw(false)
    }

    override fun hasOverlappingRendering() = false

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        animateFocus(gainFocus)
    }

    private fun animateFocus(focused: Boolean) {
        val target = if (focused) lift else 1f
        animate().scaleX(target).scaleY(target)
            .setInterpolator(Motion.focus).setDuration(Motion.FOCUS_MS).start()
        translationZ = if (focused) 1f else 0f

        focusAnim?.cancel()
        val from = focusAmount
        val to = if (focused) 1f else 0f
        if (!isAttachedToWindow || !isShown) {
            focusAmount = to; invalidate(); return
        }
        focusAnim = ValueAnimator.ofFloat(from, to).apply {
            duration = if (focused) 240 else 200
            interpolator = Motion.linearOut
            addUpdateListener { focusAmount = it.animatedValue as Float; invalidate() }
            start()
        }
        onFocusAmountChanging(focused)
    }

    /** Hook for subclasses that animate extra content when focus changes. */
    protected open fun onFocusAmountChanging(focused: Boolean) {}

    /** A short dip on press, the remote's version of a touch-down. */
    override fun setPressed(pressed: Boolean) {
        val was = isPressed
        super.setPressed(pressed)
        if (was == pressed) return
        val base = if (hasFocus()) lift else 1f
        val s = if (pressed) base * 0.965f else base
        animate().scaleX(s).scaleY(s)
            .setInterpolator(if (pressed) Motion.linearOut else Motion.focus)
            .setDuration(if (pressed) 110 else Motion.FOCUS_MS).start()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // Settle the press dip even when a long-press consumed the click.
        val r = super.onKeyUp(keyCode, event)
        if (isPressed) isPressed = false
        return r
    }

    final override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val f = focusAmount
        // Only a lifted tile casts a shadow: resting shadows on every tile cost too much fill rate.
        val shadow = shadowRest + (shadowFocus - shadowRest) * f
        if (shadow > 0.005f) {
            // Slightly narrower than the tile, so it reads as a shadow beneath, not a halo.
            val inset = (width * 0.04f).toInt()
            val g = Glow.get(width - inset * 2, height, radius, shadowBlur)
            val pad = (g.height - height) / 2f
            glowPaint.color = Palette.SHADOW
            glowPaint.alpha = (255 * shadow).toInt()
            canvas.drawBitmap(g, inset - pad, -pad + 3f.u + (shadowDrop - 3f.u) * f, glowPaint)
        }
        drawContent(canvas, w, h, f)
        if (f > 0.01f && ringAlpha > 0f) {
            ringPaint.color = ringColor
            ringPaint.alpha = (255 * ringAlpha * f).toInt()
            val inset = 0.5f.u
            rect.set(inset, inset, w - inset, h - inset)
            canvas.drawRoundRect(rect, radius, radius, ringPaint)
        }
    }

    protected abstract fun drawContent(c: Canvas, w: Float, h: Float, focus: Float)
}
