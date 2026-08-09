package vn.nghetruyen.source.vbook

import org.junit.Assert.assertEquals
import org.junit.Test
import org.mozilla.javascript.Context

class VBookBrowserPatternPreludeTest {
    @Test
    fun matcherSupportsLiteralAndCommunityRegexPatternsWithoutThrowing() {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            cx.optimizationLevel = -1
            val scope = cx.initSafeStandardObjects()
            val script = """
                var Engine = {};
                var sleep = function(){};
                var __vbookBrowserMatch = function(){ return false; };
                ${VBookBrowserPatternPrelude.build()}
                JSON.stringify([
                  __vbookBrowserMatch('https://api.example/book/1','api.example/book'),
                  __vbookBrowserMatch('https://api.example/data?id=7','*api.example/data*'),
                  __vbookBrowserMatch('https://www.alicesw.com/home/chapter/info?id=1','.*?alicesw.com/home/chapter/info.*?'),
                  __vbookBrowserMatch('https://api.example/book/1','regex:[')
                ]);
            """.trimIndent()
            val result = Context.toString(cx.evaluateString(scope, script, "browser-pattern-test", 1, null))
            assertEquals("[true,true,true,false]", result)
        } finally {
            Context.exit()
        }
    }

    @Test
    fun browserHtmlHonorsWaitBeyondInternalTwoSecondChunk() {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            cx.optimizationLevel = -1
            val scope = cx.initSafeStandardObjects()
            val script = """
                var slept = [], nativeWait = -1;
                var sleep = function(ms){ slept.push(Number(ms)); };
                var Engine = {
                  newBrowser:function(){
                    return {html:function(ms){nativeWait=Number(ms);return 'doc';}};
                  },
                  browser:function(){
                    return {html:function(ms){nativeWait=Number(ms);return 'doc';}};
                  }
                };
                var __vbookBrowserMatch = function(){ return false; };
                ${VBookBrowserPatternPrelude.build()}
                var result = Engine.newBrowser().html(5000);
                JSON.stringify({result:result,slept:slept,nativeWait:nativeWait,total:slept.reduce(function(a,b){return a+b;},0)+nativeWait});
            """.trimIndent()
            val result = Context.toString(cx.evaluateString(scope, script, "browser-wait-test", 1, null))
            assertEquals("{\"result\":\"doc\",\"slept\":[2000,1000],\"nativeWait\":2000,\"total\":5000}", result)
        } finally {
            Context.exit()
        }
    }
}
