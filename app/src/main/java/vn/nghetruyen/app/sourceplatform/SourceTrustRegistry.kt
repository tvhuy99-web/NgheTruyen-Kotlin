package vn.nghetruyen.app.sourceplatform

import android.content.Context
import android.util.Base64
import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.packagekit.SourceSignatureAlgorithm
import vn.nghetruyen.source.packagekit.SourceTrustKey
import vn.nghetruyen.source.packagekit.SourceTrustKeyValidator
import vn.nghetruyen.source.packagekit.SourceTrustRotationVerifier

class SourceTrustRegistry(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val rotationVerifier = SourceTrustRotationVerifier()

    @Synchronized
    fun allKeys(): List<SourceTrustKey> = (SourcePlatformTrustRoots.all + loadUserKeys()).distinctBy { it.keyId to it.algorithm }

    @Synchronized
    fun userKeys(): List<SourceTrustKeyUi> = loadUserKeys().map { key ->
        SourceTrustKeyUi(key.keyId, key.algorithm.name, SourceTrustKeyValidator.fingerprint(key), builtin = false)
    } + SourcePlatformTrustRoots.all.map { key ->
        SourceTrustKeyUi(key.keyId, key.algorithm.name, SourceTrustKeyValidator.fingerprint(key), builtin = true)
    }

    @Synchronized
    fun enroll(keyId: String, algorithm: String, publicKeyBase64: String, expectedFingerprint: String): Result<SourceTrustKeyUi> = runCatching {
        val parsedAlgorithm = SourceSignatureAlgorithm.valueOf(algorithm.uppercase())
        val key = SourceTrustKey.fromBase64(keyId.trim(), parsedAlgorithm, publicKeyBase64.trim())
        SourceTrustKeyValidator.validate(key)
        val fingerprint = SourceTrustKeyValidator.fingerprint(key)
        require(normalizeFingerprint(expectedFingerprint) == normalizeFingerprint(fingerprint)) {
            "Fingerprint xác nhận không khớp. Khóa chưa được thêm."
        }
        require(SourcePlatformTrustRoots.all.none { it.keyId == key.keyId }) { "Không thể thay thế khóa tích hợp." }
        val current = loadUserKeys().filterNot { it.keyId == key.keyId }.toMutableList().apply { add(key) }
        save(current)
        SourceTrustKeyUi(key.keyId, key.algorithm.name, fingerprint, builtin = false)
    }

    @Synchronized
    fun revoke(keyId: String): Result<Unit> = runCatching {
        require(SourcePlatformTrustRoots.all.none { it.keyId == keyId }) { "Không thể thu hồi khóa tích hợp." }
        val current = loadUserKeys()
        require(current.any { it.keyId == keyId }) { "Không tìm thấy khóa tin cậy." }
        save(current.filterNot { it.keyId == keyId })
    }

    @Synchronized
    fun applyRotation(raw: ByteArray): Result<SourceTrustKeyUi> = runCatching {
        val rotation = when (val result = rotationVerifier.verify(raw, allKeys())) {
            is SourcePlatformResult.Success -> result.value
            is SourcePlatformResult.Failure -> error(result.error.message)
        }
        require(SourcePlatformTrustRoots.all.none { it.keyId == rotation.oldKeyId }) {
            "Khóa tích hợp chỉ được xoay bằng bản cập nhật ứng dụng."
        }
        val current = loadUserKeys()
        require(current.any { it.keyId == rotation.oldKeyId }) { "Khóa cũ không thuộc danh sách người dùng." }
        save(current.filterNot { it.keyId == rotation.oldKeyId || it.keyId == rotation.newKey.keyId } + rotation.newKey)
        SourceTrustKeyUi(
            rotation.newKey.keyId,
            rotation.newKey.algorithm.name,
            SourceTrustKeyValidator.fingerprint(rotation.newKey),
            builtin = false,
        )
    }

    private fun loadUserKeys(): List<SourceTrustKey> = runCatching {
        val raw = preferences.getString(KEYS, null) ?: return@runCatching emptyList()
        val array = JsonCodec.parse(raw) as? JsonValue.Arr ?: return@runCatching emptyList()
        array.values.mapNotNull { value ->
            val obj = value as? JsonValue.Obj ?: return@mapNotNull null
            val key = SourceTrustKey.fromBase64(
                obj.string("keyId") ?: return@mapNotNull null,
                SourceSignatureAlgorithm.valueOf(obj.string("algorithm") ?: return@mapNotNull null),
                obj.string("publicKey") ?: return@mapNotNull null,
            )
            runCatching { SourceTrustKeyValidator.validate(key); key }.getOrNull()
        }.take(MAX_USER_KEYS)
    }.getOrDefault(emptyList())

    private fun save(keys: List<SourceTrustKey>) {
        require(keys.size <= MAX_USER_KEYS) { "Quá nhiều khóa tin cậy." }
        val json = JsonValue.Arr(keys.map { key -> JsonValue.Obj(linkedMapOf(
            "keyId" to JsonValue.Str(key.keyId),
            "algorithm" to JsonValue.Str(key.algorithm.name),
            "publicKey" to JsonValue.Str(Base64.encodeToString(key.x509PublicKey, Base64.NO_WRAP)),
        )) })
        preferences.edit().putString(KEYS, JsonCodec.stringify(json)).commit()
    }

    private fun normalizeFingerprint(raw: String) = raw.filter(Char::isLetterOrDigit).uppercase()

    companion object {
        private const val PREFERENCES = "source_trust_registry_v2"
        private const val KEYS = "user.keys"
        private const val MAX_USER_KEYS = 64
    }
}
