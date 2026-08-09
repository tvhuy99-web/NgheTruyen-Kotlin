package vn.nghetruyen.source.vbook

/**
 * Replaces the transitional fetch wrapper with a ClassShutter-safe implementation.
 * Response metadata arrives as JSON text from [VBookRawNetworkBroker], so no Java Map/array crosses
 * into the extension scope.
 */
object VBookFetchSafePrelude {
    fun build(): String = """
        function __vbookSafeCopyObject(source) {
          var target = {}, keys = Object.keys(source || {});
          for (var i=0;i<keys.length;i++) target[keys[i]] = source[keys[i]];
          return target;
        }
        function __vbookSafeHeaderValue(headers, wanted) {
          wanted = String(wanted || '').toLowerCase();
          headers = headers || {};
          var keys = Object.keys(headers);
          for (var i=0;i<keys.length;i++) if (String(keys[i]).toLowerCase() === wanted) return headers[keys[i]];
          return undefined;
        }
        function __vbookSafeCachedResponse(url, nativeOptions, nativeHeaders, responseKey, operation, charset) {
          var cacheOptions = __vbookSafeCopyObject(nativeOptions);
          var cacheHeaders = __vbookSafeCopyObject(nativeHeaders);
          cacheHeaders['${VBookRawNetworkBroker.INTERNAL_REQUEST_KEY}'] = responseKey;
          cacheHeaders['${VBookRawNetworkBroker.INTERNAL_OPERATION}'] = operation;
          if (charset !== undefined && charset !== null && String(charset).length) {
            cacheHeaders['${VBookRawNetworkBroker.INTERNAL_DECODE_CHARSET}'] = String(charset);
          }
          cacheOptions.headers = cacheHeaders;
          return __vbookNativeFetch(url, cacheOptions);
        }
        fetch = function(url, options) {
          options = options || {};
          url = String(url || '');
          if (options.queries) {
            var parts = [], qkeys = Object.keys(options.queries);
            for (var qi=0;qi<qkeys.length;qi++) {
              var qk=qkeys[qi], qv=options.queries[qk];
              parts.push(encodeURIComponent(String(qk)) + '=' + encodeURIComponent(String(qv == null ? '' : qv)));
            }
            if (parts.length) {
              var hashIndex=url.indexOf('#'), fragment=hashIndex>=0?url.substring(hashIndex):'';
              var baseUrl=hashIndex>=0?url.substring(0,hashIndex):url;
              url=baseUrl+(baseUrl.indexOf('?')>=0?'&':'?')+parts.join('&')+fragment;
            }
          }
          var nativeOptions=__vbookSafeCopyObject(options);
          var publicHeaders=__vbookSafeCopyObject(options.headers || {});
          var nativeHeaders=__vbookSafeCopyObject(publicHeaders);
          var requestKey='vbr-'+String(Date.now())+'-'+String(++__vbookFetchSeq);
          nativeHeaders['${VBookRawNetworkBroker.INTERNAL_REQUEST_KEY}']=requestKey;
          nativeHeaders['${VBookRawNetworkBroker.INTERNAL_TIMEOUT_MS}']=String(options.timeout===undefined||options.timeout===null?__vbookDefaultTimeoutMs:options.timeout);
          if (__vbookDelayMs>0) nativeHeaders['${VBookRawNetworkBroker.INTERNAL_DELAY_MS}']=String(__vbookDelayMs);
          nativeOptions.headers=nativeHeaders;
          if (nativeOptions.body && typeof nativeOptions.body === 'object') nativeOptions.body=JSON.stringify(nativeOptions.body);
          delete nativeOptions.queries;

          var response=__vbookNativeFetch(url,nativeOptions);
          var envelope;
          try { envelope=JSON.parse(String(response.body || '{}')); }
          catch (error) { throw new Error('VBOOK_FETCH_METADATA_ENVELOPE_INVALID:'+String(error)); }
          if (!envelope || envelope.__ngheVBookFetch !== 1) throw new Error('VBOOK_FETCH_METADATA_ENVELOPE_REQUIRED');
          var responseKey=String(envelope.responseKey || requestKey);
          var responseHeaders=envelope.headers && typeof envelope.headers==='object' ? envelope.headers : {};
          response.headers=responseHeaders;
          response.body=String(envelope.body == null ? '' : envelope.body);
          response.statusText=String(envelope.statusText == null ? '' : envelope.statusText);
          response.request=envelope.request && typeof envelope.request==='object' ? envelope.request : {url:url,headers:publicHeaders};
          response.header=function(name){return __vbookSafeHeaderValue(responseHeaders,name);};
          response.text=function(charset){
            return String(__vbookSafeCachedResponse(url,nativeOptions,nativeHeaders,responseKey,'${VBookRawNetworkBroker.OP_TEXT}',charset).body || '');
          };
          response.string=response.text;
          response.json=function(){return JSON.parse(response.text());};
          response.html=function(charset){return Html.parse(response.text(charset),response.url||url);};
          response.document=response.html;
          response.base64=function(){return String(__vbookSafeCachedResponse(url,nativeOptions,nativeHeaders,responseKey,'${VBookRawNetworkBroker.OP_BASE64}',null).body || '');};
          response.blob=function(){
            var type=response.header('content-type')||'';
            return {size:Number(envelope.rawSize||0),type:String(type).split(';')[0],base64:function(){return response.base64();}};
          };
          return response;
        };
    """.trimIndent()
}
