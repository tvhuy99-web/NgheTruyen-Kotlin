package vn.nghetruyen.app.freesound

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class FreesoundCredentialStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun hasApiKey(): Boolean = !apiKey().isNullOrBlank()

    @Synchronized
    fun apiKey(): String? {
        val payload = preferences.getString(KEY_API, null) ?: return null
        return runCatching { decrypt(payload) }
            .onFailure { preferences.edit().remove(KEY_API).apply() }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
    }

    @Synchronized
    fun saveApiKey(value: String) {
        val clean = value.trim()
        require(clean.length in MIN_KEY_LENGTH..MAX_KEY_LENGTH) { "Khóa API Freesound không hợp lệ." }
        preferences.edit().putString(KEY_API, encrypt(clean)).apply()
    }

    @Synchronized
    fun clearApiKey() {
        preferences.edit().remove(KEY_API).apply()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(AAD)
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(payload: String): String {
        val bytes = Base64.decode(payload, Base64.NO_WRAP)
        require(bytes.size > IV_BYTES) { "Dữ liệu khóa Freesound không hợp lệ." }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, IV_BYTES)),
        )
        cipher.updateAAD(AAD)
        return String(cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size)), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    companion object {
        const val MIN_KEY_LENGTH = 8
        const val MAX_KEY_LENGTH = 4096
        private const val PREFERENCES = "encrypted_freesound_credentials_v1"
        private const val KEY_API = "api_key"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "vn.nghetruyen.freesound.credentials.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private val AAD = "vn.nghetruyen.freesound.api-key.v1".toByteArray(Charsets.UTF_8)
    }
}
