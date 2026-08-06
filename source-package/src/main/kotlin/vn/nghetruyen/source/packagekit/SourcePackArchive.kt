package vn.nghetruyen.source.packagekit

import vn.nghetruyen.source.api.SourceErrorCode
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourcePlatformFailure
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticEvent
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity
import vn.nghetruyen.source.diagnostics.DiagnosticSink
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream

private const val HASH_FILE = "FILES.sha256"
private val SIGNATURE_FILES = setOf("SIGNATURE.ed25519", "SIGNATURE.es256")

enum class SourceSignatureAlgorithm(
    val signatureFile: String,
    val jcaSignature: String,
    val jcaKeyFactory: String,
) {
    ED25519("SIGNATURE.ed25519", "Ed25519", "Ed25519"),
    ECDSA_P256_SHA256("SIGNATURE.es256", "SHA256withECDSA", "EC"),
}

data class SourceTrustKey(
    val keyId: String,
    val algorithm: SourceSignatureAlgorithm,
    val x509PublicKey: ByteArray,
) {
    companion object {
        fun fromBase64(keyId: String, algorithm: SourceSignatureAlgorithm, base64: String): SourceTrustKey =
            SourceTrustKey(keyId, algorithm, Base64.getDecoder().decode(base64.filterNot(Char::isWhitespace)))
    }
}

data class SourcePackLimits(
    val maxArchiveBytes: Int = 16 * 1024 * 1024,
    val maxEntries: Int = 1024,
    val maxEntryBytes: Int = 8 * 1024 * 1024,
    val maxTotalUncompressedBytes: Int = 64 * 1024 * 1024,
    val maxCompressionRatio: Int = 250,
)

data class VerifiedSourcePack(
    val manifest: SourceManifest,
    val entries: Map<String, ByteArray>,
    val packageSha256: String,
    val signerKeyId: String,
    val signatureAlgorithm: SourceSignatureAlgorithm,
) {
    fun resource(path: String): ByteArray? = entries[path]
}

class SourcePackArchiveVerifier(
    private val limits: SourcePackLimits = SourcePackLimits(),
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
) {
    fun verify(
        input: InputStream,
        trustKeys: Collection<SourceTrustKey>,
        traceId: String = UUID.randomUUID().toString(),
    ): SourcePlatformResult<VerifiedSourcePack> {
        val startedAt = System.currentTimeMillis()
        diagnostics.emit(
            DiagnosticEvent(
                timestampEpochMs = startedAt,
                traceId = traceId,
                sourceId = "package:unknown",
                category = DiagnosticCategory.PACKAGE,
                name = "PACKAGE_VERIFY_STARTED",
            ),
        )
        return runCatching {
        val archive = readBounded(input, limits.maxArchiveBytes, "PACKAGE_TOO_LARGE")
        val entries = readZip(archive)
        val manifestBytes = entries["source.json"] ?: error("PACKAGE_SOURCE_JSON_MISSING")
        val hashBytes = entries[HASH_FILE] ?: error("PACKAGE_HASH_FILE_MISSING")
        val signatureEntry = SIGNATURE_FILES.singleOrNull(entries::containsKey)
            ?: error("PACKAGE_SIGNATURE_FILE_INVALID")
        val algorithm = SourceSignatureAlgorithm.entries.first { it.signatureFile == signatureEntry }
        val candidateKeys = trustKeys.filter { it.algorithm == algorithm }
        require(candidateKeys.isNotEmpty()) { "PACKAGE_TRUST_KEY_MISSING:${algorithm.name}" }
        verifyHashManifest(entries, hashBytes)
        val trustKey = candidateKeys.firstOrNull { key ->
            SourceDetachedSignatureVerifier.verify(
                trustKeys = listOf(key),
                keyId = key.keyId,
                algorithm = algorithm,
                payload = hashBytes,
                signatureBytesRaw = entries.getValue(signatureEntry),
            )
        } ?: error("PACKAGE_SIGNATURE_INVALID")
        val manifest = SourceManifestParser.parse(manifestBytes)
        manifest.actions.values.forEach { action ->
            require(entries.containsKey(action.entry)) { "PACKAGE_ACTION_ENTRY_MISSING:${action.entry}" }
        }
        manifest.runtime.entry?.let { require(entries.containsKey(it)) { "PACKAGE_RUNTIME_ENTRY_MISSING:$it" } }
        manifest.fixtures.forEach { fixture ->
            fixture.fixture?.let { require(entries.containsKey(it)) { "PACKAGE_FIXTURE_MISSING:$it" } }
            require(entries.containsKey(fixture.expected)) { "PACKAGE_EXPECTED_MISSING:${fixture.expected}" }
        }
        VerifiedSourcePack(
            manifest = manifest,
            entries = entries.filterKeys { it !in SIGNATURE_FILES && it != HASH_FILE },
            packageSha256 = sha256Hex(archive),
            signerKeyId = trustKey.keyId,
            signatureAlgorithm = algorithm,
        )
        }.fold(
        onSuccess = { pack ->
            diagnostics.emit(
                DiagnosticEvent(
                    timestampEpochMs = System.currentTimeMillis(),
                    traceId = traceId,
                    sourceId = pack.manifest.id,
                    sourceVersion = pack.manifest.version.toString(),
                    category = DiagnosticCategory.TRUST,
                    name = "PACKAGE_VERIFIED",
                    durationMs = System.currentTimeMillis() - startedAt,
                    attributes = mapOf(
                        "signerKeyId" to pack.signerKeyId,
                        "signatureAlgorithm" to pack.signatureAlgorithm.name,
                        "packageSha256" to pack.packageSha256,
                    ),
                ),
            )
            SourcePlatformResult.Success(pack)
        },
        onFailure = { throwable ->
            val message = throwable.message ?: "PACKAGE_INVALID"
            val code = when {
                        "TOO_LARGE" in message -> SourceErrorCode.PACKAGE_TOO_LARGE
                        "PATH" in message || "ZIP_ENTRY" in message -> SourceErrorCode.PACKAGE_PATH_INVALID
                        "HASH" in message -> SourceErrorCode.PACKAGE_HASH_MISMATCH
                        "SIGNATURE_UNSUPPORTED" in message -> SourceErrorCode.PACKAGE_SIGNATURE_UNSUPPORTED
                        "SIGNATURE" in message || "TRUST_KEY" in message -> SourceErrorCode.PACKAGE_SIGNATURE_INVALID
                        "SOURCE_" in message || "MANIFEST" in message -> SourceErrorCode.MANIFEST_INVALID
                        else -> SourceErrorCode.PACKAGE_INVALID
                    }
            diagnostics.emit(
                DiagnosticEvent(
                    timestampEpochMs = System.currentTimeMillis(),
                    traceId = traceId,
                    sourceId = "package:unknown",
                    category = DiagnosticCategory.TRUST,
                    name = "PACKAGE_VERIFY_FAILED",
                    severity = DiagnosticSeverity.ERROR,
                    durationMs = System.currentTimeMillis() - startedAt,
                    attributes = mapOf("code" to code.name, "error" to message),
                ),
            )
            SourcePlatformResult.Failure(
                SourcePlatformFailure(
                    code = code,
                    message = message,
                    cause = throwable,
                ),
            )
        },
    )
    }

    private fun readZip(archive: ByteArray): LinkedHashMap<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        val collisionKeys = hashSetOf<String>()
        var totalBytes = 0L
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(entries.size < limits.maxEntries) { "PACKAGE_TOO_MANY_ENTRIES" }
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                val path = canonicalArchivePath(entry.name)
                val collisionKey = path.lowercase(Locale.ROOT)
                require(collisionKeys.add(collisionKey)) { "PACKAGE_ZIP_ENTRY_COLLISION:$path" }
                val bytes = readBounded(zip, limits.maxEntryBytes, "PACKAGE_ENTRY_TOO_LARGE:$path")
                totalBytes += bytes.size
                require(totalBytes <= limits.maxTotalUncompressedBytes) { "PACKAGE_UNCOMPRESSED_TOO_LARGE" }
                if (entry.compressedSize > 0 && bytes.size > 1024) {
                    require(bytes.size / entry.compressedSize.coerceAtLeast(1) <= limits.maxCompressionRatio) {
                        "PACKAGE_COMPRESSION_RATIO_TOO_HIGH:$path"
                    }
                }
                entries[path] = bytes
                zip.closeEntry()
            }
        }
        require(entries.isNotEmpty()) { "PACKAGE_EMPTY" }
        return entries
    }

    private fun verifyHashManifest(entries: Map<String, ByteArray>, raw: ByteArray) {
        require(raw.size <= 1024 * 1024) { "PACKAGE_HASH_FILE_TOO_LARGE" }
        val text = raw.toString(StandardCharsets.US_ASCII)
        require(text.toByteArray(StandardCharsets.US_ASCII).contentEquals(raw)) { "PACKAGE_HASH_FILE_ENCODING" }
        require(text.endsWith('\n')) { "PACKAGE_HASH_FILE_NOT_CANONICAL" }
        val expected = linkedMapOf<String, String>()
        var previousPath: String? = null
        text.lineSequence().filter(String::isNotBlank).forEach { line ->
            val match = HASH_LINE.matchEntire(line) ?: error("PACKAGE_HASH_LINE_INVALID")
            val hash = match.groupValues[1].lowercase(Locale.ROOT)
            val path = canonicalArchivePath(match.groupValues[2])
            require(path !in SIGNATURE_FILES && path != HASH_FILE) { "PACKAGE_HASH_SELF_REFERENCE" }
            require(expected.put(path, hash) == null) { "PACKAGE_HASH_DUPLICATE:$path" }
            previousPath?.let { require(it < path) { "PACKAGE_HASH_FILE_NOT_SORTED" } }
            previousPath = path
        }
        val payloadPaths = entries.keys - SIGNATURE_FILES - HASH_FILE
        require(expected.keys == payloadPaths) {
            "PACKAGE_HASH_COVERAGE_MISMATCH:missing=${payloadPaths - expected.keys}:extra=${expected.keys - payloadPaths}"
        }
        expected.forEach { (path, hash) ->
            require(sha256Hex(entries.getValue(path)) == hash) { "PACKAGE_HASH_MISMATCH:$path" }
        }
    }

    companion object {
        private val HASH_LINE = Regex("^([0-9a-fA-F]{64}) {2}([^\\r\\n]+)$")

        fun canonicalArchivePath(raw: String): String {
            require(raw.isNotBlank() && raw.length <= 512) { "PACKAGE_PATH_INVALID" }
            require(!raw.startsWith('/') && !raw.startsWith('\\')) { "PACKAGE_PATH_ABSOLUTE" }
            require('\\' !in raw && '\u0000' !in raw) { "PACKAGE_PATH_INVALID" }
            val normalized = Normalizer.normalize(raw, Normalizer.Form.NFC)
            require(normalized == raw) { "PACKAGE_PATH_UNICODE_NOT_CANONICAL" }
            val parts = normalized.split('/')
            require(parts.none { part ->
                part.isBlank() || part == "." || part == ".." || part.endsWith(' ') || part.endsWith('.') ||
                    part.any { it.code < 0x20 } || ':' in part
            }) { "PACKAGE_PATH_TRAVERSAL" }
            return parts.joinToString("/")
        }

        fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

        private fun readBounded(input: InputStream, maxBytes: Int, error: String): ByteArray {
            val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= maxBytes) { error }
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }
}
