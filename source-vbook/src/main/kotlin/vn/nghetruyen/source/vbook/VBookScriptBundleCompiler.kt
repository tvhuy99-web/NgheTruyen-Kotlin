package vn.nghetruyen.source.vbook

/**
 * One compiled vBook script realm: statically loaded package libraries precede the entry script so
 * top-level let/const/class/function/var declarations share one lexical environment.
 *
 * This intentionally mirrors vBook's documented load() contract: load takes a literal string,
 * crypto.js is host-provided, and loaded libraries are not recursive loaders themselves.
 */
data class VBookCompiledScriptBundle(
    val entryPath: String,
    val source: String,
    val dependencies: List<String>,
    val loadDirectiveCount: Int,
)

object VBookScriptBundleCompiler {
    fun compile(
        entryPath: String,
        entrySource: String,
        dependencySource: (String) -> String?,
    ): VBookCompiledScriptBundle {
        val normalizedEntry = VBookPaths.normalizeScriptPath(entryPath)
        val directives = VBookLoadDirectiveParser.parse(normalizedEntry, entrySource)
        val dependencyPaths = linkedSetOf<String>()
        val dependencyBodies = mutableListOf<Pair<String, String>>()

        directives.forEach { directive ->
            val rawTarget = directive.target
                ?: throw IllegalArgumentException("VBOOK_LOAD_LITERAL_REQUIRED:$normalizedEntry")
            val target = VBookLoadPolicy.resolve(rawTarget)
            if (target.kind == VBookLoadKind.BUNDLED_CRYPTO) return@forEach
            val path = target.path ?: error("VBOOK_LOAD_TARGET_REQUIRED:$rawTarget")
            require(path != normalizedEntry) { "VBOOK_RECURSIVE_LOAD_NOT_ALLOWED:$path" }
            if (!dependencyPaths.add(path)) return@forEach
            val body = dependencySource(path)
                ?: throw IllegalArgumentException("VBOOK_RESOURCE_MISSING:$path")
            val nested = VBookLoadDirectiveParser.parse(path, body)
            require(nested.isEmpty()) { "VBOOK_RECURSIVE_LOAD_NOT_ALLOWED:$path" }
            dependencyBodies += path to body
        }

        val strippedEntry = replaceLoadCallsWithTrue(entrySource, directives)
        val bundled = buildString {
            dependencyBodies.forEach { (path, body) ->
                append("/* __vbook_loaded: ").append(path).append(" */\n")
                append(body).append('\n')
            }
            append("/* __vbook_entry: ").append(normalizedEntry).append(" */\n")
            append(strippedEntry)
        }
        return VBookCompiledScriptBundle(
            entryPath = normalizedEntry,
            source = bundled,
            dependencies = dependencyPaths.toList(),
            loadDirectiveCount = directives.size,
        )
    }

    private fun replaceLoadCallsWithTrue(source: String, directives: List<VBookLoadDirective>): String {
        if (directives.isEmpty()) return source
        val output = StringBuilder(source)
        directives.asReversed().forEach { directive ->
            val start = directive.start
            val end = start + directive.length
            require(start >= 0 && end <= output.length && directive.length > 0) {
                "VBOOK_LOAD_SOURCE_RANGE_INVALID:$start:${directive.length}"
            }
            output.replace(start, end, "true")
        }
        return output.toString()
    }
}
