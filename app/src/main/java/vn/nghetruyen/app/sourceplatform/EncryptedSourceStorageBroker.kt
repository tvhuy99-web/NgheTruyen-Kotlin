package vn.nghetruyen.app.sourceplatform

import vn.nghetruyen.source.api.SourceErrorCode
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourcePlatformFailure
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.api.SourceStorageBroker
import vn.nghetruyen.source.api.SourceStorageRequest
import vn.nghetruyen.source.runtime.FileSourceStorageBroker
import vn.nghetruyen.source.runtime.SourceSecretKeyProvider
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * Device-bound encrypted storage for vBook localStorage/cache values.
 *
 * File names retain the broker's opaque key encoding so prefix operations keep working, while every
 * value is authenticated with its source id and logical key. Quotas are enforced against plaintext;
 * encryption overhead is never charged to an extension.
 */
class EncryptedSourceStorageBroker(
    root: File,
    private val secretKeyProvider: SourceSecretKeyProvider = AndroidSourceSecretKeyProvider(),
    private val random: SecureRandom = SecureRandom(),
) : SourceStorageBroker {
    private val delegate = FileSourceStorageBroker(root)
    private val lock = Any()

    override fun get(
        manifest: SourceManifest,
        request: SourceStorageRequest,
    ): SourcePlatformResult<ByteArray?> = synchronized(lock) {
        try {
            validate(manifest, request)
            when (val stored = delegate.get(storageManifest(manifest), request)) {
                is SourcePlatformResult.Failure -> stored
                is SourcePlatformResult.Success -> SourcePlatformResult.Success(
                    stored.value?.let { decrypt(request.sourceId, request.key, it) },
                )
            }
        } catch (error: Throwable) {
            failure(error, request.traceId)
        }
    }

    override fun put(
        manifest: SourceManifest,
        request: SourceStorageRequest,
    ): SourcePlatformResult<Unit> = synchronized(lock) {
        try {
            validate(manifest, request)
            val value = request.value ?: error("SOURCE_STORAGE_VALUE_REQUIRED")
            val currentKeys = unwrap(delegate.keys(storageManifest(manifest), request.sourceId, traceId = request.traceId))
            var currentBytes = 0L
            var existingBytes = 0L
            currentKeys.forEach { key ->
                val encrypted = unwrap(delegate.get(
                    storageManifest(manifest),
                    SourceStorageRequest(request.sourceId, key, traceId = request.traceId),
                )) ?: return@forEach
                val size = decrypt(request.sourceId, key, encrypted).size.toLong()
                currentBytes += size
                if (key == request.key) existingBytes = size
            }
            require(currentBytes - existingBytes + value.size <= manifest.capabilities.storageBytes.toLong()) {
                "SOURCE_STORAGE_QUOTA_EXCEEDED"
            }
            delegate.put(
                storageManifest(manifest),
                request.copy(value = encrypt(request.sourceId, request.key, value)),
            )
        } catch (error: DelegateFailure) {
            SourcePlatformResult.Failure(error.failure)
        } catch (error: Throwable) {
            failure(error, request.traceId)
        }
    }

    override fun delete(
        manifest: SourceManifest,
        request: SourceStorageRequest,
    ): SourcePlatformResult<Unit> = synchronized(lock) {
        try {
            validate(manifest, request)
            delegate.delete(storageManifest(manifest), request)
        } catch (error: Throwable) {
            failure(error, request.traceId)
        }
    }

    override fun keys(
        manifest: SourceManifest,
        sourceId: String,
        prefix: String,
        traceId: String,
    ): SourcePlatformResult<List<String>> = synchronized(lock) {
        try {
            require(sourceId == manifest.id) { "SOURCE_STORAGE_SOURCE_ID_MISMATCH" }
            require(manifest.capabilities.storageBytes > 0) { "SOURCE_STORAGE_CAPABILITY_REQUIRED" }
            delegate.keys(storageManifest(manifest), sourceId, prefix, traceId)
        } catch (error: Throwable) {
            failure(error, traceId)
        }
    }

    override fun clearPrefix(
        manifest: SourceManifest,
        sourceId: String,
        prefix: String,
        traceId: String,
    ): SourcePlatformResult<Unit> = synchronized(lock) {
        try {
            require(sourceId == manifest.id) { "SOURCE_STORAGE_SOURCE_ID_MISMATCH" }
            require(manifest.capabilities.storageBytes > 0) { "SOURCE_STORAGE_CAPABILITY_REQUIRED" }
            delegate.clearPrefix(storageManifest(manifest), sourceId, prefix, traceId)
        } catch (error: Throwable) {
            failure(error, traceId)
        }
    }

    override fun clear(sourceId: String): SourcePlatformResult<Unit> = synchronized(lock) {
        delegate.clear(sourceId)
    }

    private fun encrypt(sourceId: String, key: String, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKeyProvider.keyFor(sourceId), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(associatedData(sourceId, key))
        return MAGIC + iv + cipher.doFinal(plaintext)
    }

    private fun decrypt(sourceId: String, key: String, payload: ByteArray): ByteArray {
        require(payload.size > MAGIC.size + IV_BYTES && payload.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            "SOURCE_STORAGE_CIPHERTEXT_INVALID"
        }
        val ivStart = MAGIC.size
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKeyProvider.keyFor(sourceId),
            GCMParameterSpec(TAG_BITS, payload.copyOfRange(ivStart, ivStart + IV_BYTES)),
        )
        cipher.updateAAD(associatedData(sourceId, key))
        return cipher.doFinal(payload.copyOfRange(ivStart + IV_BYTES, payload.size))
    }

    private fun associatedData(sourceId: String, key: String): ByteArray =
        "$sourceId\u0000$key".toByteArray(Charsets.UTF_8)

    private fun validate(manifest: SourceManifest, request: SourceStorageRequest) {
        require(request.sourceId == manifest.id) { "SOURCE_STORAGE_SOURCE_ID_MISMATCH" }
        require(manifest.capabilities.storageBytes > 0) { "SOURCE_STORAGE_CAPABILITY_REQUIRED" }
        require(request.key.length in 1..256 && request.key.none { it == '\u0000' || it == '\r' || it == '\n' }) {
            "SOURCE_STORAGE_KEY_INVALID"
        }
        request.value?.let {
            require(it.size <= manifest.capabilities.storageBytes) { "SOURCE_STORAGE_QUOTA_EXCEEDED" }
        }
    }

    private fun storageManifest(manifest: SourceManifest): SourceManifest = manifest.copy(
        capabilities = manifest.capabilities.copy(storageBytes = Int.MAX_VALUE),
    )

    private fun <T> unwrap(result: SourcePlatformResult<T>): T = when (result) {
        is SourcePlatformResult.Success -> result.value
        is SourcePlatformResult.Failure -> throw DelegateFailure(result.error)
    }

    private fun failure(error: Throwable, traceId: String?): SourcePlatformResult.Failure {
        val code = if (error.message?.contains("QUOTA") == true) {
            SourceErrorCode.STORAGE_QUOTA_EXCEEDED
        } else {
            SourceErrorCode.STORAGE_UNAVAILABLE
        }
        return SourcePlatformResult.Failure(
            SourcePlatformFailure(code, error.message ?: "SOURCE_STORAGE_FAILED", traceId, error),
        )
    }

    private class DelegateFailure(val failure: SourcePlatformFailure) : RuntimeException(failure.message, failure.cause)

    companion object {
        private val MAGIC = byteArrayOf('V'.code.toByte(), 'B'.code.toByte(), 'S'.code.toByte(), 1)
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }
}
