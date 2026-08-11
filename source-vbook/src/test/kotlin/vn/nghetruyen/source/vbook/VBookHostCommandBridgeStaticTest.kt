package vn.nghetruyen.source.vbook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class VBookHostCommandBridgeStaticTest {
    @Test
    fun rhinoBridgeUsesOnlyRuntimeNeutralHostKernelWire() {
        val root = repositoryRoot()
        val runtime = Files.readString(
            root.resolve("source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookJsRuntime.kt"),
        )
        val appKernel = Files.readString(
            root.resolve("source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookAppKernelPrelude.kt"),
        )

        listOf(
            "operation == \"host_command\"",
            "SourceHostKernelWireExecutor.execute(",
            "broker = brokers.hostKernel",
            "rawCommandJson = inputJson",
            "ScriptableObject.deleteProperty(scope, \"__ngheHostCommandInput\")",
            "VBOOK_BRIDGE_HOST_COMMAND_COMPLETED",
        ).forEach { token -> assertTrue("missing host-command runtime invariant: $token", token in runtime) }

        listOf(
            "global.__bridge('host_command', command)",
            "hostCommandExecution: true",
            "intent: hostCommandIntent",
            "execute: executeHostCommand",
        ).forEach { token -> assertTrue("missing App execution invariant: $token", token in appKernel) }

        for (forbidden in listOf("addJavascriptInterface", "Class.forName", "Runtime.getRuntime", "ProcessBuilder(")) {
            assertFalse("host command bridge must not add a platform escape: $forbidden", forbidden in appKernel)
        }
    }

    private fun repositoryRoot(): Path {
        val working = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val candidates = listOf(working, working.parent).filterNotNull()
        return candidates.firstOrNull {
            Files.isDirectory(it.resolve("source-vbook")) && Files.isDirectory(it.resolve("source-api"))
        } ?: error("NGHETRUYEN_REPOSITORY_ROOT_NOT_FOUND:$working")
    }
}
