from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, found {count}")
    return text.replace(old, new, 1)

# 1) Persistent subscription store.
store_path = Path("app/src/main/java/vn/nghetruyen/app/sourceplatform/VBookRepositorySubscriptionStore.kt")
store_path.write_text(r'''package vn.nghetruyen.app.sourceplatform

import android.content.Context

/** Persists user-added vBook repository index URLs across process/app restarts. */
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
''')

# 2) Wire the store into the app container.
path = Path("app/src/main/java/vn/nghetruyen/app/AppContainer.kt")
text = path.read_text()
text = replace_once(
    text,
    "import vn.nghetruyen.app.sourceplatform.VBookRepositoryClient\n",
    "import vn.nghetruyen.app.sourceplatform.VBookRepositoryClient\nimport vn.nghetruyen.app.sourceplatform.VBookRepositorySubscriptionStore\n",
    "AppContainer import",
)
text = replace_once(
    text,
    '''    val vBookRepositoryClient: VBookRepositoryClient by lazy {
        VBookRepositoryClient(diagnostics = sourceDiagnostics.recorder, evidence = sourceDiagnostics.evidence)
    }
''',
    '''    val vBookRepositoryClient: VBookRepositoryClient by lazy {
        VBookRepositoryClient(diagnostics = sourceDiagnostics.recorder, evidence = sourceDiagnostics.evidence)
    }

    val vBookRepositorySubscriptionStore: VBookRepositorySubscriptionStore by lazy {
        VBookRepositorySubscriptionStore(appContext)
    }
''',
    "AppContainer store",
)
text = replace_once(
    text,
    '''            vBookRepositories = vBookRepositoryClient,
            onExternalSourcesChanged = { refreshSourceRegistry() },
''',
    '''            vBookRepositories = vBookRepositoryClient,
            vBookRepositorySubscriptions = vBookRepositorySubscriptionStore,
            onExternalSourcesChanged = { refreshSourceRegistry() },
''',
    "AppContainer manager wiring",
)
path.write_text(text)

# 3) Persist vBook repository URLs, render saved placeholders immediately, and restore snapshots.
path = Path("app/src/main/java/vn/nghetruyen/app/sourceplatform/UnifiedSourcePlatformManager.kt")
text = path.read_text()
text = replace_once(
    text,
    '''    private val vBookRepositories: VBookRepositoryClient,
    private val onExternalSourcesChanged: () -> Unit,
''',
    '''    private val vBookRepositories: VBookRepositoryClient,
    private val vBookRepositorySubscriptions: VBookRepositorySubscriptionStore,
    private val onExternalSourcesChanged: () -> Unit,
''',
    "Unified constructor",
)
text = replace_once(
    text,
    '''        addAll(vBookSnapshots.map { (uiId, snapshot) ->
            SourceRepositoryUiInfo(
                id = uiId,
                name = "vBook · ${snapshot.repositories.size} catalog",
                url = snapshot.indexUrl,
                generatedAtEpochMs = 0L,
                expiresAtEpochMs = 0L,
                packageCount = snapshot.items.size,
                signerKeyId = if (snapshot.complete) "vbook-index-hash" else "vbook-index-partial",
            )
        })
''',
    '''        addAll(vBookSnapshots.map { (uiId, snapshot) ->
            SourceRepositoryUiInfo(
                id = uiId,
                name = "vBook · ${snapshot.repositories.size} catalog",
                url = snapshot.indexUrl,
                generatedAtEpochMs = 0L,
                expiresAtEpochMs = 0L,
                packageCount = snapshot.items.size,
                signerKeyId = if (snapshot.complete) "vbook-index-hash" else "vbook-index-partial",
            )
        })
        vBookRepositorySubscriptions.urls().forEach { url ->
            val uiId = vBookIndexUiId(url)
            if (uiId !in vBookSnapshots) {
                add(SourceRepositoryUiInfo(
                    id = uiId,
                    name = "vBook · Đã lưu",
                    url = url,
                    generatedAtEpochMs = 0L,
                    expiresAtEpochMs = 0L,
                    packageCount = 0,
                    signerKeyId = "vbook-index-saved",
                ))
            }
        }
''',
    "Unified saved placeholders",
)
text = replace_once(
    text,
    '''            val uiId = vBookIndexUiId(snapshot.indexUrl)
            vBookSnapshots[uiId] = snapshot
            repositories().first { it.id == uiId }
''',
    '''            val uiId = vBookIndexUiId(snapshot.indexUrl)
            vBookSnapshots[uiId] = snapshot
            vBookRepositorySubscriptions.add(snapshot.indexUrl)
            repositories().first { it.id == uiId }
''',
    "Unified persist on refresh",
)
text = replace_once(
    text,
    '''    fun removeRepository(repositoryId: String): Result<Unit> {
        clearPendingCatalogInstall()
        if (vBookSnapshots.remove(repositoryId) != null) return Result.success(Unit)
        return legacy.removeRepository(repositoryId)
    }
''',
    '''    fun removeRepository(repositoryId: String): Result<Unit> {
        clearPendingCatalogInstall()
        val snapshot = vBookSnapshots.remove(repositoryId)
        val persistedUrl = snapshot?.indexUrl ?: vBookRepositorySubscriptions.urls()
            .firstOrNull { vBookIndexUiId(it) == repositoryId }
        if (persistedUrl != null) {
            return runCatching { vBookRepositorySubscriptions.remove(persistedUrl) }
        }
        return legacy.removeRepository(repositoryId)
    }

    /** Rehydrates saved vBook repositories. Failed/offline URLs stay subscribed and remain visible. */
    fun restorePersistedRepositories(): Int {
        clearPendingCatalogInstall()
        var restored = 0
        vBookRepositorySubscriptions.urls().forEach { url ->
            runCatching { vBookRepositories.snapshot(url, strict = false) }
                .onSuccess { snapshot ->
                    if (snapshot.repositories.isNotEmpty()) {
                        vBookSnapshots[vBookIndexUiId(snapshot.indexUrl)] = snapshot
                        if (!snapshot.indexUrl.equals(url, ignoreCase = false)) {
                            runCatching {
                                vBookRepositorySubscriptions.remove(url)
                                vBookRepositorySubscriptions.add(snapshot.indexUrl)
                            }
                        }
                        restored += 1
                    }
                }
        }
        return restored
    }
''',
    "Unified remove/restore",
)
path.write_text(text)

# 4) Rehydrate repository snapshots in IO when the ViewModel starts.
path = Path("app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt")
text = path.read_text()
text = replace_once(
    text,
    '''        refreshSourceSessions()
        refreshSourcePlatformState()
        refreshAiCredentialState()
''',
    '''        refreshSourceSessions()
        refreshSourcePlatformState()
        restorePersistedSourceRepositories()
        refreshAiCredentialState()
''',
    "AppViewModel init restore",
)
marker = '''    private fun observeSettings() {
'''
insert = '''    private fun restorePersistedSourceRepositories() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                container.sourcePlatformManager.restorePersistedRepositories()
            }
            refreshSourcePlatformState()
        }
    }

'''
text = replace_once(text, marker, insert + marker, "AppViewModel restore function")
path.write_text(text)

print("vBook repository persistence patch applied")
