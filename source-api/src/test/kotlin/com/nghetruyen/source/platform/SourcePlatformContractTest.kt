package com.nghetruyen.source.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePlatformContractTest {
    @Test
    fun canonicalIdentitySeparatesEcosystems() {
        val vbook = SourceArtifactIdentity(SourceEcosystem.VBOOK, "official", "pkg/wikidich")
        val legado = SourceArtifactIdentity(SourceEcosystem.LEGADO, "official", "pkg/wikidich")

        assertTrue(vbook.canonicalKey() != legado.canonicalKey())
    }

    @Test
    fun failureCodeCarriesDefaultResponsibility() {
        val engineGap = SourceFailure(SourceFailureCode.LEGADO_RULE_UNSUPPORTED, "ruleParam is not supported")
        val deadSite = SourceFailure(SourceFailureCode.UPSTREAM_UNAVAILABLE, "origin timed out")

        assertEquals(SourceFaultOwner.ENGINE, engineGap.owner)
        assertEquals(SourceFaultOwner.UPSTREAM, deadSite.owner)
        assertTrue(deadSite.retryable)
    }

    @Test
    fun artifactRejectsInvalidHash() {
        var rejected = false
        try {
            SourceArtifactDescriptor(
                artifactId = "a",
                identity = SourceArtifactIdentity(SourceEcosystem.VBOOK, "official", "a"),
                version = "1",
                sha256 = "bad",
                compatibilityProfile = null,
                state = SourceArtifactState.CANDIDATE,
                trust = SourceTrustState.UNVERIFIED,
                installedAtEpochMs = 1,
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }
}
