package vn.nghetruyen.source.vbook

import org.junit.Assert.assertEquals
import org.junit.Test
import org.mozilla.javascript.Context

/**
 * Mirrors the lexical contract used by Chromium classic scripts: config, loaded libraries and the
 * entry script execute as separate scripts in one global realm. Top-level lexical declarations from
 * an earlier script must remain visible to later scripts even though they are not window properties.
 *
 * Rhino in the JVM test harness cannot execute every Chromium ES6 syntax form (notably `class` in
 * this version), so this regression intentionally targets the declarations that caused the real
 * compatibility failure: let/const plus legacy var/function globals.
 */
class VBookSharedScriptRealmTest {
    @Test
    fun configLibraryAndEntryShareGlobalLexicalBindings() {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initStandardObjects()

            cx.evaluateString(
                scope,
                "const CONFIG_URL='https://configured.example'; const CONFIG_TIME='2026';",
                "vbook-config",
                1,
                null,
            )
            cx.evaluateString(
                scope,
                """
                    let STVHOST='https://default.example';
                    try { if (CONFIG_URL) STVHOST=CONFIG_URL; } catch (ignored) {}
                    const LIB_CONST='const-ok';
                    let LIB_LET='let-ok';
                    var LEGACY_VAR='var-ok';
                    function sharedHelper(){ return CONFIG_TIME; }
                """.trimIndent(),
                "src/libs.js",
                1,
                null,
            )

            val result = Context.toString(cx.evaluateString(
                scope,
                """
                    (function(){
                      function execute(){
                        return [STVHOST,LIB_CONST,LIB_LET,LEGACY_VAR,sharedHelper()].join('|');
                      }
                      return execute();
                    })()
                """.trimIndent(),
                "src/gen0.js",
                1,
                null,
            ))

            assertEquals("https://configured.example|const-ok|let-ok|var-ok|2026", result)
        } finally {
            Context.exit()
        }
    }
}
