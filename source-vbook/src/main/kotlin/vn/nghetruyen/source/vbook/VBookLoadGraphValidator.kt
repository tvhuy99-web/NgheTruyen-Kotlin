package vn.nghetruyen.source.vbook

import org.mozilla.javascript.CompilerEnvirons
import org.mozilla.javascript.Context
import org.mozilla.javascript.Parser
import org.mozilla.javascript.ast.FunctionCall
import org.mozilla.javascript.ast.Name
import org.mozilla.javascript.ast.NodeVisitor
import org.mozilla.javascript.ast.StringLiteral

enum class VBookLoadIssueCode {
    NON_LITERAL,
    MISSING_TARGET,
    RECURSIVE,
}

data class VBookLoadIssue(
    val code: VBookLoadIssueCode,
    val scriptPath: String,
    val target: String? = null,
)

data class VBookLoadDirective(
    val target: String?,
    val start: Int,
    val length: Int,
)

/**
 * Parses real load(...) calls through Rhino's JavaScript AST. String/comment lookalikes are ignored,
 * while absolute source positions let runtime compilers replace only the call expression itself.
 *
 * vBook scripts routinely use ES6 syntax (let/const/class, arrows, template literals). The parser
 * therefore uses the same ES6 language level as the compatibility runtime instead of Rhino's
 * legacy parser default.
 */
object VBookLoadDirectiveParser {
    fun parse(path: String, source: String): List<VBookLoadDirective> {
        val environs = CompilerEnvirons().apply {
            languageVersion = Context.VERSION_ES6
        }
        val root = runCatching { Parser(environs).parse(source, path, 1) }
            .getOrElse { error -> throw IllegalArgumentException("VBOOK_LOAD_PARSE_FAILED:$path:${error.message}", error) }
        val calls = mutableListOf<VBookLoadDirective>()
        root.visit(NodeVisitor { node ->
            if (node is FunctionCall) {
                val target = node.target as? Name
                if (target?.identifier == "load") {
                    val first = node.arguments.firstOrNull()
                    calls += VBookLoadDirective(
                        target = (first as? StringLiteral)?.value,
                        start = node.absolutePosition,
                        length = node.length,
                    )
                }
            }
            true
        })
        return calls.sortedBy(VBookLoadDirective::start)
    }
}

/** Static validation for the documented current-engine load('file.js') contract. */
object VBookLoadGraphValidator {
    fun validate(scripts: Map<String, String>, profile: VBookContractProfile): List<VBookLoadIssue> {
        if (profile != VBookContractProfile.CURRENT_JS) return emptyList()
        val normalized = scripts.entries.associate { (path, source) -> VBookPaths.normalizeScriptPath(path) to source }
        val calls = normalized.mapValues { (path, source) ->
            VBookLoadDirectiveParser.parse(path, source).map(VBookLoadDirective::target)
        }
        val issues = mutableListOf<VBookLoadIssue>()
        val loadedTargets = linkedSetOf<String>()

        calls.forEach { (path, values) ->
            values.forEach { call ->
                if (call == null) {
                    issues += VBookLoadIssue(VBookLoadIssueCode.NON_LITERAL, path)
                    return@forEach
                }
                if (call.equals("crypto.js", ignoreCase = true)) return@forEach
                val target = runCatching { VBookPaths.normalizeScriptPath(call) }.getOrNull()
                if (target == null || target !in normalized) {
                    issues += VBookLoadIssue(VBookLoadIssueCode.MISSING_TARGET, path, target ?: call)
                } else {
                    loadedTargets += target
                }
            }
        }

        loadedTargets.forEach { target ->
            if (calls[target].orEmpty().isNotEmpty()) {
                issues += VBookLoadIssue(VBookLoadIssueCode.RECURSIVE, target)
            }
        }
        return issues.distinct()
    }
}
