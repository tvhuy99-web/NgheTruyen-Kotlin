package vn.nghetruyen.app.sourceplatform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import vn.nghetruyen.source.vbook.VBookConfigStore
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

 
class AndroidVBookSecretStore(context: Context) : VBookConfigStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    override fun read(extensionKey: String): Map<String, String> {
        val encoded = preferences.getString(prefKey(extensionKey), null) ?: return emptyMap()
        return runCatching { VBookConfigJson.decode(decrypt(extensionKey, encoded)) }
            .onFailure { preferences.edit().remove(prefKey(extensionKey)).apply() }
            .getOrDefault(emptyMap())
    }

    @Synchronized
    override fun write(extensionKey: String, values: Map<String, String>) {
        require(extensionKey.isNotBlank()) { "VBOOK_CONFIG_EXTENSION_KEY_REQUIRED" }
        preferences.edit().putString(prefKey(extensionKey), encrypt(extensionKey, VBookConfigJson.encode(values))).apply()
    }

    @Synchronized
    override fun clear(extensionKey: String) {
        preferences.edit().remove(prefKey(extensionKey)).apply()
    }

    private fun encrypt(extensionKey: String, value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(extensionKey.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(extensionKey: String, payload: String): String {
        val bytes = Base64.decode(payload, Base64.NO_WRAP)
        require(bytes.size > IV_BYTES) { "VBOOK_SECRET_ENCRYPTED_PAYLOAD_INVALID" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, IV_BYTES)),
        )
        cipher.updateAAD(extensionKey.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size)), Charsets.UTF_8)
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
        return "secret." + digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    companion object {
        private const val PREFERENCES = "encrypted_vbook_secrets_v1"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "vn.nghetruyen.vbook.secrets.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }
}
