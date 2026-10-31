package dev.orbit.launcher.data

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.util.LruCache
import dev.orbit.launcher.ui.Fonts
import dev.orbit.launcher.ui.Palette
import dev.orbit.launcher.ui.Ui
import dev.orbit.launcher.ui.ellipsize
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Card art, rendered once per app version at exactly the size it's shown, with the corners baked
 * in (no clip-to-outline at draw time). Memory: an LruCache bounded in bytes. Disk: PNGs in the
 * cache dir, so a cold start never touches another APK's resources for apps it has seen before.
 */
object Art {

    /** Sizes are design px; `lift` is the focus scale, so the focused state is drawn 1:1 and stays sharp. */
    enum class Variant(val w: Int, val h: Int, val radius: Float, val lift: Float, val withLabel: Boolean) {
        CARD(186, 104, 12f, 1.10f, true),
        HERO(256, 144, 12f, 1.02f, true),
        MINI(64, 36, 7f, 1.03f, false),
        /** A blurred 32×18 thumbnail, stretched behind the hero like a backdrop. */
        AMBIENT(32, 18, 0f, 1f, false);

        fun pxW() = Math.round(w * lift * Ui.scale)
        fun pxH() = Math.round(h * lift * Ui.scale)
    }

    private lateinit var ctx: Context
    private lateinit var pm: PackageManager
    private lateinit var dir: File

    private val memory = object : LruCache<String, Bitmap>(14 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val inflight = HashMap<String, ArrayList<(Bitmap?) -> Unit>>()

    fun init(context: Context) {
        ctx = context.applicationContext
        pm = ctx.packageManager
        dir = File(ctx.cacheDir, "art").apply { mkdirs() }
    }

    private fun keyOf(e: AppEntry, v: Variant) =
        "v2|${v.name}|${e.key}|${e.stamp}|${Ui.scale}"

    fun peek(e: AppEntry, v: Variant): Bitmap? = memory.get(keyOf(e, v))

    /** Main thread only. The callback runs on the main thread, possibly synchronously. */
    fun load(e: AppEntry, v: Variant, cb: (Bitmap?) -> Unit) {
        val key = keyOf(e, v)
        memory.get(key)?.let { cb(it); return }
        inflight[key]?.let { it += cb; return }
        inflight[key] = arrayListOf(cb)
        Work.io.execute {
            val bmp = try { fromDisk(key) ?: render(e, v)?.also { toDisk(key, it) } } catch (t: Throwable) { null }
            Work.main.post {
                if (bmp != null) memory.put(key, bmp)
                inflight.remove(key)?.forEach { it(bmp) }
            }
        }
    }

    fun onTrimMemory(level: Int) {
        // UI_HIDDEN, RUNNING_LOW and CRITICAL all clear: a hidden launcher should be small.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) memory.evictAll()
        else memory.trimToSize(memory.maxSize() / 2)
    }

    // ---- disk ----

    private fun fileFor(key: String) = File(dir, Integer.toHexString(key.hashCode()) + "_" + key.length + ".png")

    private fun fromDisk(key: String): Bitmap? {
        val f = fileFor(key)
        if (!f.exists()) return null
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
    }

    private fun toDisk(key: String, b: Bitmap) {
        val f = fileFor(key)
        val tmp = File(f.path + ".tmp")
        try {
            FileOutputStream(tmp).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            tmp.renameTo(f)
        } catch (e: Exception) {
            tmp.delete()
        }
    }

    // ---- rendering (background thread) ----

    private fun render(e: AppEntry, v: Variant): Bitmap? {
        val ai = try { pm.getActivityInfo(e.component(), 0) } catch (x: PackageManager.NameNotFoundException) { return null }
        val banner: Drawable? = ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm)
        val w = v.pxW(); val h = v.pxH()

        if (v == Variant.AMBIENT) {
            val src = banner ?: return ambientFromIcon(ai.loadIcon(pm), w, h)
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            drawCover(Canvas(b), src, w, h)
            return blur(b)
        }

        val flat = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(flat)
        if (banner != null) {
            c.drawColor(Palette.CARD)
            drawCover(c, banner, w, h)
        } else {
            drawIconCard(c, ai.loadIcon(pm), e.label, v, w, h)
        }
        return if (v.radius > 0f) round(flat, v.radius * v.lift * Ui.scale) else flat
    }

    /** Scale to fill, centre-crop. Banners are 16:9 like every variant, so this is usually a plain scale. */
    private fun drawCover(c: Canvas, d: Drawable, w: Int, h: Int) {
        val iw = if (d.intrinsicWidth > 0) d.intrinsicWidth else w
        val ih = if (d.intrinsicHeight > 0) d.intrinsicHeight else h
        val s = max(w / iw.toFloat(), h / ih.toFloat())
        val dw = Math.round(iw * s); val dh = Math.round(ih * s)
        val l = (w - dw) / 2; val t = (h - dh) / 2
        if (d is BitmapDrawable) d.paint.isFilterBitmap = true
        d.setBounds(l, t, l + dw, t + dh)
        d.draw(c)
    }

    /** No banner (phone apps): the icon on a deep tint of its own colour, with the name underneath. */
    private fun drawIconCard(c: Canvas, icon: Drawable, label: String, v: Variant, w: Int, h: Int) {
        val iconSize = Math.round(h * (if (v.withLabel) 0.40f else 0.70f))
        val sample = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, 24, 24)
        icon.draw(Canvas(sample))
        val tint = dominant(sample)

        val top = mix(tint, Palette.CARD, 0.80f)
        val bottom = mix(tint, Palette.BG_DEEP, 0.70f)
        val bg = Paint().apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)

        val cy = if (v.withLabel) h * 0.42f else h / 2f
        val l = (w - iconSize) / 2
        val t = Math.round(cy - iconSize / 2f)
        icon.setBounds(l, t, l + iconSize, t + iconSize)
        icon.draw(c)

        if (v.withLabel) {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Fonts.semibold
                textSize = h * 0.118f
                color = Palette.text(0.78f)
                textAlign = Paint.Align.CENTER
            }
            val text = ellipsize(label, tp, w * 0.84f)
            c.drawText(text, w / 2f, t + iconSize + h * 0.2f, tp)
        }
    }

    private fun ambientFromIcon(icon: Drawable, w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val sample = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, 24, 24)
        icon.draw(Canvas(sample))
        val tint = dominant(sample)
        c.drawColor(mix(tint, Palette.CARD, 0.45f))
        val s = h
        icon.setBounds(w - s - 2, 0, w - 2, s)
        icon.draw(c)
        return blur(b)
    }

    private fun round(src: Bitmap, r: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(Matrix()) }
        }
        Canvas(out).drawRoundRect(RectF(0f, 0f, src.width.toFloat(), src.height.toFloat()), r, r, p)
        src.recycle()
        return out
    }

    /** Three box-blur passes ≈ a gaussian. Tiny bitmap, so this is microseconds. */
    private fun blur(b: Bitmap): Bitmap {
        val w = b.width; val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        repeat(3) {
            boxPass(px, tmp, w, h, 3, true)
            boxPass(tmp, px, w, h, 3, false)
        }
        b.setPixels(px, 0, w, 0, 0, w, h)
        return b
    }

    private fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val n = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val div = 2 * r + 1
        for (line in 0 until lines) {
            var ra = 0; var rr = 0; var rg = 0; var rb = 0
            fun at(i: Int): Int {
                val k = min(n - 1, max(0, i))
                return if (horizontal) src[line * w + k] else src[k * w + line]
            }
            for (i in -r..r) { val c = at(i); ra += c ushr 24; rr += (c shr 16) and 255; rg += (c shr 8) and 255; rb += c and 255 }
            for (i in 0 until n) {
                val idx = if (horizontal) line * w + i else i * w + line
                dst[idx] = ((ra / div) shl 24) or ((rr / div) shl 16) or ((rg / div) shl 8) or (rb / div)
                val add = at(i + r + 1); val sub = at(i - r)
                ra += (add ushr 24) - (sub ushr 24)
                rr += ((add shr 16) and 255) - ((sub shr 16) and 255)
                rg += ((add shr 8) and 255) - ((sub shr 8) and 255)
                rb += (add and 255) - (sub and 255)
            }
        }
    }

    /** Average colour weighted towards saturated, opaque pixels: the icon's brand colour, not its white. */
    private fun dominant(b: Bitmap): Int {
        var r = 0.0; var g = 0.0; var bl = 0.0; var wsum = 0.0
        val hsv = FloatArray(3)
        for (y in 0 until b.height) for (x in 0 until b.width) {
            val c = b.getPixel(x, y)
            val a = Color.alpha(c) / 255.0
            if (a < 0.5) continue
            Color.colorToHSV(c, hsv)
            val wgt = a * (0.08 + hsv[1] * hsv[2])
            r += Color.red(c) * wgt; g += Color.green(c) * wgt; bl += Color.blue(c) * wgt; wsum += wgt
        }
        b.recycle()
        if (wsum == 0.0) return 0xFF33415C.toInt()
        return Color.rgb((r / wsum).toInt(), (g / wsum).toInt(), (bl / wsum).toInt())
    }

    fun mix(a: Int, b: Int, t: Float): Int {
        val u = 1 - t
        return Color.rgb(
            (Color.red(a) * u + Color.red(b) * t).toInt(),
            (Color.green(a) * u + Color.green(b) * t).toInt(),
            (Color.blue(a) * u + Color.blue(b) * t).toInt(),
        )
    }
}
