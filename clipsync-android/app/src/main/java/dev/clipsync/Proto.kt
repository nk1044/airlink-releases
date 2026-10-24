package dev.clipsync

import android.os.SystemClock
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Wire protocol v1; see plan.md. Must match the desktop proto.c. */
object Proto {
    const val TCP_PORT = 47817
    const val UDP_PORT = 47818
    val BT_UUID: UUID = UUID.fromString("c1b5d7a2-3f4e-4b8a-9d6c-2e7f1a0b5c93")

    const val T_AUTH = 0x01
    const val T_PING = 0x02
    const val T_PONG = 0x03
    const val T_TEXT = 0x10
    const val T_IMAGE = 0x11
    const val T_FILE_BEGIN = 0x20
    const val T_FILE_DATA = 0x21
    const val T_FILE_END = 0x22
    const val T_FILE_CANCEL = 0x23
    const val T_FILES_DONE = 0x24

    const val LINK_WIFI = 1
    const val LINK_BT = 2

    const val PAYLOAD_MAX = 16 * 1024 * 1024
    const val CHUNK = 65536
    const val TEXT_MAX = 1 shl 20
    const val IMAGE_MAX_WIFI = 10 shl 20
    const val IMAGE_MAX_BT = 2 shl 20

    private val rng = SecureRandom()

    fun random(n: Int) = ByteArray(n).also { rng.nextBytes(it) }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        for (p in parts) mac.update(p)
        return mac.doFinal()
    }

    /**
     * PBKDF2-HMAC-SHA256(password as UTF-8, "ClipSync/v1", 200000 iterations, 32 bytes).
     * Written out by hand so the password encoding is exactly UTF-8, like on Windows. Takes about a second.
     */
    fun deriveMaster(password: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        mac.update("ClipSync/v1".toByteArray())
        mac.update(byteArrayOf(0, 0, 0, 1))
        var u = mac.doFinal()
        val t = u.copyOf()
        repeat(200_000 - 1) {
            u = mac.doFinal(u)
            for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
        }
        return t
    }

    fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    fun be32(v: Int) = ByteArray(4) { (v ushr (24 - 8 * it)).toByte() }
    fun be64(v: Long) = ByteArray(8) { (v ushr (56 - 8 * it)).toByte() }
    fun readBe16(b: ByteArray, off: Int) = ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
    fun readBe64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    /** UTF-8 bytes of s, cut to at most max bytes without splitting a character. */
    fun utf8(s: String, max: Int): ByteArray {
        val b = s.toByteArray(Charsets.UTF_8)
        if (b.size <= max) return b
        var n = max
        while (n > 0 && (b[n].toInt() and 0xC0) == 0x80) n--
        return b.copyOf(n)
    }

    fun isPng(b: ByteArray) = b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() &&
        b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()

    fun isJpeg(b: ByteArray) = b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()
}

class WrongPasswordException : IOException("wrong password")

/** A decrypted frame. buf[0] is the type; the payload is buf[1 until buf.size]. */
class Frame(val type: Int, val buf: ByteArray) {
    val len get() = buf.size - 1
    fun bytes(): ByteArray = buf.copyOfRange(1, buf.size)
    fun text() = String(buf, 1, len, Charsets.UTF_8)
}

/**
 * One encrypted connection to the PC, over TCP or Bluetooth RFCOMM. The phone is always the client.
 * send() is called from the sender thread, recv() from the reader thread.
 */
class Conn(rawIn: InputStream, rawOut: OutputStream, private val closer: Closeable, val kind: Int) : Closeable {
    private val input = DataInputStream(BufferedInputStream(rawIn, 65536))
    private val output = BufferedOutputStream(rawOut, 65536)
    private lateinit var key: SecretKeySpec
    private val encryptor = Cipher.getInstance("AES/GCM/NoPadding")
    private val decryptor = Cipher.getInstance("AES/GCM/NoPadding")
    private var sendCounter = 0L
    private var recvCounter = 0L

    var peer = ""
        private set
    @Volatile var lastRecv = SystemClock.elapsedRealtime()
        private set
    @Volatile var closed = false
        private set

    /** Throws WrongPasswordException when the PC uses a different password. */
    fun handshake(master: ByteArray, name: String) {
        val clientRandom = Proto.random(32)
        output.write(MAGIC)
        output.write(clientRandom)
        output.flush()
        val theirs = ByteArray(36)
        input.readFully(theirs)
        if (!theirs.copyOfRange(0, 4).contentEquals(MAGIC)) throw IOException("not a ClipSync PC")
        val session = Proto.hmac(master, "ClipSync session".toByteArray(), clientRandom, theirs.copyOfRange(4, 36))
        key = SecretKeySpec(session, "AES")
        send(Proto.T_AUTH, Proto.utf8(name, 64))
        val f = recv()
        if (f.type != Proto.T_AUTH) throw IOException("unexpected frame")
        peer = String(f.buf, 1, minOf(f.len, 64), Charsets.UTF_8)
    }

    @Synchronized
    fun send(type: Int, vararg parts: ByteArray) {
        var n = 1
        for (p in parts) n += p.size
        if (n > Proto.PAYLOAD_MAX + 1) throw IOException("frame too large")
        val pt = ByteArray(n)
        pt[0] = type.toByte()
        var off = 1
        for (p in parts) {
            System.arraycopy(p, 0, pt, off, p.size)
            off += p.size
        }
        encryptor.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce(DIR_SEND, sendCounter++)))
        val ct = encryptor.doFinal(pt) // ciphertext followed by the 16-byte tag
        output.write(Proto.be32(ct.size))
        output.write(ct)
        output.flush()
    }

    fun recv(): Frame {
        val len = input.readInt()
        if (len < 17 || len > Proto.PAYLOAD_MAX + 17) throw IOException("bad frame length")
        val ct = ByteArray(len)
        input.readFully(ct)
        decryptor.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce(DIR_RECV, recvCounter++)))
        val pt = try {
            decryptor.doFinal(ct)
        } catch (e: AEADBadTagException) {
            throw WrongPasswordException()
        }
        lastRecv = SystemClock.elapsedRealtime()
        return Frame(pt[0].toInt() and 0xFF, pt)
    }

    override fun close() {
        closed = true
        runCatching { closer.close() }
    }

    private fun nonce(dir: Int, counter: Long) = ByteArray(12).also {
        it[0] = dir.toByte()
        for (i in 0 until 8) it[4 + i] = (counter ushr (56 - 8 * i)).toByte()
    }

    private companion object {
        val MAGIC = "CSY1".toByteArray()
        const val DIR_SEND = 0 // client -> server
        const val DIR_RECV = 1
    }
}
