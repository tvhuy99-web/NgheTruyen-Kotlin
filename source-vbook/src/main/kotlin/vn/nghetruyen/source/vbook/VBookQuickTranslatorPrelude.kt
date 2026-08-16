package vn.nghetruyen.source.vbook

 
object VBookQuickTranslatorPrelude {
    fun build(): String = """
        var __vbookNativeQtTranslate = Qt.translate;
        Qt.translate = function(text, to, extras) {
          var originalExtras = extras && typeof extras === 'object' ? extras : {};
          var carrier = {}, keys = Object.keys(originalExtras);
          for (var i = 0; i < keys.length; i++) carrier[keys[i]] = originalExtras[keys[i]];
          carrier.instruction = '${VBookTranslationBrokerRouter.QUICK_TRANSLATOR_PREFIX}' + JSON.stringify(originalExtras);
          var result = __vbookNativeQtTranslate(String(text == null ? '' : text), String(to || 'vp'), carrier);
          return result;
        };
    """.trimIndent()
}
