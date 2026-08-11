package vn.nghetruyen.source.vbook

/**
 * Unified extension-kernel facade exposed to current vBook JavaScript.
 *
 * vBook historically grows by adding globals (Http, Engine, localStorage, Qt, ...). `App` does not
 * remove those globals; it gives new extensions one stable root that represents NgheTruyen itself.
 * The surface deliberately exposes host capabilities rather than Java/Android implementation
 * objects. That is the containment boundary for the full-authority extension model.
 */
object VBookAppKernelPrelude {
    fun build(): String = """
        (function(global){
          function present(name) {
            try { return typeof global[name] !== 'undefined' && global[name] !== null; }
            catch (ignored) { return false; }
          }
          function value(name) { return present(name) ? global[name] : undefined; }
          function freeze(value) {
            try { return value && typeof value === 'object' ? Object.freeze(value) : value; }
            catch (ignored) { return value; }
          }

          var engine = value('Engine');
          var browserApi = freeze({
            create: function(){
              if (!engine || typeof engine.newBrowser !== 'function') throw new Error('APP_BROWSER_UNAVAILABLE');
              return engine.newBrowser();
            },
            current: function(){
              if (!engine || typeof engine.browser !== 'function') throw new Error('APP_BROWSER_UNAVAILABLE');
              return engine.browser();
            },
            available: !!(engine && (typeof engine.newBrowser === 'function' || typeof engine.browser === 'function'))
          });

          var networkApi = freeze({
            fetch: function(){ return global.fetch.apply(global, arguments); },
            http: value('Http') || value('HTTP'),
            available: typeof global.fetch === 'function'
          });

          var storageApi = freeze({
            local: value('localStorage'),
            cache: value('cacheStorage'),
            config: value('localConfig'),
            available: present('localStorage') || present('cacheStorage')
          });

          var diagnosticsApi = freeze({
            log: value('Log') || value('Console') || value('console'),
            console: value('console') || value('Console') || value('Log')
          });

          var capabilityView = freeze({
            authority: 'FULL_IN_APP',
            browser: true,
            network: true,
            cookies: true,
            storage: true,
            crypto: true,
            websocket: true,
            graphics: true,
            translation: true,
            script: true,
            rawAndroid: false,
            hostSecrets: false
          });

          global.App = freeze({
            apiVersion: 1,
            authority: 'FULL_IN_APP',
            capabilities: capabilityView,
            network: networkApi,
            browser: browserApi,
            html: value('Html') || value('HTML') || value('Document'),
            storage: storageApi,
            cookies: value('localCookie'),
            crypto: value('Crypto'),
            graphics: value('Graphics'),
            websocket: value('WebSocket'),
            translation: value('Qt'),
            script: value('Script'),
            userAgent: value('UserAgent'),
            diagnostics: diagnosticsApi
          });
        })(this);
    """.trimIndent()
}
