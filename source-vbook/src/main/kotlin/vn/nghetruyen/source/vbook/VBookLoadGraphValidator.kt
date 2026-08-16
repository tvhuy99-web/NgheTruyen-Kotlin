package vn.nghetruyen.source.vbook

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

 
object VBookLoadGraphValidator {
    fun validate(scripts: Map<String, String>, profile: VBookContractProfile): List<VBookLoadIssue> {
        if (profile != VBookContractProfile.CURRENT_JS) return emptyList()
        val normalized = scripts.entries.associate { (path, source) -> VBookPaths.normalizeScriptPath(path) to source }
        val calls = normalized.mapValues { (path, source) -> loadCalls(path, source) }
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

     
    private fun loadCalls(path: String, source: String): List<String?> {
        val root = Parser().parse(source, path, 1)
        val calls = mutableListOf<String?>()
        root.visit(NodeVisitor { node ->
            if (node is FunctionCall) {
                val target = node.target as? Name
                if (target?.identifier == "load") {
                    val first = node.arguments.firstOrNull()
                    calls += (first as? StringLiteral)?.value
                }
            }
            true
        })
        return calls
    }
}
