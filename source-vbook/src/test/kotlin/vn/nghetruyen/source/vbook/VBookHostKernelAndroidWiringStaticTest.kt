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
        val eventPump = Files.readString(
            root.resolve("app/src/main/java/vn/nghetruyen/app/sourceplatform/ExtensionHostEventPump.kt"),
        )
        val activity = Files.readString(
            root.resolve("app/src/main/java/vn/nghetruyen/app/MainActivity.kt"),
        )

        listOf(
            "WeakReference(viewModel)",
            "SourceHostKernelBus.install(dispatcher)",
            ".register(\"hooks\", \"poll\")",
            "SourceHostEventBus.drain(sourceId, eventName)",
            ".register(\"hooks\", \"emit\")",
            ".register(\"ui\", \"notify\")",
            ".register(\"ui\", \"refresh\")",
            "ExploreMode.HOME -> host.browseHome()",
            "Destination.Story ->",
            "host.openStory(story)",
            ".register(\"ui\", \"navigate\")",
            "host.setRootTab(RootTab.EXPLORE)",
            "host.back()",
            ".register(\"reader\", \"nextChapter\")",
            ".register(\"reader\", \"setTextMode\")",
            "ChapterTextMode.ORIGINAL -> host.showOriginalChapter()",
            "ChapterTextMode.VIETPHRASE -> host.applyVietPhraseToCurrentChapter()",
            "ChapterTextMode.AI_TRANSLATION -> host.aiTranslate()",
            ".register(\"library\", \"note\")",
            ".register(\"tts\", \"setVoice\")",
            "ExtensionHostEventPump.install(viewModel)",
            "fun installExtensionHostKernel(viewModel: AppViewModel)",
            "SOURCE_HOST_UI_SESSION_UNAVAILABLE",
        ).forEach { token -> assertTrue("missing Android host-kernel invariant: $token", token in host) }

        listOf(
            "WeakReference<AppViewModel>",
            "application.registerActivityLifecycleCallbacks",
            "activity is MainActivity",
            "\"app.start\"",
            "\"app.resume\"",
            "\"app.pause\"",
            "\"reader.enter\"",
            "\"reader.leave\"",
            "\"reader.chapterChanged\"",
            "\"playback.changed\"",
            "\"library.changed\"",
            "SourceHostEventBus.emit(",
        ).forEach { token -> assertTrue("missing lifecycle event-pump invariant: $token", token in eventPump) }

        assertTrue("installExtensionHostKernel(viewModel)" in activity)
        assertTrue("import vn.nghetruyen.app.sourceplatform.installExtensionHostKernel" in activity)

        for (forbidden in listOf("addJavascriptInterface", "Class.forName", "Runtime.getRuntime", "ProcessBuilder(")) {
            assertFalse("Android host adapter must not create a runtime escape: $forbidden", forbidden in host)
            assertFalse("Android event pump must not create a runtime escape: $forbidden", forbidden in eventPump)
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
