package vn.nghetruyen.source.vbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject

class VBookAppKernelPreludeExecutionTest {
    @Test
    fun uiHelpersAndHostCommandsExecuteInRhino() {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initStandardObjects()
            cx.evaluateString(scope, VBookAppKernelPrelude.build(), "app-kernel-v2", 1, null)

            val uiJson = Context.toString(cx.evaluateString(
                scope,
                "JSON.stringify(App.ui.notify('xin chao'))",
                "app-ui-result",
                1,
                null,
            ))
            assertEquals("{\"message\":\"xin chao\",\"openUrl\":null,\"refresh\":false}", uiJson)

            val commandJson = Context.toString(cx.evaluateString(
                scope,
                "JSON.stringify(App.reader.nextChapter())",
                "app-reader-command",
                1,
                null,
            ))
            assertTrue("\"kind\":\"nghetruyen.host-command\"" in commandJson)
            assertTrue("\"version\":2" in commandJson)
            assertTrue("\"domain\":\"reader\"" in commandJson)
            assertTrue("\"action\":\"nextChapter\"" in commandJson)
        } finally {
            Context.exit()
        }
    }

    @Test
    fun hookBusSupportsOnOnceOffAndEmit() {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initStandardObjects()
            cx.evaluateString(scope, VBookAppKernelPrelude.build(), "app-kernel-hooks", 1, null)
            val result = Context.toString(cx.evaluateString(
                scope,
                "var n=0; var h=function(v){n+=v;}; App.hooks.on('reader.enter',h); App.hooks.once('reader.enter',function(v){n+=v*10;}); App.hooks.emit('reader.enter',2); App.hooks.emit('reader.enter',3); App.hooks.off('reader.enter',h); JSON.stringify({n:n,left:App.hooks.emit('reader.enter',1).length});",
                "app-hooks",
                1,
                null,
            ))
            assertEquals("{\"n\":25,\"left\":0}", result)
            val app = ScriptableObject.getProperty(scope, "App")
            assertTrue(app !== Context.getUndefinedValue())
        } finally {
            Context.exit()
        }
    }
}
