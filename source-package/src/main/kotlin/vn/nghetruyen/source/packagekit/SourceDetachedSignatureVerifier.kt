package vn.nghetruyen.source.packagekit

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

 
object SourceDetachedSignatureVerifier {
    fun verify(
        trustKeys: Collection<SourceTrustKey>,
        keyId: String,
        algorithm: SourceSignatureAlgorithm,
        payload: ByteArray,
        signatureBytesRaw: ByteArray,
    ): Boolean {
        val candidates = trustKeys.filter { it.keyId == keyId && it.algorithm == algorithm }
        if (candidates.isEmpty()) return false
        val signatureBytes = decodeSignature(signatureBytesRaw)
        var algorithmAvailable = false
        candidates.forEach { key ->
            val signature = try {
                Signature.getInstance(key.algorithm.jcaSignature).also { algorithmAvailable = true }
            } catch (_: Exception) {
                return@forEach
            }
            val publicKey = runCatching { decodePublicKey(key) }.getOrNull() ?: return@forEach
            val valid = runCatching {
                signature.initVerify(publicKey)
                signature.update(payload)
                signature.verify(signatureBytes)
            }.getOrDefault(false)
            if (valid) return true
        }
        if (!algorithmAvailable) error("PACKAGE_SIGNATURE_UNSUPPORTED:${algorithm.name}")
        return false
    }

    private fun decodePublicKey(key: SourceTrustKey): PublicKey =
        KeyFactory.getInstance(key.algorithm.jcaKeyFactory).generatePublic(X509EncodedKeySpec(key.x509PublicKey))

    private fun decodeSignature(raw: ByteArray): ByteArray {
        if (raw.any { it == 0.toByte() || it.toInt() < 0 }) return raw
        val text = raw.toString(Charsets.US_ASCII).trim()
        return runCatching { Base64.getDecoder().decode(text) }.getOrElse { raw }
    }
}
