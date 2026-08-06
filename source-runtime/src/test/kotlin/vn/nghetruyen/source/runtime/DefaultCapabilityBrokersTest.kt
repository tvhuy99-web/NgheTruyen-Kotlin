package vn.nghetruyen.source.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.source.api.SemanticVersion
import vn.nghetruyen.source.api.SourceActionName
import vn.nghetruyen.source.api.SourceActionSpec
import vn.nghetruyen.source.api.SourceCapabilities
import vn.nghetruyen.source.api.SourceCryptoCapability
import vn.nghetruyen.source.api.SourceCryptoOperation
import vn.nghetruyen.source.api.SourceCryptoRequest
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.api.SourceRuntimeMode
import vn.nghetruyen.source.api.SourceRuntimePolicy
import vn.nghetruyen.source.api.SourceStorageRequest
import java.nio.file.Files

class DefaultCapabilityBrokersTest {
    private fun manifest(storage: Int = 128): SourceManifest = SourceManifest(
        schemaVersion = 2, id = "vn.nghetruyen.sources.test", name = "Test", version = SemanticVersion(1,0,0), apiVersion = 2,
        runtime = SourceRuntimePolicy(SourceRuntimeMode.DECLARATIVE), origins = setOf("https://example.org"),
        capabilities = SourceCapabilities(storageBytes = storage, crypto = setOf(
            SourceCryptoCapability.MD5,
            SourceCryptoCapability.SHA1,
            SourceCryptoCapability.SHA256,
            SourceCryptoCapability.SHA512,
            SourceCryptoCapability.HMAC_MD5,
            SourceCryptoCapability.HMAC_SHA1,
            SourceCryptoCapability.HMAC_SHA256,
            SourceCryptoCapability.HMAC_SHA512,
            SourceCryptoCapability.AES_COMPAT,
            SourceCryptoCapability.AES_GCM_SECRET,
        )),
        actions = mapOf(SourceActionName.DETAIL to SourceActionSpec("d"), SourceActionName.TOC to SourceActionSpec("t"), SourceActionName.CHAPTER to SourceActionSpec("c")),
    )

    @Test fun `file storage preserves values within quota`() {
        val broker = FileSourceStorageBroker(Files.createTempDirectory("storage-test").toFile())
        val m = manifest()
        val request = SourceStorageRequest(m.id, "hello", "world".toByteArray())
        assertEquals(Unit, (broker.put(m, request) as SourcePlatformResult.Success).value)
        assertTrue("world".toByteArray().contentEquals((broker.get(m, request.copy(value = null)) as SourcePlatformResult.Success).value ?: ByteArray(0)))
        broker.put(m, SourceStorageRequest(m.id, "cache/one", "1".toByteArray()))
        broker.put(m, SourceStorageRequest(m.id, "cache/two", "2".toByteArray()))
        broker.put(m, SourceStorageRequest(m.id, "other", "3".toByteArray()))
        val cacheKeys = (broker.keys(m, m.id, "cache/", "trace") as SourcePlatformResult.Success).value
        assertEquals(listOf("cache/one", "cache/two"), cacheKeys)
        assertTrue(broker.clearPrefix(m, m.id, "cache/", "trace") is SourcePlatformResult.Success)
        val remaining = (broker.keys(m, m.id, "", "trace") as SourcePlatformResult.Success).value
        assertEquals(listOf("hello", "other"), remaining)
    }

    @Test fun `crypto encrypt decrypt hashes and hmacs`() {
        val broker = JcaSourceCryptoBroker()
        val m = manifest()
        val encrypted = (broker.execute(m, SourceCryptoRequest(m.id, SourceCryptoOperation.AES_GCM_ENCRYPT, "secret".toByteArray())) as SourcePlatformResult.Success).value
        val plain = (broker.execute(m, SourceCryptoRequest(m.id, SourceCryptoOperation.AES_GCM_DECRYPT, encrypted)) as SourcePlatformResult.Success).value
        assertTrue("secret".toByteArray().contentEquals(plain))
        val payload = "x".toByteArray()
        val key = "k".toByteArray()
        val sizes = mapOf(
            SourceCryptoOperation.MD5 to 16,
            SourceCryptoOperation.SHA1 to 20,
            SourceCryptoOperation.SHA256 to 32,
            SourceCryptoOperation.SHA512 to 64,
        )
        sizes.forEach { (operation, size) ->
            val hash = (broker.execute(m, SourceCryptoRequest(m.id, operation, payload)) as SourcePlatformResult.Success).value
            assertEquals(size, hash.size)
        }
        val hmacSizes = mapOf(
            SourceCryptoOperation.HMAC_MD5 to 16,
            SourceCryptoOperation.HMAC_SHA1 to 20,
            SourceCryptoOperation.HMAC_SHA256 to 32,
            SourceCryptoOperation.HMAC_SHA512 to 64,
        )
        hmacSizes.forEach { (operation, size) ->
            val hmac = (broker.execute(m, SourceCryptoRequest(m.id, operation, payload, keyMaterial = key)) as SourcePlatformResult.Success).value
            assertEquals(size, hmac.size)
        }
    }
}
