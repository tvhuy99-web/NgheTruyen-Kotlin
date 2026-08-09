#!/usr/bin/env python3
from pathlib import Path


def rep(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"missing marker: {label}")
    return text.replace(old, new, 1)


# ---------- Room schema: persist XPK normalization measurement + fixed gain ----------
p = Path("app/src/main/java/vn/nghetruyen/app/data/local/AppDatabase.kt")
s = p.read_text()
old_entity = '''    @ColumnInfo(defaultValue = "-18.0") val loudnessLufsEstimate: Float = -18.0f,
    @ColumnInfo(defaultValue = "0") val playCount: Int = 0,
'''
new_entity = '''    @ColumnInfo(defaultValue = "-18.0") val loudnessLufsEstimate: Float = -18.0f,
    @ColumnInfo(defaultValue = "0.0") val peakDbfs: Float = 0f,
    @ColumnInfo(defaultValue = "-24.0") val normalizationTargetLufs: Float = -24f,
    @ColumnInfo(defaultValue = "0.0") val normalizationGainDb: Float = 0f,
    @ColumnInfo(defaultValue = "0") val normalizationPeakLimited: Boolean = false,
    @ColumnInfo(defaultValue = "0") val normalizationVersion: Int = 0,
    @ColumnInfo(defaultValue = "''") val normalizationError: String = "",
    @ColumnInfo(defaultValue = "0") val playCount: Int = 0,
'''
s = rep(s, old_entity, new_entity, "scene music normalization fields")
s = rep(s, "    version = 22,\n", "    version = 23,\n", "database version")
migration_anchor = '''        fun create(context: Context): AppDatabase = Room.databaseBuilder(
'''
migration = '''        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scene_music_tracks ADD COLUMN peakDbfs REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE scene_music_tracks ADD COLUMN normalizationTargetLufs REAL NOT NULL DEFAULT -24.0")
                db.execSQL("ALTER TABLE scene_music_tracks ADD COLUMN normalizationGainDb REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE scene_music_tracks ADD COLUMN normalizationPeakLimited INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE scene_music_tracks ADD COLUMN normalizationVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE scene_music_tracks ADD COLUMN normalizationError TEXT NOT NULL DEFAULT ''")
            }
        }

'''
s = rep(s, migration_anchor, migration + migration_anchor, "migration 22 23")
s = rep(s, "            MIGRATION_21_22,\n", "            MIGRATION_21_22,\n            MIGRATION_22_23,\n", "register migration")
p.write_text(s)

# ---------- Repository: description is freeform; normalization is a first-class record ----------
p = Path("app/src/main/java/vn/nghetruyen/app/data/repository/LibraryRepository.kt")
s = p.read_text()
old_metadata = '''                tagsCsv = tagsCsv.split(',')
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .distinctBy { it.lowercase(Locale.ROOT) }
                    .take(20)
                    .joinToString(",")
                    .take(500),
'''
s = rep(s, old_metadata, '''                // Legacy column name; XPK stores one freeform AI description, not CSV tags.
                tagsCsv = tagsCsv.trim().take(300),
''', "freeform music description persistence")
old_loudness = '''    suspend fun updateSceneMusicLoudness(id: String, loudnessLufsEstimate: Float) {
        val current = db.sceneMusicTrackDao().get(id) ?: return
        db.sceneMusicTrackDao().upsert(
            current.copy(
                loudnessLufsEstimate = loudnessLufsEstimate.coerceIn(-70f, 0f),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }
'''
new_loudness = '''    suspend fun updateSceneMusicNormalization(
        id: String,
        loudnessLufs: Float,
        peakDbfs: Float,
        targetLufs: Float,
        gainDb: Float,
        peakLimited: Boolean,
        version: Int,
    ) {
        val current = db.sceneMusicTrackDao().get(id) ?: return
        db.sceneMusicTrackDao().upsert(
            current.copy(
                loudnessLufsEstimate = loudnessLufs.coerceIn(-120f, 12f),
                peakDbfs = peakDbfs.coerceIn(-120f, 12f),
                normalizationTargetLufs = targetLufs.coerceIn(-36f, -18f),
                normalizationGainDb = gainDb.coerceIn(-36f, 12f),
                normalizationPeakLimited = peakLimited,
                normalizationVersion = version.coerceAtLeast(0),
                normalizationError = "",
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun markSceneMusicNormalizationError(id: String, error: String) {
        val current = db.sceneMusicTrackDao().get(id) ?: return
        db.sceneMusicTrackDao().upsert(
            current.copy(
                loudnessLufsEstimate = -70f,
                peakDbfs = 0f,
                normalizationTargetLufs = -24f,
                normalizationGainDb = 0f,
                normalizationPeakLimited = false,
                normalizationVersion = 0,
                normalizationError = error.trim().take(300),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }
'''
s = rep(s, old_loudness, new_loudness, "normalization repository methods")
p.write_text(s)

# ---------- Playback service: use stored fixed gain and expose preview pause/restore ----------
p = Path("app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt")
s = p.read_text()
s = s.replace("    private var backgroundMusicDuckFactor: Float = 0.25f\n", "    private var backgroundMusicDuckFactor: Float = 0.63095734f\n", 1)
s = s.replace("    private var sceneMusicPlaybackMode = SceneMusicPlaybackMode.SMART_AVOID_REPEAT\n", "    private var sceneMusicPlaybackMode = SceneMusicPlaybackMode.SEQUENTIAL\n", 1)
s = s.replace("    private var sceneMusicTargetLufs = -18.0f\n", "    private var sceneMusicTargetLufs = -24.0f\n", 1)
preview_state_anchor = "    private var activeSceneTrackId: String? = null\n"
preview_state = '''    private var activeSceneTrackId: String? = null
    private var musicPreviewActive = false
    private var musicPreviewPlainWasPlaying = false
    private var musicPreviewSceneWasActive = false
'''
s = rep(s, preview_state_anchor, preview_state, "music preview service state")
s = rep(s, "            ACTION_REFRESH -> refreshVoiceAndNotification()\n", '''            ACTION_REFRESH -> refreshVoiceAndNotification()
            ACTION_MUSIC_PREVIEW_BEGIN -> beginMusicPreview()
            ACTION_MUSIC_PREVIEW_END -> endMusicPreview()
''', "preview actions dispatch")
old_gain = '''        val loudnessGain = PcmLoudnessEstimator.normalizationGain(track.loudnessLufsEstimate, sceneMusicTargetLufs)
        sceneMusicController.transition(
            trackId = track.id,
            uri = track.uri,
            volume = (track.volume * cue.volume.coerceIn(0f, 1f) * loudnessGain).coerceIn(0f, 0.6f),
'''
new_gain = '''        val normalizationGain = if (
            PcmLoudnessEstimator.isReady(
                version = track.normalizationVersion,
                error = track.normalizationError,
                loudnessLufs = track.loudnessLufsEstimate,
                targetLufs = sceneMusicTargetLufs,
                storedTargetLufs = track.normalizationTargetLufs,
                gainDb = track.normalizationGainDb,
            )
        ) {
            PcmLoudnessEstimator.gainDbToLinear(track.normalizationGainDb)
        } else {
            1f
        }
        sceneMusicController.transition(
            trackId = track.id,
            uri = track.uri,
            volume = (track.volume * cue.volume.coerceIn(0f, 1f) * normalizationGain).coerceIn(0f, 0.6f),
'''
s = rep(s, old_gain, new_gain, "stored scene normalization gain")
configure_anchor = "    private fun configureBackgroundMusic(uri: String?, enabled: Boolean, volume: Float, duckFactor: Float) {\n"
preview_methods = '''    private fun beginMusicPreview() {
        if (musicPreviewActive) return
        musicPreviewActive = true
        musicPreviewPlainWasPlaying = backgroundPlayer?.runCatching { isPlaying }?.getOrDefault(false) == true
        musicPreviewSceneWasActive = sceneMusicController.activeTrackId != null
        if (musicPreviewPlainWasPlaying) backgroundPlayer?.runCatching { pause() }
        if (musicPreviewSceneWasActive) sceneMusicController.pause()
    }

    private fun endMusicPreview() {
        if (!musicPreviewActive) return
        val restorePlain = musicPreviewPlainWasPlaying
        val restoreScene = musicPreviewSceneWasActive
        musicPreviewActive = false
        musicPreviewPlainWasPlaying = false
        musicPreviewSceneWasActive = false
        val speaking = PlaybackQueueStore.state.value.isPlaying
        if (restoreScene && sceneMusicController.activeTrackId != null) {
            sceneMusicController.setSpeaking(speaking)
            sceneMusicController.resume()
        } else if (restorePlain && backgroundPlayer != null) {
            updateBackgroundMusic(ducked = speaking)
        }
    }

'''
s = rep(s, configure_anchor, preview_methods + configure_anchor, "preview service methods")
s = rep(s, "        const val ACTION_REFRESH = \"vn.nghetruyen.action.REFRESH\"\n", '''        const val ACTION_REFRESH = "vn.nghetruyen.action.REFRESH"
        const val ACTION_MUSIC_PREVIEW_BEGIN = "vn.nghetruyen.action.MUSIC_PREVIEW_BEGIN"
        const val ACTION_MUSIC_PREVIEW_END = "vn.nghetruyen.action.MUSIC_PREVIEW_END"
''', "preview action constants")
# Ensure stop/destroy cannot later restore stale pre-preview state.
s = s.replace("        pendingPreviewConfig = null\n        resumeAfterTransientFocusLoss = false\n", "        pendingPreviewConfig = null\n        musicPreviewActive = false\n        musicPreviewPlainWasPlaying = false\n        musicPreviewSceneWasActive = false\n        resumeAfterTransientFocusLoss = false\n")
p.write_text(s)

# ---------- Reader: normalized 15-second preview + save triggers gain recalculation ----------
p = Path("app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt")
s = p.read_text()
s = rep(s, "import vn.nghetruyen.app.audio.SceneMusicAnalysisWorker\n", "import vn.nghetruyen.app.audio.PcmLoudnessEstimator\nimport vn.nghetruyen.app.audio.SceneMusicAnalysisWorker\n", "reader estimator import")
# Saving a new target recalculates gains. Existing measurements are reused by the worker.
s = rep(s, "                    settings.setBackgroundMusicReleaseMillis(musicReleaseMs)\n                    ReaderPlaybackService.command(context, ReaderPlaybackService.ACTION_REFRESH)\n", '''                    settings.setBackgroundMusicReleaseMillis(musicReleaseMs)
                    state.sceneMusicTracks.forEach { SceneMusicAnalysisWorker.enqueue(context, it.id) }
                    ReaderPlaybackService.command(context, ReaderPlaybackService.ACTION_REFRESH)
''', "recalculate gains after music save")
s = s.replace(
    "        val normalizedCount = musicLibraryDraft.count { kotlin.math.abs(it.loudnessLufsEstimate + 18f) > 0.05f }\n",
    "        val normalizedCount = musicLibraryDraft.count { it.normalizationVersion >= PcmLoudnessEstimator.VERSION && it.normalizationError.isBlank() }\n",
    1,
)
old_stop = '''        fun stopPreview() {
            runCatching { musicPreviewPlayer?.stop() }
            runCatching { musicPreviewPlayer?.release() }
            musicPreviewPlayer = null
        }
'''
new_stop = '''        fun stopPreview() {
            runCatching { musicPreviewPlayer?.stop() }
            runCatching { musicPreviewPlayer?.release() }
            musicPreviewPlayer = null
            ReaderPlaybackService.command(context, ReaderPlaybackService.ACTION_MUSIC_PREVIEW_END)
        }
'''
s = rep(s, old_stop, new_stop, "library stop preview restore")
old_preview = '''                    ReaderMenuButton("NGHE THỬ") {
                        runCatching { musicPreviewPlayer?.stop() }
                        runCatching { musicPreviewPlayer?.release() }
                        musicPreviewPlayer = runCatching { MediaPlayer.create(context, Uri.parse(track.uri)) }.getOrNull()?.also { player ->
                            player.setOnCompletionListener { completed ->
                                runCatching { completed.release() }
                                if (musicPreviewPlayer === completed) musicPreviewPlayer = null
                            }
                            player.start()
                        }
                        if (musicPreviewPlayer == null) onMessage("Không nghe thử được bài nhạc này.")
                        selectedMusicTrackId = null
                    }
'''
new_preview = '''                    ReaderMenuButton("NGHE THỬ") {
                        runCatching { musicPreviewPlayer?.stop() }
                        runCatching { musicPreviewPlayer?.release() }
                        musicPreviewPlayer = null
                        ReaderPlaybackService.command(context, ReaderPlaybackService.ACTION_MUSIC_PREVIEW_BEGIN)
                        val gainDb = if (
                            track.normalizationVersion >= PcmLoudnessEstimator.VERSION &&
                            track.normalizationError.isBlank() &&
                            track.loudnessLufsEstimate.isFinite() &&
                            track.peakDbfs.isFinite()
                        ) {
                            PcmLoudnessEstimator.calculateNormalization(
                                track.loudnessLufsEstimate,
                                track.peakDbfs,
                                musicTargetLufs,
                            ).gainDb
                        } else 0f
                        val previewLevel = (track.volume * PcmLoudnessEstimator.gainDbToLinear(gainDb)).coerceIn(0f, 1f)
                        musicPreviewPlayer = runCatching { MediaPlayer.create(context, Uri.parse(track.uri)) }.getOrNull()?.also { player ->
                            player.setVolume(previewLevel, previewLevel)
                            player.setOnCompletionListener { completed ->
                                runCatching { completed.release() }
                                if (musicPreviewPlayer === completed) {
                                    musicPreviewPlayer = null
                                    ReaderPlaybackService.command(context, ReaderPlaybackService.ACTION_MUSIC_PREVIEW_END)
                                }
                            }
                            player.start()
                            scope.launch {
                                delay(15_000)
                                if (musicPreviewPlayer === player) {
                                    runCatching { player.stop() }
                                    runCatching { player.release() }
                                    musicPreviewPlayer = null
                                    ReaderPlaybackService.command(context, ReaderPlaybackService.ACTION_MUSIC_PREVIEW_END)
                                }
                            }
                        }
                        if (musicPreviewPlayer == null) {
                            ReaderPlaybackService.command(context, ReaderPlaybackService.ACTION_MUSIC_PREVIEW_END)
                            onMessage("Không nghe thử được bài nhạc này.")
                        }
                        selectedMusicTrackId = null
                    }
'''
s = rep(s, old_preview, new_preview, "normalized timed preview")
# Clear-all and delete confirmation also restore reading music through the service.
s = s.replace(
    "                musicPreviewPlayer = null\n                musicLibraryDraft = emptyList()\n",
    "                musicPreviewPlayer = null\n                ReaderPlaybackService.command(context, ReaderPlaybackService.ACTION_MUSIC_PREVIEW_END)\n                musicLibraryDraft = emptyList()\n",
    1,
)
p.write_text(s)

# ---------- Regression gate for runtime music semantics ----------
Path("scripts/check_music_runtime_parity.py").write_text('''#!/usr/bin/env python3
from pathlib import Path

db = Path("app/src/main/java/vn/nghetruyen/app/data/local/AppDatabase.kt").read_text()
repo = Path("app/src/main/java/vn/nghetruyen/app/data/repository/LibraryRepository.kt").read_text()
estimator = Path("app/src/main/java/vn/nghetruyen/app/audio/PcmLoudnessEstimator.kt").read_text()
worker = Path("app/src/main/java/vn/nghetruyen/app/audio/SceneMusicAnalysisWorker.kt").read_text()
service = Path("app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt").read_text()
reader = Path("app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt").read_text()

for token in ["version = 23", "peakDbfs", "normalizationTargetLufs", "normalizationGainDb", "normalizationPeakLimited", "normalizationVersion", "normalizationError", "MIGRATION_22_23"]:
    if token not in db: raise SystemExit("MUSIC_RUNTIME missing DB token: " + token)
for token in ["updateSceneMusicNormalization", "markSceneMusicNormalizationError", "tagsCsv = tagsCsv.trim().take(300)"]:
    if token not in repo: raise SystemExit("MUSIC_RUNTIME missing repository behavior: " + token)
if "tagsCsv.split(',')" in repo: raise SystemExit("MUSIC_RUNTIME repository still splits freeform descriptions")
for token in ["VERSION = 1", "DEFAULT_TARGET_LUFS = -24f", "PEAK_CEILING_DBFS = -1f", "shelfCoefficients", "highPassCoefficients", "integratedLoudness", "calculateNormalization", "gainDbToLinear"]:
    if token not in estimator: raise SystemExit("MUSIC_RUNTIME missing normalizer behavior: " + token)
for token in ["KEY_REUSED_MEASUREMENT", "track.normalizationVersion", "track.peakDbfs", "updateSceneMusicNormalization"]:
    if token not in worker: raise SystemExit("MUSIC_RUNTIME worker cannot reuse measurement: " + token)
for token in ["ACTION_MUSIC_PREVIEW_BEGIN", "ACTION_MUSIC_PREVIEW_END", "beginMusicPreview", "endMusicPreview", "track.normalizationGainDb", "PcmLoudnessEstimator.isReady"]:
    if token not in service: raise SystemExit("MUSIC_RUNTIME service parity missing: " + token)
for token in ["delay(15_000)", "ACTION_MUSIC_PREVIEW_BEGIN", "ACTION_MUSIC_PREVIEW_END", "calculateNormalization", "SceneMusicAnalysisWorker.enqueue(context, it.id)"]:
    if token not in reader: raise SystemExit("MUSIC_RUNTIME preview/recalc missing: " + token)
print("MUSIC_RUNTIME_PARITY=PASS")
''')

p = Path("scripts/m0_gate.sh")
s = p.read_text()
marker = "  scripts/check_reference_workflow_parity.py\n"
if "check_music_runtime_parity.py" not in s:
    s = rep(s, marker, marker + "  scripts/check_music_runtime_parity.py\n", "m0 music runtime gate")
p.write_text(s)
