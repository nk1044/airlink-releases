package dev.orbit.launcher

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.UserManager
import dev.orbit.launcher.data.AppRepository
import dev.orbit.launcher.data.Art
import dev.orbit.launcher.data.Inputs
import dev.orbit.launcher.ui.Fonts
import dev.orbit.launcher.ui.Glow
import dev.orbit.launcher.ui.Ui
import java.io.File

class OrbitApp : Application() {

    lateinit var apps: AppRepository; private set
    lateinit var inputs: Inputs; private set

    /**
     * Device-protected storage. Home is direct-boot aware so the system can start it before the
     * user is unlocked (otherwise the vendor's FallbackHome runs and opens its own launcher), and
     * credential-protected storage isn't available that early. Everything we keep lives here.
     */
    lateinit var store: Context; private set

    override fun onCreate() {
        super.onCreate()
        store = createDeviceProtectedStorageContext()
        migrateToDeviceStorage()
        Ui.init(this)
        Fonts.init(this)
        Art.init(this, store.cacheDir)
        val prefs = store.getSharedPreferences(PREFS, MODE_PRIVATE)
        apps = AppRepository(this, store.filesDir).apply {
            showPhoneApps = prefs.getBoolean(PREF_PHONE_APPS, true)
            loadCache()
            watchPackages()
        }
        inputs = Inputs(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Art.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) Glow.clear()
    }

    /** One-time move of pins, launch history and settings from the old (credential) storage. */
    private fun migrateToDeviceStorage() {
        val marker = File(store.filesDir, ".moved-to-device-storage")
        if (marker.exists()) return
        // Before unlock the old files can't be read; try again on a later start.
        if (getSystemService(UserManager::class.java)?.isUserUnlocked != true) return
        for (name in listOf("apps.v1", "usage.v1")) {
            val src = File(filesDir, name)
            val dst = File(store.filesDir, name)
            if (src.exists() && !dst.exists()) src.copyTo(dst)
        }
        store.moveSharedPreferencesFrom(this, PREFS)
        marker.createNewFile()
    }

    companion object {
        const val PREFS = "orbit"
        const val PREF_PHONE_APPS = "phone_apps"
    }
}

