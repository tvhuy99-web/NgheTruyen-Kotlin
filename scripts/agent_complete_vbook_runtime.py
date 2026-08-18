from pathlib import Path

ROOT = Path('.')


def replace(path: str, old: str, new: str) -> None:
    target = ROOT / path
    text = target.read_text(encoding='utf-8')
    if old not in text:
        raise SystemExit(f'expected block not found in {path}: {old[:160]!r}')
    target.write_text(text.replace(old, new, 1), encoding='utf-8')


def write(path: str, content: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding='utf-8')


compat = 'source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookCompatibilityRuntime.kt'
replace(
    compat,
    '''            var __vbookPackageLoad = load;\n            var __vbookInsideLoad = false;\n            load = function(name) {\n              name = String(name || '');\n              if (name.toLowerCase() === 'crypto.js') return true;\n              if (__vbookInsideLoad) throw new Error('VBOOK_RECURSIVE_LOAD_NOT_ALLOWED');\n              __vbookInsideLoad = true;\n              try { return __vbookPackageLoad(name); }\n              finally { __vbookInsideLoad = false; }\n            };\n\n            var __vbookNativeHtmlParse = Html.parse;\n''',
    '''            var __vbookNativeHtmlParse = Html.parse;\n            var __vbookNativeHtmlClean = (typeof Html.clean === 'function') ? Html.clean : null;\n''',
)
replace(
    compat,
    '''            Html.parse=function(content,baseUrl){return __vbookWrapDocument(__vbookNativeHtmlParse(String(content==null?'':content),String(baseUrl||'')));};\n\n            var __vbookNativeNewBrowser = Engine.newBrowser;\n''',
    '''            Html.parse=function(content,baseUrl){return __vbookWrapDocument(__vbookNativeHtmlParse(String(content==null?'':content),String(baseUrl||'')));};\n            Html.clean=function(content,allowedTags){\n              var raw=String(content==null?'':content);\n              if(__vbookNativeHtmlClean){\n                try{return String(__vbookNativeHtmlClean(raw,allowedTags||[]));}catch(nativeError){}\n              }\n              if(typeof DOMParser!=='function') throw new Error('VBOOK_HTML_CLEAN_UNAVAILABLE');\n              var allow={}, tags=allowedTags&&typeof allowedTags.length==='number'?allowedTags:[];\n              for(var ai=0;ai<tags.length;ai++){var tag=String(tags[ai]||'').toLowerCase();if(tag)allow[tag]=true;}\n              var doc=(new DOMParser()).parseFromString(raw,'text/html'), body=doc.body;\n              if(!body)return '';\n              var dangerous=body.querySelectorAll('script,style,template,noscript');\n              for(var di=dangerous.length-1;di>=0;di--){var dn=dangerous[di];if(dn.parentNode)dn.parentNode.removeChild(dn);}\n              var nodes=Array.prototype.slice.call(body.querySelectorAll('*'));\n              for(var ni=nodes.length-1;ni>=0;ni--){\n                var node=nodes[ni], name=String(node.tagName||'').toLowerCase();\n                if(allow[name])continue;\n                var parent=node.parentNode;if(!parent)continue;\n                while(node.firstChild)parent.insertBefore(node.firstChild,node);\n                parent.removeChild(node);\n              }\n              return String(body.innerHTML||'');\n            };\n\n            var __vbookNativeNewBrowser = Engine.newBrowser;\n''',
)

write('source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookLoadGraphValidator.kt', r'''package vn.nghetruyen.source.vbook

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
 * while absolute source positions let validators reason about the actual script graph.
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
        val directives = normalized.mapValues { (path, source) -> VBookLoadDirectiveParser.parse(path, source) }
        val graph = normalized.keys.associateWithTo(linkedMapOf()) { linkedSetOf<String>() }
        val issues = mutableListOf<VBookLoadIssue>()

        directives.forEach { (path, calls) ->
            calls.forEach { directive ->
                val call = directive.target
                if (call == null) {
                    issues += VBookLoadIssue(VBookLoadIssueCode.NON_LITERAL, path)
                    return@forEach
                }
                if (call.equals("crypto.js", ignoreCase = true)) return@forEach
                val target = runCatching { VBookPaths.normalizeScriptPath(call) }.getOrNull()
                if (target == null || target !in normalized) {
                    issues += VBookLoadIssue(VBookLoadIssueCode.MISSING_TARGET, path, target ?: call)
                } else {
                    graph.getValue(path) += target
                }
            }
        }

        val visited = linkedSetOf<String>()
        val visiting = linkedSetOf<String>()
        fun visit(path: String) {
            if (!visiting.add(path)) return
            graph[path].orEmpty().forEach { target ->
                if (target in visiting) {
                    issues += VBookLoadIssue(VBookLoadIssueCode.RECURSIVE, path, target)
                } else if (target !in visited) {
                    visit(target)
                }
            }
            visiting.remove(path)
            visited += path
        }
        normalized.keys.forEach { if (it !in visited) visit(it) }
        return issues.distinct()
    }
}
''')

write('source-vbook/src/test/kotlin/vn/nghetruyen/source/vbook/VBookLoadGraphValidatorTest.kt', r'''package vn.nghetruyen.source.vbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VBookLoadGraphValidatorTest {
    @Test
    fun currentLiteralPackageLoadIsAcceptedAndBundledCryptoIsIgnored() {
        val issues = VBookLoadGraphValidator.validate(
            mapOf(
                "src/search.js" to "load('libs.js');load('crypto.js');function execute(){return Response.success([]);}",
                "src/libs.js" to "function helper(){return 1;}",
            ),
            VBookContractProfile.CURRENT_JS,
        )
        assertTrue(issues.isEmpty())
    }

    @Test
    fun currentLiteralMjsLoadIsAccepted() {
        val issues = VBookLoadGraphValidator.validate(
            mapOf(
                "src/search.js" to "load('helper.mjs');function execute(){return Response.success([]);}",
                "src/helper.mjs" to "function helper(){return 1;}",
            ),
            VBookContractProfile.CURRENT_JS,
        )
        assertTrue(issues.isEmpty())
    }

    @Test
    fun currentAllowsNestedAcyclicLoadsButRejectsNonLiteralMissingAndCycles() {
        val nested = VBookLoadGraphValidator.validate(
            mapOf(
                "src/search.js" to "load('libs.js');function execute(){return Response.success([]);}",
                "src/libs.js" to "load('nested.js');function helper(){return nested();}",
                "src/nested.js" to "function nested(){return 1;}",
            ),
            VBookContractProfile.CURRENT_JS,
        )
        assertTrue(nested.isEmpty())

        val nonLiteral = VBookLoadGraphValidator.validate(
            mapOf("src/search.js" to "var x='libs.js';load(x);function execute(){return Response.success([]);}"),
            VBookContractProfile.CURRENT_JS,
        )
        assertEquals(VBookLoadIssueCode.NON_LITERAL, nonLiteral.single().code)

        val missing = VBookLoadGraphValidator.validate(
            mapOf("src/search.js" to "load('missing.js');function execute(){return Response.success([]);}"),
            VBookContractProfile.CURRENT_JS,
        )
        assertEquals(VBookLoadIssueCode.MISSING_TARGET, missing.single().code)

        val cycle = VBookLoadGraphValidator.validate(
            mapOf(
                "src/search.js" to "load('libs.js');function execute(){return Response.success([]);}",
                "src/libs.js" to "load('nested.js');",
                "src/nested.js" to "load('libs.js');",
            ),
            VBookContractProfile.CURRENT_JS,
        )
        assertTrue(cycle.any { it.code == VBookLoadIssueCode.RECURSIVE && it.target != null })
    }

    @Test
    fun legacyIsNotForcedIntoCurrentStaticLoadRules() {
        val issues = VBookLoadGraphValidator.validate(
            mapOf("src/search.js" to "var x='legacy.js';load(x);"),
            VBookContractProfile.LEGACY_JS,
        )
        assertTrue(issues.isEmpty())
    }
}
''')

corpus = 'source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookCorpusAnalyzer.kt'
replace(corpus, '    HTML_DOM,\n    HTML_COLLECTION_CALLBACKS,', '    HTML_DOM,\n    HTML_CLEAN,\n    HTML_COLLECTION_CALLBACKS,')
replace(
    corpus,
    '            hit(VBookFeature.HTML_DOM, Regex("\\\\b(?:Html|HTML)\\\\.parse\\\\s*\\\\(|\\\\.select\\\\s*\\\\("))\n            hit(VBookFeature.HTML_COLLECTION_CALLBACKS,',
    '            hit(VBookFeature.HTML_DOM, Regex("\\\\b(?:Html|HTML)\\\\.parse\\\\s*\\\\(|\\\\.select\\\\s*\\\\("))\n            hit(VBookFeature.HTML_CLEAN, Regex("\\\\b(?:Html|HTML)\\\\.clean\\\\s*\\\\("))\n            hit(VBookFeature.HTML_COLLECTION_CALLBACKS,',
)

matrix = 'source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookFeatureMatrix.kt'
replace(matrix, '        VBookFeature.HTML_DOM,\n        VBookFeature.HTML_COLLECTION_CALLBACKS,', '        VBookFeature.HTML_DOM,\n        VBookFeature.HTML_CLEAN,\n        VBookFeature.HTML_COLLECTION_CALLBACKS,')
replace(
    matrix,
    '        VBookFeature.HTML_COLLECTION_CALLBACKS ->\n            "Compatibility DOM exposes array-compatible forEach/map plus vBook collection helpers; reference certification still decides exact behavioral parity."\n',
    '        VBookFeature.HTML_CLEAN ->\n            "Legacy Html.clean keeps explicitly allowed tags, unwraps other markup while preserving text, removes script/style/template/noscript content, and delegates to a native cleaner when the engine already provides one."\n        VBookFeature.HTML_COLLECTION_CALLBACKS ->\n            "Compatibility DOM exposes array-compatible forEach/map plus vBook collection helpers; reference certification still decides exact behavioral parity."\n',
)

write('source-vbook/src/test/kotlin/vn/nghetruyen/source/vbook/VBookCompatibilitySurfaceStaticTest.kt', r'''package vn.nghetruyen.source.vbook

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
''')

print('complete vBook compatibility pass staged')
