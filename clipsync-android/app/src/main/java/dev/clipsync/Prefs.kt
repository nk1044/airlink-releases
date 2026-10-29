package dev.clipsync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("clipsync", Context.MODE_PRIVATE)
    @Volatile private var cachedMaster: ByteArray? = null

    /** Key derived from the password; stored encrypted with an Android Keystore key. */
    var master: ByteArray?
        get() {
            cachedMaster?.let { return it }
            val stored = sp.getString("master", null) ?: return null
            return runCatching { KeyWrap.decrypt(stored) }.getOrNull()?.also { cachedMaster = it }
        }
        set(v) {
            cachedMaster = v
            sp.edit().putString("master", v?.let { KeyWrap.encrypt(it) }).apply()
        }

    /** Optional "ip" or "ip:port" typed by the user; empty means automatic. */
    var manualHost: String
        get() = sp.getString("manual_host", "")!!
        set(v) = sp.edit().putString("manual_host", v.trim()).apply()

    val lastHost: String get() = sp.getString("last_host", "")!!
    val lastPort: Int get() = sp.getInt("last_port", Proto.TCP_PORT)

    fun saveLast(host: String, port: Int) = sp.edit().putString("last_host", host).putInt("last_port", port).apply()

    var btEnabled: Boolean
        get() = sp.getBoolean("bt_enabled", true)
        set(v) = sp.edit().putBoolean("bt_enabled", v).apply()

    var tileAdded: Boolean
        get() = sp.getBoolean("tile_added", false)
        set(v) = sp.edit().putBoolean("tile_added", v).apply()

    /** "14:32 · Wi-Fi · why · after 180 MB received", for the Battery & data card. */
    var lastDisconnect: String
        get() = sp.getString("last_disconnect", "")!!
        set(v) = sp.edit().putString("last_disconnect", v).apply()

    var autostartVisited: Boolean
        get() = sp.getBoolean("autostart_visited", false)
        set(v) = sp.edit().putBoolean("autostart_visited", v).apply()

    var btAddress: String
        get() = sp.getString("bt_address", "")!!
        set(v) = sp.edit().putString("bt_address", v).apply()

    var btName: String
        get() = sp.getString("bt_name", "")!!
        set(v) = sp.edit().putString("bt_name", v).apply()

    var saveImages: Boolean
        get() = sp.getBoolean("save_images", true)
        set(v) = sp.edit().putBoolean("save_images", v).apply()

    var paused: Boolean
        get() = sp.getBoolean("paused", false)
        set(v) = sp.edit().putBoolean("paused", v).apply()

    fun reload() {
        cachedMaster = null
    }
}

private object KeyWrap {
    private const val ALIAS = "clipsync-wrap"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun encrypt(data: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(c.iv + c.doFinal(data), Base64.NO_WRAP)
    }

    fun decrypt(s: String): ByteArray {
        val b = Base64.decode(s, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12))
        return c.doFinal(b, 12, b.size - 12)
    }
}
