package vn.nghetruyen.source.repository

import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SemanticVersion
import vn.nghetruyen.source.api.SourceErrorCode
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourcePlatformFailure
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticEvent
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity
import vn.nghetruyen.source.diagnostics.DiagnosticSink
import vn.nghetruyen.source.packagekit.SourceDetachedSignatureVerifier
import vn.nghetruyen.source.packagekit.SourceSignatureAlgorithm
import vn.nghetruyen.source.packagekit.SourceTrustKey
import java.net.URI
import java.util.Base64
import java.util.Locale
import java.util.UUID

const val SOURCE_REPOSITORY_SCHEMA_VERSION = 1

data class SourceRepositoryEntry(
    val sourceId: String,
    val name: String,
    val version: SemanticVersion,
    val description: String,
    val packageUrl: String,
    val packageSha256: String,
    val packageBytes: Int,
    val minAppVersion: SemanticVersion? = null,
    val maxAppVersion: SemanticVersion? = null,
    val adult: Boolean = false,
    val changelog: String = "",
)

data class SourceRepositoryIndex(
    val schemaVersion: Int,
    val repositoryId: String,
    val name: String,
    val generatedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val signerKeyId: String,
    val signatureAlgorithm: SourceSignatureAlgorithm,
    val packages: List<SourceRepositoryEntry>,
)

data class VerifiedSourceRepository(
    val index: SourceRepositoryIndex,
    val canonicalPayload: ByteArray,
)

enum class SourceRepositoryPackageStatus { NOT_INSTALLED, UPDATE_AVAILABLE, CURRENT, INSTALLED_NEWER, INCOMPATIBLE }

data class SourceRepositoryPackageView(
    val entry: SourceRepositoryEntry,
    val status: SourceRepositoryPackageStatus,
    val installedVersion: SemanticVersion?,
)

object SourceRepositoryCatalog {
    fun compare(
        repository: SourceRepositoryIndex,
        installedVersions: Map<String, SemanticVersion>,
        appVersion: SemanticVersion,
    ): List<SourceRepositoryPackageView> = repository.packages.map { entry ->
        val installed = installedVersions[entry.sourceId]
        val compatible = (entry.minAppVersion == null || appVersion >= entry.minAppVersion) &&
            (entry.maxAppVersion == null || appVersion <= entry.maxAppVersion)
        val status = when {
            !compatible -> SourceRepositoryPackageStatus.INCOMPATIBLE
            installed == null -> SourceRepositoryPackageStatus.NOT_INSTALLED
            installed < entry.version -> SourceRepositoryPackageStatus.UPDATE_AVAILABLE
            installed == entry.version -> SourceRepositoryPackageStatus.CURRENT
            else -> SourceRepositoryPackageStatus.INSTALLED_NEWER
        }
        SourceRepositoryPackageView(entry, status, installed)
    }.sortedWith(compareBy<SourceRepositoryPackageView> { it.status.ordinal }.thenBy { it.entry.name.lowercase(Locale.ROOT) })
}

class SourceRepositoryVerifier(
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    fun verify(
        raw: ByteArray,
        trustKeys: Collection<SourceTrustKey>,
        traceId: String = UUID.randomUUID().toString(),
    ): SourcePlatformResult<VerifiedSourceRepository> {
        val started = clockMs()
        return runCatching {
            require(raw.size in 1..MAX_INDEX_BYTES) { "SOURCE_REPOSITORY_TOO_LARGE" }
            val text = raw.toString(Charsets.UTF_8)
            require(text.toByteArray(Charsets.UTF_8).contentEquals(raw)) { "SOURCE_REPOSITORY_NOT_UTF8" }
            val root = JsonCodec.parse(text) as? JsonValue.Obj ?: error("SOURCE_REPOSITORY_NOT_OBJECT")
            root.requireOnly(ROOT_KEYS, "repository")
            val signatureBase64 = root.requiredString("signature").also {
                require(it.length <= MAX_SIGNATURE_BASE64_CHARS) { "SOURCE_REPOSITORY_SIGNATURE_INVALID" }
            }
            val signerKeyId = root.requiredString("signerKeyId").also {
                require(it.length <= MAX_SIGNER_KEY_ID_CHARS) { "SOURCE_REPOSITORY_SIGNER_KEY_INVALID" }
            }
            val algorithm = enumValue<SourceSignatureAlgorithm>(root.requiredString("signatureAlgorithm"), "signatureAlgorithm")
            val canonical = canonicalPayload(root)
            val valid = SourceDetachedSignatureVerifier.verify(
                trustKeys = trustKeys,
                keyId = signerKeyId,
                algorithm = algorithm,
                payload = canonical,
                signatureBytesRaw = runCatching { Base64.getDecoder().decode(signatureBase64) }
                    .getOrElse { error("SOURCE_REPOSITORY_SIGNATURE_INVALID") },
            )
            require(valid) { "SOURCE_REPOSITORY_SIGNATURE_INVALID" }
            val index = parseIndex(root, signerKeyId, algorithm)
            validateTime(index)
            VerifiedSourceRepository(index, canonical)
        }.fold(
            onSuccess = { verified ->
                diagnostics.emit(DiagnosticEvent(
                    timestampEpochMs = clockMs(), traceId = traceId, sourceId = "repository:${verified.index.repositoryId}",
                    category = DiagnosticCategory.TRUST, name = "REPOSITORY_VERIFIED",
                    durationMs = clockMs() - started,
                    attributes = mapOf("packages" to verified.index.packages.size.toString(), "signerKeyId" to verified.index.signerKeyId),
                ))
                SourcePlatformResult.Success(verified)
            },
            onFailure = { error ->
                val code = when {
                    error.message?.contains("SIGNATURE") == true -> SourceErrorCode.REPOSITORY_SIGNATURE_INVALID
                    error.message?.contains("EXPIRED") == true || error.message?.contains("TIME") == true -> SourceErrorCode.REPOSITORY_EXPIRED
                    else -> SourceErrorCode.REPOSITORY_INVALID
                }
                diagnostics.emit(DiagnosticEvent(
                    timestampEpochMs = clockMs(), traceId = traceId, sourceId = "repository:unknown",
                    category = DiagnosticCategory.TRUST, name = "REPOSITORY_VERIFY_FAILED", severity = DiagnosticSeverity.ERROR,
                    durationMs = clockMs() - started,
                    attributes = mapOf("code" to code.name, "error" to (error.message ?: error.javaClass.simpleName)),
                ))
                SourcePlatformResult.Failure(SourcePlatformFailure(code, error.message ?: "SOURCE_REPOSITORY_INVALID", traceId, error))
            },
        )
    }

    private fun parseIndex(
        root: JsonValue.Obj,
        signerKeyId: String,
        algorithm: SourceSignatureAlgorithm,
    ): SourceRepositoryIndex {
        val packages = root.requiredArray("packages").values.map { raw ->
            val item = raw as? JsonValue.Obj ?: error("SOURCE_REPOSITORY_PACKAGE_INVALID")
            item.requireOnly(PACKAGE_KEYS, "packages")
            val sourceId = item.requiredString("sourceId")
            require(SOURCE_ID.matches(sourceId)) { "SOURCE_REPOSITORY_SOURCE_ID_INVALID" }
            val packageUrl = item.requiredString("packageUrl")
            validatePackageUrl(packageUrl)
            val packageSha = item.requiredString("packageSha256").lowercase(Locale.ROOT)
            require(SHA256.matches(packageSha)) { "SOURCE_REPOSITORY_PACKAGE_HASH_INVALID" }
            SourceRepositoryEntry(
                sourceId = sourceId,
                name = item.requiredString("name").also { require(it.length <= 120) },
                version = SemanticVersion.parse(item.requiredString("version")),
                description = item.string("description").orEmpty().also { require(it.length <= 1000) },
                packageUrl = packageUrl,
                packageSha256 = packageSha,
                packageBytes = item.requiredInt("packageBytes").also { require(it in 1..MAX_PACKAGE_BYTES) },
                minAppVersion = item.string("minAppVersion")?.let(SemanticVersion::parse),
                maxAppVersion = item.string("maxAppVersion")?.let(SemanticVersion::parse),
                adult = item.bool("adult") ?: false,
                changelog = item.string("changelog").orEmpty().also { require(it.length <= 4000) },
            )
        }
        require(packages.isNotEmpty()) { "SOURCE_REPOSITORY_PACKAGES_REQUIRED" }
        require(packages.size <= MAX_PACKAGES) { "SOURCE_REPOSITORY_TOO_MANY_PACKAGES" }
        require(packages.map(SourceRepositoryEntry::sourceId).distinct().size == packages.size) { "SOURCE_REPOSITORY_DUPLICATE_SOURCE" }
        return SourceRepositoryIndex(
            schemaVersion = root.requiredInt("schemaVersion").also { require(it == SOURCE_REPOSITORY_SCHEMA_VERSION) },
            repositoryId = root.requiredString("repositoryId").also { require(REPOSITORY_ID.matches(it)) },
            name = root.requiredString("name").also { require(it.length <= 120) },
            generatedAtEpochMs = root.requiredLong("generatedAtEpochMs").also {
                require(it >= 0L) { "SOURCE_REPOSITORY_TIME_INVALID" }
            },
            expiresAtEpochMs = root.requiredLong("expiresAtEpochMs").also {
                require(it >= 0L) { "SOURCE_REPOSITORY_TIME_INVALID" }
            },
            signerKeyId = signerKeyId,
            signatureAlgorithm = algorithm,
            packages = packages,
        )
    }

    private fun validateTime(index: SourceRepositoryIndex) {
        val now = clockMs()
        require(index.generatedAtEpochMs <= now + MAX_CLOCK_SKEW_MS) { "SOURCE_REPOSITORY_TIME_IN_FUTURE" }
        require(index.expiresAtEpochMs > now - MAX_CLOCK_SKEW_MS) { "SOURCE_REPOSITORY_EXPIRED" }
        require(index.expiresAtEpochMs > index.generatedAtEpochMs) { "SOURCE_REPOSITORY_TIME_INVALID" }
        require(index.expiresAtEpochMs - index.generatedAtEpochMs <= MAX_VALIDITY_MS) { "SOURCE_REPOSITORY_VALIDITY_TOO_LONG" }
    }

    private fun canonicalPayload(root: JsonValue.Obj): ByteArray {
        val payload = linkedMapOf<String, JsonValue>()
        CANONICAL_KEYS.forEach { key ->
            payload[key] = if (key == "packages") {
                val packages = root.requiredArray("packages").values.map { raw ->
                    val item = raw as? JsonValue.Obj ?: error("SOURCE_REPOSITORY_PACKAGE_INVALID")
                    JsonValue.Obj(linkedMapOf<String, JsonValue>().apply {
                        PACKAGE_CANONICAL_KEYS.forEach { packageKey ->
                            item[packageKey]?.let { put(packageKey, it) }
                        }
                    })
                }
                JsonValue.Arr(packages)
            } else root[key] ?: error("SOURCE_FIELD_REQUIRED:$key")
        }
        return JsonCodec.stringify(JsonValue.Obj(payload)).toByteArray(Charsets.UTF_8)
    }

    private fun validatePackageUrl(raw: String) {
        require(raw.length <= 4096) { "SOURCE_REPOSITORY_PACKAGE_URL_INVALID" }
        val uri = runCatching { URI(raw) }.getOrNull() ?: error("SOURCE_REPOSITORY_PACKAGE_URL_INVALID")
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) {
            "SOURCE_REPOSITORY_PACKAGE_URL_INVALID"
        }
    }

    private inline fun <reified T : Enum<T>> enumValue(raw: String, field: String): T =
        enumValues<T>().firstOrNull { it.name == raw.uppercase(Locale.ROOT) } ?: error("SOURCE_ENUM_INVALID:$field:$raw")

    private fun JsonValue.Obj.requiredString(name: String): String = string(name)?.takeIf(String::isNotBlank)
        ?: error("SOURCE_FIELD_REQUIRED:$name")
    private fun JsonValue.Obj.requiredInt(name: String): Int = int(name) ?: error("SOURCE_FIELD_REQUIRED:$name")
    private fun JsonValue.Obj.requiredLong(name: String): Long = long(name) ?: error("SOURCE_FIELD_REQUIRED:$name")
    private fun JsonValue.Obj.requiredArray(name: String): JsonValue.Arr = array(name) ?: error("SOURCE_FIELD_REQUIRED:$name")
    private fun JsonValue.Obj.requireOnly(allowed: Set<String>, scope: String) {
        val unknown = values.keys - allowed
        require(unknown.isEmpty()) { "SOURCE_UNKNOWN_FIELD:$scope:${unknown.joinToString()}" }
    }

    companion object {
        const val MAX_INDEX_BYTES = 1024 * 1024
        const val MAX_PACKAGES = 500
        const val MAX_PACKAGE_BYTES = 16 * 1024 * 1024
        private const val MAX_SIGNER_KEY_ID_CHARS = 200
        private const val MAX_SIGNATURE_BASE64_CHARS = 4096
        private const val MAX_CLOCK_SKEW_MS = 5 * 60 * 1000L
        private const val MAX_VALIDITY_MS = 90L * 24 * 60 * 60 * 1000
        private val SOURCE_ID = Regex("^[a-z][a-z0-9]*(\\.[a-z0-9][a-z0-9-]*){2,}$")
        private val REPOSITORY_ID = Regex("^[a-z][a-z0-9]*(\\.[a-z0-9][a-z0-9-]*){2,}$")
        private val SHA256 = Regex("^[0-9a-f]{64}$")
        private val CANONICAL_KEYS = listOf(
            "schemaVersion", "repositoryId", "name", "generatedAtEpochMs", "expiresAtEpochMs",
            "signerKeyId", "signatureAlgorithm", "packages",
        )
        private val ROOT_KEYS = CANONICAL_KEYS.toSet() + "signature"
        private val PACKAGE_CANONICAL_KEYS = listOf(
            "sourceId", "name", "version", "description", "packageUrl", "packageSha256", "packageBytes",
            "minAppVersion", "maxAppVersion", "adult", "changelog",
        )
        private val PACKAGE_KEYS = PACKAGE_CANONICAL_KEYS.toSet()
    }
}
