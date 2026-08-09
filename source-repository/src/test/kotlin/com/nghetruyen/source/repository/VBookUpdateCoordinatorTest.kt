package com.nghetruyen.source.repository

import com.nghetruyen.source.platform.SourceArtifactDescriptor
import com.nghetruyen.source.platform.SourceArtifactIdentity
import com.nghetruyen.source.platform.SourceArtifactState
import com.nghetruyen.source.platform.SourceEcosystem
import com.nghetruyen.source.platform.SourceTrustState
import com.nghetruyen.source.store.SourceArtifactRegistry
import com.nghetruyen.source.store.SourceArtifactTransition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.source.vbook.VBookCandidateValidator

class VBookUpdateCoordinatorTest {
    private val identity = SourceArtifactIdentity(SourceEcosystem.VBOOK, "official", "artifact-x")

    @Test
    fun validUpdateActivatesAndPreservesPreviousForRollback() {
        val registry = MemoryRegistry()
        val archive = MemoryArchive()
        val coordinator = VBookUpdateCoordinator(VBookCandidateValidator(), registry, archive)
        val first = coordinator.installOrUpdate(payload("v1", "1", GOOD_PLUGIN, GOOD_SCRIPTS), 11)
        val second = coordinator.installOrUpdate(payload("v2", "2", GOOD_PLUGIN, GOOD_SCRIPTS), 21)

        assertEquals(VBookUpdateDisposition.ACTIVATED, first.disposition)
        assertEquals("v2", second.active!!.artifactId)
        assertEquals("v1", registry.previousKnownGood(identity)!!.artifactId)
        assertTrue(archive.contains("v1") && archive.contains("v2"))

        val restored = coordinator.rollback(identity, 30)
        assertEquals("v1", restored.artifactId)
    }

    @Test
    fun brokenCandidateIsQuarantinedWithoutReplacingActive() {
        val registry = MemoryRegistry()
        val archive = MemoryArchive()
        val coordinator = VBookUpdateCoordinator(VBookCandidateValidator(), registry, archive)
        coordinator.installOrUpdate(payload("good", "1", GOOD_PLUGIN, GOOD_SCRIPTS), 11)
        val badScripts = GOOD_SCRIPTS - "src/chap.js"
        val result = coordinator.installOrUpdate(payload("bad", "2", GOOD_PLUGIN, badScripts), 21)

        assertEquals(VBookUpdateDisposition.QUARANTINED, result.disposition)
        assertEquals("good", registry.active(identity)!!.artifactId)
        assertNotNull(registry.quarantined["bad"])
        assertEquals(SourceArtifactState.QUARANTINED, registry.quarantined.getValue("bad").state)
    }

    private fun payload(id: String, version: String, plugin: String, scripts: Map<String, String>) = VBookUpdatePayload(
        artifactId = id,
        identity = identity,
        version = version,
        originalPackageBytes = (id + version).toByteArray(),
        pluginJson = plugin,
        scripts = scripts,
        trust = SourceTrustState.REPOSITORY_TRUSTED,
        installedAtEpochMs = if (version == "1") 10 else 20,
    )

    private class MemoryArchive : SourceArtifactArchive {
        private val bytes = linkedMapOf<String, ByteArray>()
        override fun stage(descriptor: SourceArtifactDescriptor, originalBytes: ByteArray) {
            bytes.putIfAbsent(descriptor.artifactId, originalBytes.copyOf())
        }
        override fun contains(artifactId: String): Boolean = artifactId in bytes
    }

    private class MemoryRegistry : SourceArtifactRegistry {
        private val active = linkedMapOf<SourceArtifactIdentity, SourceArtifactDescriptor>()
        private val previous = linkedMapOf<SourceArtifactIdentity, SourceArtifactDescriptor>()
        val quarantined = linkedMapOf<String, SourceArtifactDescriptor>()

        override fun active(identity: SourceArtifactIdentity): SourceArtifactDescriptor? = active[identity]
        override fun previousKnownGood(identity: SourceArtifactIdentity): SourceArtifactDescriptor? = previous[identity]

        override fun commit(transition: SourceArtifactTransition) {
            transition.afterActive?.let { value ->
                if (value.state == SourceArtifactState.ACTIVE) active[transition.identity] = value
                else active.remove(transition.identity)
            } ?: active.remove(transition.identity)
            transition.previousKnownGood?.let { previous[transition.identity] = it }
            if (transition.previousKnownGood == null && transition.beforeActive?.state == SourceArtifactState.ACTIVE &&
                transition.afterActive?.artifactId == previous[transition.identity]?.artifactId
            ) {
                previous.remove(transition.identity)
            }
            transition.quarantined?.let { quarantined[it.artifactId] = it }
        }
    }

    companion object {
        private val GOOD_PLUGIN = """
            {
              "metadata":{"name":"x","author":"a","version":1,"source":"https://x.example","description":"","locale":"vi","regexp":"x","type":"novel","encrypt":false},
              "script":{"explore":"explore.js","search":"search.js","detail":"detail.js","toc":"toc.js","chap":"chap.js"},
              "config":{"DOMAIN":{"title":"Domain","default":"https://x.example","mode":"input","format":"text"}}
            }
        """.trimIndent()
        private val GOOD_SCRIPTS = mapOf(
            "src/explore.js" to "function execute(){return Response.success([]);}",
            "src/search.js" to "function execute(q,p){return Response.success([],p);}",
            "src/detail.js" to "function execute(u){return Response.success({name:'x',url:u});}",
            "src/toc.js" to "function execute(u){return Response.success([]);}",
            "src/chap.js" to "function execute(u){return Response.success('x');}",
        )
    }
}
