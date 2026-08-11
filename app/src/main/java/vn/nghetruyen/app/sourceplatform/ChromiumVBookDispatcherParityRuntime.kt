package vn.nghetruyen.app.sourceplatform

import vn.nghetruyen.source.api.SourceActionRequest
import vn.nghetruyen.source.api.SourceActionResponse
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.runtime.SourceResourceProvider
import vn.nghetruyen.source.vbook.VBookActionRuntime

/**
 * Inserts Chromium-only native-host parity shims into the generated vBook dispatcher.
 *
 * The shared dispatcher intentionally stays byte-identical to the Rhino path. The patch is inserted
 * immediately after its strict-mode directive, before that dispatcher captures Engine/Html host
 * functions. Source package resources are otherwise untouched.
 */
class ChromiumVBookDispatcherParityRuntime(
    private val delegate: VBookActionRuntime,
) : VBookActionRuntime {
    override fun execute(
        manifest: SourceManifest,
        resources: SourceResourceProvider,
        request: SourceActionRequest,
    ): SourcePlatformResult<SourceActionResponse> {
        val entry = manifest.actions[request.action]?.entry ?: return delegate.execute(manifest, resources, request)
        val patchedResources = object : SourceResourceProvider {
            override fun read(path: String, maxBytes: Int): ByteArray? {
                val original = resources.read(path, maxBytes) ?: return null
                if (path != entry) return original
                val text = original.toString(Charsets.UTF_8)
                if (!text.startsWith(STRICT_PREFIX) || CHROMIUM_PATCH_MARKER in text) return original
                val patched = text.replaceFirst(STRICT_PREFIX, STRICT_PREFIX + "\n" + ChromiumVBookBrowserParityPatch.build())
                val bytes = patched.toByteArray(Charsets.UTF_8)
                require(bytes.size <= maxBytes) { "CHROMIUM_DISPATCH_PATCH_TOO_LARGE" }
                return bytes
            }
        }
        return delegate.execute(manifest, patchedResources, request)
    }

    companion object {
        private const val STRICT_PREFIX = "'use strict';"
        internal const val CHROMIUM_PATCH_MARKER = "__ngheChromiumBrowserParityV1"
    }
}

internal object ChromiumVBookBrowserParityPatch {
    fun build(): String = """
        /* __ngheChromiumBrowserParityV1 */
        (function(global){
          var rawEngine=global.Engine;
          if(!rawEngine||typeof rawEngine.newBrowser!=='function') return;

          function strings(value){
            if(value===undefined||value===null) return [];
            if(Array.isArray(value)) return value.map(String).map(function(v){return v.trim();}).filter(Boolean);
            return [String(value).trim()].filter(Boolean);
          }
          function matchUrl(url,pattern){
            url=String(url||''); pattern=String(pattern||'');
            if(!pattern) return false;
            if(url.toLowerCase().indexOf(pattern.toLowerCase())>=0) return true;
            var explicit=pattern.indexOf('regex:')===0, raw=explicit?pattern.substring(6):pattern;
            var looksRegex=explicit||raw.indexOf('.*')>=0||/[+?^$()|\[\]\\]/.test(raw);
            try {
              if(looksRegex) return new RegExp(raw).test(url);
              if(raw.indexOf('*')>=0){
                var escaped=raw.split('*').map(function(part){return part.replace(/[.*+?^${'$'}{}()|\[\]\\]/g,'\\${'$'}&');}).join('.*');
                return new RegExp('^'+escaped+'${'$'}','i').test(url);
              }
            } catch(ignored) {}
            return false;
          }
          function metadata(item){
            item=item&&typeof item==='object'?item:{};
            return {
              url:String(item.url||''),
              method:String(item.method||''),
              mainFrame:!!item.mainFrame,
              resourceType:String(item.resourceType||''),
              headerNames:Array.isArray(item.headerNames)?item.headerNames.map(String):[],
              timestamp:Number(item.timestamp!==undefined?item.timestamp:(item.timestampEpochMs||0))
            };
          }
          function dialog(item){
            item=item&&typeof item==='object'?item:{};
            return {
              id:Number(item.id||0),
              type:String(item.type||''),
              message:String(item.message||''),
              defaultValue:item.defaultValue,
              url:String(item.url!==undefined?item.url:(item.pageUrl||'')),
              accepted:item.accepted,
              value:item.value!==undefined?item.value:item.responseValue,
              timestamp:Number(item.timestamp!==undefined?item.timestamp:(item.timestampEpochMs||0))
            };
          }
          function names(cookie){
            return String(cookie||'').split(';').map(function(token){return token.split('=')[0].trim();}).filter(Boolean).filter(function(value,index,self){return self.indexOf(value)===index;});
          }
          function decorate(nativeBrowser){
            if(!nativeBrowser||nativeBrowser.__ngheChromiumBrowserParityV1) return nativeBrowser;
            Object.defineProperty(nativeBrowser,'__ngheChromiumBrowserParityV1',{value:true});
            var lowLaunch=nativeBrowser.launch;
            var lowLoadHtml=nativeBrowser.loadHtml;
            var lowHtml=nativeBrowser.html;
            var lowWaitSelector=nativeBrowser.waitSelector;
            var lowRequests=nativeBrowser.requests;
            var lowCookie=typeof nativeBrowser.cookie==='function'?nativeBrowser.cookie:null;
            var lowSync=typeof nativeBrowser.syncSession==='function'?nativeBrowser.syncSession:null;
            var lowSetCookies=typeof nativeBrowser.setCookies==='function'?nativeBrowser.setCookies:null;
            var lowBlock=typeof nativeBrowser.block==='function'?nativeBrowser.block:null;
            var lowUserAgent=typeof nativeBrowser.setUserAgent==='function'?nativeBrowser.setUserAgent:null;
            var lowDialogPolicy=typeof nativeBrowser.setDialogPolicy==='function'?nativeBrowser.setDialogPolicy:null;
            var lowDialogs=typeof nativeBrowser.dialogs==='function'?nativeBrowser.dialogs:null;

            nativeBrowser.launch=function(url,timeoutMs){
              lowLaunch.call(nativeBrowser,String(url||''),timeoutMs);
              var base=typeof nativeBrowser.currentUrl==='function'?nativeBrowser.currentUrl():String(url||'');
              return global.Html.parse(String(lowHtml.call(nativeBrowser)||''),String(base||''));
            };
            nativeBrowser.launchAsync=function(url){ lowLaunch.call(nativeBrowser,String(url||'')); return true; };
            nativeBrowser.loadHtml=function(baseUrl,html){ lowLoadHtml.call(nativeBrowser,String(baseUrl||''),String(html==null?'':html)); return nativeBrowser; };
            nativeBrowser.html=function(waitMs){
              var wait=Math.max(0,Math.min(2000,Number(waitMs||0))); if(wait>0&&typeof global.sleep==='function')global.sleep(wait);
              var base=typeof nativeBrowser.currentUrl==='function'?nativeBrowser.currentUrl():'';
              return global.Html.parse(String(lowHtml.call(nativeBrowser)||''),String(base||''));
            };
            nativeBrowser.waitSelector=function(raw,timeoutMs){
              var selectors=strings(raw); if(!selectors.length)return false;
              var per=Math.max(250,Math.floor(Math.max(100,Number(timeoutMs||15000))/selectors.length));
              for(var i=0;i<selectors.length;i++) if(lowWaitSelector.call(nativeBrowser,selectors[i],per)) return selectors[i];
              return false;
            };
            nativeBrowser.requests=function(options){
              options=options&&typeof options==='object'&&!Array.isArray(options)?options:{};
              var patterns=strings(options.patterns), method=String(options.method||''), mainFrame=options.mainFrame===true||String(options.mainFrame||'')==='true';
              var limit=Math.max(1,Math.min(500,Number(options.limit||100)));
              var raw=lowRequests.call(nativeBrowser)||[], out=[];
              for(var i=0;i<raw.length;i++){
                var item=metadata(raw[i]);
                if(patterns.length&&!patterns.some(function(pattern){return matchUrl(item.url,pattern);}))continue;
                if(method&&item.method.toLowerCase()!==method.toLowerCase())continue;
                if(mainFrame&&!item.mainFrame)continue;
                out.push(item);
              }
              return out.slice(Math.max(0,out.length-limit));
            };
            nativeBrowser.urls=function(){
              var out=[]; nativeBrowser.requests({limit:500}).forEach(function(item){if(item.url&&out.indexOf(item.url)<0)out.push(item.url);}); return out;
            };
            nativeBrowser.waitRequest=function(raw,timeoutMs,options){
              var patterns=strings(raw), deadline=Date.now()+Math.max(100,Number(timeoutMs||15000)), opts=options&&typeof options==='object'?options:{};
              do {
                var values=nativeBrowser.requests({patterns:patterns,method:opts.method,mainFrame:opts.mainFrame,limit:500});
                if(values.length)return values[0];
                if(typeof global.sleep==='function')global.sleep(100);
              } while(Date.now()<deadline);
              return false;
            };
            nativeBrowser.cookieSnapshot=function(url){
              url=String(url||((typeof nativeBrowser.currentUrl==='function'&&nativeBrowser.currentUrl())||''));
              var cookie=lowCookie?String(lowCookie.call(nativeBrowser,url)||''):String(global.localCookie&&global.localCookie.getCookie?global.localCookie.getCookie(url):'');
              return {url:url,cookie:cookie,names:names(cookie)};
            };
            nativeBrowser.cookie=function(url){ return nativeBrowser.cookieSnapshot(url).cookie; };
            nativeBrowser.syncSession=function(url,direction){ if(lowSync)lowSync.call(nativeBrowser,String(url||''),String(direction||'both')); return nativeBrowser.cookieSnapshot(url); };
            nativeBrowser.setCookies=function(cookies,url){ if(lowSetCookies)lowSetCookies.call(nativeBrowser,cookies,url); return nativeBrowser.cookieSnapshot(url); };
            nativeBrowser.block=function(patterns){ if(lowBlock)lowBlock.call(nativeBrowser,patterns); return nativeBrowser; };
            nativeBrowser.setUserAgent=function(value){ if(lowUserAgent)lowUserAgent.call(nativeBrowser,String(value||'')); return nativeBrowser; };
            nativeBrowser.setReplayPolicy=function(){ return nativeBrowser; };
            nativeBrowser.setDialogPolicy=function(policy){
              policy=policy&&typeof policy==='object'?policy:{};
              if(lowDialogPolicy)lowDialogPolicy.call(nativeBrowser,{defaultAction:String(policy.default_action||policy.defaultAction||'dismiss'),defaultValue:String(policy.default_value||policy.defaultValue||'')});
              return nativeBrowser;
            };
            nativeBrowser.dialogs=function(limit){
              var raw=lowDialogs?lowDialogs.call(nativeBrowser):[], out=[]; for(var i=0;i<raw.length;i++)out.push(dialog(raw[i]));
              var count=Math.max(1,Math.min(100,Number(limit||50))); return out.slice(Math.max(0,out.length-count));
            };
            nativeBrowser.lastDialog=function(){var values=nativeBrowser.dialogs(1);return values.length?values[0]:undefined;};
            nativeBrowser.waitDialog=function(options,timeoutMs){
              options=options&&typeof options==='object'?options:{}; var type=String(options.type||'any'), needle=String(options.match||''), afterId=Number(options.after_id||options.afterId||0);
              var deadline=Date.now()+Math.max(100,Number(timeoutMs||options.timeout||15000));
              do {
                var values=nativeBrowser.dialogs(100);
                for(var i=values.length-1;i>=0;i--){var item=values[i];if(item.id<=afterId)continue;if(type!=='any'&&item.type!==type)continue;if(needle&&item.message.indexOf(needle)<0)continue;return item;}
                if(typeof global.sleep==='function')global.sleep(100);
              } while(Date.now()<deadline);
              return undefined;
            };
            var lowTap=nativeBrowser.tapSelector;
            nativeBrowser.tapSelector=function(selector,timeoutMs){return String(lowTap.call(nativeBrowser,selector,timeoutMs))==='true';};
            nativeBrowser.tap_selector=nativeBrowser.tapSelector;
            return nativeBrowser;
          }

          var lowNew=rawEngine.newBrowser, lowCurrent=rawEngine.browser;
          rawEngine.newBrowser=function(){return decorate(lowNew.apply(rawEngine,arguments));};
          rawEngine.browser=function(){return decorate(lowCurrent.apply(rawEngine,arguments));};
          if(global.Browser) global.Browser=decorate(global.Browser);
        })(this);
    """.trimIndent()
}
