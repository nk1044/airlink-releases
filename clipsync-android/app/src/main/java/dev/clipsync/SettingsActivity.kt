package dev.clipsync

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.StatusBarManager
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/** The only screen: password, PC address, Bluetooth fallback, and one-time HyperOS setup. */
class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var password: EditText
    private lateinit var host: EditText
    private lateinit var bluetooth: Switch
    private lateinit var btDevice: Button
    private lateinit var saveImages: Switch
    private lateinit var paused: Switch
    private lateinit var save: Button
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        fun label(text: String, top: Int = 16) = TextView(this).apply {
            this.text = text
            setPadding(0, dp(top), 0, dp(4))
            root.addView(this)
        }
        fun heading(text: String) = label(text, 28).apply {
            setTypeface(typeface, Typeface.BOLD)
            textSize = 16f
        }
        fun button(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { onClick() }
            root.addView(this)
        }
        fun switch(text: String, checked: Boolean) = Switch(this).apply {
            this.text = text
            isChecked = checked
            setPadding(0, dp(12), 0, dp(12))
            root.addView(this)
        }

        TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            root.addView(this)
        }
        status = label("")

        label("Password (same as on the PC)")
        password = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = if (prefs.master != null) "(unchanged)" else "12 or more characters"
            root.addView(this)
        }
        label("PC address")
        host = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "Automatic"
            setText(prefs.manualHost)
            root.addView(this)
        }

        bluetooth = switch("Bluetooth fallback (text and images)", prefs.btEnabled)
        btDevice = button("") { chooseBluetoothPc() }
        bluetooth.setOnCheckedChangeListener { _, on ->
            if (on && prefs.btAddress.isEmpty()) chooseBluetoothPc()
            updateBluetoothRow()
        }
        updateBluetoothRow()
        saveImages = switch("Save received images to Pictures/ClipSync", prefs.saveImages)
        paused = switch("Pause sync", prefs.paused)
        save = button("Save") { save() }

        heading("One-time setup")
        button("Add tile to Control Center") { addTile() }
        button("Battery: allow in background") { allowBackground() }
        button("Autostart (HyperOS)") { openAutostart() }
        label(
            "HyperOS also needs: App info → Battery saver → No restrictions. " +
                "If HyperOS asks about clipboard access, choose Always allow.\n\n" +
                "Tap the tile to send this phone's clipboard to the PC. What you copy on the PC arrives here " +
                "automatically. To send files or photos, use Share → Send to PC.",
        )
        button("Open App info") { openAppInfo() }

        val scroll = ScrollView(this).apply { addView(root) }
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        if (prefs.master != null) SyncService.start(this)
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun updateStatus() {
        status.text = when (SyncService.state) {
            SyncService.STATE_WIFI -> "Connected to ${SyncService.peer} over Wi-Fi"
            SyncService.STATE_BT -> "Connected to ${SyncService.peer} over Bluetooth"
            SyncService.STATE_PAUSED -> "Paused"
            SyncService.STATE_SETUP -> "Enter the password from the PC to start"
            SyncService.STATE_BAD_PASSWORD -> "Wrong password: it must match the PC"
            else -> if (SyncService.isRunning) "Looking for your PC…" else "Not running"
        }
    }

    private fun updateBluetoothRow() {
        btDevice.visibility = if (bluetooth.isChecked) View.VISIBLE else View.GONE
        btDevice.text = if (prefs.btName.isEmpty()) "Choose your PC (pair it in Bluetooth settings first)" else "PC: ${prefs.btName}"
    }

    private fun save() {
        val pw = password.text.toString()
        if (pw.isEmpty() && prefs.master == null) {
            toast("Enter the password you set on the PC")
            return
        }
        if (pw.isNotEmpty() && pw.length < 12) {
            AlertDialog.Builder(this)
                .setMessage("Short passwords can be guessed by someone on your Wi-Fi. Use it anyway?")
                .setPositiveButton("Use it") { _, _ -> applySettings(pw) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        applySettings(pw)
    }

    private fun applySettings(pw: String) {
        prefs.manualHost = host.text.toString()
        prefs.btEnabled = bluetooth.isChecked
        prefs.saveImages = saveImages.isChecked
        prefs.paused = paused.isChecked
        if (pw.isEmpty()) {
            toast("Saved")
            SyncService.reconfigure(this)
            return
        }
        save.isEnabled = false
        save.text = "Saving…"
        thread {
            val master = Proto.deriveMaster(pw) // about a second
            prefs.master = master
            runOnUiThread {
                password.text.clear()
                password.hint = "(unchanged)"
                save.isEnabled = true
                save.text = "Save"
                toast("Saved")
                SyncService.reconfigure(this)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun chooseBluetoothPc() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BLUETOOTH)
            return
        }
        val devices = getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices?.toList().orEmpty()
        if (devices.isEmpty()) {
            toast("Pair this phone with your PC in Bluetooth settings first")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Choose your PC")
            .setItems(devices.map { it.name ?: it.address }.toTypedArray()) { _, i ->
                prefs.btAddress = devices[i].address
                prefs.btName = devices[i].name ?: devices[i].address
                updateBluetoothRow()
            }
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_BLUETOOTH) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            chooseBluetoothPc()
        } else {
            bluetooth.isChecked = false
            toast("Bluetooth fallback needs the Nearby devices permission")
        }
    }

    private fun addTile() {
        val sbm = getSystemService(StatusBarManager::class.java)
        sbm.requestAddTileService(
            ComponentName(this, ClipTileService::class.java),
            getString(R.string.tile_label),
            Icon.createWithResource(this, R.drawable.ic_tile),
            mainExecutor,
        ) { result ->
            val added = result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
            toast(if (added) "The ClipSync tile is in Control Center" else "Add it by hand: Control Center → edit → ClipSync")
        }
    }

    @SuppressLint("BatteryLife")
    private fun allowBackground() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("Already allowed. On HyperOS also set Battery saver → No restrictions in App info.")
            return
        }
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    private fun openAutostart() {
        val miui = Intent().setComponent(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        )
        try {
            startActivity(miui)
        } catch (e: Exception) {
            toast("Turn on Autostart in App info")
            openAppInfo()
        }
    }

    private fun openAppInfo() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private companion object {
        const val REQ_BLUETOOTH = 1
    }
}
