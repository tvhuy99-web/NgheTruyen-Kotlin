from pathlib import Path

ROOT = Path('.')


def replace_once(path: str, old: str, new: str) -> None:
    p = ROOT / path
    text = p.read_text(encoding='utf-8')
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{path}: expected exactly one match, got {count}')
    p.write_text(text.replace(old, new, 1), encoding='utf-8')


resolver = 'app/src/main/java/vn/nghetruyen/app/freesound/FreesoundAutoAudioResolver.kt'
importer = 'app/src/main/java/vn/nghetruyen/app/freesound/FreesoundImporter.kt'
worker = 'app/src/main/java/vn/nghetruyen/app/audio/SceneMusicAnalysisWorker.kt'
policy_test = 'app/src/test/java/vn/nghetruyen/app/audio/Mode3FastNormalizationPolicyTest.kt'
parallel_import_test = 'app/src/test/java/vn/nghetruyen/app/freesound/FreesoundParallelImportPolicyTest.kt'

# Resolver: add a dedicated bounded four-way import/download/normalization pool.
replace_once(
    resolver,
    '''internal object FreesoundParallelSearchPolicy {
    const val MAX_PARALLEL_SEARCHES = 4

    suspend fun <T, R> mapOrdered(
        values: List<T>,
        transform: suspend (T) -> R,
    ): List<R> = coroutineScope {
        val semaphore = Semaphore(MAX_PARALLEL_SEARCHES)
        values.map { value ->
            async { semaphore.withPermit { transform(value) } }
        }.awaitAll()
    }
}
''',
    '''internal object FreesoundParallelSearchPolicy {
    const val MAX_PARALLEL_SEARCHES = 4

    suspend fun <T, R> mapOrdered(
        values: List<T>,
        transform: suspend (T) -> R,
    ): List<R> = coroutineScope {
        val semaphore = Semaphore(MAX_PARALLEL_SEARCHES)
        values.map { value ->
            async { semaphore.withPermit { transform(value) } }
        }.awaitAll()
    }
}

internal object FreesoundParallelImportPolicy {
    const val MAX_PARALLEL_IMPORTS = 4

    suspend fun <T, R> mapOrdered(
        values: List<T>,
        transform: suspend (T) -> R,
    ): List<R> = coroutineScope {
        val semaphore = Semaphore(MAX_PARALLEL_IMPORTS)
        values.map { value ->
            async { semaphore.withPermit { transform(value) } }
        }.awaitAll()
    }
}
'''
)

replace_once(
    resolver,
    '''private data class FreesoundAutoPreparedNeed(
    val index: Int,
    val need: FreesoundAutoSearchNeed,
    val cachedTrack: SceneMusicTrackEntity? = null,
    val effectiveQuery: String = "",
    val strategy: String = "CACHE",
    val search: FreesoundAutoSearchOutcome? = null,
    val searchElapsedMs: Long = 0L,
)
''',
    '''private data class FreesoundAutoPreparedNeed(
    val index: Int,
    val need: FreesoundAutoSearchNeed,
    val cachedTrack: SceneMusicTrackEntity? = null,
    val effectiveQuery: String = "",
    val strategy: String = "CACHE",
    val search: FreesoundAutoSearchOutcome? = null,
    val searchElapsedMs: Long = 0L,
)

private data class FreesoundAutoImportOutcome(
    val index: Int,
    val result: Result<FreesoundImportResult>,
    val elapsedMs: Long,
)
'''
)

replace_once(
    resolver,
    '''        diagnostics += "RESOLVE_START requirements=${requirements.size} aggregated=${needs.size} parallelSearchLimit=${FreesoundParallelSearchPolicy.MAX_PARALLEL_SEARCHES}"
''',
    '''        diagnostics += "RESOLVE_START requirements=${requirements.size} aggregated=${needs.size} parallelSearchLimit=${FreesoundParallelSearchPolicy.MAX_PARALLEL_SEARCHES} parallelImportLimit=${FreesoundParallelImportPolicy.MAX_PARALLEL_IMPORTS}"
'''
)
replace_once(
    resolver,
    '''                "parallelSearchLimit" to FreesoundParallelSearchPolicy.MAX_PARALLEL_SEARCHES.toString(),
''',
    '''                "parallelSearchLimit" to FreesoundParallelSearchPolicy.MAX_PARALLEL_SEARCHES.toString(),
                "parallelImportLimit" to FreesoundParallelImportPolicy.MAX_PARALLEL_IMPORTS.toString(),
'''
)

# Insert the import pre-pass immediately before the old deterministic resolution loop.
marker = '''        val searchedByIndex = searched.associateBy(FreesoundAutoPreparedNeed::index)

        for (seed in prepared.sortedBy(FreesoundAutoPreparedNeed::index)) {
'''
insert = '''        val searchedByIndex = searched.associateBy(FreesoundAutoPreparedNeed::index)

        // Resolve known soundIds from the local managed library before any download. Invalidly
        // normalized files are intentionally NOT counted as reusable: importer will resume their
        // normalization without downloading the bytes again.
        val preexistingSoundTrackByIndex = searched.mapNotNull { seed ->
            val remote = seed.search?.sound ?: return@mapNotNull null
            val track = knownTracks.firstOrNull { candidate ->
                candidate.enabled &&
                    AudioAssetClassifier.classify(candidate) == seed.need.kind &&
                    FreesoundImporter.soundIdFromManagedUri(candidate.uri) == remote.id &&
                    FreesoundImporter.managedFileExists(appContext, candidate.uri)
            } ?: return@mapNotNull null
            seed.index to track
        }.toMap()
        val localReusableByIndex = preexistingSoundTrackByIndex.filterValues(FreesoundImporter::hasValidNormalization)

        // Download + normalization is one bounded unit of work. At most four such units are active.
        // FreesoundImporter additionally serializes identical soundIds, so two different queries that
        // select the same remote sound cannot download or normalize it twice.
        val importSeeds = searched.filter { seed ->
            seed.search?.sound != null && localReusableByIndex[seed.index] == null
        }
        val parallelImportStartedNanos = System.nanoTime()
        val parallelImports = FreesoundParallelImportPolicy.mapOrdered(importSeeds) { seed ->
            val remote = requireNotNull(seed.search?.sound)
            liveDiagnostic(
                traceId,
                "FREESOUND_IMPORT_START",
                attributes = baseAttributes + mapOf(
                    "index" to (seed.index + 1).toString(),
                    "kind" to seed.need.kind.name,
                    "soundId" to remote.id.toString(),
                    "durationSec" to "%.2f".format(java.util.Locale.US, remote.durationSeconds),
                    "previewAvailable" to (remote.preferredPreviewUrl != null).toString(),
                    "query" to seed.need.query.take(180),
                    "parallelImportLimit" to FreesoundParallelImportPolicy.MAX_PARALLEL_IMPORTS.toString(),
                ),
            )
            val importStartedNanos = System.nanoTime()
            val result = importer.importPreview(
                sound = remote,
                kind = seed.need.kind,
                normalizationTargetLufs = normalizationTarget(seed.need.kind),
            )
            FreesoundAutoImportOutcome(
                index = seed.index,
                result = result,
                elapsedMs = (System.nanoTime() - importStartedNanos) / 1_000_000L,
            )
        }
        val parallelImportWallMs = if (importSeeds.isEmpty()) 0L
        else (System.nanoTime() - parallelImportStartedNanos) / 1_000_000L
        val parallelImportsByIndex = parallelImports.associateBy(FreesoundAutoImportOutcome::index)
        importAttempts = importSeeds.size
        importElapsedTotalMs = parallelImports.sumOf(FreesoundAutoImportOutcome::elapsedMs)
        if (parallelImports.isNotEmpty()) {
            knownTracks = runCatching { existingTracksProvider() }.getOrDefault(knownTracks)
        }

        for (seed in prepared.sortedBy(FreesoundAutoPreparedNeed::index)) {
'''
replace_once(resolver, marker, insert)

# Replace the old inline import branch with consumption of the already-finished parallel outcome.
old_branch_start = '''                val existingSoundTrack = knownTracks.firstOrNull { track ->
                    track.enabled &&
                        AudioAssetClassifier.classify(track) == need.kind &&
                        FreesoundImporter.soundIdFromManagedUri(track.uri) == remote.id &&
                        FreesoundImporter.managedFileExists(appContext, track.uri)
                }
                if (existingSoundTrack != null && FreesoundImporter.hasValidNormalization(existingSoundTrack)) {
                    resolvedTrack = existingSoundTrack
'''
new_branch_start = '''                val existingSoundTrack = localReusableByIndex[seed.index]
                if (existingSoundTrack != null) {
                    resolvedTrack = existingSoundTrack
'''
replace_once(resolver, old_branch_start, new_branch_start)

old_import = '''                } else {
                    importAttempts += 1
                    val importStartedNanos = System.nanoTime()
                    diagnostics += "IMPORT_START kind=${need.kind.name} soundId=${remote.id} durationSec=${"%.2f".format(java.util.Locale.US, remote.durationSeconds)} previewAvailable=${remote.preferredPreviewUrl != null} query=${need.query.take(140)}"
                    liveDiagnostic(
                        traceId,
                        "FREESOUND_IMPORT_START",
                        attributes = baseAttributes + mapOf(
                            "kind" to need.kind.name,
                            "soundId" to remote.id.toString(),
                            "durationSec" to "%.2f".format(java.util.Locale.US, remote.durationSeconds),
                            "previewAvailable" to (remote.preferredPreviewUrl != null).toString(),
                            "query" to need.query.take(180),
                        ),
                    )
                    val import = importer.importPreview(
                        sound = remote,
                        kind = need.kind,
                        normalizationTargetLufs = normalizationTarget(need.kind),
                    )
                    val importElapsedMs = (System.nanoTime() - importStartedNanos) / 1_000_000L
                    importElapsedTotalMs += importElapsedMs
                    if (import.isSuccess) {
                        val result = import.getOrThrow()
                        knownTracks = runCatching { existingTracksProvider() }.getOrDefault(knownTracks)
                        resolvedTrack = knownTracks.firstOrNull { it.id == result.trackId && it.enabled }
                        if (existingSoundTrack == null) imported += result.trackId else normalizationResumes += 1
'''
new_import = '''                } else {
                    val importOutcome = requireNotNull(parallelImportsByIndex[seed.index])
                    val import = importOutcome.result
                    val importElapsedMs = importOutcome.elapsedMs
                    if (import.isSuccess) {
                        val result = import.getOrThrow()
                        resolvedTrack = knownTracks.firstOrNull { it.id == result.trackId && it.enabled }
                        if (preexistingSoundTrackByIndex[seed.index] == null) imported += result.trackId else normalizationResumes += 1
'''
replace_once(resolver, old_import, new_import)

# Add aggregate import-wall diagnostics.
replace_once(
    resolver,
    '''parallelSearchLimit=${FreesoundParallelSearchPolicy.MAX_PARALLEL_SEARCHES} cacheLookupMs=$cacheLookupMs networkSearchWallMs=$networkSearchWallMs importAttempts=$importAttempts''',
    '''parallelSearchLimit=${FreesoundParallelSearchPolicy.MAX_PARALLEL_SEARCHES} parallelImportLimit=${FreesoundParallelImportPolicy.MAX_PARALLEL_IMPORTS} cacheLookupMs=$cacheLookupMs networkSearchWallMs=$networkSearchWallMs importWallMs=$parallelImportWallMs importAttempts=$importAttempts'''
)
replace_once(
    resolver,
    '''                "parallelSearchLimit" to FreesoundParallelSearchPolicy.MAX_PARALLEL_SEARCHES.toString(),
                "cacheLookupMs" to cacheLookupMs.toString(),
                "networkSearchWallMs" to networkSearchWallMs.toString(),
                "importAttempts" to importAttempts.toString(),
''',
    '''                "parallelSearchLimit" to FreesoundParallelSearchPolicy.MAX_PARALLEL_SEARCHES.toString(),
                "parallelImportLimit" to FreesoundParallelImportPolicy.MAX_PARALLEL_IMPORTS.toString(),
                "cacheLookupMs" to cacheLookupMs.toString(),
                "networkSearchWallMs" to networkSearchWallMs.toString(),
                "importWallMs" to parallelImportWallMs.toString(),
                "importAttempts" to importAttempts.toString(),
'''
)

# Importer: replace one global import mutex with striped soundId locks. This allows independent files
# to run concurrently while preserving strict de-duplication for the same remote sound.
replace_once(
    importer,
    '''        importMutex.withLock {
''',
    '''        soundImportLock(sound.id).withLock {
'''
)
replace_once(
    importer,
    '''        private const val NORMALIZATION_TIMEOUT_MS = 10L * 60L * 1_000L
        private val importMutex = Mutex()
        private val managedSoundIdRegex = Regex(
''',
    '''        private const val NORMALIZATION_TIMEOUT_MS = 10L * 60L * 1_000L
        private const val STALE_PART_AGE_MS = 15L * 60L * 1_000L
        private const val SOUND_LOCK_STRIPES = 64
        private val soundImportLocks = List(SOUND_LOCK_STRIPES) { Mutex() }
        private val managedSoundIdRegex = Regex(
'''
)
replace_once(
    importer,
    '''        internal fun cleanupStalePartFiles(context: Context): Int {
            val root = File(context.applicationContext.filesDir, MANAGED_ROOT)
            if (!root.isDirectory) return 0
            var deleted = 0
            root.walkTopDown().forEach { file ->
                if (file.isFile && file.name.endsWith(".part", ignoreCase = true) && file.delete()) {
                    deleted += 1
                }
            }
            return deleted
        }
''',
    '''        internal fun cleanupStalePartFiles(context: Context): Int {
            val root = File(context.applicationContext.filesDir, MANAGED_ROOT)
            if (!root.isDirectory) return 0
            val staleBefore = System.currentTimeMillis() - STALE_PART_AGE_MS
            var deleted = 0
            root.walkTopDown().forEach { file ->
                if (
                    file.isFile &&
                    file.name.endsWith(".part", ignoreCase = true) &&
                    file.lastModified() <= staleBefore &&
                    file.delete()
                ) {
                    deleted += 1
                }
            }
            return deleted
        }

        private fun soundImportLock(soundId: Int): Mutex =
            soundImportLocks[(soundId and Int.MAX_VALUE) % SOUND_LOCK_STRIPES]
'''
)

# Normalization: Mode-3 streaming analysis may run four workers concurrently; manual/local full-file
# analysis retains its previous single-worker mutex so this change is isolated to Freesound Mode 3.
replace_once(
    worker,
    '''import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
''',
    '''import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
'''
)
old_analysis = '''        return try {
            analysisMutex.withLock {
                val analysis = if (fastFreesound) {
                    AndroidAudioLoudnessAnalyzer.analyze(
                        context = applicationContext,
                        uri = Uri.parse(track.uri),
                        maxDecodeDurationUs = fastAnalysisDurationUs(kind),
                    )
                } else {
                    val destination = requireNotNull(temp)
                    destination.parentFile?.mkdirs()
                    AndroidAudioTrackDecoder.decodeToWave(
                        context = applicationContext,
                        uri = Uri.parse(track.uri),
                        targetSampleRate = 44_100,
                        targetChannels = 2,
                        destination = destination,
                    )
                    PcmLoudnessEstimator.analyze(destination)
                }
                val normalization = PcmLoudnessEstimator.calculateNormalization(
                    analysis.loudnessLufs,
                    analysis.peakDbfs,
                    target,
                )
                persistNormalization(
                    trackId = trackId,
                    loudnessLufs = analysis.loudnessLufs,
                    peakDbfs = analysis.peakDbfs,
                    normalization = normalization,
                )
                Result.success(
                    workDataOf(
                        KEY_LOUDNESS to analysis.loudnessLufs,
                        KEY_PEAK to analysis.peakDbfs,
                        KEY_GAIN_DB to normalization.gainDb,
                        KEY_REUSED_MEASUREMENT to false,
                    ),
                )
            }
'''
new_analysis = '''        return try {
            val analyzeAndPersist: suspend () -> Result = {
                val analysis = if (fastFreesound) {
                    AndroidAudioLoudnessAnalyzer.analyze(
                        context = applicationContext,
                        uri = Uri.parse(track.uri),
                        maxDecodeDurationUs = fastAnalysisDurationUs(kind),
                    )
                } else {
                    val destination = requireNotNull(temp)
                    destination.parentFile?.mkdirs()
                    AndroidAudioTrackDecoder.decodeToWave(
                        context = applicationContext,
                        uri = Uri.parse(track.uri),
                        targetSampleRate = 44_100,
                        targetChannels = 2,
                        destination = destination,
                    )
                    PcmLoudnessEstimator.analyze(destination)
                }
                val normalization = PcmLoudnessEstimator.calculateNormalization(
                    analysis.loudnessLufs,
                    analysis.peakDbfs,
                    target,
                )
                persistNormalization(
                    trackId = trackId,
                    loudnessLufs = analysis.loudnessLufs,
                    peakDbfs = analysis.peakDbfs,
                    normalization = normalization,
                )
                Result.success(
                    workDataOf(
                        KEY_LOUDNESS to analysis.loudnessLufs,
                        KEY_PEAK to analysis.peakDbfs,
                        KEY_GAIN_DB to normalization.gainDb,
                        KEY_REUSED_MEASUREMENT to false,
                    ),
                )
            }
            if (fastFreesound) {
                freesoundAnalysisSemaphore.withPermit { analyzeAndPersist() }
            } else {
                analysisMutex.withLock { analyzeAndPersist() }
            }
'''
replace_once(worker, old_analysis, new_analysis)
replace_once(
    worker,
    '''        private const val RETRY_BACKOFF_SECONDS = 10L
        private val analysisMutex = Mutex()

        internal fun fastAnalysisDurationUs(kind: AudioAssetKind): Long = when (kind) {
''',
    '''        private const val RETRY_BACKOFF_SECONDS = 10L
        internal const val MAX_PARALLEL_FREESOUND_ANALYSES = 4
        private val analysisMutex = Mutex()
        private val freesoundAnalysisSemaphore = Semaphore(MAX_PARALLEL_FREESOUND_ANALYSES)

        internal fun fastAnalysisDurationUs(kind: AudioAssetKind): Long = when (kind) {
'''
)

# Extend the normalization policy regression test with the hard concurrency cap.
replace_once(
    policy_test,
    '''        assertTrue(SceneMusicAnalysisWorker.fastAnalysisDurationUs(AudioAssetKind.AMBIENCE) < 60_000_000L)
''',
    '''        assertTrue(SceneMusicAnalysisWorker.fastAnalysisDurationUs(AudioAssetKind.AMBIENCE) < 60_000_000L)
        assertEquals(4, SceneMusicAnalysisWorker.MAX_PARALLEL_FREESOUND_ANALYSES)
'''
)

# Add a pure JVM concurrency test for the import/download pool.
Path(parallel_import_test).write_text('''package vn.nghetruyen.app.freesound

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FreesoundParallelImportPolicyTest {
    @Test
    fun downloadsAndNormalizationRunConcurrentlyWithHardLimitFourAndKeepOrder() = runBlocking {
        val active = AtomicInteger(0)
        val maximum = AtomicInteger(0)
        val values = (0 until 12).toList()

        val output = FreesoundParallelImportPolicy.mapOrdered(values) { value ->
            val current = active.incrementAndGet()
            maximum.updateAndGet { previous -> maxOf(previous, current) }
            try {
                delay(35)
                value * 100
            } finally {
                active.decrementAndGet()
            }
        }

        assertEquals(values.map { it * 100 }, output)
        assertEquals(4, FreesoundParallelImportPolicy.MAX_PARALLEL_IMPORTS)
        assertTrue("expected actual overlap", maximum.get() > 1)
        assertTrue("must never exceed four imports", maximum.get() <= 4)
    }
}
''', encoding='utf-8')

print('Mode 3 V15 four-way search + download + normalization patch applied.')
