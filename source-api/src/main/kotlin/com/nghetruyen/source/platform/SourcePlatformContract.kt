package com.nghetruyen.source.platform








enum class SourceEcosystem {
    NATIVE,
    VBOOK,
    LEGADO,
}

data class SourceCompatibilityProfile(
    val ecosystem: SourceEcosystem,
    val id: String,
) {
    init {
        require(id.matches(PROFILE_ID)) { "Invalid compatibility profile id: $id" }
    }

    companion object {
        private val PROFILE_ID = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    }
}

data class SourceArtifactIdentity(
    val ecosystem: SourceEcosystem,
    val repositoryId: String,
    val remoteIdentity: String,
) {
    init {
        require(repositoryId.isNotBlank()) { "repositoryId must not be blank" }
        require(remoteIdentity.isNotBlank()) { "remoteIdentity must not be blank" }
    }

     
    fun canonicalKey(): String = buildString {
        append(ecosystem.name.lowercase())
        append('\n')
        append(repositoryId.trim())
        append('\n')
        append(remoteIdentity.trim())
    }
}

enum class SourceArtifactState {
    CANDIDATE,
    ACTIVE,
    PREVIOUS_KNOWN_GOOD,
    QUARANTINED,
    DISABLED,
}

enum class SourceTrustState {
    UNVERIFIED,
    USER_TRUSTED,
    REPOSITORY_TRUSTED,
    HASH_VERIFIED,
    BLOCKED,
}

enum class SourceCompatibilityState {
    UNKNOWN,
    SUPPORTED,
    PARTIAL,
    UNSUPPORTED,
}

enum class SourceHealthState {
    UNKNOWN,
    HEALTHY,
    AUTH_REQUIRED,
    UPSTREAM_DEGRADED,
    UPSTREAM_UNAVAILABLE,
    SOURCE_RULE_BROKEN,
}

data class SourceArtifactDescriptor(
    val artifactId: String,
    val identity: SourceArtifactIdentity,
    val version: String?,
    val sha256: String,
    val compatibilityProfile: SourceCompatibilityProfile?,
    val state: SourceArtifactState,
    val trust: SourceTrustState,
    val installedAtEpochMs: Long,
    val activatedAtEpochMs: Long? = null,
    val previousKnownGoodArtifactId: String? = null,
) {
    init {
        require(artifactId.isNotBlank()) { "artifactId must not be blank" }
        require(SHA256.matches(sha256)) { "sha256 must be a lowercase 64-character hex digest" }
        require(installedAtEpochMs >= 0) { "installedAtEpochMs must be non-negative" }
        require(activatedAtEpochMs == null || activatedAtEpochMs >= installedAtEpochMs) {
            "activatedAtEpochMs cannot be earlier than installedAtEpochMs"
        }
        require(compatibilityProfile == null || compatibilityProfile.ecosystem == identity.ecosystem) {
            "Compatibility profile ecosystem must match artifact ecosystem"
        }
    }

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

enum class SourceFaultOwner {
    ENGINE,
    SOURCE_ARTIFACT,
    UPSTREAM,
    USER,
    HOST,
    UNKNOWN,
}

enum class SourceFailureDomain {
    ARTIFACT,
    VBOOK,
    LEGADO,
    NETWORK,
    BROWSER,
    AUTH,
    SANDBOX,
    UPSTREAM,
    HOST,
}

enum class SourceFailureCode(
    val domain: SourceFailureDomain,
    val defaultOwner: SourceFaultOwner,
    val defaultRetryable: Boolean,
) {
    ARTIFACT_INVALID(SourceFailureDomain.ARTIFACT, SourceFaultOwner.SOURCE_ARTIFACT, false),
    ARTIFACT_UNSUPPORTED_FORMAT(SourceFailureDomain.ARTIFACT, SourceFaultOwner.SOURCE_ARTIFACT, false),
    ARTIFACT_HASH_MISMATCH(SourceFailureDomain.ARTIFACT, SourceFaultOwner.SOURCE_ARTIFACT, false),
    ARTIFACT_UPDATE_REJECTED(SourceFailureDomain.ARTIFACT, SourceFaultOwner.SOURCE_ARTIFACT, false),

    VBOOK_CONTRACT_UNSUPPORTED(SourceFailureDomain.VBOOK, SourceFaultOwner.ENGINE, false),
    VBOOK_SCRIPT_MISSING(SourceFailureDomain.VBOOK, SourceFaultOwner.SOURCE_ARTIFACT, false),
    VBOOK_HOST_API_UNSUPPORTED(SourceFailureDomain.VBOOK, SourceFaultOwner.ENGINE, false),
    VBOOK_RESPONSE_INVALID(SourceFailureDomain.VBOOK, SourceFaultOwner.SOURCE_ARTIFACT, false),

    LEGADO_DIALECT_UNSUPPORTED(SourceFailureDomain.LEGADO, SourceFaultOwner.ENGINE, false),
    LEGADO_RULE_UNSUPPORTED(SourceFailureDomain.LEGADO, SourceFaultOwner.ENGINE, false),
    LEGADO_RULE_INVALID(SourceFailureDomain.LEGADO, SourceFaultOwner.SOURCE_ARTIFACT, false),
    LEGADO_JS_ERROR(SourceFailureDomain.LEGADO, SourceFaultOwner.SOURCE_ARTIFACT, false),
    LEGADO_URL_INVALID(SourceFailureDomain.LEGADO, SourceFaultOwner.SOURCE_ARTIFACT, false),

    NETWORK_BLOCKED(SourceFailureDomain.NETWORK, SourceFaultOwner.HOST, false),
    NETWORK_TIMEOUT(SourceFailureDomain.NETWORK, SourceFaultOwner.UPSTREAM, true),
    NETWORK_HTTP_ERROR(SourceFailureDomain.NETWORK, SourceFaultOwner.UPSTREAM, true),

    BROWSER_REQUIRED(SourceFailureDomain.BROWSER, SourceFaultOwner.HOST, false),
    BROWSER_TIMEOUT(SourceFailureDomain.BROWSER, SourceFaultOwner.UPSTREAM, true),

    AUTH_REQUIRED(SourceFailureDomain.AUTH, SourceFaultOwner.USER, false),
    AUTH_FAILED(SourceFailureDomain.AUTH, SourceFaultOwner.USER, true),

    SANDBOX_TIMEOUT(SourceFailureDomain.SANDBOX, SourceFaultOwner.ENGINE, false),
    SANDBOX_INSTRUCTION_LIMIT(SourceFailureDomain.SANDBOX, SourceFaultOwner.ENGINE, false),
    SANDBOX_RESULT_TOO_LARGE(SourceFailureDomain.SANDBOX, SourceFaultOwner.SOURCE_ARTIFACT, false),
    SANDBOX_HOST_ACCESS_DENIED(SourceFailureDomain.SANDBOX, SourceFaultOwner.SOURCE_ARTIFACT, false),
    SANDBOX_SCRIPT_ERROR(SourceFailureDomain.SANDBOX, SourceFaultOwner.SOURCE_ARTIFACT, false),

    UPSTREAM_UNAVAILABLE(SourceFailureDomain.UPSTREAM, SourceFaultOwner.UPSTREAM, true),
    UPSTREAM_CONTENT_CHANGED(SourceFailureDomain.UPSTREAM, SourceFaultOwner.UPSTREAM, false),
    SOURCE_RULE_BROKEN(SourceFailureDomain.UPSTREAM, SourceFaultOwner.SOURCE_ARTIFACT, false),

    HOST_INTERNAL_ERROR(SourceFailureDomain.HOST, SourceFaultOwner.HOST, true),
}

data class SourceFailure(
    val code: SourceFailureCode,
    val message: String,
    val sourceId: String? = null,
    val action: String? = null,
    val owner: SourceFaultOwner = code.defaultOwner,
    val retryable: Boolean = code.defaultRetryable,
    val traceId: String? = null,
    val details: Map<String, String> = emptyMap(),
) {
    init {
        require(message.isNotBlank()) { "Failure message must not be blank" }
        require(details.size <= MAX_DETAILS) { "Failure details exceed $MAX_DETAILS entries" }
    }

    companion object {
        private const val MAX_DETAILS = 32
    }
}

data class SourceCompatibilityAssessment(
    val state: SourceCompatibilityState,
    val profile: SourceCompatibilityProfile?,
    val unsupportedFeatures: Set<String> = emptySet(),
    val referenceVersion: String? = null,
) {
    init {
        require(state != SourceCompatibilityState.SUPPORTED || unsupportedFeatures.isEmpty()) {
            "SUPPORTED assessment cannot contain unsupported features"
        }
    }
}

data class SourceOperationalAssessment(
    val compatibility: SourceCompatibilityAssessment,
    val health: SourceHealthState,
    val failure: SourceFailure? = null,
)

sealed class SourceExecutionOutcome<out T> {
    data class Success<T>(val value: T) : SourceExecutionOutcome<T>()
    data class Failure(val error: SourceFailure) : SourceExecutionOutcome<Nothing>()
}
