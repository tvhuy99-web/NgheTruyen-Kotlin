package vn.nghetruyen.source.vbook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class VBookHostKernelAndroidWiringStaticTest {
    @Test
    fun appHostWiringKeepsLifecycleAndCommandBoundaryExplicit() {
        val root = repositoryRoot()
        val host = Files.readString(
            root.resolve("app/src/main/java/vn/nghetruyen/app/sourceplatform/AppViewModelHostKernel.kt"),
        )
        val activity = Files.readString(
            root.resolve("app/src/main/java/vn/nghetruyen/app/MainActivity.kt"),
        )

        listOf(
            "WeakReference(viewModel)",
            "SourceHostKernelBus.install(dispatcher)",
            ".register(\"ui\", \"notify\")",
            ".register(\"reader\", \"nextChapter\")",
            ".register(\"library\", \"note\")",
            ".register(\"tts\", \"setVoice\")",
            "fun installExtensionHostKernel(viewModel: AppViewModel)",
            "SOURCE_HOST_UI_SESSION_UNAVAILABLE",
        ).forEach { token -> assertTrue("missing Android host-kernel invariant: $token", token in host) }

        assertTrue("installExtensionHostKernel(viewModel)" in activity)
        assertTrue("import vn.nghetruyen.app.sourceplatform.installExtensionHostKernel" in activity)

        for (forbidden in listOf("addJavascriptInterface", "Class.forName", "Runtime.getRuntime", "ProcessBuilder(")) {
            assertFalse("Android host adapter must not create a runtime escape: $forbidden", forbidden in host)
        }
    }

    private fun repositoryRoot(): Path {
        val working = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val candidates = listOf(working, working.parent).filterNotNull()
        return candidates.firstOrNull {
            Files.isDirectory(it.resolve("source-vbook")) && Files.isDirectory(it.resolve("app"))
        } ?: error("NGHETRUYEN_REPOSITORY_ROOT_NOT_FOUND:$working")
    }
}
