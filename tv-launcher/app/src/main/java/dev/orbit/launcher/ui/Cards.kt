package dev.orbit.launcher.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.text.TextPaint
import dev.orbit.launcher.R
import dev.orbit.launcher.data.AppEntry
import dev.orbit.launcher.data.Art

/** Holds one piece of art for a view, fading it in when it arrives asynchronously. */
class ArtSlot(private val owner: android.view.View, private val variant: Art.Variant) {
    init { all[this] = true }

    var bitmap: Bitmap? = null; private set
    var alpha = 1f; private set
    private var entry: AppEntry? = null
    private var fade: ValueAnimator? = null

    fun bind(e: AppEntry?) {
        if (e === entry && bitmap != null) return
        entry = e
        fade?.cancel()
        if (e == null) { bitmap = null; owner.invalidate(); return }
        val cached = Art.peek(e, variant)
        if (cached != null) { bitmap = cached; alpha = 1f; owner.invalidate(); return }
        bitmap = null
        owner.invalidate()
        Art.load(e, variant) { b ->
            if (entry !== e || b == null) return@load
            bitmap = b
            if (!owner.isShown) { alpha = 1f; owner.invalidate(); return@load }
            alpha = 0f
            fade = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 220
                interpolator = Motion.linearOut
                addUpdateListener { alpha = it.animatedValue as Float; owner.invalidate() }
                start()
            }
        }
    }

    /** Drops the bitmap reference (memory trim); `ensure` re-requests it. */
    fun release() { fade?.cancel(); bitmap = null }

    fun ensure() {
        if (bitmap != null) return
        val e = entry ?: return
        entry = null
        bind(e)
    }

    companion object {
        /** Every slot, weakly: includes views RecyclerView keeps detached in its caches. */
        private val all = java.util.WeakHashMap<ArtSlot, Boolean>()

        fun releaseAll() = all.keys.toList().forEach { it.release() }
        fun restoreAttached() = all.keys.toList().forEach { if (it.owner.isAttachedToWindow) it.ensure() }
    }
}

private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f }
private val tmpRect = RectF()
private val dstRect = Rect()

private fun Canvas.drawArt(b: Bitmap, alpha: Float, l: Int, t: Int, r: Int, btm: Int) {
    bitmapPaint.alpha = (255 * alpha).toInt()
    dstRect.set(l, t, r, btm)
    drawBitmap(b, null, dstRect, bitmapPaint)
}

private fun Canvas.surface(w: Float, h: Float, radius: Float, fill: Int, border: Int) {
    if (fill != 0) {
        fillPaint.shader = null
        fillPaint.color = fill
        tmpRect.set(0f, 0f, w, h)
        drawRoundRect(tmpRect, radius, radius, fillPaint)
    }
    if (border != 0) {
        strokePaint.color = border
        strokePaint.strokeWidth = 1f.u
        tmpRect.set(0.5f.u, 0.5f.u, w - 0.5f.u, h - 0.5f.u)
        drawRoundRect(tmpRect, radius, radius, strokePaint)
    }
}

// ---------------------------------------------------------------------------------------------

/** 186×104 banner card: the "Your Apps" row and the All Apps grid. */
class AppCard(context: Context) : FocusCard(context) {
    var entry: AppEntry? = null; private set
    private val art = ArtSlot(this, Art.Variant.CARD)
    private val namePaint = textPaint(Fonts.semibold, 13f, Palette.text(0.6f))
    private var name = ""

    init {
        lift = 1.10f; radius = 12f.u; shadowBlur = 16f.u; shadowRest = 0f; shadowFocus = 0.34f; shadowDrop = 14f.u
        namePaint.textAlign = Paint.Align.CENTER
    }

    fun bind(e: AppEntry) {
        entry = e
        name = ellipsize(e.label, namePaint, 160f.u)
        contentDescription = e.label
        art.bind(e)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); art.ensure() }

    override fun drawContent(c: Canvas, w: Float, h: Float, focus: Float) {
        val b = art.bitmap
        if (b == null || art.alpha < 1f) {
            c.surface(w, h, radius, Palette.white(0.8f), 0)
            c.drawText(name, w / 2, h / 2 + 5f.u, namePaint)
        }
        if (b != null) c.drawArt(b, art.alpha, 0, 0, width, height)
        // A hairline keeps white banners (YouTube, Play Store) from melting into the ground.
        c.surface(w, h, radius, 0, Palette.line(Palette.EDGE * (1f - focus)))
    }
}

// ---------------------------------------------------------------------------------------------

/**
 * The featured "Jump back in" card: 608×298. The app's own art, blurred, fills it; a frosted wash
 * from the left carries dark text, and the crisp banner floats on the right.
 */
class HeroCard(context: Context) : FocusCard(context) {
    var entry: AppEntry? = null; private set
    private val ambient = ArtSlot(this, Art.Variant.AMBIENT)
    private val banner = ArtSlot(this, Art.Variant.HERO)

    private val eyebrowPaint = textPaint(Fonts.bold, 10f, Palette.text(0.55f), 1.6f)
    private val titlePaint = textPaint(Fonts.bold, 26f, Palette.TEXT, -0.4f)
    private val subPaint = textPaint(Fonts.regular, 13f, Palette.text(0.6f))
    private val btnPaint = textPaint(Fonts.semibold, 13f, Palette.white(1f))
    private val btn2Paint = textPaint(Fonts.semibold, 13f, Palette.text(0.7f))
    private val frost = Paint()
    private val bannerShadow = Paint(Paint.FILTER_BITMAP_FLAG)
    private val play: Drawable = context.getDrawable(R.drawable.ic_play)!!.mutate()

    private var title = ""
    private var subtitle = ""
    private var eyebrow = "JUMP BACK IN"
    var hint = "Hold OK for options"

    init {
        lift = 1.03f; radius = 18f.u; shadowBlur = 26f.u; shadowRest = 0f; shadowFocus = 0.30f; shadowDrop = 18f.u
        play.setTint(Palette.white(1f))
    }

    fun bind(e: AppEntry?, subtitle: String, eyebrow: String) {
        entry = e
        this.eyebrow = eyebrow.uppercase()
        title = if (e == null) "Welcome to Air Launch" else ellipsize(e.label, titlePaint, 290f.u)
        this.subtitle = ellipsize(subtitle, subPaint, 290f.u)
        contentDescription = e?.label
        ambient.bind(e)
        banner.bind(e)
        invalidate()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); ambient.ensure(); banner.ensure() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        frost.shader = LinearGradient(0f, 0f, w.toFloat(), 0f,
            intArrayOf(Palette.white(0.94f), Palette.white(0.80f), Palette.white(0.18f)),
            floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP)
    }

    private val ambientPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private var ambientShaderFor: Bitmap? = null
    private val ambientMatrix = android.graphics.Matrix()

    private fun Canvas.round(w: Float, h: Float, p: Paint) {
        tmpRect.set(0f, 0f, w, h)
        drawRoundRect(tmpRect, radius, radius, p)
    }

    override fun drawContent(c: Canvas, w: Float, h: Float, focus: Float) {
        // Every layer is a rounded rect with a shader: no clipPath, which is costly on this GPU.
        fillPaint.shader = null
        fillPaint.color = Palette.CARD
        c.round(w, h, fillPaint)

        val amb = ambient.bitmap
        if (amb != null) {
            if (ambientShaderFor !== amb) {
                ambientPaint.shader = BitmapShader(amb, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                ambientShaderFor = amb
            }
            ambientMatrix.setScale(w / amb.width, h / amb.height)
            ambientPaint.shader.setLocalMatrix(ambientMatrix)
            ambientPaint.alpha = (255 * ambient.alpha).toInt()
            c.round(w, h, ambientPaint)
        }
        c.round(w, h, frost)

        // Crisp banner, right, floating on its own soft shadow.
        val bw = Art.Variant.HERO.w.u; val bh = Art.Variant.HERO.h.u
        val bl = (w - 28f.u - bw).toInt(); val bt = ((h - bh) / 2 - 6f.u).toInt()
        banner.bitmap?.let {
            val g = Glow.get(bw - 16.u, bh, 12f.u, 14f.u)
            val pad = (g.height - bh) / 2f
            bannerShadow.color = Palette.SHADOW
            bannerShadow.alpha = (255 * 0.22f * banner.alpha).toInt()
            c.drawBitmap(g, bl + 8f.u - pad, bt - pad + 10f.u, bannerShadow)
            c.drawArt(it, banner.alpha, bl, bt, bl + bw, bt + bh)
        }

        // Copy, left.
        val x = 30f.u
        var y = h - 140f.u
        c.drawText(eyebrow, x, y, eyebrowPaint)
        y += 34f.u
        c.drawText(title, x, y, titlePaint)
        y += 24f.u
        c.drawText(subtitle, x, y, subPaint)

        // Buttons (the whole card is one focus target; these show what OK and long-press do).
        val by = h - 62f.u
        val bhgt = 36f.u
        val openW = 100f.u
        fillPaint.color = Palette.TEXT
        tmpRect.set(x, by, x + openW, by + bhgt)
        c.drawRoundRect(tmpRect, bhgt / 2, bhgt / 2, fillPaint)
        val ps = 10.u
        val px = (x + 26f.u).toInt(); val py = (by + (bhgt - ps) / 2).toInt()
        play.setBounds(px, py, px + ps, py + ps)
        play.draw(c)
        c.drawText("Open", px + ps + 8f.u, by + 23f.u, btnPaint)

        val x2 = x + openW + 10f.u
        val hintW = btn2Paint.measureText(hint) + 36f.u
        fillPaint.color = Palette.line(0.07f)
        tmpRect.set(x2, by, x2 + hintW, by + bhgt)
        c.drawRoundRect(tmpRect, bhgt / 2, bhgt / 2, fillPaint)
        c.drawText(hint, x2 + 18f.u, by + 23f.u, btn2Paint)

        // Edge: the frosted left side is nearly white, so outline the card against the ground.
        strokePaint.color = Palette.line(Palette.EDGE * (1f - focus))
        strokePaint.strokeWidth = 1f.u
        tmpRect.set(0.5f.u, 0.5f.u, w - 0.5f.u, h - 0.5f.u)
        c.drawRoundRect(tmpRect, radius, radius, strokePaint)
    }
}

// ---------------------------------------------------------------------------------------------

/** The two secondary hero tiles: mini banner, name, a line of context, chevron. */
class RecentTile(context: Context) : FocusCard(context) {
    var entry: AppEntry? = null; private set
    private val art = ArtSlot(this, Art.Variant.MINI)
    private val titlePaint = textPaint(Fonts.semibold, 16f, Palette.TEXT)
    private val subPaint = textPaint(Fonts.regular, 12f, Palette.text(0.55f))
    private val chevron: Drawable = context.getDrawable(R.drawable.ic_chevron)!!.mutate()
    private var title = ""
    private var sub = ""

    init {
        lift = 1.04f; radius = 16f.u; shadowBlur = 18f.u; shadowRest = 0f; shadowFocus = 0.26f; shadowDrop = 14f.u
    }

    fun bind(e: AppEntry?, subtitle: String) {
        entry = e
        title = if (e == null) "" else ellipsize(e.label, titlePaint, 380f.u)
        sub = subtitle
        contentDescription = e?.label
        art.bind(e)
        invalidate()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); art.ensure() }

    override fun drawContent(c: Canvas, w: Float, h: Float, focus: Float) {
        c.surface(w, h, radius, Palette.white(0.95f + 0.05f * focus), Palette.line(Palette.EDGE * (1f - focus)))
        val aw = Art.Variant.MINI.w.u; val ah = Art.Variant.MINI.h.u
        val al = 24.u; val at = ((h - ah) / 2).toInt()
        val b = art.bitmap
        if (b != null) c.drawArt(b, art.alpha, al, at, al + aw, at + ah)
        else {
            fillPaint.shader = null; fillPaint.color = Palette.line(0.06f)
            tmpRect.set(al.toFloat(), at.toFloat(), (al + aw).toFloat(), (at + ah).toFloat())
            c.drawRoundRect(tmpRect, 7f.u, 7f.u, fillPaint)
        }
        val tx = al + aw + 18f.u
        c.drawText(title, tx, h / 2 - 2f.u, titlePaint)
        c.drawText(sub, tx, h / 2 + 17f.u, subPaint)

        val cs = 14.u
        chevron.setTint(Palette.text(0.25f + 0.45f * focus))
        val cx = (w - 24f.u - cs).toInt(); val cy = ((h - cs) / 2).toInt()
        chevron.setBounds(cx, cy, cx + cs, cy + cs)
        chevron.draw(c)
    }
}

// ---------------------------------------------------------------------------------------------

/** HDMI / AV / settings shortcut tile: icon, title, status line. */
class InfoTile(context: Context) : FocusCard(context) {
    private val titlePaint = textPaint(Fonts.semibold, 13.5f, Palette.text(0.85f))
    private val subPaint = textPaint(Fonts.regular, 11.5f, Palette.text(0.55f))
    private var icon: Drawable? = null
    private var title = ""
    private var sub = ""
    private var live = false

    init {
        lift = 1.06f; radius = 14f.u; shadowBlur = 14f.u; shadowRest = 0f; shadowFocus = 0.24f; shadowDrop = 10f.u
    }

    fun bind(iconRes: Int, title: String, sub: String, live: Boolean) {
        icon = context.getDrawable(iconRes)!!.mutate()
        this.title = title
        this.sub = sub
        this.live = live
        contentDescription = "$title, $sub"
        subPaint.color = if (live) Palette.GREEN_TEXT else Palette.text(0.55f)
        subPaint.typeface = if (live) Fonts.semibold else Fonts.regular
        invalidate()
    }

    override fun drawContent(c: Canvas, w: Float, h: Float, focus: Float) {
        c.surface(w, h, radius, Palette.white(0.95f + 0.05f * focus), Palette.line(Palette.EDGE * (1f - focus)))
        val s = 18.u
        val ix = 18.u; val iy = ((h - s) / 2).toInt()
        icon?.let {
            it.setTint(if (live) Palette.GREEN_TEXT else Palette.text(0.45f + 0.35f * focus))
            it.setBounds(ix, iy, ix + s, iy + s)
            it.draw(c)
        }
        val tx = ix + s + 14f.u
        c.drawText(title, tx, h / 2 - 2f.u, titlePaint)
        c.drawText(sub, tx, h / 2 + 15f.u, subPaint)
        if (live) {
            fillPaint.shader = null
            fillPaint.color = Palette.GREEN
            c.drawCircle(w - 22f.u, h / 2, 3.5f.u, fillPaint)
        }
    }
}
