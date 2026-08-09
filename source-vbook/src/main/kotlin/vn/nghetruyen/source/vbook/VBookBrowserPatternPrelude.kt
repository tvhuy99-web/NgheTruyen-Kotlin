package vn.nghetruyen.source.vbook

/** Current-browser compatibility patches installed after the base browser shim. */
object VBookBrowserPatternPrelude {
    fun build(): String = """
        __vbookBrowserMatch = function(url, pattern) {
          url = String(url || '');
          pattern = String(pattern || '');
          if (!pattern) return false;
          if (url.toLowerCase().indexOf(pattern.toLowerCase()) >= 0) return true;
          var raw = pattern.indexOf('regex:') === 0 ? pattern.substring(6) : pattern;
          var looksRegex = pattern.indexOf('regex:') === 0 || /[.*+?^${'$'}()|\[\]\\]/.test(raw);
          if (!looksRegex) return false;
          try { return new RegExp(raw).test(url); }
          catch (ignored) { return false; }
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
            var deadline = new Date().getTime() + timeout;
            do {
              var urls = nativeBrowser.urls();
              for (var ui=0;ui<Number(urls && urls.length || 0);ui++) {
                var candidate = String(urls[ui]);
                for (var pi=0;pi<list.length;pi++) if (__vbookBrowserMatch(candidate, list[pi])) return candidate;
              }
              sleep(100);
            } while (new Date().getTime() < deadline);
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
