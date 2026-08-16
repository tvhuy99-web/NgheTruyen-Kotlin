package vn.nghetruyen.app.sourceplatform

import android.content.Context


class VBookRepositorySubscriptionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun urls(): List<String> = preferences
        .getStringSet(KEY_URLS, emptySet())
        .orEmpty()
        .asSequence()
        .map(String::trim)
        .filter { it.startsWith("https://", ignoreCase = true) }
        .distinct()
        .sorted()
        .toList()

    @Synchronized
    fun add(url: String) {
        val normalized = normalize(url)
        val next = urls().toMutableSet().apply { add(normalized) }
        check(preferences.edit().putStringSet(KEY_URLS, next).commit()) {
            "VBOOK_REPOSITORY_SUBSCRIPTIONS_SAVE_FAILED"
        }
    }

    @Synchronized
    fun remove(url: String) {
        val normalized = normalize(url)
        val next = urls().toMutableSet().apply { remove(normalized) }
        check(preferences.edit().putStringSet(KEY_URLS, next).commit()) {
            "VBOOK_REPOSITORY_SUBSCRIPTIONS_SAVE_FAILED"
        }
    }

    private fun normalize(url: String): String {
        val normalized = url.trim()
        require(normalized.startsWith("https://", ignoreCase = true)) {
            "VBOOK_REPOSITORY_SUBSCRIPTION_HTTPS_REQUIRED"
        }
        return normalized
    }

    companion object {
        private const val PREFERENCES = "vbook_repository_subscriptions_v1"
        private const val KEY_URLS = "urls"
    }
}
