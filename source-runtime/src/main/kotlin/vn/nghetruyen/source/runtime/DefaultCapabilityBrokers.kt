package vn.nghetruyen.source.runtime

import vn.nghetruyen.source.api.SourceCryptoBroker
import vn.nghetruyen.source.api.SourceCryptoCapability
import vn.nghetruyen.source.api.SourceCryptoOperation
import vn.nghetruyen.source.api.SourceCryptoRequest
import vn.nghetruyen.source.api.SourceErrorCode
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourcePlatformFailure
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.api.SourceStorageBroker
import vn.nghetruyen.source.api.SourceStorageRequest
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class FileSourceStorageBroker(root: File) : SourceStorageBroker {
    private val rootDir = root.canonicalFile.also(File::mkdirs)
    private val lock = Any()

    override fun get(manifest: SourceManifest, request: SourceStorageRequest): SourcePlatformResult<ByteArray?> = synchronized(lock) {
        runCatching {
            validate(manifest, request)
            valueFile(request.sourceId, request.key).takeIf(File::isFile)?.readBytes()
        }.fold(
            { SourcePlatformResult.Success(it) },
            { failure(it, request) },
        )
    }

    override fun put(manifest: SourceManifest, request: SourceStorageRequest): SourcePlatformResult<Unit> = synchronized(lock) {
        runCatching {
            validate(manifest, request)
            val value = request.value ?: error("SOURCE_STORAGE_VALUE_REQUIRED")
            val directory = sourceDirectory(request.sourceId).also(File::mkdirs)
            val target = valueFile(request.sourceId, request.key)
            val existing = target.takeIf(File::isFile)?.length() ?: 0L
            val current = directory.listFiles().orEmpty().filter(File::isFile).sumOf(File::length)
            require(current - existing + value.size <= manifest.capabilities.storageBytes) { "SOURCE_STORAGE_QUOTA_EXCEEDED" }
            atomicWrite(target, value)
        }.fold(
            { SourcePlatformResult.Success(Unit) },
            { failure(it, request) },
        )
    }

    override fun delete(manifest: SourceManifest, request: SourceStorageRequest): SourcePlatformResult<Unit> = synchronized(lock) {
        runCatching {
            validate(manifest, request)
            valueFile(request.sourceId, request.key).delete()
        }.fold(
            { SourcePlatformResult.Success(Unit) },
            { failure(it, request) },
        )
    }

    override fun keys(
        manifest: SourceManifest,
        sourceId: String,
        prefix: String,
        traceId: String,
    ): SourcePlatformResult<List<String>> = synchronized(lock) {
        runCatching {
            require(sourceId == manifest.id) { "SOURCE_STORAGE_SOURCE_ID_MISMATCH" }
            require(manifest.capabilities.storageBytes > 0) { "SOURCE_STORAGE_CAPABILITY_REQUIRED" }
            require(prefix.length <= 256 && prefix.none { it == '\u0000' || it == '\r' || it == '\n' }) { "SOURCE_STORAGE_PREFIX_INVALID" }
            sourceDirectory(sourceId).listFiles().orEmpty()
                .asSequence()
                .filter { it.isFile && it.extension == "bin" }
                .mapNotNull { decodeKey(it.nameWithoutExtension) }
                .filter { it.startsWith(prefix) }
                .sorted()
                .take(10_000)
                .toList()
        }.fold(
            { SourcePlatformResult.Success(it) },
            { SourcePlatformResult.Failure(SourcePlatformFailure(SourceErrorCode.STORAGE_UNAVAILABLE, it.message ?: "SOURCE_STORAGE_KEYS_FAILED", traceId, it)) },
        )
    }

    override fun clearPrefix(
        manifest: SourceManifest,
        sourceId: String,
        prefix: String,
        traceId: String,
    ): SourcePlatformResult<Unit> = synchronized(lock) {
        when (val keys = keys(manifest, sourceId, prefix, traceId)) {
            is SourcePlatformResult.Failure -> keys
            is SourcePlatformResult.Success -> runCatching {
                keys.value.forEach { valueFile(sourceId, it).delete() }
            }.fold(
                { SourcePlatformResult.Success(Unit) },
                { SourcePlatformResult.Failure(SourcePlatformFailure(SourceErrorCode.STORAGE_UNAVAILABLE, it.message ?: "SOURCE_STORAGE_CLEAR_PREFIX_FAILED", traceId, it)) },
            )
        }
    }

    override fun clear(sourceId: String): SourcePlatformResult<Unit> = synchronized(lock) {
        runCatching { sourceDirectory(sourceId).deleteRecursively() }.fold(
            { SourcePlatformResult.Success(Unit) },
            { SourcePlatformResult.Failure(SourcePlatformFailure(SourceErrorCode.STORAGE_UNAVAILABLE, it.message ?: "SOURCE_STORAGE_CLEAR_FAILED", cause = it)) },
        )
    }

    private fun validate(manifest: SourceManifest, request: SourceStorageRequest) {
        require(request.sourceId == manifest.id) { "SOURCE_STORAGE_SOURCE_ID_MISMATCH" }
        require(manifest.capabilities.storageBytes > 0) { "SOURCE_STORAGE_CAPABILITY_REQUIRED" }
        require(request.key.length in 1..256 && request.key.none { it == '\u0000' || it == '\r' || it == '\n' }) { "SOURCE_STORAGE_KEY_INVALID" }
        request.value?.let { require(it.size <= manifest.capabilities.storageBytes) { "SOURCE_STORAGE_QUOTA_EXCEEDED" } }
    }

    private fun sourceDirectory(sourceId: String): File {
        require(SOURCE_ID.matches(sourceId)) { "SOURCE_STORAGE_SOURCE_ID_INVALID" }
        val target = File(rootDir, sourceId).canonicalFile
        require(target.path.startsWith(rootDir.path + File.separator)) { "SOURCE_STORAGE_PATH_ESCAPE" }
        return target
    }

    private fun valueFile(sourceId: String, key: String): File {
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(key.toByteArray(Charsets.UTF_8))
        return File(sourceDirectory(sourceId), "$encoded.bin")
    }

    private fun decodeKey(encoded: String): String? = runCatching {
        Base64.getUrlDecoder().decode(encoded).toString(Charsets.UTF_8)
    }.getOrNull()?.takeIf { it.length in 1..256 && it.none { ch -> ch == '\u0000' || ch == '\r' || ch == '\n' } }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        temp.writeBytes(bytes)
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun failure(error: Throwable, request: SourceStorageRequest): SourcePlatformResult.Failure {
        val code = if (error.message?.contains("QUOTA") == true) SourceErrorCode.STORAGE_QUOTA_EXCEEDED else SourceErrorCode.STORAGE_UNAVAILABLE
        return SourcePlatformResult.Failure(SourcePlatformFailure(code, error.message ?: "SOURCE_STORAGE_FAILED", request.traceId, error))
    }

    companion object {
        private val SOURCE_ID = Regex("^[a-z][a-z0-9]*(\\.[a-z0-9][a-z0-9-]*){2,}$")
    }
}

fun interface SourceSecretKeyProvider {
    fun keyFor(sourceId: String): SecretKey
}

class InMemorySourceSecretKeyProvider(
    private val random: SecureRandom = SecureRandom(),
) : SourceSecretKeyProvider {
    private val keys = linkedMapOf<String, SecretKey>()
    @Synchronized override fun keyFor(sourceId: String): SecretKey = keys.getOrPut(sourceId) {
        ByteArray(32).also(random::nextBytes).let { SecretKeySpec(it, "AES") }
    }
}

class JcaSourceCryptoBroker(
    private val secretKeyProvider: SourceSecretKeyProvider = InMemorySourceSecretKeyProvider(),
    private val random: SecureRandom = SecureRandom(),
) : SourceCryptoBroker {
    override fun execute(manifest: SourceManifest, request: SourceCryptoRequest): SourcePlatformResult<ByteArray> = runCatching {
        require(request.sourceId == manifest.id) { "SOURCE_CRYPTO_SOURCE_ID_MISMATCH" }
        require(request.payload.size <= MAX_PAYLOAD_BYTES) { "SOURCE_CRYPTO_PAYLOAD_TOO_LARGE" }
        when (request.operation) {
            SourceCryptoOperation.MD5 -> digest(manifest, request, SourceCryptoCapability.MD5, "MD5")
            SourceCryptoOperation.SHA1 -> digest(manifest, request, SourceCryptoCapability.SHA1, "SHA-1")
            SourceCryptoOperation.SHA256 -> digest(manifest, request, SourceCryptoCapability.SHA256, "SHA-256")
            SourceCryptoOperation.SHA512 -> digest(manifest, request, SourceCryptoCapability.SHA512, "SHA-512")
            SourceCryptoOperation.HMAC_MD5 -> hmac(manifest, request, SourceCryptoCapability.HMAC_MD5, "HmacMD5")
            SourceCryptoOperation.HMAC_SHA1 -> hmac(manifest, request, SourceCryptoCapability.HMAC_SHA1, "HmacSHA1")
            SourceCryptoOperation.HMAC_SHA256 -> hmac(manifest, request, SourceCryptoCapability.HMAC_SHA256, "HmacSHA256")
            SourceCryptoOperation.HMAC_SHA512 -> hmac(manifest, request, SourceCryptoCapability.HMAC_SHA512, "HmacSHA512")
            SourceCryptoOperation.AES_GCM_ENCRYPT -> {
                require(SourceCryptoCapability.AES_GCM_SECRET in manifest.capabilities.crypto) { "SOURCE_CRYPTO_CAPABILITY_REQUIRED" }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                // Android Keystore must generate the IV for randomized-encryption keys. Supplying
                // GCMParameterSpec here fails on-device with "Caller-provided IV not permitted".
                cipher.init(Cipher.ENCRYPT_MODE, secretKeyProvider.keyFor(manifest.id), random)
                val iv = cipher.iv ?: error("SOURCE_CRYPTO_IV_UNAVAILABLE")
                require(iv.size == 12) { "SOURCE_CRYPTO_IV_INVALID" }
                if (request.associatedData.isNotEmpty()) cipher.updateAAD(request.associatedData)
                iv + cipher.doFinal(request.payload)
            }
            SourceCryptoOperation.AES_GCM_DECRYPT -> {
                require(SourceCryptoCapability.AES_GCM_SECRET in manifest.capabilities.crypto) { "SOURCE_CRYPTO_CAPABILITY_REQUIRED" }
                require(request.payload.size > 12) { "SOURCE_CRYPTO_CIPHERTEXT_INVALID" }
                val iv = request.payload.copyOfRange(0, 12)
                val ciphertext = request.payload.copyOfRange(12, request.payload.size)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, secretKeyProvider.keyFor(manifest.id), GCMParameterSpec(128, iv))
                if (request.associatedData.isNotEmpty()) cipher.updateAAD(request.associatedData)
                cipher.doFinal(ciphertext)
            }
        }
    }.fold(
        { SourcePlatformResult.Success(it) },
        { SourcePlatformResult.Failure(SourcePlatformFailure(SourceErrorCode.CRYPTO_UNAVAILABLE, it.message ?: "SOURCE_CRYPTO_FAILED", request.traceId, it)) },
    )

    private fun digest(
        manifest: SourceManifest,
        request: SourceCryptoRequest,
        capability: SourceCryptoCapability,
        algorithm: String,
    ): ByteArray {
        require(capability in manifest.capabilities.crypto) { "SOURCE_CRYPTO_CAPABILITY_REQUIRED" }
        return MessageDigest.getInstance(algorithm).digest(request.payload)
    }

    private fun hmac(
        manifest: SourceManifest,
        request: SourceCryptoRequest,
        capability: SourceCryptoCapability,
        algorithm: String,
    ): ByteArray {
        require(capability in manifest.capabilities.crypto) { "SOURCE_CRYPTO_CAPABILITY_REQUIRED" }
        val key = request.keyMaterial ?: error("SOURCE_CRYPTO_KEY_REQUIRED")
        require(key.size in 1..4096) { "SOURCE_CRYPTO_KEY_INVALID" }
        return Mac.getInstance(algorithm).run {
            init(SecretKeySpec(key, algorithm))
            doFinal(request.payload)
        }
    }

    companion object { private const val MAX_PAYLOAD_BYTES = 4 * 1024 * 1024 }
}
