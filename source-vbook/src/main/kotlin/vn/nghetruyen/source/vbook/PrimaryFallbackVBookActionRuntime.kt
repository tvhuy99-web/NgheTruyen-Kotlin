package vn.nghetruyen.source.vbook

import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SourceActionRequest
import vn.nghetruyen.source.api.SourceActionResponse
import vn.nghetruyen.source.api.SourceErrorCode
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.runtime.SourceResourceProvider
import java.util.ArrayDeque

/**
 * Selects a primary engine without replaying extension side effects after ordinary script failures.
 *
 * Actions that statically reference the synchronous vBook Browser host API are routed to the
 * portable runtime before Chromium executes any extension code. Chromium dispatch itself runs on
 * the Android main thread, while Browser operations need that same thread to drive their WebView;
 * entering Browser.* from the synchronous Chromium bridge can therefore deadlock the broker.
 *
 * Runtime-unavailable alone is not sufficient to replay an action. Fallback is restricted to a
 * small set of explicit pre-execution Chromium states. Renderer crashes and future unavailable
 * errors therefore fail closed unless they are deliberately classified here after proving that no
 * extension code or host side effect could have run.
 */
class PrimaryFallbackVBookActionRuntime(
    private val primary: VBookActionRuntime,
    private val fallback: VBookActionRuntime,
) : VBookActionRuntime {
    override fun execute(
        manifest: SourceManifest,
        resources: SourceResourceProvider,
        request: SourceActionRequest,
    ): SourcePlatformResult<SourceActionResponse> {
        if (requiresPortableBrowserRuntime(manifest, resources, request)) {
            return fallback.execute(manifest, resources, request)
        }
        return when (val result = primary.execute(manifest, resources, request)) {
            is SourcePlatformResult.Success -> result
            is SourcePlatformResult.Failure -> if (canFallback(result)) {
                fallback.execute(manifest, resources, request)
            } else result
        }
    }

    private fun requiresPortableBrowserRuntime(
        manifest: SourceManifest,
        resources: SourceResourceProvider,
        request: SourceActionRequest,
    ): Boolean {
        val action = manifest.actions[request.action] ?: return false
        val pending = ArrayDeque<String>()
        pending.add(normalizeScriptPath(action.entry))

        val dynamicScript = (request.input.values[DYNAMIC_SCRIPT_INPUT_KEY] as? JsonValue.Str)
            ?.value
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        if (dynamicScript != null) {
            runCatching { normalizeScriptPath(dynamicScript) }
                .getOrNull()
                ?.let(pending::addLast)
        }

        val visited = linkedSetOf<String>()
        while (pending.isNotEmpty() && visited.size < MAX_ROUTING_SCRIPTS) {
            val path = pending.removeFirst()
            if (!visited.add(path)) continue
            val bytes = runCatching { resources.read(path, MAX_ROUTING_SCRIPT_BYTES) }.getOrNull() ?: continue
            val source = bytes.toString(Charsets.UTF_8)

            if (BROWSER_HOST_ACCESS.containsMatchIn(VBookJavaScriptLexicalMask.executable(source))) {
                return true
            }

            val withoutComments = VBookJavaScriptLexicalMask.withoutComments(source)
            LOAD_LITERAL.findAll(withoutComments).forEach { match ->
                val target = match.groupValues[2]
                if (target.equals("crypto.js", ignoreCase = true)) return@forEach
                val normalized = runCatching { normalizeScriptPath(target) }.getOrNull() ?: return@forEach
                if (normalized !in visited) pending.addLast(normalized)
            }
        }
        return false
    }

    private fun normalizeScriptPath(raw: String): String {
        val clean = raw.replace('\\', '/').removePrefix("/")
        val path = if (clean.startsWith("src/")) clean else "src/$clean"
        SourceManifest.requireSafeRelativePath(path)
        return path
    }

    private fun canFallback(result: SourcePlatformResult.Failure): Boolean {
        if (result.error.code != SourceErrorCode.VBOOK_RUNTIME_UNAVAILABLE) return false
        return PRE_EXECUTION_UNAVAILABLE_PREFIXES.any(result.error.message::startsWith)
    }

    companion object {
        private const val DYNAMIC_SCRIPT_INPUT_KEY = "script"
        private const val MAX_ROUTING_SCRIPT_BYTES = 2 * 1024 * 1024
        private const val MAX_ROUTING_SCRIPTS = 64
        private val BROWSER_HOST_ACCESS = Regex("""\bBrowser\s*(?:\.|\[)""")
        private val LOAD_LITERAL = Regex("""\bload\s*\(\s*(['"])([^'"]+)\1""")
        private val PRE_EXECUTION_UNAVAILABLE_PREFIXES = setOf(
            "CHROMIUM_RUNTIME_CLOSED",
            "CHROMIUM_VBOOK_MODE_REQUIRED",
            "CHROMIUM_COMPAT_DISPATCH_ACTION_REQUIRED:",
            "CHROMIUM_WEBVIEW_UNAVAILABLE:",
            "CHROMIUM_MAIN_THREAD_CALLER_UNSUPPORTED",
        )
    }
}
