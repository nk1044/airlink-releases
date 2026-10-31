package dev.orbit.launcher.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.TimeInterpolator
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A damped spring baked into a lookup table, so it can drive ViewPropertyAnimator (RenderNode
 * properties only) at the cost of one array read per frame. The duration given to the animator
 * is the spring's settle time.
 *
 * dampingRatio < 1 overshoots a little (the "lift" feel of tvOS focus); 1 is critically damped.
 */
class SpringInterpolator(dampingRatio: Float) : TimeInterpolator {
    private val table = FloatArray(SAMPLES + 1)

    init {
        val z = dampingRatio.toDouble()
        // Choose the natural frequency so the envelope has decayed to ~0.1% at t = 1.
        val w0 = if (z < 1.0) 6.9 / z else 9.2
        for (i in 0..SAMPLES) {
            val t = i.toDouble() / SAMPLES
            val x = if (z < 1.0) {
                val wd = w0 * sqrt(1 - z * z)
                1 - exp(-z * w0 * t) * (cos(wd * t) + (z * w0 / wd) * sin(wd * t))
            } else {
                1 - exp(-w0 * t) * (1 + w0 * t)
            }
            table[i] = x.toFloat()
        }
        // Remove the residual so the animation lands exactly on its target.
        val end = table[SAMPLES]
        for (i in 0..SAMPLES) table[i] = table[i] / end
        table[0] = 0f
    }

    override fun getInterpolation(input: Float): Float {
        if (input <= 0f) return 0f
        if (input >= 1f) return 1f
        val f = input * SAMPLES
        val i = f.toInt()
        val frac = f - i
        return table[i] + (table[i + 1] - table[i]) * frac
    }

    private companion object {
        const val SAMPLES = 240
    }
}

object Motion {
    /** Focus lift: quick, with a hint of overshoot. */
    val focus = SpringInterpolator(0.78f)
    const val FOCUS_MS = 420L

    /** Sheets and pages: smooth, no visible bounce. */
    val sheet = SpringInterpolator(0.92f)
    const val SHEET_MS = 560L

    val page = SpringInterpolator(1f)
    const val PAGE_MS = 620L

    /** Apple's presentation curve: fast out of the gate, long gentle landing. */
    val easeOut = PathInterpolator(0.32f, 0.72f, 0f, 1f)

    /** For things leaving the screen: gather speed, then go. */
    val easeIn = PathInterpolator(0.55f, 0f, 0.9f, 0.4f)

    val linearOut = PathInterpolator(0f, 0f, 0.2f, 1f)

    private val bumpBack = SpringInterpolator(0.55f)

    /**
     * Rubber-band "nothing more this way" nudge, as tvOS does at the end of a row. Uses its own
     * animators so it never cancels a running focus-scale animation on the same view.
     */
    fun bump(v: View, horizontal: Boolean, sign: Int) {
        val prop = if (horizontal) View.TRANSLATION_X else View.TRANSLATION_Y
        (v.getTag(dev.orbit.launcher.R.id.tag_bump) as? AnimatorSet)?.cancel()
        val out = ObjectAnimator.ofFloat(v, prop, 0f, 9f.u * sign).setDuration(80)
        out.interpolator = linearOut
        val back = ObjectAnimator.ofFloat(v, prop, 9f.u * sign, 0f).setDuration(460)
        back.interpolator = bumpBack
        val set = AnimatorSet()
        set.playSequentially(out, back)
        v.setTag(dev.orbit.launcher.R.id.tag_bump, set)
        set.start()
    }
}
