package vn.nghetruyen.app.sourceplatform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.vbook.VBookConfigStore
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypted config storage keyed by stable vBook repository/package identity. */
class AndroidVBookConfigStore(context: Context) : VBookConfigStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    override fun read(extensionKey: String): Map<String, String> {
        val encoded = preferences.getString(prefKey(extensionKey), null) ?: return emptyMap()
        return runCatching { decode(decrypt(extensionKey, encoded)) }
            .onFailure { preferences.edit().remove(prefKey(extensionKey)).apply() }
            .getOrDefault(emptyMap())
    }

    @Synchronized
    override fun write(extensionKey: String, values: Map<String, String>) {
        require(extensionKey.isNotBlank()) { "VBOOK_CONFIG_EXTENSION_KEY_REQUIRED" }
        require(values.size <= MAX_ENTRIES) { "VBOOK_CONFIG_TOO_MANY_ENTRIES" }
        val json = JsonCodec.stringify(JsonValue.Obj(values.toSortedMap().mapValuesTo(linkedMapOf()) { (_, value) ->
            require(value.length <= MAX_VALUE_LENGTH) { "VBOOK_CONFIG_VALUE_TOO_LONG" }
            JsonValue.Str(value)
        }))
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) { "VBOOK_CONFIG_PAYLOAD_TOO_LARGE" }
        preferences.edit().putString(prefKey(extensionKey), encrypt(extensionKey, json)).apply()
    }

    @Synchronized
    override fun clear(extensionKey: String) {
        preferences.edit().remove(prefKey(extensionKey)).apply()
    }

    private fun decode(json: String): Map<String, String> {
        val root = JsonCodec.parse(json, maxDepth = 8, maxNodes = MAX_ENTRIES * 4) as? JsonValue.Obj
            ?: error("VBOOK_CONFIG_OBJECT_REQUIRED")
        require(root.values.size <= MAX_ENTRIES) { "VBOOK_CONFIG_TOO_MANY_ENTRIES" }
        return root.values.mapNotNull { (key, value) ->
            (value as? JsonValue.Str)?.value?.takeIf { it.length <= MAX_VALUE_LENGTH }?.let { key to it }
        }.toMap(LinkedHashMap())
    }

    private fun encrypt(extensionKey: String, value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(extensionKey.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = ByteArray(cipher.iv.size + encrypted.size)
        cipher.iv.copyInto(payload, 0)
        encrypted.copyInto(payload, cipher.iv.size)
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(extensionKey: String, payload: String): String {
        val bytes = Base64.decode(payload, Base64.NO_WRAP)
        require(bytes.size > IV_BYTES) { "VBOOK_CONFIG_ENCRYPTED_PAYLOAD_INVALID" }
        val iv = bytes.copyOfRange(0, IV_BYTES)
        val encrypted = bytes.copyOfRange(IV_BYTES, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(extensionKey.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun prefKey(extensionKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(extensionKey.toByteArray(Charsets.UTF_8))
        return "config." + digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    companion object {
        private const val PREFERENCES = "encrypted_vbook_config_v1"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "vn.nghetruyen.vbook.config.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val MAX_ENTRIES = 512
        private const val MAX_VALUE_LENGTH = 64 * 1024
        private const val MAX_PAYLOAD_BYTES = 2 * 1024 * 1024
    }
}
