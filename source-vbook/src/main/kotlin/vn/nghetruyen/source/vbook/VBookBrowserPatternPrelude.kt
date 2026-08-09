package vn.nghetruyen.source.vbook

/** Patches current-browser request URL matching after the base browser shim has been installed. */
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
    """.trimIndent()
}
