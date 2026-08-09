package vn.nghetruyen.source.vbook

/** JavaScript injected by [VBookCompatibilityRuntime] around the legacy Qt host bridge. */
object VBookQuickTranslatorPrelude {
    fun build(): String = """
        var __vbookNativeQtTranslate = Qt.translate;
        Qt.translate = function(text, to, extras) {
          var originalExtras = extras && typeof extras === 'object' ? extras : {};
          var carrier = {}, keys = Object.keys(originalExtras);
          for (var i = 0; i < keys.length; i++) carrier[keys[i]] = originalExtras[keys[i]];
          carrier.instruction = '${VBookTranslationBrokerRouter.QUICK_TRANSLATOR_PREFIX}' + JSON.stringify(originalExtras);
          var result = __vbookNativeQtTranslate(String(text == null ? '' : text), String(to || 'vp'), carrier);
          if (!result || typeof result !== 'object') return result;
          var segments = result.segments;
          if (!segments || Number(segments.length || 0) === 0) {
            result.segments = undefined;
            return result;
          }
          var normalized = [];
          for (var si = 0; si < segments.length; si++) {
            var item = segments[si];
            if (typeof item === 'string') {
              try { item = JSON.parse(item); } catch (ignored) {}
            }
            normalized.push(item);
          }
          result.segments = normalized;
          return result;
        };
    """.trimIndent()
}
