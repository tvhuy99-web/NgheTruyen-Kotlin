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
          // The generic host currently exposes its legacy segments as a Java array. Reading that
          // value would violate the deny-all ClassShutter. Offset-compatible Quick Translator
          // segments remain explicitly PARTIAL, so hide the unsafe representation rather than
          // weakening the sandbox. translateText/provider and all extras still cross normally.
          result.segments = undefined;
          return result;
        };
    """.trimIndent()
}
