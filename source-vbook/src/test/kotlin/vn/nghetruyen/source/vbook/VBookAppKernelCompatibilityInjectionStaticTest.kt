package vn.nghetruyen.source.vbook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class VBookAppKernelCompatibilityInjectionStaticTest {
    @Test
    fun currentAndLegacyProfilesShareOneAppInjectionPoint() {
        val root = repositoryRoot()
        val compatibility = Files.readString(
            root.resolve("source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookCompatibilityRuntime.kt"),
        )
        val websocketPrelude = Files.readString(
            root.resolve("source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookWebSocketPrelude.kt"),
        )

        val appInjection = "append(VBookAppKernelPrelude.build())"
        assertTrue(appInjection in compatibility)
        assertTrue("if (profile == VBookContractProfile.CURRENT_JS)" in compatibility)
        assertTrue("append(VBookWebSocketPrelude.build())" in compatibility)
        assertFalse("App injection must not remain nested inside the current-only WebSocket prelude", appInjection in websocketPrelude)

        val currentOnlyIndex = compatibility.indexOf("if (profile == VBookContractProfile.CURRENT_JS)")
        val appIndex = compatibility.indexOf(appInjection)
        assertTrue("App injection must occur after the current-only compatibility wrappers", currentOnlyIndex >= 0 && appIndex > currentOnlyIndex)
    }

    private fun repositoryRoot(): Path {
        val working = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val candidates = listOf(working, working.parent).filterNotNull()
        return candidates.firstOrNull {
            Files.isDirectory(it.resolve("source-vbook")) && Files.isDirectory(it.resolve("source-api"))
        } ?: error("NGHETRUYEN_REPOSITORY_ROOT_NOT_FOUND:$working")
    }
}
