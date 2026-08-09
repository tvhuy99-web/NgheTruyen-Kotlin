package com.nghetruyen.source.repository

import com.nghetruyen.source.platform.SourceArtifactDescriptor
import com.nghetruyen.source.platform.SourceArtifactIdentity
import com.nghetruyen.source.platform.SourceCompatibilityState
import com.nghetruyen.source.platform.SourceFailure
import com.nghetruyen.source.platform.SourceFailureCode
import com.nghetruyen.source.platform.SourceTrustState
import com.nghetruyen.source.store.SourceArtifactActivator
import com.nghetruyen.source.store.SourceArtifactLifecycle
import com.nghetruyen.source.store.SourceArtifactRegistry
import vn.nghetruyen.source.vbook.VBookCandidate
import vn.nghetruyen.source.vbook.VBookCandidateValidation
import vn.nghetruyen.source.vbook.VBookCandidateValidator
import vn.nghetruyen.source.vbook.VBookPackageReader
import vn.nghetruyen.source.vbook.VBookScriptPayloadDecoder

/** Immutable archive for original extension bytes. Implementations may be file-, database- or blob-backed. */
interface SourceArtifactArchive {
    fun stage(descriptor: SourceArtifactDescriptor, originalBytes: ByteArray)
    fun contains(artifactId: String): Boolean
    fun sha256(artifactId: String): String?
}

enum class VBookUpdateDisposition {
    ACTIVATED,
    QUARANTINED,
}

data class VBookUpdatePayload(
    val artifactId: String,
    val identity: SourceArtifactIdentity,
    val version: String?,
    val originalPackageBytes: ByteArray,
    val trust: SourceTrustState,
    val installedAtEpochMs: Long,
)

data class VBookUpdateResult(
    val disposition: VBookUpdateDisposition,
    val descriptor: SourceArtifactDescriptor,
    val validation: VBookCandidateValidation,
    val active: SourceArtifactDescriptor?,
)

/**
 * Validates the exact immutable bytes that are staged and later activated. A caller cannot validate
 * one source tree and archive another package. Pointer changes remain atomic at the registry layer.
 */
class VBookUpdateCoordinator(
    private val validator: VBookCandidateValidator,
    private val registry: SourceArtifactRegistry,
    private val archive: SourceArtifactArchive,
    private val scriptDecoder: VBookScriptPayloadDecoder = VBookScriptPayloadDecoder.PLAIN_UTF8,
) {
    private val activator = SourceArtifactActivator(registry)

    fun installOrUpdate(payload: VBookUpdatePayload, activatedAtEpochMs: Long): VBookUpdateResult {
        require(payload.originalPackageBytes.isNotEmpty()) { "VBOOK_PACKAGE_BYTES_REQUIRED" }
        val validation = validateExactPackage(payload)
        val descriptor = SourceArtifactLifecycle.candidate(
            artifactId = payload.artifactId,
            identity = payload.identity,
            version = payload.version,
            bytes = payload.originalPackageBytes,
            profile = validation.profile,
            trust = payload.trust,
            installedAtEpochMs = payload.installedAtEpochMs,
        )

        archive.stage(descriptor, payload.originalPackageBytes.copyOf())
        require(archive.contains(descriptor.artifactId)) { "SOURCE_ARTIFACT_ARCHIVE_STAGE_FAILED" }
        require(archive.sha256(descriptor.artifactId) == descriptor.sha256) { "SOURCE_ARTIFACT_ARCHIVE_HASH_MISMATCH" }

        if (!validation.activatable) {
            val failure = validation.failures.firstOrNull() ?: SourceFailure(
                SourceFailureCode.ARTIFACT_UPDATE_REJECTED,
                "VBOOK_CANDIDATE_NOT_ACTIVATABLE",
                sourceId = payload.artifactId,
            )
            activator.quarantine(descriptor, failure)
            return VBookUpdateResult(
                disposition = VBookUpdateDisposition.QUARANTINED,
                descriptor = descriptor,
                validation = validation,
                active = registry.active(payload.identity),
            )
        }

        val active = activator.activate(descriptor, activatedAtEpochMs)
        return VBookUpdateResult(
            disposition = VBookUpdateDisposition.ACTIVATED,
            descriptor = descriptor,
            validation = validation,
            active = active,
        )
    }

    fun rollback(identity: SourceArtifactIdentity, activatedAtEpochMs: Long): SourceArtifactDescriptor =
        activator.rollback(identity, activatedAtEpochMs)

    private fun validateExactPackage(payload: VBookUpdatePayload): VBookCandidateValidation = runCatching {
        val pkg = VBookPackageReader.read(payload.originalPackageBytes)
        val pluginJson = pkg.pluginJson()
        val scripts = pkg.decodeScripts(scriptDecoder)
        validator.validate(VBookCandidate(payload.artifactId, pluginJson, scripts))
    }.getOrElse { error ->
        VBookCandidateValidation(
            candidate = VBookCandidate(payload.artifactId, "", emptyMap()),
            audit = null,
            state = SourceCompatibilityState.UNSUPPORTED,
            profile = null,
            failures = listOf(SourceFailure(
                SourceFailureCode.ARTIFACT_INVALID,
                error.message ?: "VBOOK_PACKAGE_INVALID",
                sourceId = payload.artifactId,
            )),
            warnings = emptyList(),
        )
    }
}
