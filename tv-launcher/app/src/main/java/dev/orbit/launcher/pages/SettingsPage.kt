package dev.orbit.launcher.pages

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import dev.orbit.launcher.BuildConfig
import dev.orbit.launcher.OrbitApp
import dev.orbit.launcher.R
import dev.orbit.launcher.data.HomeRole
import dev.orbit.launcher.ui.ActionRow
import dev.orbit.launcher.ui.InfoTile
import dev.orbit.launcher.ui.Sheet
import dev.orbit.launcher.ui.WRAP
import dev.orbit.launcher.ui.frameParams
import dev.orbit.launcher.ui.u

/**
 * Two halves. Left: shortcuts into the TV's own Settings screens (Orbit only opens them; it
 * never writes a system setting). Right: Orbit's own options.
 */
class SettingsPage(host: Host) : Page(host) {

    override val view = Canvas720(context)
    private val tiles = LinearLayout(context)
    private val rows = LinearLayout(context)
    private val defaultRow = ActionRow(context)
    private val startupRow = ActionRow(context)
    private val phoneRow = ActionRow(context)
    private val aboutRow = ActionRow(context)

    private class Shortcut(val icon: Int, val title: String, val action: String)

    init {
        view.addView(sectionLabel(context, "TV settings"), frameParams(WRAP, WRAP, 52.u, 18.u))
        tiles.orientation = LinearLayout.VERTICAL
        tiles.clipChildren = false
        val shortcuts = listOf(
            Shortcut(R.drawable.ic_wifi, "Network & Wi-Fi", Settings.ACTION_WIFI_SETTINGS),
            Shortcut(R.drawable.ic_bluetooth, "Bluetooth & remotes", Settings.ACTION_BLUETOOTH_SETTINGS),
            Shortcut(R.drawable.ic_display, "Display", Settings.ACTION_DISPLAY_SETTINGS),
            Shortcut(R.drawable.ic_sound, "Sound", Settings.ACTION_SOUND_SETTINGS),
            Shortcut(R.drawable.ic_clock, "Date & time", Settings.ACTION_DATE_SETTINGS),
            Shortcut(R.drawable.ic_settings, "All settings", Settings.ACTION_SETTINGS),
        ).filter { Intent(it.action).resolveActivity(context.packageManager) != null }
        shortcuts.forEachIndexed { i, s ->
            val t = InfoTile(context)
            t.bind(s.icon, s.title, "Opens the TV's settings", false)
            t.onClick = { host.launchIntent(Intent(s.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), t) }
            tiles.addView(t, LinearLayout.LayoutParams(420.u, 68.u).apply { if (i > 0) topMargin = 10.u })
        }
        view.addView(tiles, frameParams(420.u, WRAP, 52.u, 42.u))

        view.addView(sectionLabel(context, "Air Launch"), frameParams(WRAP, WRAP, 528.u, 18.u))
        rows.orientation = LinearLayout.VERTICAL
        rows.clipChildren = false
        listOf(defaultRow, startupRow, phoneRow, aboutRow).forEachIndexed { i, r ->
            rows.addView(r, LinearLayout.LayoutParams(ROW_W, 68.u).apply { if (i > 0) topMargin = 10.u })
        }
        view.addView(rows, frameParams(ROW_W, WRAP, 528.u, 42.u))

        defaultRow.onClick = ::askForDefault
        startupRow.onClick = {
            val on = !HomeRole.startupEnabled(context)
            HomeRole.setStartup(context, on)
            refresh()
            host.hud(if (on) "Air Launch will open when the TV starts" else "Startup launch off")
        }
        phoneRow.onClick = {
            val app = context.applicationContext as OrbitApp
            val on = !host.apps.showPhoneApps
            host.apps.showPhoneApps = on
            context.getSharedPreferences("orbit", Context.MODE_PRIVATE).edit().putBoolean(OrbitApp.PREF_PHONE_APPS, on).apply()
            app.apps.refresh()
            refresh()
        }
        aboutRow.onClick = {}
        refresh()
    }

    override fun defaultFocus(): View? = tiles.getChildAt(0) ?: defaultRow

    override fun onShown() = refresh()

    fun refresh() {
        val isDefault = HomeRole.isDefault(context)
        val current = HomeRole.currentHomeLabel(context)
        defaultRow.set(
            R.drawable.ic_home, "Default launcher",
            when {
                isDefault -> "Air Launch opens when you press Home and after the TV starts"
                current != null -> "Home is set to $current. Press OK to choose Air Launch"
                else -> "Press OK, then pick Air Launch and \"Always\""
            },
            if (isDefault) "On" else "",
        )
        defaultRow.accentIcon = isDefault
        startupRow.set(R.drawable.ic_power, "Open at TV startup", "Also bring Air Launch up after boot if the TV starts its own home")
        startupRow.toggle = HomeRole.startupEnabled(context)
        phoneRow.set(R.drawable.ic_grid, "Show phone apps", "Include sideloaded apps that have no TV banner")
        phoneRow.toggle = host.apps.showPhoneApps
        aboutRow.set(
            R.drawable.ic_info, "About Air Launch",
            "Version ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} · ${host.apps.apps.size} apps",
        )
    }

    private fun askForDefault() {
        val actions = ArrayList<Sheet.Action>()
        actions += Sheet.Action(R.drawable.ic_home, "Choose Home app now", "The TV asks which app to use: pick Air Launch, then Always") {
            HomeRole.showChooser(host.activity)
        }
        if (HomeRole.hasHomeSettings(context)) {
            actions += Sheet.Action(R.drawable.ic_settings, "Open Home app settings") { HomeRole.openHomeSettings(host.activity) }
        }
        host.sheet.open(
            defaultRow,
            "Make Air Launch your Home",
            if (HomeRole.isDefault(context)) "Air Launch is already your Home app" else "",
            null,
            actions,
            body = "Some TVs hide this choice. If no chooser appears, run this once from a computer:\n" +
                "adb shell cmd package set-home-activity ${context.packageName}/.HomeActivity\n\n" +
                "To go back to the TV's own home later, choose it in the same way.",
        )
    }

    private companion object {
        val ROW_W get() = 700.u
    }
}
