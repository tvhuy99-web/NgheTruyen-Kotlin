package com.nghetruyen.source.store

import com.nghetruyen.source.platform.SourceArtifactIdentity
import com.nghetruyen.source.platform.SourceCompatibilityProfile
import com.nghetruyen.source.platform.SourceEcosystem
import com.nghetruyen.source.platform.SourceTrustState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class FileSourceArtifactStoreTest {
    @Test
    fun activePreviousAndOriginalBytesSurviveReopenAndRollback() {
        val root = Files.createTempDirectory("source-artifact-store")
        val identity = SourceArtifactIdentity(SourceEcosystem.VBOOK, "official", "repo/path")
        val profile = SourceCompatibilityProfile(SourceEcosystem.VBOOK, "current-js")

        val firstStore = FileSourceArtifactStore(root)
        val v1Bytes = "package-v1".toByteArray()
        val v1 = SourceArtifactLifecycle.candidate(
            artifactId = "v1",
            identity = identity,
            version = "1",
            bytes = v1Bytes,
            profile = profile,
            trust = SourceTrustState.HASH_VERIFIED,
            installedAtEpochMs = 10,
        )
        firstStore.stage(v1, v1Bytes)
        SourceArtifactActivator(firstStore).activate(v1, 11)

        val reopened = FileSourceArtifactStore(root)
        assertEquals("v1", reopened.active(identity)?.artifactId)
        assertEquals(listOf("v1"), reopened.activeArtifacts(SourceEcosystem.VBOOK).map { it.artifactId })
        assertArrayEquals(v1Bytes, reopened.originalBytes("v1"))

        val secondIdentity = SourceArtifactIdentity(SourceEcosystem.VBOOK, "community", "other/path")
        val otherBytes = "package-other".toByteArray()
        val other = SourceArtifactLifecycle.candidate(
            artifactId = "other-v1",
            identity = secondIdentity,
            version = "1",
            bytes = otherBytes,
            profile = profile,
            trust = SourceTrustState.REPOSITORY_TRUSTED,
            installedAtEpochMs = 15,
        )
        reopened.stage(other, otherBytes)
        SourceArtifactActivator(reopened).activate(other, 16)

        val v2Bytes = "package-v2".toByteArray()
        val v2 = SourceArtifactLifecycle.candidate(
            artifactId = "v2",
            identity = identity,
            version = "2",
            bytes = v2Bytes,
            profile = profile,
            trust = SourceTrustState.HASH_VERIFIED,
            installedAtEpochMs = 20,
        )
        reopened.stage(v2, v2Bytes)
        SourceArtifactActivator(reopened).activate(v2, 21)

        val reopenedAgain = FileSourceArtifactStore(root)
        assertEquals("v2", reopenedAgain.active(identity)?.artifactId)
        assertEquals("v1", reopenedAgain.previousKnownGood(identity)?.artifactId)
        assertEquals(setOf("v2", "other-v1"), reopenedAgain.activeArtifacts(SourceEcosystem.VBOOK).map { it.artifactId }.toSet())
        assertTrue(reopenedAgain.activeArtifacts(SourceEcosystem.NATIVE).isEmpty())

        val restored = SourceArtifactActivator(reopenedAgain).rollback(identity, 30)
        assertEquals("v1", restored.artifactId)

        val afterRollback = FileSourceArtifactStore(root)
        assertEquals("v1", afterRollback.active(identity)?.artifactId)
        assertNull(afterRollback.previousKnownGood(identity))
        assertEquals(setOf("v1", "other-v1"), afterRollback.activeArtifacts(SourceEcosystem.VBOOK).map { it.artifactId }.toSet())
        assertArrayEquals(v2Bytes, afterRollback.originalBytes("v2"))
    }

    @Test
    fun archiveIsImmutableForSameArtifactId() {
        val root = Files.createTempDirectory("source-artifact-immutable")
        val store = FileSourceArtifactStore(root)
        val identity = SourceArtifactIdentity(SourceEcosystem.VBOOK, "official", "repo/path")
        val profile = SourceCompatibilityProfile(SourceEcosystem.VBOOK, "current-js")
        val bytes = "one".toByteArray()
        val descriptor = SourceArtifactLifecycle.candidate(
            "artifact", identity, "1", bytes, profile, SourceTrustState.HASH_VERIFIED, 1,
        )
        store.stage(descriptor, bytes)
        val failure = runCatching { store.stage(descriptor.copy(sha256 = SourceArtifactLifecycle.sha256("two".toByteArray())), "two".toByteArray()) }.exceptionOrNull()
        assertTrue(failure != null)
        assertArrayEquals(bytes, store.originalBytes("artifact"))
    }
}
