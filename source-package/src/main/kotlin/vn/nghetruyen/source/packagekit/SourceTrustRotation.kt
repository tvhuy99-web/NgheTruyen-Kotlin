package vn.nghetruyen.source.packagekit

import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SourceErrorCode
import vn.nghetruyen.source.api.SourcePlatformFailure
import vn.nghetruyen.source.api.SourcePlatformResult
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Locale

const val SOURCE_TRUST_ROTATION_SCHEMA_VERSION = 1

data class SourceTrustKeyRotation(
    val oldKeyId: String,
    val newKey: SourceTrustKey,
    val notBeforeEpochMs: Long,
    val expiresAtEpochMs: Long,
    val signatureAlgorithm: SourceSignatureAlgorithm,
    val signature: ByteArray,
    val canonicalPayload: ByteArray,
)

object SourceTrustKeyValidator {
    fun validate(key: SourceTrustKey) {
        require(KEY_ID.matches(key.keyId)) { "SOURCE_TRUST_KEY_ID_INVALID" }
        require(key.x509PublicKey.size in 32..4096) { "SOURCE_TRUST_KEY_BYTES_INVALID" }
        val publicKey = KeyFactory.getInstance(key.algorithm.jcaKeyFactory)
            .generatePublic(X509EncodedKeySpec(key.x509PublicKey))
        require(publicKey.encoded.contentEquals(key.x509PublicKey)) { "SOURCE_TRUST_KEY_ENCODING_INVALID" }
    }

    fun fingerprint(key: SourceTrustKey): String = MessageDigest.getInstance("SHA-256")
        .digest(key.x509PublicKey)
        .joinToString(":") { "%02X".format(Locale.ROOT, it) }

    private val KEY_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{2,199}$")
}

class SourceTrustRotationVerifier(
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    fun verify(raw: ByteArray, currentKeys: Collection<SourceTrustKey>): SourcePlatformResult<SourceTrustKeyRotation> = runCatching {
        require(raw.size in 1..64 * 1024) { "SOURCE_TRUST_ROTATION_TOO_LARGE" }
        val text = raw.toString(Charsets.UTF_8)
        require(text.toByteArray(Charsets.UTF_8).contentEquals(raw)) { "SOURCE_TRUST_ROTATION_NOT_UTF8" }
        val root = JsonCodec.parse(text) as? JsonValue.Obj ?: error("SOURCE_TRUST_ROTATION_NOT_OBJECT")
        val allowed = CANONICAL_KEYS.toSet() + "signature"
        require((root.values.keys - allowed).isEmpty()) { "SOURCE_TRUST_ROTATION_UNKNOWN_FIELD" }
        require(root.int("schemaVersion") == SOURCE_TRUST_ROTATION_SCHEMA_VERSION) { "SOURCE_TRUST_ROTATION_SCHEMA_INVALID" }
        val oldKeyId = root.string("oldKeyId") ?: error("SOURCE_TRUST_ROTATION_OLD_KEY_REQUIRED")
        val newKeyId = root.string("newKeyId") ?: error("SOURCE_TRUST_ROTATION_NEW_KEY_REQUIRED")
        require(oldKeyId != newKeyId) { "SOURCE_TRUST_ROTATION_SAME_KEY" }
        val newAlgorithm = enumValue<SourceSignatureAlgorithm>(root.string("newAlgorithm") ?: error("SOURCE_TRUST_ROTATION_ALGORITHM_REQUIRED"))
        val signatureAlgorithm = enumValue<SourceSignatureAlgorithm>(root.string("signatureAlgorithm") ?: error("SOURCE_TRUST_ROTATION_SIGNATURE_ALGORITHM_REQUIRED"))
        val newKey = SourceTrustKey.fromBase64(newKeyId, newAlgorithm, root.string("newPublicKeyBase64") ?: error("SOURCE_TRUST_ROTATION_PUBLIC_KEY_REQUIRED"))
        SourceTrustKeyValidator.validate(newKey)
        val notBefore = root.long("notBeforeEpochMs") ?: error("SOURCE_TRUST_ROTATION_TIME_REQUIRED")
        val expires = root.long("expiresAtEpochMs") ?: error("SOURCE_TRUST_ROTATION_TIME_REQUIRED")
        val now = clockMs()
        require(notBefore <= now + MAX_CLOCK_SKEW_MS) { "SOURCE_TRUST_ROTATION_NOT_ACTIVE" }
        require(expires > now - MAX_CLOCK_SKEW_MS && expires > notBefore) { "SOURCE_TRUST_ROTATION_EXPIRED" }
        require(expires - notBefore <= MAX_VALIDITY_MS) { "SOURCE_TRUST_ROTATION_VALIDITY_TOO_LONG" }
        val payload = JsonValue.Obj(linkedMapOf<String, JsonValue>().apply {
            CANONICAL_KEYS.forEach { key -> put(key, root[key] ?: error("SOURCE_TRUST_ROTATION_FIELD_REQUIRED:$key")) }
        }).let(JsonCodec::stringify).toByteArray(Charsets.UTF_8)
        val signature = runCatching { Base64.getDecoder().decode(root.string("signature") ?: error("SOURCE_TRUST_ROTATION_SIGNATURE_REQUIRED")) }
            .getOrElse { error("SOURCE_TRUST_ROTATION_SIGNATURE_INVALID") }
        val valid = SourceDetachedSignatureVerifier.verify(currentKeys, oldKeyId, signatureAlgorithm, payload, signature)
        require(valid) { "SOURCE_TRUST_ROTATION_SIGNATURE_INVALID" }
        SourceTrustKeyRotation(oldKeyId, newKey, notBefore, expires, signatureAlgorithm, signature, payload)
    }.fold(
        { SourcePlatformResult.Success(it) },
        { SourcePlatformResult.Failure(SourcePlatformFailure(SourceErrorCode.TRUST_KEY_ROTATION_INVALID, it.message ?: "SOURCE_TRUST_ROTATION_INVALID", cause = it)) },
    )

    private inline fun <reified T : Enum<T>> enumValue(raw: String): T =
        enumValues<T>().firstOrNull { it.name == raw.uppercase(Locale.ROOT) } ?: error("SOURCE_TRUST_ROTATION_ENUM_INVALID:$raw")

    companion object {
        private const val MAX_CLOCK_SKEW_MS = 5 * 60 * 1000L
        private const val MAX_VALIDITY_MS = 365L * 24 * 60 * 60 * 1000
        private val CANONICAL_KEYS = listOf(
            "schemaVersion", "oldKeyId", "newKeyId", "newAlgorithm", "newPublicKeyBase64",
            "notBeforeEpochMs", "expiresAtEpochMs", "signatureAlgorithm",
        )
    }
}
