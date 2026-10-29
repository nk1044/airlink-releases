package dev.clipsync

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement

/**
 * Links this phone with a PC whose "Link a phone" window is open: ECDH P-256 plus a 6-digit code the user compares
 * on both screens. The result is a random 256-bit key both sides keep for good (protocol in plan.md).
 * [run] blocks: call it off the main thread.
 */
class Linking(private val context: Context) {
    sealed class Result {
        class Linked(val pc: String) : Result()
        object NotFound : Result()
        object Declined : Result()
        class Failed(val reason: String) : Result()
    }

    private val prefs = Prefs(context)
    private val keys: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val pub = keys.public as ECPublicKey
    private val pubBytes = fixed32(pub.w.affineX) + fixed32(pub.w.affineY)
    private val nonce = Proto.random(16)
    private val commit = Proto.sha256("ClipSync commit".toByteArray(), pubBytes, nonce)
    @Volatile private var current: Closeable? = null
    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
        runCatching { current?.close() }
    }

    fun run(showCode: (pc: String, code: String) -> Unit): Result = try {
        findAndLink(showCode)
    } catch (e: Exception) {
        if (cancelled) Result.Failed("Cancelled") else Result.Failed("The connection dropped. Try again.")
    } finally {
        runCatching { current?.close() }
    }

    private fun findAndLink(showCode: (String, String) -> Unit): Result {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val manual = prefs.manualHost
        if (manual.isNotEmpty()) {
            val host = manual.substringBefore(':')
            val port = manual.substringAfter(':', "").toIntOrNull() ?: Proto.TCP_PORT
            overTcp(null, host, port, host, showCode)?.let { return it }
        }
        // Wi-Fi, and this phone's own hotspot: ask "who's ready to link?"
        for (lan in Discovery.lans(cm, wifiNetwork(cm))) {
            if (cancelled) return Result.Failed("Cancelled")
            val found = runCatching { Discovery.findLinkable(lan) }.getOrNull() ?: continue
            val host = found.address.hostAddress ?: continue
            overTcp(lan.network, host, found.port, found.name, showCode)?.let {
                if (it is Result.Linked && manual.isEmpty()) prefs.saveLast(host, found.port)
                return it
            }
        }
        // Bluetooth: paired computers. One whose window isn't open just hangs up.
        overBluetooth(showCode)?.let { return it }
        return Result.NotFound
    }

    private fun overTcp(net: Network?, host: String, port: Int, name: String, showCode: (String, String) -> Unit): Result? {
        val s = Socket()
        current = s
        try {
            net?.let { n -> runCatching { n.bindSocket(s) } }
            s.connect(InetSocketAddress(host, port), 3000)
            s.soTimeout = 15_000
        } catch (e: IOException) {
            runCatching { s.close() }
            return null
        }
        return exchange(s.getInputStream(), s.getOutputStream(), name, { s.soTimeout = it }, showCode).also { s.close() }
    }

    @SuppressLint("MissingPermission")
    private fun overBluetooth(showCode: (String, String) -> Unit): Result? {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return null
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled } ?: return null
        for (device in adapter.bondedDevices.orEmpty().filter { SyncService.mightBePc(it) }) {
            if (cancelled) return Result.Failed("Cancelled")
            val s = runCatching { device.createRfcommSocketToServiceRecord(Proto.BT_UUID) }.getOrNull() ?: continue
            current = s
            try {
                s.connect()
            } catch (e: IOException) {
                runCatching { s.close() }
                continue
            }
            val r = exchange(s.inputStream, s.outputStream, device.name ?: "PC", {}, showCode)
            runCatching { s.close() }
            if (r != null) {
                if (r is Result.Linked) prefs.btAddress = device.address
                return r
            }
        }
        return null
    }

    /** Returns null if this PC isn't in linking mode (it hangs up right away). */
    private fun exchange(
        rawIn: InputStream,
        out: OutputStream,
        pcName: String,
        setTimeout: (Int) -> Unit,
        showCode: (String, String) -> Unit,
    ): Result? {
        val input = DataInputStream(BufferedInputStream(rawIn))
        out.write("CSYP".toByteArray())
        out.write(commit)
        out.flush()
        val pkPc = ByteArray(64)
        try {
            input.readFully(pkPc)
        } catch (e: IOException) {
            return null
        }
        val name = Proto.utf8(deviceName(), 64)
        out.write(pubBytes)
        out.write(nonce)
        out.write(name.size)
        out.write(name)
        out.flush()

        val z = sharedSecret(pkPc)
        val t = Proto.sha256("ClipSync pair".toByteArray(), pubBytes, pkPc, nonce)
        val key = Proto.hmac(z, "ClipSync key".toByteArray(), t)
        val sas = ((t[0].toLong() and 0xFF) shl 24 or ((t[1].toLong() and 0xFF) shl 16) or
            ((t[2].toLong() and 0xFF) shl 8) or (t[3].toLong() and 0xFF)) % 1_000_000
        showCode(pcName, "%03d %03d".format(sas / 1000, sas % 1000))

        setTimeout(150_000) // the user compares codes and clicks Link on the PC
        if (input.read() != 1) return Result.Declined
        val proof = ByteArray(16).also { input.readFully(it) }
        if (!MessageDigest.isEqual(proof, Proto.hmac(key, "ClipSync paired".toByteArray()).copyOf(16))) {
            return Result.Failed("The PC's answer didn't check out. Try again.")
        }
        val nl = input.read()
        if (nl < 0) throw IOException("closed")
        val pc = ByteArray(nl).also { input.readFully(it) }.toString(Charsets.UTF_8).ifEmpty { pcName }
        out.write("OK".toByteArray())
        out.write(Proto.hmac(key, "ClipSync done".toByteArray()).copyOf(16))
        out.flush()

        prefs.master = key
        prefs.btName = pc
        return Result.Linked(pc)
    }

    private fun sharedSecret(pkPc: ByteArray): ByteArray {
        val point = ECPoint(BigInteger(1, pkPc.copyOfRange(0, 32)), BigInteger(1, pkPc.copyOfRange(32, 64)))
        val peer = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, pub.params))
        return KeyAgreement.getInstance("ECDH").run {
            init(keys.private)
            doPhase(peer, true)
            generateSecret()
        }
    }

    private fun deviceName(): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL

    @Suppress("DEPRECATION")
    private fun wifiNetwork(cm: ConnectivityManager): Network? = cm.allNetworks.firstOrNull { n ->
        cm.getNetworkCapabilities(n)?.let {
            it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } == true
    }

    private fun fixed32(v: BigInteger): ByteArray {
        val a = v.toByteArray()
        return when {
            a.size == 32 -> a
            a.size > 32 -> a.copyOfRange(a.size - 32, a.size)
            else -> ByteArray(32 - a.size) + a
        }
    }
}
