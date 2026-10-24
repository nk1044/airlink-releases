package dev.clipsync

import android.net.LinkProperties
import android.net.Network
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Finds the PC on the Wi-Fi network with an authenticated UDP broadcast (see plan.md). */
object Discovery {
    class Found(val address: InetAddress, val port: Int, val name: String)

    fun find(master: ByteArray, network: Network?, link: LinkProperties?, timeoutMs: Int = 1500): Found? {
        val nonce = Proto.random(16)
        val ask = Proto.hmac(master, "ClipSync discover".toByteArray(), nonce).copyOf(16)
        val packet = "CSYD".toByteArray() + nonce + ask
        DatagramSocket().use { s ->
            runCatching { network?.bindSocket(s) }
            s.broadcast = true
            s.soTimeout = timeoutMs
            for (addr in broadcastAddresses(link)) {
                runCatching { s.send(DatagramPacket(packet, packet.size, addr, Proto.UDP_PORT)) }
            }
            val buf = ByteArray(128)
            while (true) {
                val p = DatagramPacket(buf, buf.size)
                try {
                    s.receive(p)
                } catch (e: SocketTimeoutException) {
                    return null
                }
                if (p.length < 38 || String(buf, 0, 4, Charsets.US_ASCII) != "CSYA" ||
                    !buf.copyOfRange(4, 20).contentEquals(nonce)) continue
                val expect = Proto.hmac(master, "ClipSync answer".toByteArray(), nonce, buf.copyOfRange(20, 22)).copyOf(16)
                if (!MessageDigest.isEqual(expect, buf.copyOfRange(22, 38))) continue
                return Found(p.address, Proto.readBe16(buf, 20), String(buf, 38, p.length - 38, Charsets.UTF_8))
            }
        }
        @Suppress("UNREACHABLE_CODE")
        return null
    }

    private fun broadcastAddresses(link: LinkProperties?): List<InetAddress> {
        val out = mutableListOf(InetAddress.getByAddress(byteArrayOf(-1, -1, -1, -1)))
        link?.linkAddresses?.forEach { la ->
            val a = la.address
            if (a is Inet4Address && la.prefixLength in 1..30) {
                val ip = ByteBuffer.wrap(a.address).int
                val mask = -1 shl (32 - la.prefixLength)
                out += InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(ip or mask.inv()).array())
            }
        }
        return out
    }
}
