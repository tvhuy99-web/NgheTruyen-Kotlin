package com.nghetruyen.source.repository

import com.nghetruyen.source.platform.SourceArtifactDescriptor
import com.nghetruyen.source.platform.SourceArtifactIdentity
import com.nghetruyen.source.platform.SourceArtifactState
import com.nghetruyen.source.platform.SourceCompatibilityState
import com.nghetruyen.source.platform.SourceEcosystem
import com.nghetruyen.source.platform.SourceTrustState
import com.nghetruyen.source.store.SourceArtifactArchive
import com.nghetruyen.source.store.SourceArtifactLifecycle
import com.nghetruyen.source.store.SourceArtifactRegistry
import com.nghetruyen.source.store.SourceArtifactTransition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.source.vbook.VBookCandidateValidator
import vn.nghetruyen.source.vbook.VBookFeature
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class VBookUpdateCoordinatorTest {
    private val identity = SourceArtifactIdentity(SourceEcosystem.VBOOK, "official", "artifact-x")

    @Test
    fun validUpdateActivatesAndPreservesPreviousForRollback() {
        val registry = MemoryRegistry()
        val archive = MemoryArchive()
        val coordinator = VBookUpdateCoordinator(VBookCandidateValidator(), registry, archive)
        val first = coordinator.installOrUpdate(payload("v1", "1", goodPlugin(1), GOOD_SCRIPTS), 11)
        val second = coordinator.installOrUpdate(payload("v2", "2", goodPlugin(2), GOOD_SCRIPTS), 21)

        assertEquals(VBookUpdateDisposition.ACTIVATED, first.disposition)
        assertEquals("v2", second.active!!.artifactId)
        assertEquals("2", second.active!!.version)
        assertEquals("v1", registry.previousKnownGood(identity)!!.artifactId)
        assertTrue(archive.contains("v1") && archive.contains("v2"))
        assertEquals(second.descriptor.sha256, archive.sha256("v2"))

        val restored = coordinator.rollback(identity, 30)
        assertEquals("v1", restored.artifactId)
        assertEquals("1", restored.version)
    }

    @Test
    fun brokenCandidateIsQuarantinedWithoutReplacingActive() {
        val registry = MemoryRegistry()
        val archive = MemoryArchive()
        val coordinator = VBookUpdateCoordinator(VBookCandidateValidator(), registry, archive)
        coordinator.installOrUpdate(payload("good", "1", goodPlugin(1), GOOD_SCRIPTS), 11)
        val badScripts = GOOD_SCRIPTS - "src/chap.js"
        val result = coordinator.installOrUpdate(payload("bad", "2", goodPlugin(2), badScripts), 21)

        assertEquals(VBookUpdateDisposition.QUARANTINED, result.disposition)
        assertEquals("good", registry.active(identity)!!.artifactId)
        assertNotNull(registry.quarantined["bad"])
        assertEquals(SourceArtifactState.QUARANTINED, registry.quarantined.getValue("bad").state)
    }

    @Test
    fun partialEngineFeatureIsQuarantinedWithoutReplacingKnownGood() {
        val registry = MemoryRegistry()
        val archive = MemoryArchive()
        val coordinator = VBookUpdateCoordinator(VBookCandidateValidator(), registry, archive)
        coordinator.installOrUpdate(payload("good", "1", goodPlugin(1), GOOD_SCRIPTS), 11)

        val websocketScripts = GOOD_SCRIPTS + mapOf(
            "src/search.js" to """
                function execute(q,p){
                  var ws = new WebSocket('wss://ws.example');
                  var frame = ws.message();
                  return Response.success([{type:frame.type,data:frame.data}],p);
                }
            """.trimIndent(),
        )
        val result = coordinator.installOrUpdate(payload("ws-v2", "2", goodPlugin(2), websocketScripts), 21)

        assertEquals(VBookUpdateDisposition.QUARANTINED, result.disposition)
        assertEquals(SourceCompatibilityState.PARTIAL, result.validation.state)
        assertTrue(VBookFeature.WEBSOCKET in result.validation.blockingFeatures)
        assertFalse(VBookFeature.WEBSOCKET_FRAMES in result.validation.blockingFeatures)
        assertEquals("good", registry.active(identity)!!.artifactId)
        assertEquals(SourceArtifactState.QUARANTINED, registry.quarantined.getValue("ws-v2").state)
    }

    @Test
    fun advertisedVersionMismatchIsQuarantinedAndPackageVersionRemainsAuthoritative() {
        val registry = MemoryRegistry()
        val archive = MemoryArchive()
        val coordinator = VBookUpdateCoordinator(VBookCandidateValidator(), registry, archive)
        coordinator.installOrUpdate(payload("good", "1", goodPlugin(1), GOOD_SCRIPTS), 11)

        val result = coordinator.installOrUpdate(
            payload("mismatch", "2", goodPlugin(1), GOOD_SCRIPTS),
            21,
        )

        assertEquals(VBookUpdateDisposition.QUARANTINED, result.disposition)
        assertEquals("1", result.descriptor.version)
        assertTrue(result.validation.failures.any { it.message.startsWith("VBOOK_PACKAGE_VERSION_MISMATCH") })
        assertEquals("good", registry.active(identity)!!.artifactId)
    }

    @Test
    fun malformedZipIsArchivedAndQuarantinedNotActivated() {
        val registry = MemoryRegistry()
        val archive = MemoryArchive()
        val coordinator = VBookUpdateCoordinator(VBookCandidateValidator(), registry, archive)
        coordinator.installOrUpdate(payload("good", "1", goodPlugin(1), GOOD_SCRIPTS), 11)
        val malformed = VBookUpdatePayload(
            artifactId = "corrupt",
            identity = identity,
            version = "2",
            originalPackageBytes = "not-a-zip".toByteArray(),
            trust = SourceTrustState.UNVERIFIED,
            installedAtEpochMs = 20,
        )
        val result = coordinator.installOrUpdate(malformed, 21)

        assertEquals(VBookUpdateDisposition.QUARANTINED, result.disposition)
        assertEquals("good", registry.active(identity)!!.artifactId)
        assertTrue(result.validation.failures.isNotEmpty())
        assertEquals(result.descriptor.sha256, archive.sha256("corrupt"))
    }

    private fun payload(id: String, version: String, plugin: String, scripts: Map<String, String>): VBookUpdatePayload =
        VBookUpdatePayload(
            artifactId = id,
            identity = identity,
            version = version,
            originalPackageBytes = packageZip(plugin, scripts),
            trust = SourceTrustState.REPOSITORY_TRUSTED,
            installedAtEpochMs = if (version == "1") 10 else 20,
        )

    private fun packageZip(plugin: String, scripts: Map<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            val files = linkedMapOf<String, ByteArray>("plugin.json" to plugin.toByteArray())
            scripts.forEach { (path, source) -> files[path] = source.toByteArray() }
            files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private class MemoryArchive : SourceArtifactArchive {
        private val bytes = linkedMapOf<String, ByteArray>()
        override fun stage(descriptor: SourceArtifactDescriptor, originalBytes: ByteArray) {
            val existing = bytes[descriptor.artifactId]
            if (existing != null) require(existing.contentEquals(originalBytes)) { "ARCHIVE_IMMUTABLE" }
            else bytes[descriptor.artifactId] = originalBytes.copyOf()
        }
        override fun contains(artifactId: String): Boolean = artifactId in bytes
        override fun sha256(artifactId: String): String? = bytes[artifactId]?.let(SourceArtifactLifecycle::sha256)
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
        private fun goodPlugin(version: Int) = """
            {
              "metadata":{"name":"x","author":"a","version":$version,"source":"https://x.example","description":"","locale":"vi","regexp":"x","type":"novel","encrypt":false},
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
