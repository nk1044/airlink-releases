package dev.orbit.launcher.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import dev.orbit.launcher.R

/**
 * Layout is authored in design pixels on a 1280×720 canvas. On the target TV (1280×720, mdpi)
 * one design pixel is one screen pixel; on a 1080p TV everything scales by 1.5.
 */
object Ui {
    var scale = 1f
        private set

    fun init(context: Context) {
        val dm = context.resources.displayMetrics
        scale = maxOf(dm.widthPixels, dm.heightPixels) / 1280f
    }

    fun px(v: Float): Int = Math.round(v * scale)
    fun px(v: Int): Int = Math.round(v * scale)
    fun pxf(v: Float): Float = v * scale
}

val Int.u: Int get() = Ui.px(this)
val Float.u: Float get() = Ui.pxf(this)

/** Light theme, after the Apple TV home screen: pale grey ground, white tiles, ink text. */
object Palette {
    const val BG_DEEP = 0xFFE2E4E9.toInt()
    const val BG = 0xFFE6E8EC.toInt()
    const val CARD = 0xFFFFFFFF.toInt()
    const val PANEL = 0xFFF7F8FA.toInt()
    const val TEXT = 0xFF1D1D1F.toInt()
    const val SHADOW = 0xFF0B1220.toInt()
    const val GREEN = 0xFF34C759.toInt()
    /** Darker green for text on white (4.5:1). */
    const val GREEN_TEXT = 0xFF1A7F37.toInt()
    const val RED = 0xFFD70015.toInt()
    const val BLUE = 0xFF0071E3.toInt()

    /** Ink at reduced alpha: secondary and tertiary text, icons. */
    fun text(alpha: Float): Int = withAlpha(TEXT, alpha)
    /** Black at low alpha: hairlines, borders, wells. */
    fun line(alpha: Float): Int = withAlpha(0, alpha)
    /** Resting edge for white tiles: without resting shadows, this is what separates them from the ground. */
    const val EDGE = 0.10f
    fun white(alpha: Float): Int = withAlpha(Color.WHITE, alpha)
    fun withAlpha(c: Int, alpha: Float): Int = (c and 0xFFFFFF) or ((alpha * 255f + 0.5f).toInt() shl 24)
}

/** One family, three weights, loaded once. */
object Fonts {
    lateinit var regular: Typeface; private set
    lateinit var semibold: Typeface; private set
    lateinit var bold: Typeface; private set

    fun init(context: Context) {
        if (this::regular.isInitialized) return
        val r = context.resources
        regular = r.getFont(R.font.dm_sans_regular)
        semibold = r.getFont(R.font.dm_sans_semibold)
        bold = r.getFont(R.font.dm_sans_bold)
    }
}

fun textPaint(face: Typeface, sizePx: Float, color: Int, letterSpacingPx: Float = 0f): TextPaint =
    TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = face
        textSize = sizePx.u
        this.color = color
        if (letterSpacingPx != 0f) letterSpacing = letterSpacingPx / sizePx
    }

fun Context.label(
    text: CharSequence,
    face: Typeface,
    sizePx: Float,
    color: Int,
    letterSpacingPx: Float = 0f,
    caps: Boolean = false,
): TextView = TextView(this).apply {
    this.text = text
    typeface = face
    setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx.u)
    setTextColor(color)
    if (letterSpacingPx != 0f) letterSpacing = letterSpacingPx / sizePx
    isAllCaps = caps
    maxLines = 1
    ellipsize = TextUtils.TruncateAt.END
    includeFontPadding = false
    isFocusable = false
}

fun frameParams(w: Int, h: Int, left: Int = 0, top: Int = 0) =
    FrameLayout.LayoutParams(w, h).apply { leftMargin = left; topMargin = top }

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun View.isDescendantOf(ancestor: View): Boolean {
    var v: View? = this
    while (v != null) {
        if (v === ancestor) return true
        v = v.parent as? View
    }
    return false
}

fun ellipsize(text: CharSequence, paint: TextPaint, maxWidth: Float): String =
    TextUtils.ellipsize(text, paint, maxWidth, TextUtils.TruncateAt.END).toString()
