package vn.nghetruyen.source.vbook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class VBookCompatibilitySurfaceStaticTest {
    @Test
    fun legacyChromiumAndRhinoCompatibilitySurfaceStayWired() {
        val root = repositoryRoot()
        val runtime = Files.readString(
            root.resolve("source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookCompatibilityRuntime.kt"),
        )
        val rhinoRuntime = Files.readString(
            root.resolve("source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookJsRuntime.kt"),
        )
        val chromiumPrelude = Files.readString(
            root.resolve("app/src/main/java/vn/nghetruyen/app/sourceplatform/ChromiumVBookPrelude.kt"),
        )
        val safeFetch = Files.readString(
            root.resolve("source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookFetchSafePrelude.kt"),
        )

        assertTrue("Html.clean legacy API must be exposed", "Html.clean=function(content,allowedTags)" in runtime)
        assertTrue("native Html.clean should be preferred when available", "__vbookNativeHtmlClean" in runtime)
        assertTrue("Chromium Html.clean fallback should use DOMParser", "new DOMParser()" in runtime)
        assertTrue("Rhino host must expose native Html.clean", "VBookHtmlCleaner.clean(content, allowed)" in rhinoRuntime)
        assertFalse("dispatcher must not prohibit every nested load", "VBOOK_RECURSIVE_LOAD_NOT_ALLOWED" in runtime)

        assertTrue("Chromium loader must execute classic scripts in the shared document realm", "global.document.createElement('script')" in chromiumPrelude)
        assertTrue("Chromium loader must cache successfully loaded scripts", "__loadedScripts[path]" in chromiumPrelude)
        assertTrue("Chromium loader must detect actual cycles", "VBOOK_LOAD_CYCLE:" in chromiumPrelude)
        assertTrue("config globals must install into the shared script realm", "__ngheInstallGlobalPrelude" in chromiumPrelude)

        assertTrue("JSON decoding must preserve an explicit charset", "response.json=function(charset)" in safeFetch)
        assertTrue("request/response headers must expose lowercase aliases", "function __vbookSafeHeaderObject(source)" in safeFetch)
        assertTrue("safe fetch must recover replay response keys", "envelope.responseKey || requestKey" in safeFetch)
    }

    private fun repositoryRoot(): Path {
        val working = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        return listOfNotNull(working, working.parent).firstOrNull {
            Files.isDirectory(it.resolve("source-vbook")) && Files.isDirectory(it.resolve("app"))
        } ?: error("NGHETRUYEN_REPOSITORY_ROOT_NOT_FOUND:$working")
    }
}
