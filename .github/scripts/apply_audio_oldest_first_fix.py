from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, got {count}: {old[:100]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


# 1) DAO ordering is stable oldest-first for equal/manual order slots, and exposes a
# transaction-safe append position for all import paths (manual + Freesound).
replace_once(
    "app/src/main/java/vn/nghetruyen/app/data/local/AppDatabase.kt",
    '''    @Insert(onConflict = OnConflictStrategy.REPLACE)\n    suspend fun upsertAll(items: List<SceneMusicTrackEntity>)\n\n    @Query("SELECT * FROM scene_music_tracks ORDER BY orderIndex ASC, title COLLATE NOCASE")\n    suspend fun listAll(): List<SceneMusicTrackEntity>\n\n    @Query("SELECT * FROM scene_music_tracks ORDER BY orderIndex ASC, title COLLATE NOCASE")\n    fun observeAll(): Flow<List<SceneMusicTrackEntity>>\n\n    @Query("SELECT * FROM scene_music_tracks WHERE enabled = 1 ORDER BY title COLLATE NOCASE")\n    suspend fun listEnabled(): List<SceneMusicTrackEntity>\n''',
    '''    @Insert(onConflict = OnConflictStrategy.REPLACE)\n    suspend fun upsertAll(items: List<SceneMusicTrackEntity>)\n\n    @Query("SELECT COALESCE(MAX(orderIndex), -1) FROM scene_music_tracks")\n    suspend fun maxOrderIndex(): Int\n\n    @Query("SELECT * FROM scene_music_tracks ORDER BY orderIndex ASC, updatedAt ASC, id ASC")\n    suspend fun listAll(): List<SceneMusicTrackEntity>\n\n    @Query("SELECT * FROM scene_music_tracks ORDER BY orderIndex ASC, updatedAt ASC, id ASC")\n    fun observeAll(): Flow<List<SceneMusicTrackEntity>>\n\n    @Query("SELECT * FROM scene_music_tracks WHERE enabled = 1 ORDER BY orderIndex ASC, updatedAt ASC, id ASC")\n    suspend fun listEnabled(): List<SceneMusicTrackEntity>\n''',
)

# 2) Every newly-created asset gets the current global tail index inside the same
# Room transaction. Parallel Freesound imports therefore cannot all receive index 0.
# Re-saving an existing URI preserves its original display slot and analysis metadata.
replace_once(
    "app/src/main/java/vn/nghetruyen/app/data/repository/LibraryRepository.kt",
    '''    suspend fun saveSceneMusicTrack(title: String, uri: String, tagsCsv: String): Result<String> = runCatching {\n        require(uri.isNotBlank()) { "Thiếu URI tệp nhạc." }\n        val id = UUID.nameUUIDFromBytes(uri.toByteArray()).toString()\n        db.sceneMusicTrackDao().upsert(\n            SceneMusicTrackEntity(\n                id = id,\n                title = title.trim().ifBlank { "Nhạc cảnh" }.take(120),\n                uri = uri.trim().take(2000),\n                tagsCsv = tagsCsv.trim().take(500),\n                volume = 1.0f,\n                enabled = true,\n                updatedAt = System.currentTimeMillis(),\n            ),\n        )\n        id\n    }\n''',
    '''    suspend fun saveSceneMusicTrack(title: String, uri: String, tagsCsv: String): Result<String> = runCatching {\n        require(uri.isNotBlank()) { "Thiếu URI tệp nhạc." }\n        val cleanUri = uri.trim().take(2000)\n        val cleanTitle = title.trim().ifBlank { "Nhạc cảnh" }.take(120)\n        val cleanTags = tagsCsv.trim().take(500)\n        val id = UUID.nameUUIDFromBytes(uri.toByteArray()).toString()\n        db.withTransaction {\n            val dao = db.sceneMusicTrackDao()\n            val now = System.currentTimeMillis()\n            val existing = dao.get(id)\n            val saved = if (existing != null) {\n                existing.copy(\n                    title = cleanTitle,\n                    uri = cleanUri,\n                    tagsCsv = cleanTags,\n                    updatedAt = now,\n                )\n            } else {\n                val nextOrderIndex = (dao.maxOrderIndex().toLong() + 1L)\n                    .coerceAtMost(Int.MAX_VALUE.toLong())\n                    .toInt()\n                SceneMusicTrackEntity(\n                    id = id,\n                    title = cleanTitle,\n                    uri = cleanUri,\n                    tagsCsv = cleanTags,\n                    volume = 1.0f,\n                    enabled = true,\n                    orderIndex = nextOrderIndex,\n                    updatedAt = now,\n                )\n            }\n            dao.upsert(saved)\n        }\n        id\n    }\n''',
)

# 3) All three manager dialogs share this one ordering function. Existing legacy rows
# that still share orderIndex=0 use updatedAt rather than alphabetic title, so old rows
# stay above newer rows. Freshly observed rows are appended in the same stable order.
replace_once(
    "app/src/main/java/vn/nghetruyen/app/ui/components/UnifiedAudioAssetManagerDialog.kt",
    '''/** Canonical asset-library dialog shared by MUSIC, AMBIENCE and SFX. */\n@Composable\nfun UnifiedAudioAssetManagerDialog(\n''',
    '''/** Canonical asset-library dialog shared by MUSIC, AMBIENCE and SFX. */\ninternal fun audioAssetRowsOldestFirst(\n    tracks: List<SceneMusicTrackEntity>,\n): List<SceneMusicTrackEntity> = tracks.sortedWith(\n    compareBy<SceneMusicTrackEntity> { it.orderIndex }\n        .thenBy { it.updatedAt }\n        .thenBy { it.id },\n)\n\n@Composable\nfun UnifiedAudioAssetManagerDialog(\n''',
)

replace_once(
    "app/src/main/java/vn/nghetruyen/app/ui/components/UnifiedAudioAssetManagerDialog.kt",
    '''    val initialRows = remember(kind) {\n        tracks.sortedWith(compareBy<SceneMusicTrackEntity> { it.orderIndex }.thenBy { it.title.lowercase() })\n            .mapIndexed { index, row -> row.copy(orderIndex = index) }\n    }\n''',
    '''    val initialRows = remember(kind) {\n        audioAssetRowsOldestFirst(tracks)\n            .mapIndexed { index, row -> row.copy(orderIndex = index) }\n    }\n''',
)

replace_once(
    "app/src/main/java/vn/nghetruyen/app/ui/components/UnifiedAudioAssetManagerDialog.kt",
    '''        val added = tracks.filter {\n            it.id !in baselineIds && it.id !in draftIds && it.id !in movedIds\n        }\n        if (added.isNotEmpty()) {\n            draft = (draft + added).take(500).mapIndexed { index, row -> row.copy(orderIndex = index) }\n        }\n''',
    '''        val added = audioAssetRowsOldestFirst(\n            tracks.filter {\n                it.id !in baselineIds && it.id !in draftIds && it.id !in movedIds\n            },\n        )\n        if (added.isNotEmpty()) {\n            draft = (draft + added).take(500).mapIndexed { index, row -> row.copy(orderIndex = index) }\n        }\n''',
)

# 4) Moving an asset to another kind is an addition to the destination library: place
# it after every existing asset, rather than deriving an index from count (which can
# collide with gaps/legacy indices).
replace_once(
    "app/src/main/java/vn/nghetruyen/app/ui/components/UnifiedAudioAssetManagerDialog.kt",
    '''                                    val destinationCounts = AudioAssetKind.entries.associateWith { destination ->\n                                        existing.count {\n                                            it.id !in movedTracks.keys && AudioAssetClassifier.classify(it) == destination\n                                        }\n                                    }.toMutableMap()\n                                    val movedRows = movedTracks.values.map { row ->\n                                        val destination = AudioAssetClassifier.classify(row)\n                                        val order = destinationCounts.getValue(destination)\n                                        destinationCounts[destination] = order + 1\n                                        row.copy(orderIndex = order, updatedAt = now)\n                                    }\n''',
    '''                                    var nextMovedOrder = (existing.asSequence()\n                                        .filterNot { it.id in movedTracks.keys }\n                                        .map { it.orderIndex }\n                                        .maxOrNull() ?: -1) + 1\n                                    val movedRows = movedTracks.values.map { row ->\n                                        row.copy(orderIndex = nextMovedOrder++, updatedAt = now)\n                                    }\n''',
)

# Regression tests for both legacy equal-index rows and explicit append indices.
test = Path("app/src/test/java/vn/nghetruyen/app/ui/components/UnifiedAudioAssetManagerOrderingTest.kt")
test.parent.mkdir(parents=True, exist_ok=True)
test.write_text('''package vn.nghetruyen.app.ui.components\n\nimport org.junit.Assert.assertEquals\nimport org.junit.Test\nimport vn.nghetruyen.app.data.local.SceneMusicTrackEntity\n\nclass UnifiedAudioAssetManagerOrderingTest {\n    @Test\n    fun legacyEqualOrderIndexUsesOldestTimestampNotAlphabeticalTitle() {\n        val older = track(id = "older", title = "Z old", orderIndex = 0, updatedAt = 100L)\n        val newer = track(id = "newer", title = "A new", orderIndex = 0, updatedAt = 200L)\n\n        assertEquals(listOf("older", "newer"), audioAssetRowsOldestFirst(listOf(newer, older)).map { it.id })\n    }\n\n    @Test\n    fun appendedAssetWithHigherOrderIndexStaysAtBottom() {\n        val oldest = track(id = "one", title = "One", orderIndex = 0, updatedAt = 300L)\n        val middle = track(id = "two", title = "Two", orderIndex = 1, updatedAt = 100L)\n        val newest = track(id = "three", title = "Three", orderIndex = 2, updatedAt = 50L)\n\n        assertEquals(\n            listOf("one", "two", "three"),\n            audioAssetRowsOldestFirst(listOf(newest, middle, oldest)).map { it.id },\n        )\n    }\n\n    @Test\n    fun equalTimestampHasStableIdTieBreak() {\n        val b = track(id = "b", title = "B", orderIndex = 0, updatedAt = 100L)\n        val a = track(id = "a", title = "A", orderIndex = 0, updatedAt = 100L)\n\n        assertEquals(listOf("a", "b"), audioAssetRowsOldestFirst(listOf(b, a)).map { it.id })\n    }\n\n    private fun track(\n        id: String,\n        title: String,\n        orderIndex: Int,\n        updatedAt: Long,\n    ) = SceneMusicTrackEntity(\n        id = id,\n        title = title,\n        uri = "file:///$id.mp3",\n        tagsCsv = "type:music",\n        volume = 1f,\n        enabled = true,\n        orderIndex = orderIndex,\n        updatedAt = updatedAt,\n    )\n}\n''', encoding="utf-8")

print("AUDIO_OLDEST_FIRST_PATCH_APPLIED")
