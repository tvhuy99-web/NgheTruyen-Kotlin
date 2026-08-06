package vn.nghetruyen.app.sources

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class EncryptedSourceSessionStore(context: Context) : SourceSessionStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    override fun cookieHeader(sourceId: String): String? {
        val encrypted = preferences.getString(key(sourceId), null) ?: return null
        return runCatching { decrypt(sourceId, encrypted) }
            .onFailure { preferences.edit().remove(key(sourceId)).apply() }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
    }

    @Synchronized
    override fun replaceCookieHeader(sourceId: String, cookieHeader: String) {
        val normalized = CookieHeaderCodec.normalize(cookieHeader)
        if (normalized.isBlank()) {
            clear(sourceId)
            return
        }
        preferences.edit().putString(key(sourceId), encrypt(sourceId, normalized)).apply()
    }

    @Synchronized
    override fun mergeSetCookieHeaders(sourceId: String, setCookieHeaders: List<String>) {
        replaceCookieHeader(
            sourceId,
            CookieHeaderCodec.merge(cookieHeader(sourceId).orEmpty(), setCookieHeaders),
        )
    }

    @Synchronized
    override fun clear(sourceId: String) {
        preferences.edit().remove(key(sourceId)).apply()
    }

    private fun encrypt(sourceId: String, value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(sourceId.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = ByteArray(cipher.iv.size + encrypted.size)
        cipher.iv.copyInto(payload, 0)
        encrypted.copyInto(payload, cipher.iv.size)
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(sourceId: String, payload: String): String {
        val bytes = Base64.decode(payload, Base64.NO_WRAP)
        require(bytes.size > IV_BYTES) { "Dữ liệu phiên nguồn không hợp lệ." }
        val iv = bytes.copyOfRange(0, IV_BYTES)
        val encrypted = bytes.copyOfRange(IV_BYTES, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(sourceId.toByteArray(Charsets.UTF_8))
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

    private fun key(sourceId: String): String = "session.${sourceId.replace(Regex("[^A-Za-z0-9_.-]"), "_")}" 

    companion object {
        private const val PREFERENCES = "encrypted_source_sessions_v1"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "vn.nghetruyen.source.sessions.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }
}
