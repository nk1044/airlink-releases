package dev.orbit.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.UserHandle
import android.util.AtomicFile
import java.io.File

/** One launchable activity. Labels and stamps come from the disk cache until a refresh lands. */
class AppEntry(
    val pkg: String,
    val activity: String,
    val label: String,
    val isTv: Boolean,
    /** Package lastUpdateTime: invalidates cached card art when the app updates. */
    val stamp: Long,
) {
    val key: String get() = "$pkg/$activity"
    val id: Long = key.hashCode().toLong() and 0xFFFFFFFFL
    var lastUsed = 0L
    var launches = 0
    /** Position among pinned apps, or -1. */
    var pin = -1

    fun component() = ComponentName(pkg, activity)
}

/**
 * The app list: rendered from a tiny disk cache on cold start, then refreshed from PackageManager
 * in the background. Launch history is our own log: UsageStatsManager needs a permission that's
 * unreachable on most vendor TVs.
 */
class AppRepository(private val context: Context) {

    interface Listener {
        fun onAppsChanged()
    }

    private val pm = context.packageManager
    private val appsFile = AtomicFile(File(context.filesDir, "apps.v1"))
    private val usageFile = AtomicFile(File(context.filesDir, "usage.v1"))

    @Volatile
    var apps: List<AppEntry> = emptyList()
        private set

    private val usage = HashMap<String, Usage>()
    private val listeners = ArrayList<Listener>()
    private var refreshQueued = false
    var showPhoneApps = true

    private class Usage(var lastUsed: Long, var launches: Int, var pin: Int)

    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }

    /** Synchronous and small (a few KB): the first frame has real labels in it. */
    fun loadCache() {
        readUsage()
        val list = ArrayList<AppEntry>()
        readLines(appsFile)?.forEach { line ->
            val f = line.split('\t')
            if (f.size >= 5) list += AppEntry(f[0], f[1], f[2], f[3] == "1", f[4].toLongOrNull() ?: 0L)
        }
        list.forEach(::applyUsage)
        apps = list
    }

    /** Re-reads PackageManager off the main thread, then notifies on the main thread. */
    fun refresh() {
        if (refreshQueued) return
        refreshQueued = true
        Work.main.postDelayed({
            refreshQueued = false
            Work.io.execute {
                val fresh = queryPackageManager()
                Work.ui {
                    fresh.forEach(::applyUsage)
                    val changed = !sameList(apps, fresh)
                    apps = fresh
                    if (changed) {
                        listeners.toList().forEach { it.onAppsChanged() }
                        Work.io.execute { writeAppsCache(fresh) }
                    }
                }
            }
        }, 120)
    }

    private fun queryPackageManager(): List<AppEntry> {
        val self = context.packageName
        val seen = HashSet<String>()
        val out = ArrayList<AppEntry>()
        val stamps = HashMap<String, Long>()
        fun stampOf(pkg: String) = stamps.getOrPut(pkg) {
            try { pm.getPackageInfo(pkg, 0).lastUpdateTime } catch (e: PackageManager.NameNotFoundException) { 0L }
        }
        val tv = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
        for (ri in tv) {
            val ai = ri.activityInfo ?: continue
            if (ai.packageName == self || !seen.add(ai.packageName)) continue
            out += AppEntry(ai.packageName, ai.name, ri.loadLabel(pm).toString().trim(), true, stampOf(ai.packageName))
        }
        if (showPhoneApps) {
            // Sideloaded phone apps have no TV entry point; offer them as a fallback.
            val phone = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            for (ri in phone) {
                val ai = ri.activityInfo ?: continue
                if (ai.packageName == self || !seen.add(ai.packageName)) continue
                out += AppEntry(ai.packageName, ai.name, ri.loadLabel(pm).toString().trim(), false, stampOf(ai.packageName))
            }
        }
        return out
    }

    private fun sameList(a: List<AppEntry>, b: List<AppEntry>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            val x = a[i]; val y = b[i]
            if (x.key != y.key || x.label != y.label || x.stamp != y.stamp) return false
        }
        return true
    }

    // ---- ordering ----

    /** Home row: pinned first, then most launched, then A–Z. Stable between launches. */
    fun homeOrder(): List<AppEntry> = apps.sortedWith(
        compareBy<AppEntry> { if (it.pin >= 0) it.pin else Int.MAX_VALUE }
            .thenByDescending { it.launches }
            .thenByDescending { it.isTv }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
    )

    /** Hero: the three most recently opened, topped up with the home order. */
    fun recents(n: Int = 3): List<AppEntry> {
        val used = apps.filter { it.lastUsed > 0 }.sortedByDescending { it.lastUsed }
        if (used.size >= n) return used.subList(0, n)
        val out = ArrayList(used)
        for (e in homeOrder()) {
            if (out.size >= n) break
            if (e.lastUsed == 0L && e.isTv) out += e
        }
        return out
    }

    fun alphabetical(): List<AppEntry> = apps.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })

    // ---- launch log and pins ----

    fun recordLaunch(e: AppEntry) {
        val u = usage.getOrPut(e.key) { Usage(0, 0, -1) }
        u.lastUsed = System.currentTimeMillis()
        u.launches++
        applyUsage(e)
        saveUsage()
    }

    fun togglePin(e: AppEntry) {
        val u = usage.getOrPut(e.key) { Usage(0, 0, -1) }
        if (u.pin >= 0) {
            val removed = u.pin
            u.pin = -1
            usage.values.forEach { if (it.pin > removed) it.pin-- }
        } else {
            u.pin = usage.values.count { it.pin >= 0 }
        }
        apps.forEach(::applyUsage)
        saveUsage()
        listeners.toList().forEach { it.onAppsChanged() }
    }

    private fun applyUsage(e: AppEntry) {
        val u = usage[e.key]
        e.lastUsed = u?.lastUsed ?: 0
        e.launches = u?.launches ?: 0
        e.pin = u?.pin ?: -1
    }

    // ---- package change callbacks ----

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val callback = object : LauncherApps.Callback() {
        override fun onPackageAdded(packageName: String, user: UserHandle) = refresh()
        override fun onPackageRemoved(packageName: String, user: UserHandle) = refresh()
        override fun onPackageChanged(packageName: String, user: UserHandle) = refresh()
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = refresh()
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = refresh()
    }

    fun watchPackages() {
        launcherApps?.registerCallback(callback, Work.main)
    }

    // ---- files ----

    private fun readUsage() {
        usage.clear()
        readLines(usageFile)?.forEach { line ->
            val f = line.split('\t')
            if (f.size >= 4) usage[f[0]] = Usage(f[1].toLongOrNull() ?: 0, f[2].toIntOrNull() ?: 0, f[3].toIntOrNull() ?: -1)
        }
    }

    private fun saveUsage() {
        val sb = StringBuilder()
        for ((k, u) in usage) sb.append(k).append('\t').append(u.lastUsed).append('\t').append(u.launches).append('\t').append(u.pin).append('\n')
        val text = sb.toString()
        Work.io.execute { writeText(usageFile, text) }
    }

    private fun writeAppsCache(list: List<AppEntry>) {
        val sb = StringBuilder()
        for (e in list) {
            sb.append(e.pkg).append('\t').append(e.activity).append('\t').append(e.label.replace('\t', ' ').replace('\n', ' '))
                .append('\t').append(if (e.isTv) '1' else '0').append('\t').append(e.stamp).append('\n')
        }
        writeText(appsFile, sb.toString())
    }

    private fun readLines(f: AtomicFile): List<String>? = try {
        String(f.readFully(), Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }

    @Synchronized
    private fun writeText(f: AtomicFile, text: String) {
        val out = try { f.startWrite() } catch (e: Exception) { return }
        try {
            out.write(text.toByteArray(Charsets.UTF_8))
            f.finishWrite(out)
        } catch (e: Exception) {
            f.failWrite(out)
        }
    }
}
