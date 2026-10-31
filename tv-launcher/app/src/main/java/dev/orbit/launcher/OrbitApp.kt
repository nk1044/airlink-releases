package dev.orbit.launcher

import android.app.Application
import android.content.ComponentCallbacks2
import dev.orbit.launcher.data.AppRepository
import dev.orbit.launcher.data.Art
import dev.orbit.launcher.data.Inputs
import dev.orbit.launcher.ui.Fonts
import dev.orbit.launcher.ui.Glow
import dev.orbit.launcher.ui.Ui

class OrbitApp : Application() {

    lateinit var apps: AppRepository; private set
    lateinit var inputs: Inputs; private set

    override fun onCreate() {
        super.onCreate()
        Ui.init(this)
        Fonts.init(this)
        Art.init(this)
        val prefs = getSharedPreferences("orbit", MODE_PRIVATE)
        apps = AppRepository(this).apply {
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

    companion object {
        const val PREF_PHONE_APPS = "phone_apps"
    }
}

