package vn.nghetruyen.source.vbook

import org.junit.Assert.assertEquals
import org.junit.Test
import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject

class VBookBrowserPatternPreludeTest {
    @Test
    fun matcherSupportsLiteralAndCommunityRegexPatternsWithoutThrowing() {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            cx.optimizationLevel = -1
            val scope = cx.initSafeStandardObjects()
            val script = """
                var __vbookBrowserMatch = function(){ return false; };
                ${VBookBrowserPatternPrelude.build()}
                JSON.stringify([
                  __vbookBrowserMatch('https://api.example/book/1','api.example/book'),
                  __vbookBrowserMatch('https://www.alicesw.com/home/chapter/info?id=1','.*?alicesw.com/home/chapter/info.*?'),
                  __vbookBrowserMatch('https://api.example/book/1','regex:[')
                ]);
            """.trimIndent()
            val result = Context.toString(cx.evaluateString(scope, script, "browser-pattern-test", 1, null))
            assertEquals("[true,true,false]", result)
        } finally {
            Context.exit()
        }
    }
}
