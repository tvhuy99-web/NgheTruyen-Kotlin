from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 match, got {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_count(path: Path, old: str, new: str, expected: int, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != expected:
        raise SystemExit(f"{label}: expected {expected} matches, got {count}")
    path.write_text(text.replace(old, new), encoding="utf-8")


# 1) Do not ask the Android platform for a Chromium runtime until the first actual execute().
rhino = Path("source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/RhinoVBookActionRuntime.kt")
replace_once(
    rhino,
    '''    private val selected: VBookActionRuntime = VBookActionRuntimeRegistry
        .platformRuntime(brokers, diagnostics)
        ?.let { primary -> PrimaryFallbackVBookActionRuntime(primary, fallback) }
        ?: fallback
''',
    '''    private val selected: VBookActionRuntime by lazy {
        VBookActionRuntimeRegistry
            .platformRuntime(brokers, diagnostics)
            ?.let { primary -> PrimaryFallbackVBookActionRuntime(primary, fallback) }
            ?: fallback
    }
''',
    "lazy platform runtime selection",
)

runtime_test = Path("source-vbook/src/test/kotlin/vn/nghetruyen/source/vbook/PrimaryFallbackVBookActionRuntimeTest.kt")
replace_once(
    runtime_test,
    '''    private fun request(input: JsonValue.Obj = JsonValue.Obj()) = SourceActionRequest(
''',
    '''    @Test
    fun platformRuntimeFactoryIsLazyAndInitializedOnlyOnce() {
        var factoryCalls = 0
        var primaryCalls = 0
        VBookActionRuntimeRegistry.install { _, _ ->
            factoryCalls += 1
            VBookActionRuntime { _, _, request ->
                primaryCalls += 1
                SourcePlatformResult.Success(SourceActionResponse(JsonValue.Str("primary"), request.traceId, 1))
            }
        }
        try {
            val runtime = RhinoVBookActionRuntime()
            assertEquals(0, factoryCalls)

            runtime.execute(manifest(), resources(), request())
            runtime.execute(manifest(), resources(), request())

            assertEquals(1, factoryCalls)
            assertEquals(2, primaryCalls)
        } finally {
            VBookActionRuntimeRegistry.clear()
        }
    }

    private fun request(input: JsonValue.Obj = JsonValue.Obj()) = SourceActionRequest(
''',
    "lazy runtime regression test",
)

# 2) Add small synchronized caches: a bounded LRU for per-source runtime data and an artifact cache
# that can reuse immutable active source instances across registry refreshes.
cache_file = Path("app/src/main/java/vn/nghetruyen/app/sourceplatform/VBookRuntimeCaches.kt")
if cache_file.exists():
    raise SystemExit("VBookRuntimeCaches.kt already exists")
cache_file.write_text('''package vn.nghetruyen.app.sourceplatform

import java.util.LinkedHashMap

internal class BoundedLruCache<K, V>(private val maxEntries: Int) {
    init {
        require(maxEntries > 0) { "CACHE_MAX_ENTRIES_MUST_BE_POSITIVE" }
    }

    private val entries = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean =
            size > maxEntries
    }

    @Synchronized
    operator fun get(key: K): V? = entries[key]

    @Synchronized
    operator fun set(key: K, value: V) {
        entries[key] = value
    }

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun containsKey(key: K): Boolean = entries.containsKey(key)
}

internal class ArtifactValueCache<V>(private val maxEntries: Int) {
    init {
        require(maxEntries > 0) { "CACHE_MAX_ENTRIES_MUST_BE_POSITIVE" }
    }

    private data class CachedValue<V>(val value: V?)

    private val entries = object : LinkedHashMap<String, CachedValue<V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedValue<V>>?): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun getOrLoad(key: String, cacheNull: Boolean = true, loader: () -> V?): V? {
        entries[key]?.let { return it.value }
        val loaded = loader()
        if (loaded != null || cacheNull) entries[key] = CachedValue(loaded)
        return loaded
    }

    @Synchronized
    fun retainKeys(keys: Set<String>) {
        entries.keys.retainAll(keys)
    }

    @Synchronized
    fun size(): Int = entries.size
}
''', encoding="utf-8")

cache_test = Path("app/src/test/java/vn/nghetruyen/app/sourceplatform/VBookRuntimeCachesTest.kt")
if cache_test.exists():
    raise SystemExit("VBookRuntimeCachesTest.kt already exists")
cache_test.write_text('''package vn.nghetruyen.app.sourceplatform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VBookRuntimeCachesTest {
    @Test
    fun boundedLruEvictsLeastRecentlyUsedEntry() {
        val cache = BoundedLruCache<String, Int>(2)
        cache["a"] = 1
        cache["b"] = 2
        assertEquals(1, cache["a"])
        cache["c"] = 3

        assertTrue(cache.containsKey("a"))
        assertFalse(cache.containsKey("b"))
        assertTrue(cache.containsKey("c"))
        assertEquals(2, cache.size())
    }

    @Test
    fun artifactCacheReusesImmutableValueAndPrunesInactiveKeys() {
        val cache = ArtifactValueCache<Any>(4)
        var loads = 0
        val first = cache.getOrLoad("artifact-a") { loads += 1; Any() }
        val second = cache.getOrLoad("artifact-a") { loads += 1; Any() }

        assertSame(first, second)
        assertEquals(1, loads)

        cache.retainKeys(setOf("artifact-b"))
        cache.getOrLoad("artifact-a") { loads += 1; Any() }
        assertEquals(2, loads)
    }

    @Test
    fun artifactCacheCanAvoidStickyNullForRecoverableBlobMiss() {
        val cache = ArtifactValueCache<String>(4)
        var loads = 0
        assertEquals(null, cache.getOrLoad("artifact-a", cacheNull = false) { loads += 1; null })
        assertEquals("ready", cache.getOrLoad("artifact-a", cacheNull = false) { loads += 1; "ready" })
        assertEquals(2, loads)
        assertEquals(1, cache.size())
    }
}
''', encoding="utf-8")

# 3) Bound caches inside a long-lived VBookStorySource before reusing instances.
story = Path("app/src/main/java/vn/nghetruyen/app/sourceplatform/VBookStorySource.kt")
replace_once(
    story,
    'import java.util.UUID\nimport java.util.concurrent.ConcurrentHashMap\n',
    'import java.util.UUID\n',
    "remove unbounded ConcurrentHashMap import",
)
replace_once(
    story,
    '''    private val configKey = artifact.identity.canonicalKey()
    private val pageCache = ConcurrentHashMap<PageKey, VBookCompatibilityRuntime.ExecutionResult>()
    private val chapterByUrl = ConcurrentHashMap<String, ChapterSummary>()
''',
    '''    private val configKey = artifact.identity.canonicalKey()
    private val pageCache = BoundedLruCache<PageKey, VBookCompatibilityRuntime.ExecutionResult>(MAX_PAGE_CACHE_ENTRIES)
    private val chapterByUrl = BoundedLruCache<String, ChapterSummary>(MAX_CHAPTER_CACHE_ENTRIES)
''',
    "bound VBookStorySource caches",
)
replace_once(
    story,
    '''        private const val MAX_COMMENT_CURSOR_CHARS = 64 * 1024
        private const val MAX_SUGGESTIONS = 30
''',
    '''        private const val MAX_COMMENT_CURSOR_CHARS = 64 * 1024
        private const val MAX_SUGGESTIONS = 30
        private const val MAX_PAGE_CACHE_ENTRIES = 128
        private const val MAX_CHAPTER_CACHE_ENTRIES = 4_096
''',
    "story cache limits",
)

# 4) Reuse immutable vBook story source instances across registry refreshes and cache parsed manifests.
platform = Path("app/src/main/java/vn/nghetruyen/app/sourceplatform/VBookSourcePlatform.kt")
replace_once(
    platform,
    'import vn.nghetruyen.source.vbook.VBookContentType\nimport vn.nghetruyen.source.vbook.VBookFeature\n',
    'import vn.nghetruyen.source.vbook.VBookContentType\nimport vn.nghetruyen.source.vbook.VBookExtensionManifest\nimport vn.nghetruyen.source.vbook.VBookFeature\n',
    "manifest cache import",
)
replace_once(
    platform,
    '''    private val coordinator = VBookUpdateCoordinator(
        validator = validator,
        registry = store,
        archive = store,
    )
''',
    '''    private val coordinator = VBookUpdateCoordinator(
        validator = validator,
        registry = store,
        archive = store,
    )
    private val storySourceCache = ArtifactValueCache<VBookStorySource>(32)
    private val manifestCache = ArtifactValueCache<VBookExtensionManifest>(64)
''',
    "vBook artifact caches",
)
replace_once(
    platform,
    '''    fun installedSources(): List<VBookInstalledSourceInfo> = store.installedArtifacts(SourceEcosystem.VBOOK).mapNotNull { current ->
        val bytes = store.originalBytes(current.artifactId) ?: return@mapNotNull null
        val manifest = runCatching { VBookManifestParser.parse(VBookPackageReader.read(bytes).pluginJson()) }.getOrNull()
            ?: return@mapNotNull null
''',
    '''    fun installedSources(): List<VBookInstalledSourceInfo> {
        val installed = store.installedArtifacts(SourceEcosystem.VBOOK)
        manifestCache.retainKeys(installed.mapTo(linkedSetOf()) { it.artifactId })
        return installed.mapNotNull { current ->
            val manifest = runCatching { manifestFor(current) }.getOrNull() ?: return@mapNotNull null
''',
    "cache installed manifests",
)
replace_once(
    platform,
    '''            loginAvailable = loginInfo(manifest, sourceId = VBookHostManifestFactory.stableSourceId(current.identity.canonicalKey())) != null,
        )
    }

    fun loginInfoBySourceId(sourceId: String): VBookLoginInfo? {
''',
    '''            loginAvailable = loginInfo(manifest, sourceId = VBookHostManifestFactory.stableSourceId(current.identity.canonicalKey())) != null,
        )
        }
    }

    fun loginInfoBySourceId(sourceId: String): VBookLoginInfo? {
''',
    "close installedSources block body",
)
replace_once(
    platform,
    '''    fun loginInfoBySourceId(sourceId: String): VBookLoginInfo? {
        val current = installedBySourceId(sourceId)
        val bytes = store.originalBytes(current.artifactId) ?: return null
        val manifest = runCatching { VBookManifestParser.parse(VBookPackageReader.read(bytes).pluginJson()) }.getOrNull() ?: return null
        return loginInfo(manifest, sourceId)
    }
''',
    '''    fun loginInfoBySourceId(sourceId: String): VBookLoginInfo? {
        val current = installedBySourceId(sourceId)
        val manifest = runCatching { manifestFor(current) }.getOrNull() ?: return null
        return loginInfo(manifest, sourceId)
    }
''',
    "reuse manifest for login metadata",
)
replace_once(
    platform,
    '''    fun activeStorySources(): List<StorySource> = activeArtifacts().mapNotNull { artifact ->
        val bytes = store.originalBytes(artifact.artifactId) ?: return@mapNotNull null
        runCatching {
            val pkg = VBookPackageReader.read(bytes)
            val plugin = VBookManifestParser.parse(pkg.pluginJson())
            if (plugin.metadata.type !in setOf(VBookContentType.NOVEL, VBookContentType.CHINESE_NOVEL)) {
                return@runCatching null
            }
            VBookStorySource(artifact, bytes, brokers, configReader, diagnostics, evidence)
        }.getOrNull()
    }
''',
    '''    fun activeStorySources(): List<StorySource> {
        val artifacts = activeArtifacts()
        storySourceCache.retainKeys(artifacts.mapTo(linkedSetOf()) { it.artifactId })
        return artifacts.mapNotNull { artifact ->
            storySourceCache.getOrLoad(artifact.artifactId, cacheNull = false) {
                val bytes = store.originalBytes(artifact.artifactId) ?: return@getOrLoad null
                runCatching {
                    VBookStorySource(artifact, bytes, brokers, configReader, diagnostics, evidence)
                }.getOrNull()
            }
        }
    }
''',
    "reuse immutable story sources and remove duplicate package parse",
)
replace_count(
    platform,
    '''        val bytes = store.originalBytes(current.artifactId) ?: error("VBOOK_INSTALLED_ARTIFACT_BYTES_MISSING")
        val manifest = VBookManifestParser.parse(VBookPackageReader.read(bytes).pluginJson())
''',
    '''        val manifest = manifestFor(current)
''',
    6,
    "reuse parsed manifest in config paths",
)
replace_once(
    platform,
    '''    fun originalPackageBytes(artifactId: String): ByteArray? = store.originalBytes(artifactId)

    private fun installedBySourceId(sourceId: String): SourceArtifactDescriptor =
''',
    '''    fun originalPackageBytes(artifactId: String): ByteArray? = store.originalBytes(artifactId)

    private fun manifestFor(current: SourceArtifactDescriptor): VBookExtensionManifest =
        manifestCache.getOrLoad(current.artifactId) {
            val bytes = store.originalBytes(current.artifactId) ?: error("VBOOK_INSTALLED_ARTIFACT_BYTES_MISSING")
            VBookManifestParser.parse(VBookPackageReader.read(bytes).pluginJson())
        } ?: error("VBOOK_INSTALLED_ARTIFACT_MANIFEST_MISSING")

    private fun installedBySourceId(sourceId: String): SourceArtifactDescriptor =
''',
    "manifest cache loader",
)

# Final structural guards.
assert "private val selected: VBookActionRuntime by lazy" in rhino.read_text(encoding="utf-8")
assert "platformRuntimeFactoryIsLazyAndInitializedOnlyOnce" in runtime_test.read_text(encoding="utf-8")
story_text = story.read_text(encoding="utf-8")
assert "ConcurrentHashMap" not in story_text
assert "MAX_PAGE_CACHE_ENTRIES = 128" in story_text
assert "MAX_CHAPTER_CACHE_ENTRIES = 4_096" in story_text
platform_text = platform.read_text(encoding="utf-8")
assert "storySourceCache.getOrLoad(artifact.artifactId, cacheNull = false)" in platform_text
assert "manifestCache.retainKeys" in platform_text
assert platform_text.count("val manifest = manifestFor(current)") == 6
assert "VBookRuntimeCaches.kt" in str(cache_file)
print("Round 5 vBook lazy runtime/cache patch applied with guards satisfied.")
