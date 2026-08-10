package vn.nghetruyen.app.sourceplatform

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.source.api.SemanticVersion
import vn.nghetruyen.source.api.SourceActionName
import vn.nghetruyen.source.api.SourceActionSpec
import vn.nghetruyen.source.api.SourceCapabilities
import vn.nghetruyen.source.api.SourceErrorCode
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.api.SourceRuntimeMode
import vn.nghetruyen.source.api.SourceRuntimePolicy
import vn.nghetruyen.source.api.SourceStorageRequest
import vn.nghetruyen.source.runtime.InMemorySourceSecretKeyProvider
import java.nio.file.Files

class EncryptedSourceStorageBrokerTest {
    @Test
    fun `values are encrypted on disk and quota counts plaintext`() {
        val root = Files.createTempDirectory("vbook-encrypted-storage").toFile()
        val broker = EncryptedSourceStorageBroker(root, InMemorySourceSecretKeyProvider())
        val manifest = manifest(storageBytes = 10)
        val secret = "secret".toByteArray()

        assertTrue(broker.put(manifest, SourceStorageRequest(manifest.id, "token", secret)) is SourcePlatformResult.Success)
        val restored = broker.get(manifest, SourceStorageRequest(manifest.id, "token")) as SourcePlatformResult.Success
        assertArrayEquals(secret, restored.value)
        assertFalse(root.walkTopDown().filter { it.isFile }.any { it.readBytes().containsSlice(secret) })

        assertTrue(broker.put(manifest, SourceStorageRequest(manifest.id, "rest", "1234".toByteArray())) is SourcePlatformResult.Success)
        val quota = broker.put(manifest, SourceStorageRequest(manifest.id, "extra", byteArrayOf(1))) as SourcePlatformResult.Failure
        assertTrue(quota.error.code == SourceErrorCode.STORAGE_QUOTA_EXCEEDED)
    }

    private fun manifest(storageBytes: Int) = SourceManifest(
        schemaVersion = 2,
        id = "vn.nghetruyen.sources.vbooktest",
        name = "vBook Test",
        version = SemanticVersion(1, 0, 0),
        apiVersion = 2,
        runtime = SourceRuntimePolicy(SourceRuntimeMode.VBOOK_JS_COMPAT),
        origins = setOf("https://example.org"),
        capabilities = SourceCapabilities(storageBytes = storageBytes),
        actions = mapOf(
            SourceActionName.DETAIL to SourceActionSpec("detail.js"),
            SourceActionName.TOC to SourceActionSpec("toc.js"),
            SourceActionName.CHAPTER to SourceActionSpec("chapter.js"),
        ),
    )
}

private fun ByteArray.containsSlice(needle: ByteArray): Boolean {
    if (needle.isEmpty()) return true
    return indices.any { start ->
        start + needle.size <= size && needle.indices.all { offset -> this[start + offset] == needle[offset] }
    }
}
