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
            cx.evaluateString(
                scope,
                "var __bridgeCalls=[]; function __bridge(op,payload){__bridgeCalls.push({op:op,payload:payload}); return {accepted:true,traceId:'rhino-test'};}",
                "app-host-bridge-stub",
                1,
                null,
            )
            cx.evaluateString(scope, VBookAppKernelPrelude.build(), "app-kernel-v2", 1, null)

            val uiJson = Context.toString(cx.evaluateString(
                scope,
                "JSON.stringify(App.ui.notify('xin chao'))",
                "app-ui-result",
                1,
                null,
            ))
            assertEquals("{\"message\":\"xin chao\",\"openUrl\":null,\"refresh\":false}", uiJson)

            val executionJson = Context.toString(cx.evaluateString(
                scope,
                "JSON.stringify(App.reader.nextChapter())",
                "app-reader-command",
                1,
                null,
            ))
            assertEquals("{\"accepted\":true,\"traceId\":\"rhino-test\"}", executionJson)

            val callJson = Context.toString(cx.evaluateString(
                scope,
                "JSON.stringify(__bridgeCalls[0])",
                "app-reader-bridge-call",
                1,
                null,
            ))
            assertTrue("\"op\":\"host_command\"" in callJson)
            assertTrue("\"kind\":\"nghetruyen.host-command\"" in callJson)
            assertTrue("\"version\":2" in callJson)
            assertTrue("\"domain\":\"reader\"" in callJson)
            assertTrue("\"action\":\"nextChapter\"" in callJson)

            val intentJson = Context.toString(cx.evaluateString(
                scope,
                "JSON.stringify(App.intent('tts','play',{}))",
                "app-command-intent",
                1,
                null,
            ))
            assertTrue("\"domain\":\"tts\"" in intentJson)
            assertTrue("\"action\":\"play\"" in intentJson)
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

    @Test
    fun lifecycleSubscriptionPollsThenReplaysQueuedHostEvents() {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initStandardObjects()
            cx.evaluateString(
                scope,
                """
                    var __pollCalls=[];
                    function __bridge(op,command){
                      if(op==='host_command' && command.domain==='hooks' && command.action==='poll'){
                        __pollCalls.push(command.payload.name);
                        return {events:[{kind:'nghetruyen.host-event',version:2,name:'reader.enter',payload:{chapterId:'chapter-7'}}]};
                      }
                      return {accepted:true};
                    }
                """.trimIndent(),
                "app-lifecycle-bridge-stub",
                1,
                null,
            )
            cx.evaluateString(scope, VBookAppKernelPrelude.build(), "app-kernel-lifecycle", 1, null)
            val result = Context.toString(cx.evaluateString(
                scope,
                "var seen=''; App.lifecycle.on('reader.enter',function(event){seen=String(event.chapterId||'');}); JSON.stringify({seen:seen,polls:__pollCalls});",
                "app-lifecycle-replay",
                1,
                null,
            ))
            assertEquals("{\"seen\":\"chapter-7\",\"polls\":[\"reader.enter\"]}", result)
        } finally {
            Context.exit()
        }
    }
}
