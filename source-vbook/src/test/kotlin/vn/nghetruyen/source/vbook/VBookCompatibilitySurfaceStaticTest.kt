package vn.nghetruyen.source.vbook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class VBookCompatibilitySurfaceStaticTest {
    @Test
    fun legacyHtmlCleanAndNestedLoadSupportStayWired() {
        val root = repositoryRoot()
        val runtime = Files.readString(
            root.resolve("source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookCompatibilityRuntime.kt"),
        )
        val chromiumPrelude = Files.readString(
            root.resolve("app/src/main/java/vn/nghetruyen/app/sourceplatform/ChromiumVBookPrelude.kt"),
        )

        assertTrue("Html.clean legacy API must be exposed", "Html.clean=function(content,allowedTags)" in runtime)
        assertTrue("native Html.clean should be preferred when available", "__vbookNativeHtmlClean" in runtime)
        assertTrue("Chromium fallback should use DOMParser", "new DOMParser()" in runtime)
        assertFalse("dispatcher must not prohibit every nested load", "VBOOK_RECURSIVE_LOAD_NOT_ALLOWED" in runtime)
        assertTrue("Chromium loader must cache successfully loaded scripts", "__loadedScripts[path]" in chromiumPrelude)
        assertTrue("Chromium loader must detect actual cycles", "VBOOK_LOAD_CYCLE:" in chromiumPrelude)
    }

    private fun repositoryRoot(): Path {
        val working = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        return listOfNotNull(working, working.parent).firstOrNull {
            Files.isDirectory(it.resolve("source-vbook")) && Files.isDirectory(it.resolve("app"))
        } ?: error("NGHETRUYEN_REPOSITORY_ROOT_NOT_FOUND:$working")
    }
}
