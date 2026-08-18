package vn.nghetruyen.source.vbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.javascript.Context

class VBookReplaySafeFetchEnvelopeTest {
    @Test
    fun appKernelRecoversOriginalRawResponseKeyFromReplayedEnvelope() {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initStandardObjects()
            cx.evaluateString(
                scope,
                """
                    var __calls=[];
                    var __vbookFetchSeq=0;
                    var __vbookDefaultTimeoutMs=15000;
                    var __vbookDelayMs=0;
                    var Html={parse:function(content,baseUrl){return {content:String(content),baseUrl:String(baseUrl||'')};}};
                    function __vbookNativeFetch(url,options){
                      options=options||{};
                      var headers=options.headers||{};
                      var operation=String(headers['${VBookRawNetworkBroker.INTERNAL_OPERATION}']||'');
                      var requestKey=String(headers['${VBookRawNetworkBroker.INTERNAL_REQUEST_KEY}']||'');
                      __calls.push({operation:operation,requestKey:requestKey});
                      if(!operation){
                        return {
                          status:200,
                          statusText:'',
                          url:String(url),
                          headers:{},
                          body:JSON.stringify({
                            __ngheVBookFetch:1,
                            responseKey:'cached-pass-a',
                            body:'{\"ok\":true}',
                            rawSize:11,
                            statusText:'OK',
                            headers:{'Content-Type':'application/json; charset=utf-8'},
                            request:{url:String(url),headers:{'X-Upstream':'real'}}
                          })
                        };
                      }
                      if(requestKey!=='cached-pass-a') throw new Error('WRONG_REPLAY_RESPONSE_KEY:'+requestKey);
                      if(operation==='${VBookRawNetworkBroker.OP_TEXT}') return {body:'{\"ok\":true}'};
                      throw new Error('UNEXPECTED_RAW_OPERATION:'+operation);
                    }
                    var fetch=__vbookNativeFetch;
                """.trimIndent(),
                "replay-safe-fetch-stub",
                1,
                null,
            )

            cx.evaluateString(scope, VBookAppKernelPrelude.build(), "app-kernel-replay-safe-fetch", 1, null)
            val result = Context.toString(cx.evaluateString(
                scope,
                """
                    var response=fetch('https://example.com/protected');
                    JSON.stringify({
                      body:response.body,
                      ok:response.json().ok,
                      requestUrl:response.request.url,
                      contentType:response.header('content-type'),
                      callCount:__calls.length,
                      initialKey:__calls[0].requestKey,
                      cacheKey:__calls[1].requestKey
                    });
                """.trimIndent(),
                "replay-safe-fetch-assertion",
                1,
                null,
            ))

            assertTrue("\"body\":\"{\\\"ok\\\":true}\"" in result)
            assertTrue("\"ok\":true" in result)
            assertTrue("\"requestUrl\":\"https://example.com/protected\"" in result)
            assertTrue("\"contentType\":\"application/json; charset=utf-8\"" in result)
            assertTrue("\"callCount\":2" in result)
            assertTrue("\"cacheKey\":\"cached-pass-a\"" in result)
            val initialKey = Context.toString(cx.evaluateString(scope, "__calls[0].requestKey", "initial-key", 1, null))
            assertTrue(initialKey.startsWith("vbr-"))
            assertTrue(initialKey != "cached-pass-a")
            assertEquals("cached-pass-a", Context.toString(cx.evaluateString(scope, "__calls[1].requestKey", "cache-key", 1, null)))
        } finally {
            Context.exit()
        }
    }
}
