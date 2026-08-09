package vn.nghetruyen.source.vbook

/** JavaScript compatibility shim for the current vBook WebSocket ABI. */
object VBookWebSocketPrelude {
    fun build(): String = """
        var __vbookNativeWebSocket = WebSocket;
        function __vbookDecodeWsFrame(raw) {
          raw = String(raw == null ? '' : raw);
          if (raw.indexOf('${VBookWebSocketBroker.FRAME_PREFIX}') !== 0) {
            return {type:'text', data:raw};
          }
          var payload = raw.substring('${VBookWebSocketBroker.FRAME_PREFIX}'.length);
          var split = payload.indexOf(':');
          if (split < 0) return {type:'text', data:raw};
          var type = payload.substring(0, split) === 'b' ? 'binary' : 'text';
          var encoded = payload.substring(split + 1);
          var decoded = Crypto.base64ToUtf8(encoded);
          return {type:type, data:decoded};
        }
        WebSocket = function(url, headers) {
          var nativeSocket = __vbookNativeWebSocket(String(url || ''));
          var headerObject = headers && typeof headers === 'object' ? headers : {};
          var headerMarker = '${VBookWebSocketBroker.HEADER_PREFIX}' + Crypto.utf8ToBase64(JSON.stringify(headerObject));
          var headerQueued = false;
          var out = {};
          function queueHeaders() {
            if (!headerQueued) {
              nativeSocket.send(headerMarker);
              headerQueued = true;
            }
          }
          out.connect = function() {
            var connected = nativeSocket.connect.apply(nativeSocket, arguments);
            queueHeaders();
            return connected;
          };
          out.send = function(message) {
            queueHeaders();
            return nativeSocket.send(String(message == null ? '' : message));
          };
          out.message = function() {
            queueHeaders();
            return __vbookDecodeWsFrame(nativeSocket.message.apply(nativeSocket, arguments));
          };
          out.receive = out.message;
          out.messages = function() {
            queueHeaders();
            var raw = nativeSocket.messages.apply(nativeSocket, arguments) || [];
            var result = [];
            for (var i=0; i<Number(raw.length || 0); i++) result.push(__vbookDecodeWsFrame(raw[i]));
            return result;
          };
          out.close = function() { return nativeSocket.close.apply(nativeSocket, arguments); };
          out.isConnected = function() { return !!nativeSocket.connected; };
          Object.defineProperty(out, 'connected', {get:function(){return !!nativeSocket.connected;}});
          Object.defineProperty(out, 'closeCode', {get:function(){return nativeSocket.closeCode;}});
          Object.defineProperty(out, 'closeReason', {get:function(){return nativeSocket.closeReason;}});
          return out;
        };
    """.trimIndent()
}
