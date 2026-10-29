package dev.clipsync

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Finds the PC on a local network with a UDP broadcast (see plan.md). */
object Discovery {
    class Found(val address: InetAddress, val port: Int, val name: String)

    /**
     * A network to look for the PC on: the Wi-Fi network the phone is connected to, or a network the phone itself
     * provides (hotspot, USB or Bluetooth tethering), identified by the phone's own address on it.
     */
    class Lan(
        val network: Network?,
        val local: Inet4Address?,
        val prefix: Int,
        val broadcasts: List<InetAddress>,
    ) {
        val isHotspot get() = local != null

        fun contains(ip: String): Boolean {
            val l = local ?: return false
            val a = runCatching { InetAddress.getByName(ip) }.getOrNull() as? Inet4Address ?: return false
            val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
            return (ByteBuffer.wrap(a.address).int and mask) == (ByteBuffer.wrap(l.address).int and mask)
        }
    }

    private val ALL = InetAddress.getByAddress(byteArrayOf(-1, -1, -1, -1))
    private val TETHER = listOf("swlan", "softap", "ap", "wlan", "rndis", "usb", "ncm", "bt-pan")

    /** Wi-Fi first, then this phone's hotspot/tethering networks. Cheap: no network traffic. */
    fun lans(cm: ConnectivityManager, wifi: Network?): List<Lan> {
        val out = mutableListOf<Lan>()
        val wifiLink = wifi?.let { cm.getLinkProperties(it) }
        if (wifi != null) out += Lan(wifi, null, 0, broadcastAddresses(wifiLink))
        val wifiIface = wifiLink?.interfaceName
        runCatching {
            for (ni in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
                if (!ni.isUp || ni.isLoopback || ni.name == wifiIface || TETHER.none { ni.name.startsWith(it) }) continue
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address as? Inet4Address ?: continue
                    val prefix = ia.networkPrefixLength.toInt()
                    if (prefix !in 8..30) continue
                    out += Lan(null, a, prefix, listOfNotNull(ia.broadcast ?: broadcastOf(a, prefix), ALL))
                }
            }
        }
        return out
    }

    /** Authenticated query ("CSYD"/"CSYA"): only our own PC answers. */
    fun find(master: ByteArray, lan: Lan, timeoutMs: Int = 1500): Found? {
        val nonce = Proto.random(16)
        val ask = Proto.hmac(master, "ClipSync discover".toByteArray(), nonce).copyOf(16)
        return query(lan, "CSYD".toByteArray() + nonce + ask, timeoutMs) { buf, len, from ->
            if (len < 38 || String(buf, 0, 4, Charsets.US_ASCII) != "CSYA" || !buf.copyOfRange(4, 20).contentEquals(nonce)) {
                null
            } else {
                val expect = Proto.hmac(master, "ClipSync answer".toByteArray(), nonce, buf.copyOfRange(20, 22)).copyOf(16)
                if (!MessageDigest.isEqual(expect, buf.copyOfRange(22, 38))) null
                else Found(from, Proto.readBe16(buf, 20), String(buf, 38, len - 38, Charsets.UTF_8))
            }
        }
    }

    /** "Who's ready to link?" ("CSYQ"/"CSYR"): answered only by a PC whose "Link a phone" window is open. */
    fun findLinkable(lan: Lan, timeoutMs: Int = 1500): Found? =
        query(lan, "CSYQ".toByteArray(), timeoutMs) { buf, len, from ->
            if (len < 6 || String(buf, 0, 4, Charsets.US_ASCII) != "CSYR") null
            else Found(from, Proto.readBe16(buf, 4), String(buf, 6, len - 6, Charsets.UTF_8))
        }

    private fun <T> query(lan: Lan, packet: ByteArray, timeoutMs: Int, parse: (ByteArray, Int, InetAddress) -> T?): T? {
        DatagramSocket(null).use { s ->
            if (lan.local != null) s.bind(InetSocketAddress(lan.local, 0))
            else {
                s.bind(InetSocketAddress(0))
                runCatching { lan.network?.bindSocket(s) }
            }
            s.broadcast = true
            s.soTimeout = timeoutMs
            for (addr in lan.broadcasts) runCatching { s.send(DatagramPacket(packet, packet.size, addr, Proto.UDP_PORT)) }
            val buf = ByteArray(128)
            while (true) {
                val p = DatagramPacket(buf, buf.size)
                try {
                    s.receive(p)
                } catch (e: SocketTimeoutException) {
                    return null
                }
                parse(buf, p.length, p.address)?.let { return it }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        return null
    }

    private fun broadcastOf(a: Inet4Address, prefix: Int): InetAddress {
        val ip = ByteBuffer.wrap(a.address).int
        val mask = -1 shl (32 - prefix)
        return InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(ip or mask.inv()).array())
    }

    private fun broadcastAddresses(link: LinkProperties?): List<InetAddress> {
        val out = mutableListOf(ALL)
        link?.linkAddresses?.forEach { la ->
            val a = la.address
            if (a is Inet4Address && la.prefixLength in 1..30) out += broadcastOf(a, la.prefixLength)
        }
        return out
    }
}
