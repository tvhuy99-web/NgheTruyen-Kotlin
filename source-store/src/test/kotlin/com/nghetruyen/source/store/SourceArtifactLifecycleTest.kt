package com.nghetruyen.source.store

import com.nghetruyen.source.platform.SourceArtifactIdentity
import com.nghetruyen.source.platform.SourceArtifactState
import com.nghetruyen.source.platform.SourceCompatibilityProfile
import com.nghetruyen.source.platform.SourceEcosystem
import com.nghetruyen.source.platform.SourceFailure
import com.nghetruyen.source.platform.SourceFailureCode
import com.nghetruyen.source.platform.SourceTrustState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceArtifactLifecycleTest {
    private val identity = SourceArtifactIdentity(SourceEcosystem.VBOOK, "official", "catalog/wiki")
    private val profile = SourceCompatibilityProfile(SourceEcosystem.VBOOK, "current-js")

    @Test
    fun activationKeepsPreviousKnownGood() {
        val v1 = SourceArtifactLifecycle.candidate("v1", identity, "1", byteArrayOf(1), profile, SourceTrustState.HASH_VERIFIED, 10)
        val first = SourceArtifactLifecycle.activate(v1, null, 11).afterActive!!
        val v2 = SourceArtifactLifecycle.candidate("v2", identity, "2", byteArrayOf(2), profile, SourceTrustState.HASH_VERIFIED, 20)
        val transition = SourceArtifactLifecycle.activate(v2, first, 21)

        assertEquals(SourceArtifactState.ACTIVE, transition.afterActive!!.state)
        assertEquals("v1", transition.afterActive!!.previousKnownGoodArtifactId)
        assertEquals(SourceArtifactState.PREVIOUS_KNOWN_GOOD, transition.previousKnownGood!!.state)
    }

    @Test
    fun rejectedCandidateNeverReplacesActive() {
        val active = SourceArtifactLifecycle.activate(
            SourceArtifactLifecycle.candidate("good", identity, "1", byteArrayOf(1), profile, SourceTrustState.HASH_VERIFIED, 1),
            null,
            2,
        ).afterActive!!
        val bad = SourceArtifactLifecycle.candidate("bad", identity, "2", byteArrayOf(2), profile, SourceTrustState.UNVERIFIED, 3)
        val transition = SourceArtifactLifecycle.quarantine(
            bad,
            active,
            SourceFailure(SourceFailureCode.VBOOK_RESPONSE_INVALID, "invalid candidate"),
        )

        assertEquals(active, transition.afterActive)
        assertEquals(SourceArtifactState.QUARANTINED, transition.quarantined!!.state)
        assertTrue(transition.failure != null)
    }

    @Test
    fun rollbackRestoresPreviousAndQuarantinesBrokenActive() {
        val v1 = SourceArtifactLifecycle.activate(
            SourceArtifactLifecycle.candidate("v1", identity, "1", byteArrayOf(1), profile, SourceTrustState.HASH_VERIFIED, 1),
            null,
            2,
        ).afterActive!!
        val transition2 = SourceArtifactLifecycle.activate(
            SourceArtifactLifecycle.candidate("v2", identity, "2", byteArrayOf(2), profile, SourceTrustState.HASH_VERIFIED, 3),
            v1,
            4,
        )
        val rollback = SourceArtifactLifecycle.rollback(transition2.afterActive!!, transition2.previousKnownGood!!, 5)

        assertEquals("v1", rollback.afterActive!!.artifactId)
        assertEquals(SourceArtifactState.ACTIVE, rollback.afterActive!!.state)
        assertEquals("v2", rollback.quarantined!!.artifactId)
        assertNull(rollback.previousKnownGood)
    }
}
