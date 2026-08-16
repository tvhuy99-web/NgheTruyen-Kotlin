package vn.nghetruyen.source.vbook

 
object VBookBrowserPatternPrelude {
    fun build(): String = """
        __vbookBrowserMatch = function(url, pattern) {
          url = String(url || '');
          pattern = String(pattern || '');
          if (!pattern) return false;
          if (url.toLowerCase().indexOf(pattern.toLowerCase()) >= 0) return true;
          var explicitRegex = pattern.indexOf('regex:') === 0;
          var raw = explicitRegex ? pattern.substring(6) : pattern;
          var looksRegex = explicitRegex || raw.indexOf('.*') >= 0 || /[+?^${'$'}()|\[\]\\]/.test(raw);
          try {
            if (looksRegex) return new RegExp(raw).test(url);
            if (raw.indexOf('*') >= 0) {
              var escaped = raw.split('*').map(function(part) {
                return part.replace(/[.*+?^${'$'}{}()|\[\]\\]/g, '\\${'$'}&');
              }).join('.*');
              return new RegExp('^' + escaped + '${'$'}', 'i').test(url);
            }
          }
          catch (ignored) { return false; }
          return false;
        };

        function __vbookInvokeHost(fn, args) {
          switch (Number(args && args.length || 0)) {
            case 0: return fn();
            case 1: return fn(args[0]);
            case 2: return fn(args[0], args[1]);
            case 3: return fn(args[0], args[1], args[2]);
            case 4: return fn(args[0], args[1], args[2], args[3]);
            default: throw new Error('VBOOK_BROWSER_ARGUMENT_COUNT_UNSUPPORTED:' + Number(args.length || 0));
          }
        }

        function __vbookWrapBrowserSafe(nativeBrowser) {
          var out = {};
          var names = ['launch','launchAsync','waitSelector','waitRequest','requests','urls','html','callJs','evaluate','callJson','callJsAsync','evaluate_async','tapSelector','tap_selector','getVariable','cookie','cookieSnapshot','syncSession','setCookies','clearCookies','block','setUserAgent','setReplayPolicy','setDialogPolicy','dialogs','lastDialog','waitDialog','close','currentUrl'];
          for (var i=0;i<names.length;i++) (function(name) {
            var fn = nativeBrowser && nativeBrowser[name];
            if (typeof fn === 'function') out[name] = function(){ return __vbookInvokeHost(fn, arguments); };
          })(names[i]);
          out.loadHtml = function(html, baseUrl) {
            return nativeBrowser.loadHtml(String(baseUrl || ''), String(html == null ? '' : html));
          };
          out.waitUrl = function(patterns, timeoutMs) {
            var list = Array.isArray(patterns) ? patterns : [patterns];
            var timeout = Number(timeoutMs || 15000);
            // Current vBook documents waitUrl as waiting for a network-request URL. The native
            // waitRequest host returns one metadata object, avoiding the Java-array urls() bridge.
            var metadata = nativeBrowser.waitRequest(list, timeout);
            if (!metadata || metadata === false) return false;
            var candidate = String(metadata.url || '');
            if (!candidate) return false;
            for (var pi=0;pi<list.length;pi++) if (__vbookBrowserMatch(candidate,list[pi])) return candidate;
            return false;
          };
          return out;
        }

        function __vbookHonorBrowserHtmlWait(browser) {
          if (!browser || typeof browser.html !== 'function' || browser.__ngheHtmlWaitPatched) return browser;
          var nativeHtml = browser.html;
          browser.html = function(waitMs) {
            var requested = Number(waitMs || 0);
            if (!isFinite(requested) || requested < 0) requested = 0;
            requested = Math.min(120000, Math.floor(requested));
            var nativeWait = Math.min(requested, 2000);
            var remaining = requested - nativeWait;
            while (remaining > 0) {
              var chunk = Math.min(remaining, 2000);
              sleep(chunk);
              remaining -= chunk;
            }
            return nativeHtml(nativeWait);
          };
          Object.defineProperty(browser, '__ngheHtmlWaitPatched', {value:true, enumerable:false});
          return browser;
        }

        if (typeof Engine === 'object' && Engine) {
          if (typeof __vbookNativeNewBrowser === 'function') {
            Engine.newBrowser = function() {
              return __vbookHonorBrowserHtmlWait(__vbookWrapBrowserSafe(__vbookNativeNewBrowser()));
            };
          } else if (typeof Engine.newBrowser === 'function') {
            var __vbookHtmlWaitNewBrowser = Engine.newBrowser;
            Engine.newBrowser = function() {
              return __vbookHonorBrowserHtmlWait(__vbookHtmlWaitNewBrowser());
            };
          }
          if (typeof __vbookNativeBrowser === 'function') {
            Engine.browser = function() {
              return __vbookHonorBrowserHtmlWait(__vbookWrapBrowserSafe(__vbookNativeBrowser()));
            };
          } else if (typeof Engine.browser === 'function') {
            var __vbookHtmlWaitBrowser = Engine.browser;
            Engine.browser = function() {
              return __vbookHonorBrowserHtmlWait(__vbookHtmlWaitBrowser());
            };
          }
        }
    """.trimIndent()
}
