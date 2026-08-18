package vn.nghetruyen.source.vbook

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
