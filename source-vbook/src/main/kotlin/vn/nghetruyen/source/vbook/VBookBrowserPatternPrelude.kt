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

        function __vbookHonorBrowserHtmlWait(browser) {
          if (!browser || typeof browser.html !== 'function' || browser.__ngheHtmlWaitPatched) return browser;
          var nativeHtml = browser.html;
          browser.html = function(waitMs) {
            var requested = Number(waitMs || 0);
            if (!isFinite(requested) || requested < 0) requested = 0;
            requested = Math.min(120000, Math.floor(requested));
            // The underlying Android host historically caps one wait call at 2s. Preserve the
            // documented vBook wait by spending the remainder through the sandbox-budgeted sleep.
            var nativeWait = Math.min(requested, 2000);
            var remaining = requested - nativeWait;
            while (remaining > 0) {
              var chunk = Math.min(remaining, 2000);
              sleep(chunk);
              remaining -= chunk;
            }
            return nativeHtml.call(browser, nativeWait);
          };
          Object.defineProperty(browser, '__ngheHtmlWaitPatched', {value:true, enumerable:false});
          return browser;
        }

        if (typeof Engine === 'object' && Engine) {
          if (typeof Engine.newBrowser === 'function') {
            var __vbookHtmlWaitNewBrowser = Engine.newBrowser;
            Engine.newBrowser = function() {
              return __vbookHonorBrowserHtmlWait(__vbookHtmlWaitNewBrowser.apply(Engine, arguments));
            };
          }
          if (typeof Engine.browser === 'function') {
            var __vbookHtmlWaitBrowser = Engine.browser;
            Engine.browser = function() {
              return __vbookHonorBrowserHtmlWait(__vbookHtmlWaitBrowser.apply(Engine, arguments));
            };
          }
        }
    """.trimIndent()
}
