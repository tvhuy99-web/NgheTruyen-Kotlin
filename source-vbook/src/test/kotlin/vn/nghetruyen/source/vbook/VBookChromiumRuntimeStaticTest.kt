package vn.nghetruyen.source.vbook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class VBookChromiumRuntimeStaticTest {
    @Test
    fun chromiumActionEngineStaysHeadlessJsonOnlyAndNetworkBrokered() {
        val root = repositoryRoot()
        val runtime = Files.readString(
            root.resolve("app/src/main/java/vn/nghetruyen/app/sourceplatform/AndroidChromiumVBookRuntime.kt"),
        )
        val prelude = Files.readString(
            root.resolve("app/src/main/java/vn/nghetruyen/app/sourceplatform/ChromiumVBookPrelude.kt"),
        )

        listOf(
            "HandlerThread(\"NgheTruyen-VBook-Chromium\")",
            "override fun onJsPrompt(",
            "blockNetworkLoads = true",
            "allowFileAccess = false",
            "allowContentAccess = false",
            "SourceHostKernelWireExecutor.execute(",
            "brokers.network.execute(",
            "brokers.browser.execute(",
            "MAX_BRIDGE_CALLS",
        ).forEach { token -> assertTrue("missing Chromium containment invariant: $token", token in runtime) }

        listOf(
            "global.prompt.bind(global)",
            "Object.defineProperty(global,'__bridge'",
            "__rpc('network_fetch'",
            "__rpc('resource_read'",
            "factory.call(global)",
            "return String(Script.execute(\$entry,'execute',__payload));",
            "global.Html=global.HTML=global.Document={",
            "global.Engine={newBrowser:",
            "global.Qt={translate:",
            "out.waitRequest=function(pattern,timeoutMs)",
            "out.loadHtml=function(baseUrl,html)",
            "out.setCookies=function(cookies,url)",
        ).forEach { token -> assertTrue("missing Chromium prelude invariant: $token", token in prelude) }

        for (forbidden in listOf(
            "addJavascriptInterface(",
            "setAllowUniversalAccessFromFileURLs(",
            "setAllowFileAccessFromFileURLs(",
            "Class.forName(",
            "Runtime.getRuntime(",
            "ProcessBuilder(",
            "global.Html=global.HTML=global.Document=Object.freeze(",
            "global.Engine=Object.freeze(",
            "global.Qt=Object.freeze(",
        )) {
            assertFalse("Chromium action engine must not expose process/platform escape or freeze compatibility decorators: $forbidden", forbidden in runtime || forbidden in prelude)
        }
    }

    private fun repositoryRoot(): Path {
        val working = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        return listOfNotNull(working, working.parent).firstOrNull {
            Files.isDirectory(it.resolve("source-vbook")) && Files.isDirectory(it.resolve("app"))
        } ?: error("NGHETRUYEN_REPOSITORY_ROOT_NOT_FOUND:$working")
    }
}
