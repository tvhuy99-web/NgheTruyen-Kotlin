package vn.nghetruyen.app.sourceplatform

import org.json.JSONObject

/**
 * Safe, read-only WebView page probe modeled after the useful parts of the Lua/XPK browser
 * diagnostics. It intentionally excludes resource URLs, cookie names/values, storage keys/values,
 * form values and script source URLs. The full JSON is suitable for diagnostic evidence after the
 * normal redaction layer; [summary] is small enough to attach to the structured event stream.
 */
object BrowserForensics {
    val pageScript: String = """
        (()=>{try{
          const now=Date.now();
          if(!window.__ngheDiagDomWatch){
            window.__ngheDiagDomWatch={lastMutation:now};
            try{
              const observer=new MutationObserver(()=>{window.__ngheDiagDomWatch.lastMutation=Date.now()});
              observer.observe(document.documentElement||document,{subtree:true,childList:true,characterData:true,attributes:true});
              window.__ngheDiagDomWatch.observer=observer;
            }catch(_e){}
          }
          const has=(fn)=>{try{return !!fn()}catch(_e){return false}};
          const capabilities={
            Promise:has(()=>typeof Promise!=='undefined'),
            fetch:has(()=>typeof fetch==='function'),
            XMLHttpRequest:has(()=>typeof XMLHttpRequest!=='undefined'),
            WebSocket:has(()=>typeof WebSocket!=='undefined'),
            crypto:has(()=>!!window.crypto),
            cryptoSubtle:has(()=>!!(window.crypto&&window.crypto.subtle)),
            TextEncoder:has(()=>typeof TextEncoder!=='undefined'),
            TextDecoder:has(()=>typeof TextDecoder!=='undefined'),
            URL:has(()=>typeof URL!=='undefined'),
            URLSearchParams:has(()=>typeof URLSearchParams!=='undefined'),
            localStorage:has(()=>!!window.localStorage),
            sessionStorage:has(()=>!!window.sessionStorage),
            IndexedDB:has(()=>!!window.indexedDB),
            WebAssembly:has(()=>typeof WebAssembly!=='undefined'),
            Intl:has(()=>typeof Intl!=='undefined'),
            MutationObserver:has(()=>typeof MutationObserver!=='undefined'),
            IntersectionObserver:has(()=>typeof IntersectionObserver!=='undefined'),
            ResizeObserver:has(()=>typeof ResizeObserver!=='undefined'),
            AbortController:has(()=>typeof AbortController!=='undefined'),
            structuredClone:has(()=>typeof structuredClone==='function'),
            userAgentData:has(()=>!!navigator.userAgentData)
          };
          const root=document.documentElement, body=document.body;
          const recent=[];
          try{
            const entries=(performance&&performance.getEntriesByType)?performance.getEntriesByType('resource').slice(-12):[];
            for(const e of entries){
              recent.push({
                type:String(e.initiatorType||''),
                duration:Math.round(Number(e.duration||0)),
                transferSize:Number(e.transferSize||0),
                decodedBodySize:Number(e.decodedBodySize||0),
                status:Number(e.responseStatus||0)
              });
            }
          }catch(_e2){}
          return JSON.stringify({
            readyState:String(document.readyState||''),
            visibility:String(document.visibilityState||''),
            online:!!navigator.onLine,
            secureContext:!!window.isSecureContext,
            cookieEnabled:!!navigator.cookieEnabled,
            charset:String(document.characterSet||''),
            forms:document.forms?document.forms.length:0,
            scripts:document.scripts?document.scripts.length:0,
            iframes:document.getElementsByTagName('iframe').length,
            innerWidth:Number(window.innerWidth||0),
            innerHeight:Number(window.innerHeight||0),
            devicePixelRatio:Number(window.devicePixelRatio||1),
            scrollWidth:Number(root&&root.scrollWidth||0),
            scrollHeight:Number(root&&root.scrollHeight||0),
            elementCount:Number(document.getElementsByTagName('*').length||0),
            htmlLength:Number(root&&root.outerHTML?root.outerHTML.length:0),
            textLength:Number(body&&body.innerText?body.innerText.length:0),
            mutationAgeMs:Math.max(0,now-Number(window.__ngheDiagDomWatch.lastMutation||now)),
            resourceCount:(performance&&performance.getEntriesByType)?performance.getEntriesByType('resource').length:0,
            recentResources:recent,
            capabilities:capabilities
          });
        }catch(e){return JSON.stringify({error:String(e&&e.stack?e.stack:e)})}})()
    """.trimIndent()

    fun parse(raw: String): JSONObject? = runCatching { JSONObject(raw) }.getOrNull()

    fun summary(raw: JSONObject, sessionId: String, navigationGeneration: Long): Map<String, String> {
        val output = linkedMapOf(
            "flow" to "browser",
            "stage" to "page_forensics",
            "sessionId" to sessionId,
            "navigationGeneration" to navigationGeneration.toString(),
            "readyState" to raw.optString("readyState"),
            "visibility" to raw.optString("visibility"),
            "online" to raw.optBoolean("online").toString(),
            "secureContext" to raw.optBoolean("secureContext").toString(),
            "cookieEnabled" to raw.optBoolean("cookieEnabled").toString(),
            "forms" to raw.optInt("forms").toString(),
            "scripts" to raw.optInt("scripts").toString(),
            "iframes" to raw.optInt("iframes").toString(),
            "htmlLength" to raw.optLong("htmlLength").toString(),
            "textLength" to raw.optLong("textLength").toString(),
            "elementCount" to raw.optLong("elementCount").toString(),
            "scrollHeight" to raw.optLong("scrollHeight").toString(),
            "resourceCount" to raw.optLong("resourceCount").toString(),
            "mutationAgeMs" to raw.optLong("mutationAgeMs").toString(),
        )
        raw.optJSONObject("capabilities")?.let { capabilities ->
            capabilities.keys().forEach { key ->
                output["capability.$key"] = capabilities.optBoolean(key).toString()
            }
        }
        raw.optString("error").takeIf(String::isNotBlank)?.let { output["probeError"] = it.take(2_000) }
        return output
    }
}
