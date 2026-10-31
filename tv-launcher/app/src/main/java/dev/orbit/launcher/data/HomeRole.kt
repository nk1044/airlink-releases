package dev.orbit.launcher.data

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import dev.orbit.launcher.BootReceiver

/**
 * Becoming Home, without touching any TV setting the user didn't ask to change.
 *
 * - "Default launcher": API 28 has no RoleManager. Briefly enabling and disabling a second HOME
 *   component makes PackageManager drop the saved Home choice, so the HOME intent shows the
 *   system's chooser, where the user picks Orbit and "Always".
 * - "Open at startup": a BOOT_COMPLETED receiver, disabled until switched on, for vendor builds
 *   that start their own home after boot regardless of the saved choice.
 */
object HomeRole {

    private fun homeIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    fun isDefault(context: Context): Boolean {
        val ri = context.packageManager.resolveActivity(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY)
        return ri?.activityInfo?.packageName == context.packageName
    }

    /** The current Home app's label, or null when no default is saved (the chooser would show). */
    fun currentHomeLabel(context: Context): String? {
        val pm = context.packageManager
        val ri = pm.resolveActivity(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY) ?: return null
        val pkg = ri.activityInfo?.packageName ?: return null
        if (pkg == "android") return null // ResolverActivity: nothing saved
        return ri.loadLabel(pm)?.toString()
    }

    fun hasHomeSettings(context: Context) =
        Intent(Settings.ACTION_HOME_SETTINGS).resolveActivity(context.packageManager) != null

    /** Clears the saved Home choice and fires HOME, so the system asks which app to use. */
    fun showChooser(activity: Activity) {
        val pm = activity.packageManager
        val alias = ComponentName(activity, "${activity.packageName}.HomeChooserTrigger")
        pm.setComponentEnabledSetting(alias, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        pm.setComponentEnabledSetting(alias, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        activity.startActivity(homeIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openHomeSettings(activity: Activity): Boolean = try {
        activity.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }

    // ---- open at startup ----

    private fun bootComponent(context: Context) = ComponentName(context, BootReceiver::class.java)

    fun startupEnabled(context: Context) =
        context.packageManager.getComponentEnabledSetting(bootComponent(context)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun setStartup(context: Context, on: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            bootComponent(context),
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }
}
