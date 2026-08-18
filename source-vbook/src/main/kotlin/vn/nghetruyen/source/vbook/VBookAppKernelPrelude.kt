package vn.nghetruyen.source.vbook

/**
 * Unified extension-kernel facade exposed to vBook JavaScript.
 *
 * vBook historically grows by adding globals (Http, Engine, localStorage, Qt, ...). `App` does not
 * remove those globals; it gives new extensions one stable root that represents NgheTruyen itself.
 * The surface deliberately exposes host capabilities and serializable host-command messages rather
 * than Java/Android implementation objects. That is the containment boundary for the full-authority
 * extension model.
 */
object VBookAppKernelPrelude {
    fun build(): String {
        val safeFetchPrelude = VBookFetchSafePrelude.build()
        return """
        if (typeof __vbookNativeFetch === 'function' && typeof __vbookFetchSeq !== 'undefined') {
          (function(){
            $safeFetchPrelude
          })();
        }
        (function(global){
          function present(name) {
            try { return typeof global[name] !== 'undefined' && global[name] !== null; }
            catch (ignored) { return false; }
          }
          function value(name) { return present(name) ? global[name] : undefined; }
          function freeze(value) {
            try { return value && (typeof value === 'object' || typeof value === 'function') ? Object.freeze(value) : value; }
            catch (ignored) { return value; }
          }
          function copyObject(source) {
            var out = {}, input = source && typeof source === 'object' ? source : {}, keys = Object.keys(input);
            for (var i=0; i<keys.length; i++) out[keys[i]] = input[keys[i]];
            return out;
          }
          function hostCommandIntent(domain, action, payload) {
            return freeze({
              kind: 'nghetruyen.host-command',
              version: 2,
              domain: String(domain || ''),
              action: String(action || ''),
              payload: freeze(copyObject(payload))
            });
          }
          function executeHostCommand(command) {
            if (typeof global.__bridge !== 'function') throw new Error('APP_HOST_COMMAND_BRIDGE_UNAVAILABLE');
            return global.__bridge('host_command', command);
          }
          function hostCommand(domain, action, payload) {
            return executeHostCommand(hostCommandIntent(domain, action, payload));
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

          /*
           * Existing UI_ACTION handlers already understand message/openUrl/refresh. Those helpers
           * keep their compatibility result contract. Explicit ui.command() and the Reader/Library/
           * TTS helpers use the v2 bridge and execute against the NgheTruyen host immediately.
           */
          function uiResult(options) {
            var input = options && typeof options === 'object' ? options : {};
            return {
              message: input.message == null ? '' : String(input.message),
              openUrl: input.openUrl == null ? null : String(input.openUrl),
              refresh: !!input.refresh
            };
          }
          var uiApi = freeze({
            result: uiResult,
            notify: function(message){ return uiResult({message:message}); },
            open: function(url){ return uiResult({openUrl:url}); },
            refresh: function(message){ return uiResult({message:message, refresh:true}); },
            command: function(action,payload){ return hostCommand('ui', action, payload); },
            navigate: function(route,payload){
              var data = copyObject(payload); data.route = String(route || '');
              return hostCommand('ui', 'navigate', data);
            }
          });

          var readerApi = freeze({
            refresh: function(message){ return uiResult({message:message, refresh:true}); },
            command: function(action,payload){ return hostCommand('reader', action, payload); },
            nextChapter: function(){ return hostCommand('reader', 'nextChapter', {}); },
            previousChapter: function(){ return hostCommand('reader', 'previousChapter', {}); },
            moveParagraph: function(delta){ return hostCommand('reader', 'moveParagraph', {delta:Number(delta || 0)}); },
            setMode: function(mode){ return hostCommand('reader', 'setMode', {mode:String(mode || '')}); },
            setTextMode: function(mode){ return hostCommand('reader', 'setTextMode', {mode:String(mode || '')}); },
            openChapter: function(chapterId,url){ return hostCommand('reader', 'openChapter', {chapterId:String(chapterId || ''),url:String(url || '')}); }
          });

          var libraryApi = freeze({
            command: function(action,payload){ return hostCommand('library', action, payload); },
            follow: function(storyId){ return hostCommand('library', 'follow', {storyId:String(storyId || '')}); },
            unfollow: function(storyId){ return hostCommand('library', 'unfollow', {storyId:String(storyId || '')}); },
            bookmark: function(chapterId,paragraphIndex){ return hostCommand('library', 'bookmark', {chapterId:String(chapterId || ''),paragraphIndex:Number(paragraphIndex || 0)}); },
            unbookmark: function(bookmarkId){ return hostCommand('library', 'unbookmark', {bookmarkId:String(bookmarkId || '')}); },
            note: function(chapterId,text,paragraphIndex){ return hostCommand('library', 'note', {chapterId:String(chapterId || ''),text:String(text || ''),paragraphIndex:Number(paragraphIndex || 0)}); },
            removeNote: function(noteId){ return hostCommand('library', 'removeNote', {noteId:String(noteId || '')}); }
          });

          var ttsApi = freeze({
            command: function(action,payload){ return hostCommand('tts', action, payload); },
            play: function(){ return hostCommand('tts', 'play', {}); },
            pause: function(){ return hostCommand('tts', 'pause', {}); },
            stop: function(){ return hostCommand('tts', 'stop', {}); },
            toggle: function(){ return hostCommand('tts', 'toggle', {}); },
            setRate: function(rate){ return hostCommand('tts', 'setRate', {rate:Number(rate || 1)}); },
            setPitch: function(pitch){ return hostCommand('tts', 'setPitch', {pitch:Number(pitch || 1)}); },
            setVoice: function(voiceId){ return hostCommand('tts', 'setVoice', {voiceId:String(voiceId || '')}); }
          });

          var hookListeners = {};
          function hookList(name) {
            name = String(name || '');
            if (!hookListeners[name]) hookListeners[name] = [];
            return hookListeners[name];
          }
          var hooksApi = freeze({
            on: function(name,handler){
              if (typeof handler !== 'function') throw new Error('APP_HOOK_HANDLER_REQUIRED');
              hookList(name).push(handler); return handler;
            },
            once: function(name,handler){
              if (typeof handler !== 'function') throw new Error('APP_HOOK_HANDLER_REQUIRED');
              var wrapper = function(payload){ hooksApi.off(name, wrapper); return handler(payload); };
              hookList(name).push(wrapper); return wrapper;
            },
            off: function(name,handler){
              var list = hookList(name), next = [];
              for (var i=0; i<list.length; i++) if (list[i] !== handler) next.push(list[i]);
              hookListeners[String(name || '')] = next;
              return next.length !== list.length;
            },
            emit: function(name,payload){
              var list = hookList(name).slice(), results = [];
              for (var i=0; i<list.length; i++) results.push(list[i](payload));
              return results;
            },
            hostEvent: function(name,payload){ return hostCommand('hooks', 'emit', {name:String(name || ''),payload:copyObject(payload)}); }
          });

          function pollLifecycleEvents(name) {
            name = String(name || '');
            var response = hostCommand('hooks', 'poll', {name:name});
            var events = response && response.events && typeof response.events.length === 'number' ? response.events : [];
            for (var i=0; i<events.length; i++) {
              var event = events[i] || {};
              if (String(event.name || '') === name) hooksApi.emit(name, event.payload || {});
            }
            return events.length;
          }
          function subscribeLifecycle(name, handler, once) {
            name = String(name || '');
            var registered = once ? hooksApi.once(name, handler) : hooksApi.on(name, handler);
            try { pollLifecycleEvents(name); } catch (ignored) {}
            return registered;
          }
          var lifecycleApi = freeze({
            events: freeze([
              'app.start','app.resume','app.pause','explore.enter','story.enter','reader.enter','reader.leave',
              'reader.chapterChanged','playback.changed','library.changed'
            ]),
            on: function(name,handler){ return subscribeLifecycle(name, handler, false); },
            once: function(name,handler){ return subscribeLifecycle(name, handler, true); },
            off: hooksApi.off,
            poll: pollLifecycleEvents
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
            ui: true,
            reader: true,
            library: true,
            tts: true,
            hooks: true,
            lifecycle: true,
            hostCommandContract: true,
            hostCommandExecution: true,
            rawAndroid: false,
            hostSecrets: false
          });

          global.App = freeze({
            apiVersion: 2,
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
            diagnostics: diagnosticsApi,
            ui: uiApi,
            reader: readerApi,
            library: libraryApi,
            tts: ttsApi,
            hooks: hooksApi,
            lifecycle: lifecycleApi,
            intent: hostCommandIntent,
            execute: executeHostCommand,
            command: hostCommand
          });
        })(this);
        """.trimIndent()
    }
}
