package vn.nghetruyen.source.store

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Properties

class SourcePackStoreStartupProbeTest {
    @Test
    fun `startup probe skips only the exact bundled package sha`() {
        val root = Files.createTempDirectory("source-store-startup-probe").toFile()
        try {
            val sourceId = "vn.test.source"
            val versionDir = File(root, "sources/$sourceId/versions/1.0.0").apply { mkdirs() }
            File(root, "sources/$sourceId/active.version").writeText("1.0.0")
            File(versionDir, "source.json").writeText("{}")
            File(versionDir, "package.properties").outputStream().use { output ->
                Properties().apply { setProperty("packageSha256", "abc123") }.store(output, null)
            }

            val store = SourcePackStore(root)

            assertTrue(store.hasStoredSource(sourceId, "abc123"))
            assertTrue(store.hasStoredSource(sourceId, "ABC123"))
            assertFalse(store.hasStoredSource(sourceId, "different"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `startup probe rejects incomplete stored source`() {
        val root = Files.createTempDirectory("source-store-startup-probe-incomplete").toFile()
        try {
            val sourceId = "vn.test.source"
            val versionDir = File(root, "sources/$sourceId/versions/1.0.0").apply { mkdirs() }
            File(root, "sources/$sourceId/active.version").writeText("1.0.0")
            File(versionDir, "source.json").writeText("{}")

            val store = SourcePackStore(root)

            assertFalse(store.hasStoredSource(sourceId, "abc123"))
        } finally {
            root.deleteRecursively()
        }
    }
}
