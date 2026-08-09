package com.nghetruyen.source.repository

import com.nghetruyen.source.platform.SourceArtifactDescriptor
import com.nghetruyen.source.platform.SourceArtifactIdentity
import com.nghetruyen.source.platform.SourceFailure
import com.nghetruyen.source.platform.SourceFailureCode
import com.nghetruyen.source.platform.SourceTrustState
import com.nghetruyen.source.store.SourceArtifactActivator
import com.nghetruyen.source.store.SourceArtifactLifecycle
import com.nghetruyen.source.store.SourceArtifactRegistry
import vn.nghetruyen.source.vbook.VBookCandidate
import vn.nghetruyen.source.vbook.VBookCandidateValidation
import vn.nghetruyen.source.vbook.VBookCandidateValidator

/** Immutable archive for original extension bytes. Implementations may be file-, database- or blob-backed. */
interface SourceArtifactArchive {
    fun stage(descriptor: SourceArtifactDescriptor, originalBytes: ByteArray)
    fun contains(artifactId: String): Boolean
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
    val pluginJson: String,
    val scripts: Map<String, String>,
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
 * Downloading and ZIP extraction happen before this coordinator. The coordinator enforces the
 * irreversible boundary: validate -> stage immutable bytes -> atomically move the active pointer.
 */
class VBookUpdateCoordinator(
    private val validator: VBookCandidateValidator,
    private val registry: SourceArtifactRegistry,
    private val archive: SourceArtifactArchive,
) {
    private val activator = SourceArtifactActivator(registry)

    fun installOrUpdate(payload: VBookUpdatePayload, activatedAtEpochMs: Long): VBookUpdateResult {
        require(payload.originalPackageBytes.isNotEmpty()) { "VBOOK_PACKAGE_BYTES_REQUIRED" }
        val validation = validator.validate(VBookCandidate(
            artifactId = payload.artifactId,
            pluginJson = payload.pluginJson,
            scripts = payload.scripts,
        ))
        val descriptor = SourceArtifactLifecycle.candidate(
            artifactId = payload.artifactId,
            identity = payload.identity,
            version = payload.version,
            bytes = payload.originalPackageBytes,
            profile = validation.profile,
            trust = payload.trust,
            installedAtEpochMs = payload.installedAtEpochMs,
        )

        // Archive is content-addressed/immutable. Staging before pointer commit is crash-safe:
        // orphan candidates can be garbage-collected, while the existing active artifact remains valid.
        archive.stage(descriptor, payload.originalPackageBytes.copyOf())
        require(archive.contains(descriptor.artifactId)) { "SOURCE_ARTIFACT_ARCHIVE_STAGE_FAILED" }

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
}
