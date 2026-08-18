from pathlib import Path

ROOT = Path('.')


def write(path: str, content: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding='utf-8')


def replace(path: str, old: str, new: str) -> None:
    target = ROOT / path
    text = target.read_text(encoding='utf-8')
    if old not in text:
        raise SystemExit(f'expected block not found in {path}: {old[:120]!r}')
    target.write_text(text.replace(old, new, 1), encoding='utf-8')


write('source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookLoadGraphValidator.kt', r'''package vn.nghetruyen.source.vbook

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
 */
object VBookLoadDirectiveParser {
    fun parse(path: String, source: String): List<VBookLoadDirective> {
        val root = runCatching { Parser().parse(source, path, 1) }
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
''')

write('source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookScriptBundleCompiler.kt', r'''package vn.nghetruyen.source.vbook

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
''')

write('source-vbook/src/test/kotlin/vn/nghetruyen/source/vbook/VBookScriptBundleCompilerTest.kt', r'''package vn.nghetruyen.source.vbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.javascript.Context

class VBookScriptBundleCompilerTest {
    @Test
    fun loadedLexicalDeclarationsAndConfigShareEntryScope() {
        val bundle = VBookScriptBundleCompiler.compile(
            entryPath = "gen0.js",
            entrySource = """
                load('libs.js');
                function execute() {
                  return STVHOST + '|' + LIB_CONST + '|' + Helper.name + '|' + legacyVar + '|' + helper() + '|' + DOMAIN;
                }
            """.trimIndent(),
        ) { path ->
            when (path) {
                "src/libs.js" -> """
                    let STVHOST = 'https://shared.example';
                    const LIB_CONST = 'const-ok';
                    class Helper {}
                    Helper.name = 'class-ok';
                    var legacyVar = 'var-ok';
                    function helper() { return 'function-ok'; }
                """.trimIndent()
                else -> null
            }
        }

        assertEquals(listOf("src/libs.js"), bundle.dependencies)
        assertEquals(1, bundle.loadDirectiveCount)
        assertFalse(bundle.source.contains("load('libs.js')"))
        val result = evaluate("""
            (function(){
              const DOMAIN = 'config-ok';
              ${bundle.source}
              return execute();
            })()
        """.trimIndent())
        assertEquals(
            "https://shared.example|const-ok|class-ok|var-ok|function-ok|config-ok",
            result,
        )
    }

    @Test
    fun allLiteralLoadsAreStaticEvenInsideControlFlowAndDuplicatesLoadOnce() {
        val bundle = VBookScriptBundleCompiler.compile(
            "main.js",
            """
                if (false) load("a.js");
                function execute() {
                  load('a.js');
                  load('b.js');
                  return A + B;
                }
            """.trimIndent(),
        ) { path -> when (path) {
            "src/a.js" -> "let A='a';"
            "src/b.js" -> "let B='b';"
            else -> null
        } }
        assertEquals(listOf("src/a.js", "src/b.js"), bundle.dependencies)
        assertEquals(3, bundle.loadDirectiveCount)
        assertEquals("ab", evaluate("(function(){${bundle.source};return execute();})()"))
    }

    @Test
    fun commentsAndStringsThatLookLikeLoadAreIgnored() {
        val source = """
            // load('fake-a.js');
            var text = "load('fake-b.js')";
            /* load('fake-c.js'); */
            load('real.js');
            function execute(){ return REAL + text.length; }
        """.trimIndent()
        val bundle = VBookScriptBundleCompiler.compile("main.js", source) { path ->
            if (path == "src/real.js") "let REAL='ok';" else null
        }
        assertEquals(listOf("src/real.js"), bundle.dependencies)
        assertEquals(1, bundle.loadDirectiveCount)
        assertTrue(bundle.source.contains("load('fake-b.js')"))
    }

    @Test
    fun bundledCryptoDoesNotReadPackageCryptoFile() {
        var reads = 0
        val bundle = VBookScriptBundleCompiler.compile(
            "main.js",
            "load('crypto.js'); function execute(){ return typeof CryptoJS; }",
        ) { _ -> reads += 1; "bad" }
        assertEquals(0, reads)
        assertTrue(bundle.dependencies.isEmpty())
        assertFalse(bundle.source.contains("load('crypto.js')"))
    }

    @Test
    fun nonLiteralAndRecursiveLoadsFailWithContractErrors() {
        val nonLiteral = runCatching {
            VBookScriptBundleCompiler.compile("main.js", "var p='a.js'; load(p);", dependencySource = { null })
        }.exceptionOrNull()
        assertTrue(nonLiteral?.message.orEmpty().contains("VBOOK_LOAD_LITERAL_REQUIRED"))

        val recursive = runCatching {
            VBookScriptBundleCompiler.compile("main.js", "load('lib.js');") { path ->
                if (path == "src/lib.js") "load('nested.js'); var X=1;" else null
            }
        }.exceptionOrNull()
        assertTrue(recursive?.message.orEmpty().contains("VBOOK_RECURSIVE_LOAD_NOT_ALLOWED:src/lib.js"))
    }

    private fun evaluate(source: String): String {
        val cx = Context.enter()
        return try {
            cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initStandardObjects()
            Context.toString(cx.evaluateString(scope, source, "bundle-test", 1, null))
        } finally {
            Context.exit()
        }
    }
}
''')

prelude_path = 'app/src/main/java/vn/nghetruyen/app/sourceplatform/ChromiumVBookPrelude.kt'
old = r'''              var __loaded={};
              function __source(raw){ return String(__rpc('resource_read',{path:__path(raw)})||''); }
              function load(raw){
                if(String(raw||'').toLowerCase()==='crypto.js') return true;
                var path=__path(raw);
                if(__loaded[path]) return true;
                __loaded[path]=true;
                var code=__source(path);
                (0,eval)(code+'\n//# sourceURL='+path.replace(/\s/g,'_'));
                return true;
              }
              global.load=load;
              global.Script=Object.freeze({
                execute:function(rawPath,functionName){
                  var path=__path(rawPath), requested=String(functionName||'execute');
                  if(!/^[A-Za-z_$][A-Za-z0-9_$]{0,127}$/.test(requested)) throw new Error('VBOOK_SCRIPT_FUNCTION_INVALID');
                  var code=__source(path);
                  var factory=(0,eval)('(function(){\n'+code+'\n;return (typeof '+requested+'===\'function\'?'+requested+':(typeof execute===\'function\'?execute:null));})\n//# sourceURL='+path.replace(/\s/g,'_'));
                  var fn=factory.call(global);
                  if(typeof fn!=='function') throw new Error('VBOOK_SCRIPT_FUNCTION_MISSING:'+requested);
                  return fn.apply(global,Array.prototype.slice.call(arguments,2));
                }
              });
'''
new = r'''              var __scriptExecutionPrelude='';
              function load(){
                throw new Error('VBOOK_LOAD_LITERAL_REQUIRED');
              }
              global.load=load;
              var __scriptApi={
                execute:function(rawPath,functionName){
                  var path=__path(rawPath), requested=String(functionName||'execute');
                  if(!/^[A-Za-z_$][A-Za-z0-9_$]{0,127}$/.test(requested)) throw new Error('VBOOK_SCRIPT_FUNCTION_INVALID');
                  var compiled=__rpc('script_compile',{path:path})||{};
                  var code=String(compiled.source||'');
                  var prefix=String(__scriptExecutionPrelude||'');
                  var factory=(0,eval)('(function(){\n'+prefix+'\n'+code+'\n;return (typeof '+requested+'===\'function\'?'+requested+':(typeof execute===\'function\'?execute:null));})\n//# sourceURL='+path.replace(/\s/g,'_'));
                  var fn=factory.call(global);
                  if(typeof fn!=='function') throw new Error('VBOOK_SCRIPT_FUNCTION_MISSING:'+requested);
                  return fn.apply(global,Array.prototype.slice.call(arguments,2));
                }
              };
              Object.defineProperty(__scriptApi,'__ngheSetExecutionPrelude',{
                value:function(code){__scriptExecutionPrelude=String(code||'');return true;},
                enumerable:false,
                writable:false,
                configurable:false
              });
              global.Script=Object.freeze(__scriptApi);
'''
replace(prelude_path, old, new)

runtime_path = 'app/src/main/java/vn/nghetruyen/app/sourceplatform/AndroidChromiumVBookRuntime.kt'
replace(
    runtime_path,
    'import vn.nghetruyen.source.vbook.VBookActionRuntime\n',
    'import vn.nghetruyen.source.vbook.VBookActionRuntime\nimport vn.nghetruyen.source.vbook.VBookScriptBundleCompiler\n',
)
replace(
    runtime_path,
    '                "resource_read" -> resourceRead(payload)\n',
    '                "script_compile" -> scriptCompile(payload)\n                "resource_read" -> resourceRead(payload)\n',
)
old_resource = r'''        private fun resourceRead(payload: JsonValue.Obj): JsonValue {
            val rawPath = payload.string("path")?.trim().orEmpty()
            val clean = rawPath.replace('\\', '/').removePrefix("/")
            val path = if (clean.startsWith("src/")) clean else "src/$clean"
            SourceManifest.requireSafeRelativePath(path)
            val bytes = resources.read(path, MAX_SCRIPT_BYTES) ?: error("VBOOK_RESOURCE_MISSING:$path")
            return JsonValue.Str(bytes.toString(Charsets.UTF_8))
        }
'''
new_resource = r'''        private fun scriptCompile(payload: JsonValue.Obj): JsonValue {
            val rawPath = payload.string("path")?.trim().orEmpty()
            val clean = rawPath.replace('\\', '/').removePrefix("/")
            val path = if (clean.startsWith("src/")) clean else "src/$clean"
            SourceManifest.requireSafeRelativePath(path)
            val entryBytes = resources.read(path, MAX_SCRIPT_BYTES) ?: error("VBOOK_RESOURCE_MISSING:$path")
            val compiled = VBookScriptBundleCompiler.compile(
                entryPath = path,
                entrySource = entryBytes.toString(Charsets.UTF_8),
            ) { dependencyPath ->
                resources.read(dependencyPath, MAX_SCRIPT_BYTES)?.toString(Charsets.UTF_8)
            }
            val compiledBytes = compiled.source.toByteArray(Charsets.UTF_8).size
            require(compiledBytes <= MAX_COMPILED_SCRIPT_BYTES) { "CHROMIUM_COMPILED_SCRIPT_TOO_LARGE:$compiledBytes" }
            diagnostics.emit(event(manifest, request, "CHROMIUM_SCRIPT_COMPILED", DiagnosticSeverity.DEBUG, attributes = mapOf(
                "flow" to "compile",
                "stage" to "bundle",
                "entry" to compiled.entryPath.take(500),
                "dependencies" to compiled.dependencies.joinToString(",").take(2_000),
                "dependencyCount" to compiled.dependencies.size.toString(),
                "loadDirectiveCount" to compiled.loadDirectiveCount.toString(),
                "compiledBytes" to compiledBytes.toString(),
            )))
            captureEvidence(
                manifest,
                request,
                "chromium-compiled-script.js",
                "text/javascript",
                compiled.source,
            )
            return JsonValue.Obj(linkedMapOf(
                "source" to JsonValue.Str(compiled.source),
                "entry" to JsonValue.Str(compiled.entryPath),
                "dependencies" to JsonValue.Arr(compiled.dependencies.map(JsonValue::Str)),
                "loadDirectiveCount" to number(compiled.loadDirectiveCount),
            ))
        }

        private fun resourceRead(payload: JsonValue.Obj): JsonValue {
            val rawPath = payload.string("path")?.trim().orEmpty()
            val clean = rawPath.replace('\\', '/').removePrefix("/")
            val path = if (clean.startsWith("src/")) clean else "src/$clean"
            SourceManifest.requireSafeRelativePath(path)
            val bytes = resources.read(path, MAX_SCRIPT_BYTES) ?: error("VBOOK_RESOURCE_MISSING:$path")
            return JsonValue.Str(bytes.toString(Charsets.UTF_8))
        }
'''
replace(runtime_path, old_resource, new_resource)
replace(
    runtime_path,
    '        private const val MAX_SCRIPT_BYTES = 2 * 1024 * 1024\n',
    '        private const val MAX_SCRIPT_BYTES = 2 * 1024 * 1024\n        private const val MAX_COMPILED_SCRIPT_BYTES = 6 * 1024 * 1024\n',
)

compat_path = 'source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookCompatibilityRuntime.kt'
replace(
    compat_path,
    '''        val connection = config.connectionSettings()\n        val prelude = buildString {\n''',
    '''        val connection = config.connectionSettings()\n        val targetScriptPreludeJson = JsonCodec.stringify(JsonValue.Str(VBookConfigPrelude.build(profile, config)))\n        val prelude = buildString {\n''',
)
old_local = r'''            localConfig = Object.freeze({
              getItem:function(key){ key=String(key||''); return Object.prototype.hasOwnProperty.call(__vbookConfigValues,key) ? __vbookConfigValues[key] : undefined; },
              key:function(index){ var keys=Object.keys(__vbookConfigValues).sort(); return keys[Number(index)||0]; },
              length:Object.keys(__vbookConfigValues).length
            });

            var __vbookPackageLoad = load;
'''
new_local = r'''            localConfig = Object.freeze({
              getItem:function(key){ key=String(key||''); return Object.prototype.hasOwnProperty.call(__vbookConfigValues,key) ? __vbookConfigValues[key] : undefined; },
              key:function(index){ var keys=Object.keys(__vbookConfigValues).sort(); return keys[Number(index)||0]; },
              length:Object.keys(__vbookConfigValues).length
            });
            var __vbookTargetScriptPrelude = $targetScriptPreludeJson;
            if (Script && typeof Script.__ngheSetExecutionPrelude === 'function') {
              Script.__ngheSetExecutionPrelude(__vbookTargetScriptPrelude);
            }

            var __vbookPackageLoad = load;
'''
replace(compat_path, old_local, new_local)

print('vBook Chromium script-scope patch staged successfully')
