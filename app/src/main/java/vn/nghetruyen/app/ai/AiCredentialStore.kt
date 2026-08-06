package vn.nghetruyen.app.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import vn.nghetruyen.app.data.settings.AiProvider
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface AiCredentialStore {
    fun hasApiKey(provider: AiProvider): Boolean
    fun apiKey(provider: AiProvider): String?
    fun saveApiKey(provider: AiProvider, value: String)
    fun clearApiKey(provider: AiProvider)
}

class EncryptedAiCredentialStore(context: Context) : AiCredentialStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    override fun hasApiKey(provider: AiProvider): Boolean = !apiKey(provider).isNullOrBlank()

    @Synchronized
    override fun apiKey(provider: AiProvider): String? {
        migrateLegacyKeyIfNeeded()
        val payload = preferences.getString(keyName(provider), null) ?: return null
        return runCatching { decrypt(payload, provider) }
            .onFailure { preferences.edit().remove(keyName(provider)).apply() }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
    }

    @Synchronized
    override fun saveApiKey(provider: AiProvider, value: String) {
        val clean = value.trim()
        require(clean.length in 8..4096) { "API key không hợp lệ." }
        preferences.edit().putString(keyName(provider), encrypt(clean, provider)).apply()
    }

    @Synchronized
    override fun clearApiKey(provider: AiProvider) {
        preferences.edit().remove(keyName(provider)).apply()
    }

    private fun migrateLegacyKeyIfNeeded() {
        if (preferences.contains(KEY_OPENAI)) {
            if (preferences.contains(KEY_LEGACY)) preferences.edit().remove(KEY_LEGACY).apply()
            return
        }
        val legacy = preferences.getString(KEY_LEGACY, null) ?: return
        val plain = runCatching { decryptLegacy(legacy) }.getOrNull()
        val editor = preferences.edit().remove(KEY_LEGACY)
        if (!plain.isNullOrBlank()) editor.putString(KEY_OPENAI, encrypt(plain, AiProvider.OPENAI_COMPATIBLE))
        editor.apply()
    }

    private fun encrypt(value: String, provider: AiProvider): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(aad(provider))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(payload: String, provider: AiProvider): String {
        val bytes = Base64.decode(payload, Base64.NO_WRAP)
        require(bytes.size > IV_BYTES) { "Dữ liệu khóa AI không hợp lệ." }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, IV_BYTES)),
        )
        cipher.updateAAD(aad(provider))
        return String(cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size)), Charsets.UTF_8)
    }

    private fun decryptLegacy(payload: String): String {
        val bytes = Base64.decode(payload, Base64.NO_WRAP)
        require(bytes.size > IV_BYTES) { "Dữ liệu khóa AI cũ không hợp lệ." }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, IV_BYTES)),
        )
        cipher.updateAAD(LEGACY_AAD)
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

    private fun keyName(provider: AiProvider): String = when (provider) {
        AiProvider.OPENAI_COMPATIBLE -> KEY_OPENAI
        AiProvider.GEMINI -> KEY_GEMINI
    }

    private fun aad(provider: AiProvider): ByteArray =
        "vn.nghetruyen.ai.api-key.v2.${provider.name}".toByteArray(Charsets.UTF_8)

    companion object {
        private const val PREFERENCES = "encrypted_ai_credentials_v1"
        private const val KEY_LEGACY = "api_key"
        private const val KEY_OPENAI = "api_key_openai"
        private const val KEY_GEMINI = "api_key_gemini"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "vn.nghetruyen.ai.credentials.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private val LEGACY_AAD = "vn.nghetruyen.ai.api-key.v1".toByteArray(Charsets.UTF_8)
    }
}
