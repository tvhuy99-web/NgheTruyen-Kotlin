package vn.nghetruyen.app.ai.vietphrase

import android.content.Context

/** Small compatibility state used by the XPK-style VietPhrase settings dialog. */
object ReferenceVietPhraseRuntime {
    private const val PREFS = "reference_vietphrase_settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_FALLBACK = "fallback_hanviet"

    @Volatile
    var enabled: Boolean = true
        private set

    @Volatile
    var fallbackHanViet: Boolean = true
        private set

    @Volatile
    private var pendingImportKind: VietPhraseDictionaryKind? = null

    fun load(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        enabled = prefs.getBoolean(KEY_ENABLED, true)
        fallbackHanViet = prefs.getBoolean(KEY_FALLBACK, true)
    }

    fun setEnabled(context: Context, value: Boolean) {
        enabled = value
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, value).apply()
    }

    fun setFallbackHanViet(context: Context, value: Boolean) {
        fallbackHanViet = value
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_FALLBACK, value).apply()
    }

    fun prepareImport(kind: VietPhraseDictionaryKind?) {
        pendingImportKind = kind
    }

    fun consumeImportKind(): VietPhraseDictionaryKind? {
        val result = pendingImportKind
        pendingImportKind = null
        return result
    }
}
