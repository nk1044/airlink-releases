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
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Date
import kotlin.concurrent.thread
import kotlin.math.roundToLong

/** The only screen: status, a step-by-step setup checklist, options, and what ClipSync costs in battery and data. */
class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var root: LinearLayout
    private var night = false
    private var textPrimary = Color.BLACK
    private val textSecondary get() = if (night) 0xFFA8AEB7.toInt() else 0xFF5F6368.toInt()

    private lateinit var statusCard: LinearLayout
    private lateinit var statusTitle: TextView
    private lateinit var statusHint: TextView
    private lateinit var progressLabel: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var setupToggle: TextView
    private lateinit var steps: LinearLayout
    private lateinit var stepBluetooth: Step
    private lateinit var stepLink: Step
    private lateinit var stepTile: Step
    private lateinit var stepBattery: Step
    private var stepXiaomi: Step? = null
    private lateinit var statsText: TextView
    private lateinit var host: EditText
    private lateinit var btPc: TextView
    private var stepsOpen: Boolean? = null // null: open until setup is complete

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    /** One numbered setup step: "3  Title" turns into "✓  Title" when done. */
    private inner class Step(parent: LinearLayout, private val number: Int, private val title: String) {
        private val header = parent.text("", 16f, bold = true, top = 16)
        private val detail = parent.text("", 14f, color = textSecondary, top = 2)
        private var action: (() -> Unit)? = null
        private val button = parent.button("") { action?.invoke() }
        var done = false
            private set

        fun show(done: Boolean, text: String, buttonText: String?, primary: Boolean = true, onClick: (() -> Unit)? = null) {
            this.done = done
            header.text = if (done) "✓   $title" else "$number    $title"
            header.setTextColor(if (done) GREEN else textPrimary)
            detail.text = text
            button.visibility = if (buttonText == null) View.GONE else View.VISIBLE
            if (buttonText != null) {
                button.text = buttonText
                styleButton(button, primary)
            }
            action = onClick
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        textPrimary = TextView(this).currentTextColor
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(32))
        }

        root.text(getString(R.string.app_name), 28f, bold = true, top = 8)

        statusCard = card()
        statusTitle = statusCard.text("", 19f, bold = true, top = 0)
        statusHint = statusCard.text("", 14f, color = textSecondary, top = 4)
        progressLabel = statusCard.text("", 14f, top = 12)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(ACCENT)
            statusCard.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(6) })
        }

        val setup = card()
        val setupHead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        setup.addView(setupHead)
        setupHead.addView(TextView(this).apply {
            text = "Setup"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        setupToggle = TextView(this).apply {
            textSize = 14f
            setTextColor(ACCENT)
            setPadding(dp(12), dp(8), 0, dp(8))
            setOnClickListener { stepsOpen = steps.visibility != View.VISIBLE; refresh() }
            setupHead.addView(this)
        }
        steps = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setup.addView(steps)
        stepBluetooth = Step(steps, 1, "Bluetooth")
        stepLink = Step(steps, 2, "Link with your PC")
        stepTile = Step(steps, 3, "Control Center button")
        stepBattery = Step(steps, 4, "Run in the background")
        if (isXiaomi()) stepXiaomi = Step(steps, 5, "Xiaomi / POCO: Autostart")

        card().apply {
            text("How to use", 18f, bold = true, top = 0)
            text(
                "•  Copy anything on your PC: it's on this phone a moment later.\n" +
                    "•  To send this phone's clipboard to the PC, tap the ClipSync button in Control Center.\n" +
                    "•  For photos and files: Share → Send to PC.\n" +
                    "•  ClipSync uses Wi-Fi (or your hotspot) when it can and Bluetooth when it can't, and reconnects by itself.",
                14f, color = textSecondary, top = 6,
            ).setLineSpacing(0f, 1.15f)
        }

        card().apply {
            text("Options", 18f, bold = true, top = 0)
            switch("Pause sync", prefs.paused) { prefs.paused = it; SyncService.reconfigure(this@SettingsActivity) }
            switch("Save photos from the PC to the Gallery", prefs.saveImages) { prefs.saveImages = it }
            switch("Use Bluetooth when Wi-Fi can't connect", prefs.btEnabled) {
                prefs.btEnabled = it
                SyncService.reconfigure(this@SettingsActivity)
            }
        }

        card().apply {
            text("Battery & data", 18f, bold = true, top = 0)
            statsText = text("", 14f, color = textSecondary, top = 6)
            statsText.setLineSpacing(0f, 1.15f)
            text(
                "For Android's own battery figure (in mAh), plug the phone into the PC with USB debugging on and run " +
                    "tools\\battery-report.ps1 from the ClipSync Android folder.",
                12f, color = textSecondary, top = 10,
            )
        }

        val advanced = card()
        advanced.text("Advanced", 18f, bold = true, top = 0)
        advanced.text(
            "PC address: leave empty. Only if the phone can't find your PC, type the address shown in ClipSync " +
                "settings on the PC.",
            14f, color = textSecondary, top = 6,
        )
        host = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "Automatic"
            setText(prefs.manualHost)
        }
        advanced.addView(host)
        btPc = advanced.text("", 14f, top = 12)
        advanced.button("Choose Bluetooth PC") { chooseBluetoothPc() }.also { styleButton(it, primary = false) }

        val scroll = ScrollView(this).apply {
            addView(root)
            setBackgroundColor(if (night) 0xFF0F1115.toInt() else 0xFFF3F4F7.toInt())
        }
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
        val h = host.text.toString().trim()
        if (h != prefs.manualHost) {
            prefs.manualHost = h
            SyncService.reconfigure(this)
        }
        super.onPause()
    }

    // ---- view helpers (plain framework views: nothing extra to load) ----

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(18))
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(if (night) 0xFF1B1E24.toInt() else Color.WHITE)
        }
        root.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(14) })
    }

    private fun LinearLayout.text(s: String, size: Float, bold: Boolean = false, color: Int? = null, top: Int = 6) =
        TextView(this@SettingsActivity).apply {
            text = s
            textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            if (color != null) setTextColor(color)
            setPadding(0, dp(top), 0, 0)
            this@text.addView(this)
        }

    private fun LinearLayout.button(s: String, onClick: () -> Unit) = Button(this@SettingsActivity).apply {
        text = s
        isAllCaps = false
        textSize = 15f
        setOnClickListener { onClick() }
        styleButton(this, primary = true)
        this@button.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { topMargin = dp(10) })
    }

    private fun LinearLayout.switch(s: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this@SettingsActivity).apply {
        text = s
        textSize = 15f
        isChecked = checked
        setPadding(0, dp(12), 0, dp(4))
        setOnCheckedChangeListener { _, on -> onChange(on) }
        this@switch.addView(this)
    }

    /** Pill buttons: filled blue for the next thing to do, outlined for everything else. */
    private fun styleButton(b: Button, primary: Boolean) {
        val shape = GradientDrawable().apply {
            cornerRadius = dp(22).toFloat()
            if (primary) {
                setColor(ACCENT)
            } else {
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), if (night) 0xFF3A3F47.toInt() else 0xFFD0D4DA.toInt())
            }
        }
        b.background = RippleDrawable(ColorStateList.valueOf(if (primary) 0x40FFFFFF else 0x223B82F6), shape, null)
        b.setTextColor(if (primary) Color.WHITE else ACCENT)
        b.stateListAnimator = null
        b.setPadding(dp(20), 0, dp(20), 0)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ---- state ----

    private fun refresh() {
        refreshStatus()
        refreshSteps()
        refreshStats()
        btPc.text = "Bluetooth PC: " + prefs.btName.ifEmpty { if (prefs.btAddress.isEmpty()) "found automatically" else prefs.btAddress }
    }

    private fun refreshStatus() {
        val pc = SyncService.peer
        val (title, hint, color) = when (SyncService.state) {
            SyncService.STATE_WIFI -> Triple(
                "●  Connected to $pc",
                if (SyncService.viaHotspot) "Over this phone's hotspot. Everything works." else "Over Wi-Fi. Everything works.",
                GREEN,
            )
            SyncService.STATE_BT -> Triple("●  Connected to $pc", "Over Bluetooth: text and pictures work. Files need Wi-Fi.", GREEN)
            SyncService.STATE_PAUSED -> Triple("●  Paused", "Turn off \"Pause sync\" below to continue.", ORANGE)
            SyncService.STATE_SETUP -> Triple("●  Not linked yet", "Follow the setup steps below.", ORANGE)
            SyncService.STATE_BAD_PASSWORD -> Triple(
                "●  Not linked with this PC",
                "The PC doesn't recognise this phone (maybe it linked another one). Link again in step 2.",
                RED,
            )
            else -> if (SyncService.isRunning) {
                Triple(
                    "●  Looking for your PC…",
                    "Make sure ClipSync is running on the PC (clipboard icon near the clock). It connects by itself " +
                        "on the same Wi-Fi, on this phone's hotspot, or over Bluetooth.",
                    ORANGE,
                )
            } else {
                Triple("●  Starting…", "", ORANGE)
            }
        }
        statusTitle.text = title
        statusTitle.setTextColor(color)
        statusHint.text = hint
        (statusCard.background as GradientDrawable).setColor(
            if (night) blend(0xFF1B1E24.toInt(), color, 0.14f) else blend(Color.WHITE, color, 0.10f),
        )

        val p = SyncService.progress
        val busy = p != null
        progressLabel.visibility = if (busy) View.VISIBLE else View.GONE
        progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        if (p != null) {
            progressLabel.text = if (p.sending) {
                "Sending file ${p.file} of ${p.files} to the PC · ${p.percent}%"
            } else {
                "Receiving file ${p.file} from the PC · ${p.percent}%"
            }
            progressBar.progress = p.percent
        }
    }

    private fun refreshSteps() {
        refreshBluetooth()

        val linked = prefs.master != null
        stepLink.show(
            linked,
            if (linked) {
                "Linked with ${prefs.btName.ifEmpty { "your PC" }}. They connect by themselves from now on."
            } else {
                "On the PC, right-click the ClipSync icon near the clock and choose \"Link a phone\". Then tap below."
            },
            if (linked) "Link again" else "Link with PC",
            primary = !linked,
        ) { startLinking() }

        stepTile.show(
            prefs.tileAdded,
            if (prefs.tileAdded) "Swipe down to Control Center and tap ClipSync to send this phone's clipboard to the PC."
            else "Adds a ClipSync button next to Wi-Fi and Bluetooth in Control Center.",
            if (prefs.tileAdded) null else "Add button",
        ) { addTile() }

        val unrestricted = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        stepBattery.show(
            unrestricted,
            if (unrestricted) "Allowed. ClipSync stays connected when the screen is off."
            else "Lets ClipSync stay connected when the screen is off. It uses very little battery (see below).",
            if (unrestricted) null else "Allow",
        ) { allowBackground() }

        stepXiaomi?.show(
            prefs.autostartVisited,
            "Xiaomi phones stop apps in the background unless you allow them:\n" +
                "1. Turn on Autostart for ClipSync.\n2. In App info → Battery saver, choose \"No restrictions\".",
            if (prefs.autostartVisited) "Open Autostart again" else "Open Autostart",
            primary = !prefs.autostartVisited,
        ) { openAutostart() }

        val complete = stepBluetooth.done && stepLink.done && stepTile.done && stepBattery.done && (stepXiaomi?.done ?: true)
        val open = stepsOpen ?: !complete
        steps.visibility = if (open) View.VISIBLE else View.GONE
        setupToggle.text = when {
            !complete -> ""
            open -> "Hide"
            else -> "✓ Complete · Show"
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshBluetooth() {
        if (!prefs.btEnabled) {
            stepBluetooth.show(true, "Off. Turn on \"Use Bluetooth when Wi-Fi can't connect\" in Options to use it.", null)
            return
        }
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            stepBluetooth.show(
                false,
                "Lets ClipSync connect over Bluetooth when Wi-Fi can't (for example on college or office Wi-Fi).",
                "Allow Bluetooth",
            ) { requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BLUETOOTH) }
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            stepBluetooth.show(false, "Turn on Bluetooth.", "Open Bluetooth settings") { openBluetoothSettings() }
            return
        }
        // Allowed and on: that's this step done. Pairing is a one-time tip, not a reason to untick it.
        val pairedPc = prefs.btAddress.isNotEmpty() || adapter.bondedDevices.orEmpty().any { SyncService.mightBePc(it) }
        if (!pairedPc) {
            stepBluetooth.show(
                true,
                "Allowed. One more thing: pair this phone with your PC once. On the PC open Settings → Bluetooth → " +
                    "Add device, then pick this phone.",
                "Open Bluetooth settings",
                primary = false,
            ) { openBluetoothSettings() }
            return
        }
        stepBluetooth.show(true, "Ready. Used automatically when Wi-Fi can't reach the PC.", null)
    }

    private fun refreshStats() {
        val s = SyncService.stats() ?: run {
            statsText.text = "ClipSync isn't running yet."
            return
        }
        val hours = s.elapsedMs / 3_600_000.0
        val cpuShare = if (s.elapsedMs > 0) s.cpuMs * 100.0 / s.elapsedMs else 0.0
        val perHour = if (hours > 0) (s.wakeups / hours).roundToLong() else 0L
        statsText.text = "Since ${DateFormat.getTimeFormat(this).format(Date(s.sinceWallMs))} (${duration(s.elapsedMs)})\n" +
            "Connected: Wi-Fi ${duration(s.wifiMs)} · Bluetooth ${duration(s.btMs)}\n" +
            "Processor time: ${"%.1f".format(s.cpuMs / 1000.0)} s (${"%.2f".format(cpuShare)}% of the time, " +
            "including this screen)\n" +
            "Wake-ups from the PC: ${s.wakeups} (about $perHour an hour)\n" +
            "Reconnects: ${s.connects} · Data: ${Formatter.formatShortFileSize(this, s.bytes)}" +
            prefs.lastDisconnect.let { if (it.isEmpty()) "" else "\nLast disconnect: $it" }
    }

    private fun duration(ms: Long): String {
        val min = ms / 60_000
        return when {
            min < 1 -> "under a minute"
            min < 60 -> "$min min"
            else -> "${min / 60} h ${min % 60} min"
        }
    }

    // ---- actions ----

    private fun startLinking() {
        val linking = Linking(this)
        val message = TextView(this).apply {
            textSize = 15f
            text = "Looking for your PC…\n\nOn the PC: right-click the ClipSync icon near the clock → \"Link a phone\"."
        }
        val code = TextView(this).apply {
            textSize = 40f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            setPadding(0, dp(18), 0, dp(6))
            visibility = View.GONE
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), 0)
            addView(message)
            addView(code)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Link with PC")
            .setView(box)
            .setNegativeButton("Cancel") { _, _ -> linking.cancel() }
            .setCancelable(false)
            .show()
        thread(name = "clipsync-linking") {
            val result = linking.run { pc, c ->
                runOnUiThread {
                    message.text = "Check that \"$pc\" shows this code, then click Link on the PC:"
                    code.text = c
                    code.visibility = View.VISIBLE
                }
            }
            runOnUiThread {
                if (isFinishing || !dialog.isShowing) return@runOnUiThread
                code.visibility = View.GONE
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text = "Close"
                when (result) {
                    is Linking.Result.Linked -> {
                        dialog.dismiss()
                        toast("Linked with ${result.pc}. You won't need to do this again.")
                        SyncService.reconfigure(this)
                        refresh()
                    }
                    Linking.Result.NotFound -> message.text =
                        "Couldn't find a PC that's ready to link.\n\n" +
                            "1. On the PC, right-click the ClipSync icon near the clock → \"Link a phone\".\n" +
                            "2. The phone must be on the same Wi-Fi (or the PC on this phone's hotspot), or paired " +
                            "with the PC over Bluetooth.\n" +
                            "3. Tap \"Link with PC\" again."
                    Linking.Result.Declined -> message.text = "The PC didn't accept (or its window was closed). Try again."
                    is Linking.Result.Failed -> message.text = result.reason
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_BLUETOOTH) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            SyncService.reconfigure(this)
        } else {
            toast("Without this, ClipSync can only connect over Wi-Fi")
        }
        refresh()
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
        val names = arrayOf("Find automatically") + devices.map { it.name ?: it.address }
        AlertDialog.Builder(this)
            .setTitle("Your PC")
            .setItems(names) { _, i ->
                if (i == 0) {
                    prefs.btAddress = ""
                } else {
                    prefs.btAddress = devices[i - 1].address
                    prefs.btName = devices[i - 1].name ?: devices[i - 1].address
                }
                SyncService.reconfigure(this)
                refresh()
            }
            .show()
    }

    private fun addTile() {
        getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(this, ClipTileService::class.java),
            getString(R.string.tile_label),
            Icon.createWithResource(this, R.drawable.ic_tile),
            mainExecutor,
        ) { result ->
            if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
            ) {
                prefs.tileAdded = true
                refresh()
            } else {
                toast("Add it yourself: swipe down to Control Center → tap the edit (pencil) button → drag ClipSync in")
            }
        }
    }

    @SuppressLint("BatteryLife")
    private fun allowBackground() {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    private fun openAutostart() {
        prefs.autostartVisited = true
        val miui = Intent().setComponent(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        )
        try {
            startActivity(miui)
            toast("Turn on ClipSync, then come back. Also set App info → Battery saver → No restrictions.")
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            toast("Turn on Autostart, and set Battery saver → No restrictions")
        }
    }

    private fun openBluetoothSettings() {
        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }

    private fun isXiaomi() = Build.MANUFACTURER.lowercase().let { it.contains("xiaomi") || it.contains("redmi") || it.contains("poco") }

    private fun blend(base: Int, tint: Int, amount: Float): Int {
        fun ch(shift: Int) = ((base shr shift and 0xFF) * (1 - amount) + (tint shr shift and 0xFF) * amount).toInt()
        return Color.rgb(ch(16), ch(8), ch(0))
    }

    private companion object {
        const val REQ_BLUETOOTH = 1
        val ACCENT = Color.rgb(0x3B, 0x82, 0xF6)
        val GREEN = Color.rgb(0x1E, 0x9E, 0x4A)
        val ORANGE = Color.rgb(0xE0, 0x8A, 0x00)
        val RED = Color.rgb(0xD9, 0x30, 0x25)
    }
}
