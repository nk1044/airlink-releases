package dev.orbit.launcher

import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.FocusFinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import dev.orbit.launcher.data.AppEntry
import dev.orbit.launcher.data.AppRepository
import dev.orbit.launcher.data.Art
import dev.orbit.launcher.data.Inputs
import dev.orbit.launcher.data.TvInput
import dev.orbit.launcher.data.Work
import dev.orbit.launcher.pages.AppsPage
import dev.orbit.launcher.pages.HomePage
import dev.orbit.launcher.pages.Host
import dev.orbit.launcher.pages.InputsPage
import dev.orbit.launcher.pages.Page
import dev.orbit.launcher.pages.SettingsPage
import dev.orbit.launcher.ui.AppCard
import dev.orbit.launcher.ui.FocusCard
import dev.orbit.launcher.ui.ArtSlot
import dev.orbit.launcher.ui.HeroCard
import dev.orbit.launcher.ui.Hud
import dev.orbit.launcher.ui.Keys
import dev.orbit.launcher.ui.MATCH
import dev.orbit.launcher.ui.Motion
import dev.orbit.launcher.ui.Palette
import dev.orbit.launcher.ui.RecentTile
import dev.orbit.launcher.ui.Sheet
import dev.orbit.launcher.ui.TopBar
import dev.orbit.launcher.ui.WRAP
import dev.orbit.launcher.ui.frameParams
import dev.orbit.launcher.ui.isDescendantOf
import dev.orbit.launcher.ui.u

class HomeActivity : Activity(), Host, AppRepository.Listener {

    override val activity: Activity get() = this
    override val apps: AppRepository get() = (application as OrbitApp).apps
    override val inputs: Inputs get() = (application as OrbitApp).inputs
    /** Built on first use: most sessions never open it, and cold start is what matters. */
    private var sheetView: Sheet? = null
    override val sheet: Sheet
        get() = sheetView ?: Sheet(this, content).also {
            sheetView = it
            root.addView(it, root.indexOfChild(content) + 1, FrameLayout.LayoutParams(MATCH, MATCH))
        }
    private val sheetOpen get() = sheetView?.isOpen == true
    override val cardPool = RecyclerView.RecycledViewPool().apply { setMaxRecycledViews(1, 24) }

    private lateinit var root: Root
    private lateinit var content: FrameLayout
    private lateinit var topBar: TopBar
    private lateinit var strip: FrameLayout
    private var hudView: Hud? = null
    private lateinit var curtain: View

    private val titles = listOf("Home", "Apps", "Inputs", "Settings")
    private val pages = arrayOfNulls<Page>(titles.size)
    private var current = 0
    private var pageGen = 0

    private var resumed = false
    private var launching = false
    private var appsDirty = false
    private var artReleased = false
    private var lastMoveAt = 0L
    private var swallowConfirmUp = false

    // ---- lifecycle ----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(null) // stateNotNeeded: always start clean
        buildViews()
        apps.addListener(this)
        inputs.onChanged = { pages.forEach { it?.onInputsChanged() } }

        // First frame first; then refresh the lists and build the other pages while idle.
        Work.main.post {
            apps.refresh()
            inputs.refresh()
        }
        Work.main.postDelayed({
            Looper.myQueue().addIdleHandler {
                for (i in 1 until titles.size) page(i)
                false
            }
        }, 1500)
    }

    override fun onStart() {
        super.onStart()
        if (artReleased) { ArtSlot.restoreAttached(); artReleased = false }
        if (appsDirty) { appsDirty = false; pages.forEach { it?.onAppsChanged() } }
        inputs.watch(true)
        watchNetwork(true)
        pages.forEach { (it as? SettingsPage)?.refresh() }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        // The veil is up on first start and after leaving for an app. Lifting it here (not in
        // onStart) also covers apps that only paused us, like a translucent panel.
        if (curtain.visibility == View.VISIBLE) playArrival()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onStop() {
        inputs.watch(false)
        watchNetwork(false)
        super.onStop()
    }

    override fun onDestroy() {
        apps.removeListener(this)
        inputs.onChanged = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!intent.hasCategory(Intent.CATEGORY_HOME)) return
        val wasShowing = resumed
        sheetView?.close()
        // HOME while Home is already showing: back to the top, no recreate.
        if (wasShowing) goHome()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            // Hidden: give back every decoded bitmap. They come back from the disk cache on return.
            ArtSlot.releaseAll()
            cardPool.clear()
            artReleased = true
        }
    }

    override fun onAppsChanged() {
        if (resumed || hasWindowFocus()) pages.forEach { it?.onAppsChanged() } else appsDirty = true
    }

    // ---- views ----

    private fun buildViews() {
        root = Root()
        content = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        topBar = TopBar(this, titles)
        topBar.onTabFocused = { showPage(it) }
        content.addView(topBar, frameParams(MATCH, 64.u))
        content.addView(View(this).apply { setBackgroundColor(Palette.line(0.05f)) }, frameParams(MATCH, 1.u, 0, 64.u))

        strip = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        content.addView(strip, frameParams(MATCH, 656.u, 0, 64.u))
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))

        // Cold start: the veil matches the window background, so the first frame never flashes.
        curtain = View(this).apply { setBackgroundColor(Palette.BG); isFocusable = false }
        root.addView(curtain, FrameLayout.LayoutParams(MATCH, MATCH))

        setContentView(root)
        page(0)
        pages[0]!!.defaultFocus()?.requestFocus()
    }

    private fun page(i: Int): Page {
        pages[i]?.let { return it }
        val p = when (i) {
            0 -> HomePage(this)
            1 -> AppsPage(this)
            2 -> InputsPage(this)
            else -> SettingsPage(this)
        }
        pages[i] = p
        p.view.translationX = (i * 1280).u.toFloat()
        strip.addView(p.view, FrameLayout.LayoutParams(1280.u, MATCH))
        setPageState(i, p)
        return p
    }

    private fun setPageState(i: Int, p: Page) {
        val isCurrent = i == current
        (p.view as ViewGroup).descendantFocusability =
            if (isCurrent) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
        if (isCurrent) p.view.visibility = View.VISIBLE
        else if (strip.translationX == -(i * 1280).u.toFloat()) p.view.visibility = View.VISIBLE
        else p.view.visibility = View.INVISIBLE
    }

    /** Pages sit side by side on a strip; switching slides the strip on a critically damped spring. */
    private fun showPage(i: Int) {
        if (i == current) return
        val from = current
        current = i
        topBar.select(i)
        val target = page(i)
        val lo = minOf(from, i); val hi = maxOf(from, i)
        pages.forEachIndexed { j, p ->
            if (p == null) return@forEachIndexed
            (p.view as ViewGroup).descendantFocusability =
                if (j == i) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
            // Keep every page the strip passes over drawn; the rest stay invisible.
            if (j in lo..hi || p.view.visibility == View.VISIBLE) p.view.visibility = View.VISIBLE
        }
        target.onShown()
        val gen = ++pageGen
        strip.animate().translationX(-(i * 1280).u.toFloat())
            .setInterpolator(Motion.page).setDuration(Motion.PAGE_MS)
            .withEndAction {
                if (gen != pageGen) return@withEndAction
                pages.forEachIndexed { j, p -> if (p != null && j != current) p.view.visibility = View.INVISIBLE }
            }.start()
    }

    private fun goHome() {
        if (current != 0) topBar.tabs[0].requestFocus()
        pages[0]?.reset()
        pages.forEach { if (it !== pages[0]) it?.reset() }
    }

    // ---- motion: leaving for an app and coming back ----

    /** Zoom into the chosen tile while the screen fades to the background colour, then launch. */
    private fun zoomInto(from: View, start: () -> Boolean) {
        if (launching) return
        launching = true
        val a = IntArray(2); val b = IntArray(2)
        from.getLocationInWindow(a); content.getLocationInWindow(b)
        content.pivotX = a[0] - b[0] + from.width / 2f
        content.pivotY = a[1] - b[1] + from.height / 2f
        content.animate().scaleX(1.10f).scaleY(1.10f).setInterpolator(Motion.easeIn).setDuration(170).start()
        // Apps are mostly dark: dissolve through black into them, and back out of it on return.
        curtain.setBackgroundColor(0xFF000000.toInt())
        curtain.visibility = View.VISIBLE
        curtain.alpha = 0f
        curtain.animate().alpha(1f).setInterpolator(Motion.easeIn).setDuration(170).withEndAction {
            launching = false
            if (!start()) playArrival()
        }.start()
    }

    /** Coming back (or first start): the page settles from slightly enlarged as the veil lifts. */
    private fun playArrival() {
        if (launching) return
        if (content.scaleX == 1f) {
            val dm = resources.displayMetrics
            content.pivotX = dm.widthPixels / 2f
            content.pivotY = dm.heightPixels / 2f
            content.scaleX = 1.05f
            content.scaleY = 1.05f
        }
        curtain.visibility = View.VISIBLE
        if (curtain.alpha == 0f) curtain.alpha = 1f
        content.animate().scaleX(1f).scaleY(1f).setInterpolator(Motion.sheet).setDuration(Motion.SHEET_MS + 80).start()
        curtain.animate().alpha(0f).setInterpolator(Motion.easeOut).setDuration(380)
            .withEndAction { curtain.visibility = View.GONE }.start()
    }

    private fun tryStart(intent: Intent): Boolean = try {
        startActivity(intent)
        overridePendingTransition(0, 0)
        true
    } catch (e: Exception) {
        hud("Couldn't open that")
        false
    }

    override fun launch(e: AppEntry, from: View) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(if (e.isTv) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
            .setComponent(e.component())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        zoomInto(from) {
            val ok = tryStart(intent) || (packageManager.getLaunchIntentForPackage(e.pkg)?.let {
                tryStart(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } ?: false)
            if (ok) { apps.recordLaunch(e); appsDirty = true }
            ok
        }
    }

    override fun launchInput(input: TvInput, from: View) {
        zoomInto(from) { tryStart(inputs.intentFor(input.id)) }
    }

    /** Settings screens on Android TV are often side panels over Home, so no zoom-out for these. */
    override fun launchIntent(intent: Intent, from: View) {
        tryStart(intent)
    }

    override fun hud(msg: String) {
        val h = hudView ?: Hud(this).also {
            hudView = it
            root.addView(it, root.indexOfChild(curtain),
                FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = 76.u })
        }
        h.show(msg)
    }

    override fun showOptions(e: AppEntry, from: View) {
        val pinned = e.pin >= 0
        val actions = arrayListOf(
            Sheet.Action(R.drawable.ic_play, "Open") { launch(e, from) },
            Sheet.Action(if (pinned) R.drawable.ic_unpin else R.drawable.ic_pin, if (pinned) "Unpin" else "Pin to the front", "Of the Your Apps row") {
                apps.togglePin(e)
                hud(if (pinned) "Unpinned ${e.label}" else "Pinned ${e.label}")
            },
            Sheet.Action(R.drawable.ic_info, "App info") {
                launchIntent(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", e.pkg, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), from)
            },
        )
        val system = try {
            packageManager.getApplicationInfo(e.pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0
        } catch (x: Exception) { true }
        if (!system) {
            actions += Sheet.Action(R.drawable.ic_trash, "Uninstall", danger = true) {
                tryStart(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", e.pkg, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        sheet.open(
            from, e.label,
            if (e.isTv) "TV app" else "Phone app",
            Art.peek(e, Art.Variant.HERO) ?: Art.peek(e, Art.Variant.CARD),
            actions,
        )
    }

    // ---- keys ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val dpad = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT ||
            code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
        if (dpad && event.action == KeyEvent.ACTION_DOWN) {
            if (launching) return true
            val now = SystemClock.uptimeMillis()
            Keys.repeating = event.repeatCount > 0
            if (event.repeatCount > 0) {
                // A held D-pad fires ~20/s. Drop events that queued up behind a slow frame, and
                // move at most once per ~2 frames, so focus never runs ahead of the scroll.
                if (now - event.eventTime > 90 || now - lastMoveAt < 40) return true
            }
            lastMoveAt = now
        } else if (dpad && event.action == KeyEvent.ACTION_UP) {
            Keys.repeating = false
        }

        // Long-press OK. Views time a hold themselves, but remotes (and `input --longpress`)
        // may only deliver the system's long-press flag. Honour it, and swallow the release so
        // it doesn't also count as a click.
        val confirm = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
        if (confirm && event.action == KeyEvent.ACTION_DOWN && event.isLongPress) {
            val f = currentFocus
            if (f is FocusCard && f.onLongClick != null && !sheetOpen) {
                f.cancelLongPress()
                f.performLongClick()
                swallowConfirmUp = true
            }
            return true
        }
        if (confirm && event.action == KeyEvent.ACTION_UP && swallowConfirmUp) {
            swallowConfirmUp = false
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_INFO) {
            val f = currentFocus
            val e = when (f) {
                is AppCard -> f.entry
                is HeroCard -> f.entry
                is RecentTile -> f.entry
                else -> null
            }
            if (e != null && f != null) { showOptions(e, f); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Back is handled here for API 28")
    override fun onBackPressed() {
        when {
            sheetOpen -> sheet.close()
            current != 0 -> topBar.tabs[0].requestFocus()
            currentFocus !is HeroCard -> pages[0]?.reset()
            // At the top of Home: a launcher has nothing behind it, so Back does nothing.
        }
    }

    // ---- network glyph ----

    private val cm by lazy { getSystemService(ConnectivityManager::class.java) }
    private var netRegistered = false
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            topBar.setNetwork(kindOf(caps))
        }
        override fun onLost(network: Network) = topBar.setNetwork(TopBar.NET_NONE)
    }

    private fun kindOf(caps: NetworkCapabilities?) = when {
        caps == null -> TopBar.NET_NONE
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> TopBar.NET_ETHERNET
        else -> TopBar.NET_WIFI
    }

    private fun watchNetwork(on: Boolean) {
        val m = cm ?: return
        if (on == netRegistered) return
        netRegistered = on
        try {
            if (on) {
                topBar.setNetwork(kindOf(m.activeNetwork?.let { m.getNetworkCapabilities(it) }))
                m.registerDefaultNetworkCallback(netCallback, Work.main)
            } else {
                m.unregisterNetworkCallback(netCallback)
            }
        } catch (e: Exception) {
            netRegistered = false
        }
    }

    // ---- focus rules ----

    /**
     * Nav ⇄ page navigation lives here:
     * - Down from the nav enters the current page at its default spot.
     * - Up from a page's top row goes to the selected tab.
     * - Left/right never wander between nav and page, and focus never leaves the current page.
     */
    private inner class Root : FrameLayout(this@HomeActivity) {
        init {
            clipChildren = false
        }

        override fun focusSearch(focused: View?, direction: Int): View? {
            if (focused == null) return super.focusSearch(null, direction)
            val next = FocusFinder.getInstance().findNextFocus(this, focused, direction)
            val pageView = pages[current]?.view
            if (focused.isDescendantOf(topBar)) {
                return when (direction) {
                    View.FOCUS_DOWN -> pages[current]?.defaultFocus() ?: focused
                    View.FOCUS_LEFT, View.FOCUS_RIGHT -> if (next != null && next.isDescendantOf(topBar)) next else bump(focused, direction)
                    else -> focused
                }
            }
            if (next != null && pageView != null && next.isDescendantOf(pageView)) return next
            return if (direction == View.FOCUS_UP) topBar.tabs[current] else bump(focused, direction)
        }

        private fun bump(v: View, direction: Int): View {
            val horizontal = direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT
            // TvList bumps its own items; everything else bumps here.
            if (v.parent !is RecyclerView) {
                Motion.bump(v, horizontal, if (direction == View.FOCUS_LEFT || direction == View.FOCUS_UP) -1 else 1)
            }
            return v
        }
    }
}
