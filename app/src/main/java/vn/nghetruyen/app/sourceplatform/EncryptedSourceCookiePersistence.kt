package vn.nghetruyen.app.sourceplatform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.network.SourceCookiePersistence
import vn.nghetruyen.source.network.SourceCookieRecord
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class EncryptedSourceCookiePersistence(context: Context) : SourceCookiePersistence {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    override fun save(sourceId: String, records: List<SourceCookieRecord>) {
        if (records.isEmpty()) {
            preferences.edit().remove(prefKey(sourceId)).apply()
            return
        }
        val payload = JsonCodec.stringify(JsonValue.Arr(records.take(MAX_COOKIES).map(::toJson)))
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_PERSISTED_BYTES) { "SOURCE_COOKIE_STORE_TOO_LARGE" }
        preferences.edit().putString(prefKey(sourceId), encrypt(sourceId, payload)).apply()
    }

    @Synchronized
    override fun load(sourceId: String): List<SourceCookieRecord> {
        val raw = preferences.getString(prefKey(sourceId), null) ?: return emptyList()
        return runCatching {
            val array = JsonCodec.parse(decrypt(sourceId, raw)) as? JsonValue.Arr ?: return@runCatching emptyList()
            array.values.mapNotNull(::fromJson).take(MAX_COOKIES)
        }.onFailure { preferences.edit().remove(prefKey(sourceId)).apply() }.getOrDefault(emptyList())
    }

    @Synchronized
    override fun clear(sourceId: String) {
        preferences.edit().remove(prefKey(sourceId)).apply()
    }

    private fun toJson(cookie: SourceCookieRecord) = JsonValue.Obj(linkedMapOf(
        "name" to JsonValue.Str(cookie.name),
        "value" to JsonValue.Str(cookie.value),
        "domain" to JsonValue.Str(cookie.domain),
        "path" to JsonValue.Str(cookie.path),
        "expiresAtEpochMs" to (cookie.expiresAtEpochMs?.let { JsonValue.Num(it.toDouble(), it.toString()) } ?: JsonValue.Null),
        "secure" to JsonValue.Bool(cookie.secure),
        "httpOnly" to JsonValue.Bool(cookie.httpOnly),
        "hostOnly" to JsonValue.Bool(cookie.hostOnly),
        "sameSite" to (cookie.sameSite?.let(JsonValue::Str) ?: JsonValue.Null),
        "createdAtEpochMs" to JsonValue.Num(cookie.createdAtEpochMs.toDouble(), cookie.createdAtEpochMs.toString()),
    ))

    private fun fromJson(value: JsonValue): SourceCookieRecord? {
        val obj = value as? JsonValue.Obj ?: return null
        return SourceCookieRecord(
            name = obj.string("name") ?: return null,
            value = obj.string("value") ?: return null,
            domain = obj.string("domain") ?: return null,
            path = obj.string("path") ?: "/",
            expiresAtEpochMs = obj.long("expiresAtEpochMs"),
            secure = obj.bool("secure") ?: false,
            httpOnly = obj.bool("httpOnly") ?: false,
            hostOnly = obj.bool("hostOnly") ?: true,
            sameSite = obj.string("sameSite"),
            createdAtEpochMs = obj.long("createdAtEpochMs") ?: 0L,
        )
    }

    private fun encrypt(sourceId: String, value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(sourceId.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(sourceId: String, value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > IV_BYTES) { "SOURCE_COOKIE_STORE_INVALID" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, IV_BYTES)))
        cipher.updateAAD(sourceId.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size)).toString(Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
            generateKey()
        }
    }

    private fun prefKey(sourceId: String) = "cookies.${sourceId.replace(Regex("[^A-Za-z0-9_.-]"), "_")}" 

    companion object {
        private const val PREFERENCES = "encrypted_sourcepack_cookies_v2"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "vn.nghetruyen.sourcepack.cookies.v2"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val MAX_COOKIES = 256
        private const val MAX_PERSISTED_BYTES = 256 * 1024
    }
}
