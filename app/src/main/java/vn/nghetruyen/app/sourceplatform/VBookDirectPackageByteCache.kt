package vn.nghetruyen.app.sourceplatform

/**
 * Short-lived exact-byte handoff for a direct vBook plugin.zip URL.
 *
 * Classification already had to download the package to prove it is a valid vBook ZIP. Keeping
 * those exact bytes until prepare avoids a second request that could return a different package.
 * Entries are intentionally tiny in count, time-bounded, copied on both sides, and consumed once.
 */
internal class VBookDirectPackageByteCache(
    private val maxEntries: Int = 2,
    private val ttlMillis: Long = 15 * 60 * 1000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(
        val packageUrl: String,
        val sha256: String,
        val bytes: ByteArray,
        val storedAtEpochMs: Long,
    )

    data class Hit(
        val sha256: String,
        val bytes: ByteArray,
    )

    private val entries = LinkedHashMap<String, Entry>()

    @Synchronized
    fun put(installIdentity: String, packageUrl: String, sha256: String, bytes: ByteArray) {
        require(installIdentity.isNotBlank() && packageUrl.isNotBlank() && sha256.isNotBlank() && bytes.isNotEmpty()) {
            "VBOOK_DIRECT_PACKAGE_CACHE_INVALID"
        }
        purgeExpiredLocked()
        entries.remove(installIdentity)
        entries[installIdentity] = Entry(packageUrl, sha256, bytes.copyOf(), clock())
        while (entries.size > maxEntries.coerceAtLeast(1)) {
            entries.remove(entries.keys.first())
        }
    }

    /** Returns and removes the matching entry so one classification can feed only one prepare. */
    @Synchronized
    fun take(installIdentity: String, packageUrl: String): Hit? {
        purgeExpiredLocked()
        val entry = entries.remove(installIdentity) ?: return null
        if (entry.packageUrl != packageUrl) return null
        return Hit(entry.sha256, entry.bytes.copyOf())
    }

    @Synchronized
    fun removeUrl(packageUrl: String) {
        entries.entries.removeAll { it.value.packageUrl == packageUrl }
    }

    private fun purgeExpiredLocked() {
        val now = clock()
        entries.entries.removeAll { now - it.value.storedAtEpochMs > ttlMillis }
    }
}
