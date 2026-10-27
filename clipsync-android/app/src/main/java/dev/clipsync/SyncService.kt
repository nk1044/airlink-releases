package dev.clipsync

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Keeps one connection to the PC, over Wi-Fi or Bluetooth (whichever works), and moves clipboard items and files.
 *
 *   link thread    connects (Wi-Fi first, or Bluetooth first on a Wi-Fi network where the PC wasn't reachable),
 *                  then reads frames until the connection drops
 *   send thread    the only writer; text and images go ahead of file chunks
 *   probe thread   only while on Bluetooth: moves back to Wi-Fi when the PC is reachable there
 *   hello thread   UDP: the PC says "I'm back" (startup, wake from sleep, network change)
 *   wake thread    Bluetooth: the PC connects to the phone's wake service for the same reason
 *
 * Reconnects are driven by events (Wi-Fi up, Bluetooth on, PC hello/wake, tile tap); the timed retry backs off
 * to 5 minutes so a PC that's switched off costs little battery.
 *
 * Runs as a connectedDevice foreground service. The app never asks for notification permission, so on
 * Android 13+ its notification stays out of the notification shade.
 */
class SyncService : Service() {

    companion object {
        const val STATE_OFF = 0
        const val STATE_WIFI = 1
        const val STATE_BT = 2
        const val STATE_PAUSED = 3
        const val STATE_SETUP = 4
        const val STATE_BAD_PASSWORD = 5

        @Volatile var state = STATE_OFF
            private set
        @Volatile var peer = ""
            private set
        /** Connected through this phone's own hotspot/tethering. */
        @Volatile var viaHotspot = false
            private set
        /** A file transfer in progress, or null. */
        @Volatile var progress: Progress? = null
            private set
        val isRunning get() = instance != null

        class Progress(val sending: Boolean, val percent: Int, val file: Int, val files: Int)

        /** What ClipSync has cost since its process started: the inputs for the "Battery & data" panel. */
        class Stats(
            val sinceWallMs: Long,
            val elapsedMs: Long,
            val cpuMs: Long,
            val wifiMs: Long,
            val btMs: Long,
            val connects: Int,
            val bytes: Long,
            val wakeups: Long,
        )

        fun stats(): Stats? = instance?.snapshotStats()

        @Volatile private var instance: SyncService? = null
        private val pendingText = ConcurrentLinkedQueue<String>()

        private const val TAG = "ClipSync"
        private const val CHANNEL = "link"
        private const val ACTION_SEND_TEXT = "dev.clipsync.SEND_TEXT"
        private const val ACTION_SEND_URIS = "dev.clipsync.SEND_URIS"
        private const val ACTION_RECONFIGURE = "dev.clipsync.RECONFIGURE"
        private const val EXTRA_AS_IMAGE = "as_image"
        // System broadcasts without public constants.
        private const val ACTION_HOTSPOT_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        private const val ACTION_TETHER_CHANGED = "android.net.conn.TETHER_STATE_CHANGED"

        private const val MIN_BACKOFF = 3_000L
        private const val MAX_BACKOFF = 5 * 60_000L
        private const val PROBE_MIN = 60_000L
        private const val PROBE_MAX = 15 * 60_000L
        private const val RETRY_WINDOW = 30_000L

        fun start(context: Context, intent: Intent? = null): Boolean {
            val i = (intent ?: Intent()).setClass(context, SyncService::class.java)
            return try {
                context.startForegroundService(i)
                true
            } catch (e: Exception) {
                Log.w(TAG, "can't start service", e)
                false
            }
        }

        /** Text goes through memory, not the Intent, so large clips don't hit the Binder size limit. */
        fun sendText(context: Context, text: String) {
            pendingText.add(text)
            start(context, Intent(ACTION_SEND_TEXT))
        }

        fun sendUris(context: Context, uris: List<Uri>, asImage: Boolean) {
            val clip = ClipData.newRawUri("", uris[0])
            for (u in uris.drop(1)) clip.addItem(ClipData.Item(u))
            val intent = Intent(ACTION_SEND_URIS).putExtra(EXTRA_AS_IMAGE, asImage)
            intent.clipData = clip
            // Pass the read grant on to the service; fall back without it if the system refuses.
            if (!start(context, Intent(intent).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))) start(context, intent)
        }

        fun reconfigure(context: Context) {
            start(context, Intent(ACTION_RECONFIGURE))
        }

        /** Paired devices worth trying as the PC: computers, plus devices that don't say what they are. */
        @SuppressLint("MissingPermission")
        fun mightBePc(d: BluetoothDevice): Boolean = when (d.bluetoothClass?.majorDeviceClass) {
            null, BluetoothClass.Device.Major.COMPUTER, BluetoothClass.Device.Major.UNCATEGORIZED,
            BluetoothClass.Device.Major.MISC -> true
            else -> false
        }
    }

    private class Out(val type: Int, val data: ByteArray? = null, val imageUri: Uri? = null)

    private lateinit var prefs: Prefs
    private lateinit var connectivity: ConnectivityManager
    private lateinit var wakeLock: PowerManager.WakeLock
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var running = true
    @Volatile private var conn: Conn? = null
    @Volatile private var pendingConn: Conn? = null
    @Volatile private var wifi: Network? = null
    @Volatile private var wifiFailedOn: Network? = null
    @Volatile private var badPassword = false
    @Volatile private var retry: Out? = null
    @Volatile private var retryUntil = 0L
    @Volatile private var helloSocket: DatagramSocket? = null
    @Volatile private var wakeServer: BluetoothServerSocket? = null
    private val switchLock = Any()

    // Battery & data counters; finished connections are added in when they end.
    @Volatile private var wifiMs = 0L
    @Volatile private var btMs = 0L
    @Volatile private var connects = 0
    @Volatile private var bytesDone = 0L
    @Volatile private var wakeupsDone = 0L
    @Volatile private var connectedAt = 0L
    @Volatile private var lastProgressPush = 0L

    private val linkSignal = Semaphore(0)
    private val probeSignal = Semaphore(0)
    private val sendSignal = Semaphore(0)
    private val btSignal = Semaphore(0)
    private val outQueue = ConcurrentLinkedQueue<Out>()
    private val fileJobs = ConcurrentLinkedQueue<List<Uri>>()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            wifi = network
            linkSignal.release()
            probeSignal.release()
        }

        override fun onLost(network: Network) {
            if (wifi == network) {
                wifi = null
                conn?.takeIf { it.kind == Proto.LINK_WIFI && !it.viaHotspot }?.close()
            }
            linkSignal.release()
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_ON -> {
                        btSignal.release()
                        linkSignal.release()
                    }
                    BluetoothAdapter.STATE_TURNING_OFF -> {
                        conn?.takeIf { it.kind == Proto.LINK_BT }?.close()
                        runCatching { wakeServer?.close() }
                    }
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    if (conn == null && d != null && d.address == prefs.btAddress) linkSignal.release()
                }
                ACTION_HOTSPOT_CHANGED, ACTION_TETHER_CHANGED -> {
                    // Hotspot turned on or off: look for the PC there now, and check a hotspot link still works.
                    if (conn?.viaHotspot == true) nudge() else linkSignal.release()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        connectivity = getSystemService(ConnectivityManager::class.java)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ClipSync:transfer")
            .apply { setReferenceCounted(false) }
        if (!enterForeground()) {
            stopSelf()
            return
        }
        instance = this
        val wifiRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // a router without internet is fine
            .build()
        connectivity.registerNetworkCallback(wifiRequest, networkCallback)
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(ACTION_HOTSPOT_CHANGED)
            addAction(ACTION_TETHER_CHANGED)
        }
        registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        thread(name = "clipsync-link") { linkLoop() }
        thread(name = "clipsync-send") { sendLoop() }
        thread(name = "clipsync-hello") { helloLoop() }
        thread(name = "clipsync-wake") { wakeLoop() }
    }

    private fun enterForeground(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Connection to PC", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) },
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("ClipSync")
            .setOngoing(true)
            .build()
        return try {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            true
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed", e)
            false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (instance !== this) return START_NOT_STICKY // onCreate couldn't go foreground
        when (intent?.action) {
            ACTION_SEND_URIS -> {
                val clip = intent.clipData
                val uris = (0 until (clip?.itemCount ?: 0)).mapNotNull { clip!!.getItemAt(it).uri }
                if (uris.isNotEmpty()) {
                    if (intent.getBooleanExtra(EXTRA_AS_IMAGE, false)) {
                        submit(Out(Proto.T_IMAGE, imageUri = uris[0]))
                    } else {
                        fileJobs.add(uris) // sent as soon as the PC is connected
                        sendSignal.release()
                        if (conn == null) {
                            toast("Connecting to PC… the files will be sent when it connects.")
                            linkSignal.release()
                        }
                    }
                }
            }
            ACTION_RECONFIGURE -> {
                prefs.reload()
                badPassword = false
                wifiFailedOn = null
                pendingConn?.close()
                conn?.close()
                linkSignal.release()
                btSignal.release()
            }
        }
        while (true) submit(Out(Proto.T_TEXT, (pendingText.poll() ?: break).toByteArray()))
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        if (instance === this) {
            instance = null
            runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
            runCatching { unregisterReceiver(bluetoothReceiver) }
        }
        conn?.close()
        runCatching { helloSocket?.close() }
        runCatching { wakeServer?.close() }
        for (s in listOf(linkSignal, probeSignal, sendSignal, btSignal)) s.release()
        setState(STATE_OFF)
        super.onDestroy()
    }

    // ---- helpers ----

    private fun setState(s: Int) {
        if (state == s) return
        state = s
        updateTile()
    }

    private fun updateTile() {
        main.post { ClipTileService.update(this) }
    }

    /** Transfer progress for the tile and the app; the tile is refreshed at most once a second. */
    private fun setProgress(p: Progress?) {
        val old = progress
        if (p != null && old != null && p.percent == old.percent && p.file == old.file) return
        progress = p
        val now = SystemClock.elapsedRealtime()
        if (p == null || old == null || now - lastProgressPush >= 1000) {
            lastProgressPush = now
            updateTile()
        }
    }

    private fun snapshotStats(): Stats {
        val c = conn
        val now = SystemClock.elapsedRealtime()
        val live = if (c != null && connectedAt > 0) now - connectedAt else 0L
        val processStart = android.os.Process.getStartElapsedRealtime()
        return Stats(
            sinceWallMs = System.currentTimeMillis() - (now - processStart),
            elapsedMs = now - processStart,
            cpuMs = android.os.Process.getElapsedCpuTime(),
            wifiMs = wifiMs + if (c?.kind == Proto.LINK_WIFI) live else 0L,
            btMs = btMs + if (c?.kind == Proto.LINK_BT) live else 0L,
            connects = connects,
            bytes = bytesDone + (c?.let { it.bytesSent + it.bytesReceived } ?: 0L),
            wakeups = wakeupsDone + (c?.framesReceived ?: 0L),
        )
    }

    private fun toast(msg: String) = main.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }

    private fun deviceName(): String =
        Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL

    /** Waits for a signal or the timeout (ms < 0: forever). Returns true if signalled. */
    private fun waitSignal(s: Semaphore, ms: Long): Boolean {
        val got = if (ms < 0) {
            s.acquire()
            true
        } else {
            s.tryAcquire(ms, TimeUnit.MILLISECONDS)
        }
        s.drainPermits()
        return got
    }

    private fun clipboard() = getSystemService(ClipboardManager::class.java)

    /** Bluetooth adapter if the fallback is on, permitted and powered; else null. */
    private fun bluetooth(): BluetoothAdapter? {
        if (!prefs.btEnabled) return null
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return null
        return getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled }
    }

    /**
     * Something says the PC is there (hello, wake). If we're not connected, connect now. If we are, make sure the
     * link is really alive: a PC that restarted or slept leaves a dead link behind that still looks open.
     */
    private fun nudge() {
        val c = conn
        if (c == null) {
            linkSignal.release()
            return
        }
        if (c.kind == Proto.LINK_BT) probeSignal.release()
        val sentAt = SystemClock.elapsedRealtime()
        outQueue.add(Out(Proto.T_PING))
        sendSignal.release()
        main.postDelayed({ if (conn === c && c.lastRecv < sentAt) c.close() }, 4_000)
    }

    // ---- link thread ----

    private fun linkLoop() {
        var backoff = MIN_BACKOFF
        while (running) {
            val master = prefs.master
            val idle = when {
                master == null -> STATE_SETUP
                prefs.paused -> STATE_PAUSED
                badPassword -> STATE_BAD_PASSWORD
                else -> -1
            }
            if (master == null || idle >= 0) {
                setState(idle)
                waitSignal(linkSignal, -1) // until settings change
                continue
            }

            val c = try {
                pendingConn?.also { pendingConn = null }?.takeUnless { it.closed } ?: connectAny(master)
            } catch (e: WrongPasswordException) {
                badPassword = true
                toast("This phone isn't linked with that PC. Open ClipSync and tap \"Link with PC\".")
                continue
            } catch (e: Exception) {
                Log.w(TAG, "connect failed", e)
                null
            }
            if (c == null) {
                setState(STATE_OFF)
                backoff = if (waitSignal(linkSignal, backoff)) MIN_BACKOFF else minOf(backoff * 2, MAX_BACKOFF)
                continue
            }

            backoff = MIN_BACKOFF
            connects++
            connectedAt = SystemClock.elapsedRealtime()
            conn = c
            peer = c.peer
            viaHotspot = c.viaHotspot
            setState(if (c.kind == Proto.LINK_WIFI) STATE_WIFI else STATE_BT)
            updateTile()
            retry?.let {
                retry = null
                if (SystemClock.elapsedRealtime() < retryUntil) {
                    outQueue.add(it)
                    sendSignal.release()
                }
            }
            if (fileJobs.isNotEmpty()) sendSignal.release()
            if (c.kind == Proto.LINK_BT) thread(name = "clipsync-probe") { probeWifi(c, master) }
            readLoop(c)
            val lasted = SystemClock.elapsedRealtime() - connectedAt
            if (c.kind == Proto.LINK_WIFI) wifiMs += lasted else btMs += lasted
            bytesDone += c.bytesSent + c.bytesReceived
            wakeupsDone += c.framesReceived
            connectedAt = 0L
            conn = null
            setState(STATE_OFF)
            synchronized(switchLock) {} // if a Wi-Fi probe is mid-switch, let it finish and hand over
        }
    }

    /** Wi-Fi first, unless Wi-Fi already failed on this network: then Bluetooth first, for a faster connect. */
    private fun connectAny(master: ByteArray): Conn? {
        val btFirst = wifi != null && wifi == wifiFailedOn
        return if (btFirst) connectBluetooth(master) ?: tryWifi(master) else tryWifi(master) ?: connectBluetooth(master)
    }

    private fun tryWifi(master: ByteArray): Conn? {
        val net = wifi
        val c = connectWifi(master)
        if (net != null) wifiFailedOn = if (c == null) net else null
        return c
    }

    /**
     * Over the Wi-Fi network, or over this phone's own hotspot / USB tethering (then the PC is one of the phone's
     * clients). The last address that worked first, then discovery on each network.
     */
    private fun connectWifi(master: ByteArray): Conn? {
        val lans = Discovery.lans(connectivity, wifi)
        val manual = prefs.manualHost
        if (manual.isNotEmpty()) {
            val host = manual.substringBefore(':')
            val port = manual.substringAfter(':', "").toIntOrNull() ?: Proto.TCP_PORT
            val hotspot = lans.any { it.contains(host) }
            return openTcp(if (hotspot) null else wifi, host, port, master)?.also { it.viaHotspot = hotspot }
        }
        if (lans.isEmpty()) return null
        val last = prefs.lastHost
        if (last.isNotEmpty()) {
            val hotspot = lans.any { it.contains(last) }
            if (hotspot || wifi != null) {
                openTcp(if (hotspot) null else wifi, last, prefs.lastPort, master)?.let {
                    it.viaHotspot = hotspot
                    return it
                }
            }
        }
        for (lan in lans) {
            val found = runCatching { Discovery.find(master, lan) }.getOrNull() ?: continue
            val host = found.address.hostAddress ?: continue
            val c = openTcp(lan.network, host, found.port, master) ?: continue
            c.viaHotspot = lan.isHotspot
            prefs.saveLast(host, found.port)
            return c
        }
        return null
    }

    private fun openTcp(net: Network?, host: String, port: Int, master: ByteArray): Conn? {
        val s = Socket()
        try {
            net?.let { n -> runCatching { n.bindSocket(s) } }
            s.connect(InetSocketAddress(host, port), 3000)
            s.tcpNoDelay = true
            s.soTimeout = 15_000
            val c = Conn(s.getInputStream(), s.getOutputStream(), s, Proto.LINK_WIFI)
            c.handshake(master, deviceName())
            s.soTimeout = 300_000 // the PC pings every 2 minutes
            return c
        } catch (e: WrongPasswordException) {
            runCatching { s.close() }
            throw e
        } catch (e: Exception) {
            runCatching { s.close() }
            return null
        }
    }

    /** The saved PC first, then any other paired computer. The PC also tells us its address over Wi-Fi. */
    @SuppressLint("MissingPermission")
    private fun connectBluetooth(master: ByteArray): Conn? {
        val adapter = bluetooth() ?: return null
        val saved = prefs.btAddress
        val candidates = buildList {
            if (saved.isNotEmpty()) runCatching { adapter.getRemoteDevice(saved) }.getOrNull()?.let { add(it) }
            adapter.bondedDevices.orEmpty()
                .filter { it.address != saved && mightBePc(it) }
                .forEach { add(it) }
        }
        for (device in candidates) {
            val c = connectBluetoothDevice(device, master) ?: continue
            if (device.address != saved) prefs.btAddress = device.address
            prefs.btName = c.peer
            return c
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun connectBluetoothDevice(device: BluetoothDevice, master: ByteArray): Conn? {
        val socket = try {
            device.createRfcommSocketToServiceRecord(Proto.BT_UUID)
        } catch (e: Exception) {
            return null
        }
        try {
            socket.connect()
            val c = Conn(socket.inputStream, socket.outputStream, socket, Proto.LINK_BT)
            c.handshake(master, deviceName())
            return c
        } catch (e: WrongPasswordException) {
            runCatching { socket.close() }
            throw e
        } catch (e: Exception) {
            runCatching { socket.close() }
            return null
        }
    }

    /** While on Bluetooth: try Wi-Fi now and then (sooner when Wi-Fi changes or the PC says hello), then switch. */
    private fun probeWifi(bt: Conn, master: ByteArray) {
        var interval = PROBE_MIN
        while (running && conn === bt && !bt.closed) {
            if (waitSignal(probeSignal, interval)) interval = PROBE_MIN
            if (conn !== bt || bt.closed || (wifi == null && prefs.manualHost.isEmpty())) continue
            val switched = synchronized(switchLock) {
                val w = try {
                    tryWifi(master)
                } catch (e: Exception) {
                    null
                }
                if (w != null) {
                    pendingConn = w
                    bt.close()
                }
                w != null
            }
            if (switched) return
            interval = minOf(interval * 2, PROBE_MAX)
        }
    }

    private fun readLoop(c: Conn) {
        val files = FileReceiver(this) { file, percent -> setProgress(Progress(false, percent, file, 0)) }
        try {
            while (running) {
                val f = c.recv()
                wakeLock.acquire(15_000)
                handle(c, f, files)
            }
        } catch (e: Exception) {
            // connection closed
        } finally {
            files.cancel()
            if (progress?.sending == false) setProgress(null)
            c.close()
        }
    }

    private fun handle(c: Conn, f: Frame, files: FileReceiver) {
        when (f.type) {
            Proto.T_PING -> {
                outQueue.add(Out(Proto.T_PONG))
                sendSignal.release()
            }
            Proto.T_INFO -> readInfo(c, f)
            Proto.T_TEXT -> if (!prefs.paused && f.len in 1..Proto.TEXT_MAX) {
                val text = f.text()
                main.post { clipboard().setPrimaryClip(ClipData.newPlainText("ClipSync", text)) }
            }
            Proto.T_IMAGE -> if (!prefs.paused) receiveImage(f.bytes())
            else -> if (c.kind == Proto.LINK_WIFI) when (f.type) {
                Proto.T_FILE_BEGIN -> files.begin(f)
                Proto.T_FILE_DATA -> files.data(f)
                Proto.T_FILE_END -> files.end()
                Proto.T_FILE_CANCEL -> files.cancel()
                Proto.T_FILES_DONE -> files.done().let { n ->
                    setProgress(null)
                    if (n > 0) toast("Received $n file${if (n == 1) "" else "s"} in Downloads/ClipSync")
                }
            }
        }
    }

    /** TLV list from the PC. Tag 1: its Bluetooth address, so the Bluetooth fallback needs no setup. */
    private fun readInfo(c: Conn, f: Frame) {
        var i = 1
        while (i + 2 <= f.buf.size) {
            val tag = f.buf[i].toInt() and 0xFF
            val len = f.buf[i + 1].toInt() and 0xFF
            i += 2
            if (i + len > f.buf.size) break
            if (tag == 1 && len == 6) {
                val mac = (0 until 6).joinToString(":") { "%02X".format(f.buf[i + it].toInt() and 0xFF) }
                if (prefs.btAddress != mac) prefs.btAddress = mac
                prefs.btName = c.peer
                btSignal.release()
            }
            i += len
        }
    }

    /** Keeps only the latest image in the cache, served to other apps by ClipProvider. */
    private fun receiveImage(bytes: ByteArray) {
        val ext = if (Proto.isJpeg(bytes)) "jpg" else "png"
        val dir = File(cacheDir, ClipProvider.DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val name = "image-${System.currentTimeMillis()}.$ext"
        try {
            File(dir, name).writeBytes(bytes)
        } catch (e: IOException) {
            return
        }
        val uri = ClipProvider.uriFor(name)
        main.post { clipboard().setPrimaryClip(ClipData.newUri(contentResolver, "Image", uri)) }
        if (prefs.saveImages) saveToPictures(bytes, name, if (ext == "jpg") "image/jpeg" else "image/png")
    }

    private fun saveToPictures(bytes: ByteArray, name: String, mime: String) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ClipSync")
        }
        runCatching {
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        }
    }

    // ---- PC "I'm back" signals ----

    /** UDP hello from the PC ("CSYH", see plan.md): verify it, remember where the PC is, reconnect. */
    private fun helloLoop() {
        val s = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(Proto.UDP_PORT))
            }
        } catch (e: Exception) {
            Log.w(TAG, "hello socket", e)
            return
        }
        helloSocket = s
        val buf = ByteArray(128)
        while (running) {
            val p = DatagramPacket(buf, buf.size)
            try {
                s.receive(p)
            } catch (e: Exception) {
                if (running) Thread.sleep(1000)
                continue
            }
            val master = prefs.master ?: continue
            if (p.length < 38 || String(buf, 0, 4, Charsets.US_ASCII) != "CSYH") continue
            val nonce = buf.copyOfRange(4, 20)
            val port = buf.copyOfRange(20, 22)
            val expect = Proto.hmac(master, "ClipSync hello".toByteArray(), nonce, port).copyOf(16)
            if (!MessageDigest.isEqual(expect, buf.copyOfRange(22, 38))) continue
            val host = p.address.hostAddress ?: continue
            if (prefs.manualHost.isEmpty()) prefs.saveLast(host, Proto.readBe16(buf, 20))
            wifiFailedOn = null
            nudge()
        }
        runCatching { s.close() }
    }

    /** Bluetooth wake service: the PC connects and hangs up; that alone means "dial me now". */
    @SuppressLint("MissingPermission")
    private fun wakeLoop() {
        while (running) {
            val server = bluetooth()?.let {
                runCatching { it.listenUsingRfcommWithServiceRecord("ClipSync wake", Proto.BT_WAKE_UUID) }.getOrNull()
            }
            if (server == null) {
                waitSignal(btSignal, -1) // until Bluetooth is on / allowed
                continue
            }
            wakeServer = server
            try {
                while (running) {
                    val s = server.accept()
                    runCatching { s.close() }
                    nudge()
                }
            } catch (e: Exception) {
                // Bluetooth turned off or the server was closed
            }
            runCatching { server.close() }
            wakeServer = null
            if (running) Thread.sleep(2000)
        }
    }

    // ---- send thread ----

    private fun submit(o: Out) {
        when {
            prefs.paused -> toast("Sync is paused")
            badPassword -> toast("Not linked with the PC. Open ClipSync and tap \"Link with PC\".")
            conn == null -> {
                // Keep it for a moment: it goes out as soon as the link (re)connects.
                retry = o
                retryUntil = SystemClock.elapsedRealtime() + RETRY_WINDOW
                toast("Connecting to PC…")
                linkSignal.release()
            }
            else -> {
                outQueue.add(o)
                sendSignal.release()
            }
        }
    }

    private fun sendLoop() {
        while (running) {
            sendSignal.acquire()
            sendSignal.drainPermits()
            flushQueue()
            while (running && conn != null) {
                val job = fileJobs.poll() ?: break
                sendFiles(job)
                flushQueue()
            }
        }
    }

    private fun flushQueue() {
        while (true) {
            val o = outQueue.poll() ?: return
            val c = conn ?: continue
            val userItem = o.type == Proto.T_TEXT || o.type == Proto.T_IMAGE
            try {
                if (o.imageUri != null) {
                    val bytes = loadImage(o.imageUri, if (c.kind == Proto.LINK_BT) Proto.IMAGE_MAX_BT else Proto.IMAGE_MAX_WIFI)
                    if (bytes == null) {
                        toast("Couldn't read that image")
                        continue
                    }
                    c.send(Proto.T_IMAGE, bytes)
                } else {
                    c.send(o.type, o.data ?: ByteArray(0))
                }
                if (userItem) {
                    toast("Sent to PC")
                    checkAlive(c, o)
                }
            } catch (e: Exception) {
                c.close()
                if (userItem) {
                    retry = o
                    retryUntil = SystemClock.elapsedRealtime() + RETRY_WINDOW
                }
            }
        }
    }

    /**
     * A link can look open after the PC went to sleep. Ping after each item; if nothing comes back, reconnect
     * and send the item again.
     */
    private fun checkAlive(c: Conn, o: Out) {
        val sentAt = SystemClock.elapsedRealtime()
        runCatching { c.send(Proto.T_PING) }
        main.postDelayed({
            if (conn === c && c.lastRecv < sentAt) {
                retry = o
                retryUntil = SystemClock.elapsedRealtime() + RETRY_WINDOW
                c.close()
            }
        }, 5_000)
    }

    /** Reads an image; re-encodes other formats to PNG, and anything over the cap to a smaller JPEG. */
    private fun loadImage(uri: Uri, cap: Int): ByteArray? {
        val raw = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: return null
        val pngOrJpeg = Proto.isPng(raw) || Proto.isJpeg(raw)
        if (pngOrJpeg && raw.size <= cap) return raw
        val bitmap = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(raw)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull() ?: return null
        if (!pngOrJpeg) compress(bitmap, Bitmap.CompressFormat.PNG, 100).let { if (it.size <= cap) return it }
        var scale = 1.0
        repeat(8) {
            val b = if (scale < 1.0) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(16),
                    (bitmap.height * scale).toInt().coerceAtLeast(16),
                    true,
                )
            } else {
                bitmap
            }
            val jpg = compress(b, Bitmap.CompressFormat.JPEG, 85)
            if (jpg.size <= cap) return jpg
            scale *= 0.7
        }
        return null
    }

    private fun compress(b: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray =
        ByteArrayOutputStream().also { b.compress(format, quality, it) }.toByteArray()

    private fun sendFiles(uris: List<Uri>) {
        val c = conn
        if (c == null) {
            toast("PC not connected. Files weren't sent.")
            return
        }
        if (c.kind != Proto.LINK_WIFI) {
            toast("Files need Wi-Fi. Connected over Bluetooth right now.")
            return
        }
        wakeLock.acquire(60 * 60_000L)
        toast(if (uris.size == 1) "Sending file…" else "Sending ${uris.size} files…")
        val items = uris.mapNotNull { describe(it) }
        val total = items.sumOf { it.size }.coerceAtLeast(1)
        var sent = 0L
        var count = 0
        val buf = ByteArray(Proto.CHUNK)
        try {
            for ((i, item) in items.withIndex()) {
                setProgress(Progress(true, (sent * 100 / total).toInt(), i + 1, items.size))
                val ok = sendFile(c, item, buf) { n ->
                    sent += n
                    setProgress(Progress(true, (sent * 100 / total).toInt(), i + 1, items.size))
                }
                if (ok) count++
            }
            c.send(Proto.T_FILES_DONE, Proto.be16(count))
            toast("Sent $count file${if (count == 1) "" else "s"} to PC")
        } catch (e: Exception) {
            c.close()
            toast("File transfer interrupted")
        } finally {
            items.forEach { it.temp?.delete() }
            setProgress(null)
            wakeLock.release()
        }
    }

    private class Outgoing(val uri: Uri, val name: String, val size: Long, val temp: File?)

    /** Name and size of a shared item. If the provider doesn't say the size, copy it to the cache first. */
    private fun describe(uri: Uri): Outgoing? {
        var name = uri.lastPathSegment ?: "file"
        var size = -1L
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst()) {
                    if (!it.isNull(0)) name = it.getString(0)
                    if (!it.isNull(1)) size = it.getLong(1)
                }
            }
        }
        if (size >= 0) return Outgoing(uri, name, size, null)
        val t = File.createTempFile("send", null, cacheDir)
        val copied = runCatching {
            contentResolver.openInputStream(uri)?.use { i -> t.outputStream().use { i.copyTo(it) } }
        }.getOrNull()
        if (copied == null) {
            t.delete()
            return null
        }
        return Outgoing(uri, name, t.length(), t)
    }

    /** Streams one file. Returns false if it couldn't be read; throws if the connection failed. */
    private fun sendFile(c: Conn, item: Outgoing, buf: ByteArray, onBytes: (Int) -> Unit): Boolean {
        val input = (item.temp?.inputStream() ?: runCatching { contentResolver.openInputStream(item.uri) }.getOrNull())
            ?: return false
        input.use {
            val nameBytes = Proto.utf8(item.name, 1024)
            c.send(Proto.T_FILE_BEGIN, Proto.be64(item.size), Proto.be16(nameBytes.size), nameBytes)
            var left = item.size
            while (left > 0) {
                flushQueue() // let clipboard items overtake the file
                val n = try {
                    it.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                } catch (e: IOException) {
                    -1
                }
                if (n <= 0) break
                c.send(Proto.T_FILE_DATA, if (n == buf.size) buf else buf.copyOf(n))
                left -= n
                onBytes(n)
            }
            if (left > 0) {
                c.send(Proto.T_FILE_CANCEL)
                return false
            }
            c.send(Proto.T_FILE_END)
            return true
        }
    }
}
