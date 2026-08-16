package com.nghetruyen.source.store

import com.nghetruyen.source.platform.SourceArtifactDescriptor
import com.nghetruyen.source.platform.SourceArtifactIdentity
import com.nghetruyen.source.platform.SourceArtifactState
import com.nghetruyen.source.platform.SourceCompatibilityProfile
import com.nghetruyen.source.platform.SourceFailure
import com.nghetruyen.source.platform.SourceTrustState
import java.security.MessageDigest

data class SourceArtifactTransition(
    val identity: SourceArtifactIdentity,
    val beforeActive: SourceArtifactDescriptor?,
    val afterActive: SourceArtifactDescriptor?,
    val previousKnownGood: SourceArtifactDescriptor?,
    val quarantined: SourceArtifactDescriptor? = null,
    val failure: SourceFailure? = null,
)





object SourceArtifactLifecycle {
    fun candidate(
        artifactId: String,
        identity: SourceArtifactIdentity,
        version: String?,
        bytes: ByteArray,
        profile: SourceCompatibilityProfile?,
        trust: SourceTrustState,
        installedAtEpochMs: Long,
    ): SourceArtifactDescriptor = SourceArtifactDescriptor(
        artifactId = artifactId,
        identity = identity,
        version = version,
        sha256 = sha256(bytes),
        compatibilityProfile = profile,
        state = SourceArtifactState.CANDIDATE,
        trust = trust,
        installedAtEpochMs = installedAtEpochMs,
    )

    fun activate(
        candidate: SourceArtifactDescriptor,
        currentActive: SourceArtifactDescriptor?,
        activatedAtEpochMs: Long,
    ): SourceArtifactTransition {
        require(candidate.state == SourceArtifactState.CANDIDATE) { "SOURCE_ACTIVATION_CANDIDATE_REQUIRED" }
        require(currentActive == null || currentActive.state == SourceArtifactState.ACTIVE) { "SOURCE_ACTIVE_STATE_INVALID" }
        require(currentActive == null || currentActive.identity == candidate.identity) { "SOURCE_ACTIVATION_IDENTITY_MISMATCH" }
        require(activatedAtEpochMs >= candidate.installedAtEpochMs) { "SOURCE_ACTIVATION_TIME_INVALID" }

        val previous = currentActive?.copy(state = SourceArtifactState.PREVIOUS_KNOWN_GOOD)
        val active = candidate.copy(
            state = SourceArtifactState.ACTIVE,
            activatedAtEpochMs = activatedAtEpochMs,
            previousKnownGoodArtifactId = previous?.artifactId,
        )
        return SourceArtifactTransition(
            identity = candidate.identity,
            beforeActive = currentActive,
            afterActive = active,
            previousKnownGood = previous,
        )
    }

    fun quarantine(
        candidate: SourceArtifactDescriptor,
        currentActive: SourceArtifactDescriptor?,
        failure: SourceFailure,
    ): SourceArtifactTransition {
        require(candidate.state == SourceArtifactState.CANDIDATE) { "SOURCE_QUARANTINE_CANDIDATE_REQUIRED" }
        require(currentActive == null || currentActive.identity == candidate.identity) { "SOURCE_QUARANTINE_IDENTITY_MISMATCH" }
        return SourceArtifactTransition(
            identity = candidate.identity,
            beforeActive = currentActive,
            afterActive = currentActive,
            previousKnownGood = null,
            quarantined = candidate.copy(state = SourceArtifactState.QUARANTINED),
            failure = failure,
        )
    }

    fun rollback(
        currentActive: SourceArtifactDescriptor,
        previousKnownGood: SourceArtifactDescriptor,
        activatedAtEpochMs: Long,
        quarantineCurrent: Boolean = true,
    ): SourceArtifactTransition {
        require(currentActive.state == SourceArtifactState.ACTIVE) { "SOURCE_ROLLBACK_ACTIVE_REQUIRED" }
        require(previousKnownGood.state == SourceArtifactState.PREVIOUS_KNOWN_GOOD) { "SOURCE_ROLLBACK_PREVIOUS_REQUIRED" }
        require(currentActive.identity == previousKnownGood.identity) { "SOURCE_ROLLBACK_IDENTITY_MISMATCH" }

        val restored = previousKnownGood.copy(
            state = SourceArtifactState.ACTIVE,
            activatedAtEpochMs = activatedAtEpochMs,
            previousKnownGoodArtifactId = null,
        )
        return SourceArtifactTransition(
            identity = currentActive.identity,
            beforeActive = currentActive,
            afterActive = restored,
            previousKnownGood = null,
            quarantined = currentActive.takeIf { quarantineCurrent }?.copy(state = SourceArtifactState.QUARANTINED),
        )
    }

    fun disable(active: SourceArtifactDescriptor): SourceArtifactTransition {
        require(active.state == SourceArtifactState.ACTIVE) { "SOURCE_DISABLE_ACTIVE_REQUIRED" }
        return SourceArtifactTransition(
            identity = active.identity,
            beforeActive = active,
            afterActive = active.copy(state = SourceArtifactState.DISABLED),
            previousKnownGood = null,
        )
    }

    fun enable(disabled: SourceArtifactDescriptor, activatedAtEpochMs: Long): SourceArtifactTransition {
        require(disabled.state == SourceArtifactState.DISABLED) { "SOURCE_ENABLE_DISABLED_REQUIRED" }
        require(activatedAtEpochMs >= disabled.installedAtEpochMs) { "SOURCE_ENABLE_TIME_INVALID" }
        return SourceArtifactTransition(
            identity = disabled.identity,
            beforeActive = null,
            afterActive = disabled.copy(
                state = SourceArtifactState.ACTIVE,
                activatedAtEpochMs = activatedAtEpochMs,
            ),
            previousKnownGood = null,
        )
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

 
interface SourceArtifactArchive {
    fun stage(descriptor: SourceArtifactDescriptor, originalBytes: ByteArray)
    fun contains(artifactId: String): Boolean
    fun sha256(artifactId: String): String?
}

 
interface SourceArtifactRegistry {
    fun active(identity: SourceArtifactIdentity): SourceArtifactDescriptor?
    fun previousKnownGood(identity: SourceArtifactIdentity): SourceArtifactDescriptor?
    fun commit(transition: SourceArtifactTransition)
}

class SourceArtifactActivator(private val registry: SourceArtifactRegistry) {
    fun activate(candidate: SourceArtifactDescriptor, nowEpochMs: Long): SourceArtifactDescriptor {
        val transition = SourceArtifactLifecycle.activate(candidate, registry.active(candidate.identity), nowEpochMs)
        registry.commit(transition)
        return requireNotNull(transition.afterActive)
    }

    fun quarantine(candidate: SourceArtifactDescriptor, failure: SourceFailure) {
        registry.commit(SourceArtifactLifecycle.quarantine(candidate, registry.active(candidate.identity), failure))
    }

    fun rollback(identity: SourceArtifactIdentity, nowEpochMs: Long): SourceArtifactDescriptor {
        val active = registry.active(identity) ?: error("SOURCE_ROLLBACK_ACTIVE_MISSING")
        val previous = registry.previousKnownGood(identity) ?: error("SOURCE_ROLLBACK_PREVIOUS_MISSING")
        val transition = SourceArtifactLifecycle.rollback(active, previous, nowEpochMs)
        registry.commit(transition)
        return requireNotNull(transition.afterActive)
    }

    fun disable(identity: SourceArtifactIdentity): SourceArtifactDescriptor {
        val active = registry.active(identity) ?: error("SOURCE_DISABLE_ACTIVE_MISSING")
        val transition = SourceArtifactLifecycle.disable(active)
        registry.commit(transition)
        return requireNotNull(transition.afterActive)
    }
}
