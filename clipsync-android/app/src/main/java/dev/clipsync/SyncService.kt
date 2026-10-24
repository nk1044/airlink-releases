package dev.clipsync

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
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
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Keeps one connection to the PC (Wi-Fi first, Bluetooth as fallback) and moves clipboard items and files.
 *
 *   link thread    connects, then reads frames until the connection drops
 *   send thread    the only writer; text and images go ahead of file chunks
 *   probe thread   only while on Bluetooth: switches back to Wi-Fi when the PC is reachable again
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
        val isRunning get() = instance != null

        @Volatile private var instance: SyncService? = null
        private val pendingText = ConcurrentLinkedQueue<String>()

        private const val TAG = "ClipSync"
        private const val CHANNEL = "link"
        private const val ACTION_SEND_TEXT = "dev.clipsync.SEND_TEXT"
        private const val ACTION_SEND_URIS = "dev.clipsync.SEND_URIS"
        private const val ACTION_RECONFIGURE = "dev.clipsync.RECONFIGURE"
        private const val EXTRA_AS_IMAGE = "as_image"

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
    @Volatile private var badPassword = false
    @Volatile private var retry: Out? = null
    @Volatile private var retryUntil = 0L

    private val linkSignal = Semaphore(0)
    private val probeSignal = Semaphore(0)
    private val sendSignal = Semaphore(0)
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
                conn?.takeIf { it.kind == Proto.LINK_WIFI }?.close()
            }
            linkSignal.release()
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
        thread(name = "clipsync-link") { linkLoop() }
        thread(name = "clipsync-send") { sendLoop() }
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
                        fileJobs.add(uris)
                        sendSignal.release()
                    }
                }
            }
            ACTION_RECONFIGURE -> {
                prefs.reload()
                badPassword = false
                pendingConn?.close()
                conn?.close()
                linkSignal.release()
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
        }
        conn?.close()
        linkSignal.release()
        probeSignal.release()
        sendSignal.release()
        setState(STATE_OFF)
        super.onDestroy()
    }

    // ---- state ----

    private fun setState(s: Int) {
        if (state == s) return
        state = s
        TileService.requestListeningState(this, ComponentName(this, ClipTileService::class.java))
    }

    private fun toast(msg: String) = main.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }

    private fun deviceName(): String =
        Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL

    private fun waitSignal(s: Semaphore, ms: Long) {
        if (ms < 0) s.acquire() else s.tryAcquire(ms, TimeUnit.MILLISECONDS)
        s.drainPermits()
    }

    // ---- link thread ----

    private fun linkLoop() {
        var backoff = 5_000L
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
                pendingConn?.also { pendingConn = null } ?: connectWifi(master) ?: connectBluetooth(master)
            } catch (e: WrongPasswordException) {
                badPassword = true
                toast("Wrong password. It must match the one on the PC.")
                continue
            } catch (e: Exception) {
                Log.w(TAG, "connect failed", e)
                null
            }
            if (c == null) {
                setState(STATE_OFF)
                waitSignal(linkSignal, backoff)
                backoff = minOf(backoff * 2, 60_000L)
                continue
            }

            backoff = 5_000L
            conn = c
            peer = c.peer
            setState(if (c.kind == Proto.LINK_WIFI) STATE_WIFI else STATE_BT)
            retry?.let {
                retry = null
                if (SystemClock.elapsedRealtime() < retryUntil) {
                    outQueue.add(it)
                    sendSignal.release()
                }
            }
            if (c.kind == Proto.LINK_BT) thread(name = "clipsync-probe") { probeWifi(c, master) }
            readLoop(c)
            conn = null
            setState(STATE_OFF)
        }
    }

    private fun connectWifi(master: ByteArray): Conn? {
        val net = wifi
        val manual = prefs.manualHost
        if (manual.isNotEmpty()) {
            val host = manual.substringBefore(':')
            val port = manual.substringAfter(':', "").toIntOrNull() ?: Proto.TCP_PORT
            return openTcp(net, host, port, master)
        }
        if (net == null) return null
        val last = prefs.lastHost
        if (last.isNotEmpty()) openTcp(net, last, prefs.lastPort, master)?.let { return it }
        val found = runCatching { Discovery.find(master, net, connectivity.getLinkProperties(net)) }.getOrNull() ?: return null
        val host = found.address.hostAddress ?: return null
        return openTcp(net, host, found.port, master)?.also { prefs.saveLast(host, found.port) }
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
            s.soTimeout = 600_000 // the PC pings every 4 minutes
            return c
        } catch (e: WrongPasswordException) {
            runCatching { s.close() }
            throw e
        } catch (e: Exception) {
            runCatching { s.close() }
            return null
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectBluetooth(master: ByteArray): Conn? {
        if (!prefs.btEnabled || prefs.btAddress.isEmpty()) return null
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return null
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        if (!adapter.isEnabled) return null
        val socket = try {
            adapter.getRemoteDevice(prefs.btAddress).createRfcommSocketToServiceRecord(Proto.BT_UUID)
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

    /** While on Bluetooth, retries Wi-Fi every minute or when Wi-Fi comes back, then drops Bluetooth. */
    private fun probeWifi(bt: Conn, master: ByteArray) {
        while (running && conn === bt && !bt.closed) {
            waitSignal(probeSignal, 60_000)
            if (conn !== bt || bt.closed || wifi == null) continue
            val w = try {
                connectWifi(master)
            } catch (e: Exception) {
                null
            } ?: continue
            pendingConn = w
            bt.close()
            return
        }
    }

    private fun readLoop(c: Conn) {
        val files = FileReceiver(this)
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
            c.close()
        }
    }

    private fun handle(c: Conn, f: Frame, files: FileReceiver) {
        when (f.type) {
            Proto.T_PING -> {
                outQueue.add(Out(Proto.T_PONG))
                sendSignal.release()
            }
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
                    if (n > 0) toast("Received $n file${if (n == 1) "" else "s"} in Downloads/ClipSync")
                }
            }
        }
    }

    private fun clipboard() = getSystemService(ClipboardManager::class.java)

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

    // ---- send thread ----

    private fun submit(o: Out) {
        when {
            prefs.paused -> toast("Sync is paused")
            badPassword -> toast("Wrong password. Check ClipSync settings.")
            conn == null -> {
                // Keep it for a moment: it goes out as soon as the link (re)connects.
                retry = o
                retryUntil = SystemClock.elapsedRealtime() + 10_000
                toast("PC not connected, trying…")
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
            while (running) {
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
                if (o.type == Proto.T_TEXT || o.type == Proto.T_IMAGE) {
                    toast("Sent to PC")
                    checkAlive(c, o)
                }
            } catch (e: Exception) {
                c.close()
                if (o.type == Proto.T_TEXT || o.type == Proto.T_IMAGE) {
                    retry = o
                    retryUntil = SystemClock.elapsedRealtime() + 10_000
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
                retryUntil = SystemClock.elapsedRealtime() + 10_000
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
        var count = 0
        val buf = ByteArray(Proto.CHUNK)
        try {
            for (uri in uris) {
                if (sendFile(c, uri, buf)) count++
            }
            c.send(Proto.T_FILES_DONE, Proto.be16(count))
            toast("Sent $count file${if (count == 1) "" else "s"} to PC")
        } catch (e: Exception) {
            c.close()
            toast("File transfer interrupted")
        } finally {
            wakeLock.release()
        }
    }

    /** Streams one file. Returns false if it couldn't be read; throws if the connection failed. */
    private fun sendFile(c: Conn, uri: Uri, buf: ByteArray): Boolean {
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
        // Size unknown (some providers): copy to the cache first, since FILE_BEGIN needs it.
        var temp: File? = null
        if (size < 0) {
            val t = File.createTempFile("send", null, cacheDir)
            val copied = runCatching {
                contentResolver.openInputStream(uri)?.use { i -> t.outputStream().use { i.copyTo(it) } }
            }.getOrNull()
            if (copied == null) {
                t.delete()
                return false
            }
            size = t.length()
            temp = t
        }
        try {
            val input = (temp?.inputStream() ?: runCatching { contentResolver.openInputStream(uri) }.getOrNull()) ?: return false
            input.use {
                val nameBytes = Proto.utf8(name, 1024)
                c.send(Proto.T_FILE_BEGIN, Proto.be64(size), Proto.be16(nameBytes.size), nameBytes)
                var left = size
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
                }
                if (left > 0) {
                    c.send(Proto.T_FILE_CANCEL)
                    return false
                }
                c.send(Proto.T_FILE_END)
                return true
            }
        } finally {
            temp?.delete()
        }
    }
}
