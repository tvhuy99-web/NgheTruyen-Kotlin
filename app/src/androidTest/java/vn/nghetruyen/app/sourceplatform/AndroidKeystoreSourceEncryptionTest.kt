package vn.nghetruyen.app.sourceplatform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
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
import vn.nghetruyen.source.runtime.JcaSourceCryptoBroker
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreSourceEncryptionTest {
    @Test
    fun randomizedEncryptionKeyLetsProviderGenerateStorageAndCryptoIvs() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "source-keystore-${UUID.randomUUID()}")
        val manifest = manifest()
        val keyProvider = AndroidSourceSecretKeyProvider()

        try {
            val storage = EncryptedSourceStorageBroker(root, keyProvider)
            val secret = "csrf-token".toByteArray()
            val put = storage.put(manifest, SourceStorageRequest(manifest.id, "token", secret, "storage-put"))
            assertTrue(failureMessage(put), put is SourcePlatformResult.Success)
            val restored = storage.get(manifest, SourceStorageRequest(manifest.id, "token", traceId = "storage-get"))
            assertTrue(failureMessage(restored), restored is SourcePlatformResult.Success)
            assertArrayEquals(secret, (restored as SourcePlatformResult.Success).value)

            val crypto = JcaSourceCryptoBroker(keyProvider)
            val aad = "source-boundary".toByteArray()
            val encrypted = crypto.execute(manifest, SourceCryptoRequest(
                manifest.id,
                SourceCryptoOperation.AES_GCM_ENCRYPT,
                secret,
                associatedData = aad,
                traceId = "crypto-encrypt",
            ))
            assertTrue(failureMessage(encrypted), encrypted is SourcePlatformResult.Success)
            val decrypted = crypto.execute(manifest, SourceCryptoRequest(
                manifest.id,
                SourceCryptoOperation.AES_GCM_DECRYPT,
                (encrypted as SourcePlatformResult.Success).value,
                associatedData = aad,
                traceId = "crypto-decrypt",
            ))
            assertTrue(failureMessage(decrypted), decrypted is SourcePlatformResult.Success)
            assertArrayEquals(secret, (decrypted as SourcePlatformResult.Success).value)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun failureMessage(result: SourcePlatformResult<*>): String =
        (result as? SourcePlatformResult.Failure)?.let { "${it.error.code}:${it.error.message}" } ?: "expected success"

    private fun manifest() = SourceManifest(
        schemaVersion = 2,
        id = "vn.nghetruyen.sources.keystoretest",
        name = "Android Keystore Test",
        version = SemanticVersion(1, 0, 0),
        apiVersion = 2,
        runtime = SourceRuntimePolicy(SourceRuntimeMode.VBOOK_JS_COMPAT),
        origins = setOf("https://example.org"),
        capabilities = SourceCapabilities(
            storageBytes = 4_096,
            crypto = setOf(SourceCryptoCapability.AES_GCM_SECRET),
        ),
        actions = mapOf(
            SourceActionName.DETAIL to SourceActionSpec("detail.js"),
            SourceActionName.TOC to SourceActionSpec("toc.js"),
            SourceActionName.CHAPTER to SourceActionSpec("chapter.js"),
        ),
    )
}
