package vn.nghetruyen.app.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import vn.nghetruyen.app.MainActivity
import vn.nghetruyen.app.ai.ChapterAiWorkflow
import vn.nghetruyen.app.ai.NarrationPlanCoordinator
import vn.nghetruyen.app.ai.XpkSceneMusicParity
import vn.nghetruyen.app.ai.XpkVoiceCastSplitter
import vn.nghetruyen.app.NgheTruyenApplication
import vn.nghetruyen.app.R
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.core.model.AudioInterruptionMode
import vn.nghetruyen.app.core.model.SceneMusicPlaybackMode
import vn.nghetruyen.app.core.model.VoiceRoleDraft
import vn.nghetruyen.app.core.model.ChapterContent
import vn.nghetruyen.app.core.model.ChapterSummary
import vn.nghetruyen.app.data.local.PronunciationEntity
import vn.nghetruyen.app.data.local.PlaybackCheckpointEntity
import vn.nghetruyen.app.data.local.PlaybackQueueChapterEntity
import vn.nghetruyen.app.data.local.ChapterVoiceAssignmentEntity
import vn.nghetruyen.app.data.local.SceneMusicCueEntity
import vn.nghetruyen.app.data.local.SceneMusicTrackEntity
import vn.nghetruyen.app.data.local.VoiceRoleEntity
import vn.nghetruyen.app.ui.reference.ReferenceVoiceRoleExtras
import vn.nghetruyen.app.audio.Pcm16WaveConverter
import vn.nghetruyen.app.audio.AudioAssetClassifier
import vn.nghetruyen.app.audio.AudioAssetKind
import vn.nghetruyen.app.audio.AudioDirectionPreferences
import vn.nghetruyen.app.audio.StoryAudioModeRouter
import vn.nghetruyen.app.audio.StoryAudioSourceMode
import vn.nghetruyen.app.audio.SonicPcmProcessor
import vn.nghetruyen.app.audio.PcmLoudnessEstimator
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity
import vn.nghetruyen.app.freesound.FreesoundImporter
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID

private data class RuntimeVoiceConfig(
    val enginePackage: String?,
    val voiceName: String?,
    val languageTag: String,
    val rate: Float,
    val pitch: Float,
    val volume: Float,
    val sonicSpeed: Float = 1f,
    val sonicPitch: Float = 1f,
    val sonicEnabled: Boolean = false,
    val sonicAccurate: Boolean = false,
)

private data class ActiveSpeechAttempt(
    val text: String,
    val config: RuntimeVoiceConfig,
    val usedSonic: Boolean,
    val recovery: SpeechRecoveryState = SpeechRecoveryState(),
)

internal object NarrationAutomaticPlanPolicy {
    // Automatic playback preparation is cache-first. Explicit user re-cast paths may still force.
    const val FORCE_REGENERATION = false
}

class ReaderPlaybackService : Service() {
    private lateinit var tts: TextToSpeech
    private lateinit var mediaSession: MediaSession
    private lateinit var audioManager: AudioManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val container by lazy { (application as NgheTruyenApplication).container }
    private val mediaButtonGestures = MediaButtonGestureInterpreter()
    private val mediaButtonDeduplicator = MediaButtonEventDeduplicator()
    private var mediaButtonMapping = MediaButtonMapping.DEFAULT
    private val ttsGenerationGuard = TtsGenerationGuard()
    private val completionGuard = PlaybackCompletionGuard()
    private val speechCompletionMonitor = SpeechCompletionMonitor()
    private val playbackHealth = PlaybackHealthMonitor()
    private val chapterPageNavigation = ChapterPageNavigationCache()
    private val playbackSessionId = UUID.randomUUID().toString()
    private var mediaButtonFlush: Runnable? = null
    private lateinit var sceneMusicController: SceneMusicController

    private var audioFocusRequest: AudioFocusRequest? = null
    private var initWatchdog: Runnable? = null
    private var speechWatchdog: Runnable? = null
    private var activeSpeechAttempt: ActiveSpeechAttempt? = null
    private var pendingRecoveryState: SpeechRecoveryState? = null
    private var pendingForceNoSonic = false
    private var ttsCache: TtsAudioCache? = null
    private var ttsCacheEnabled = true
    private var ttsCacheLimitMiB = 64
    private var normalizeTtsVolumeEnabled = true
    private var ttsTargetLufs = -18.0f
    private var pronunciationRevision = "empty"
    private var activeSonicCacheKey: TtsAudioCache.Key? = null
    private var hasAudioFocus = false
    private var resumeAfterTransientFocusLoss = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var ttsReady = false
    private var voiceSettingsReady = false
    private var pendingPlay = false
    private var pendingPreviewText: String? = null
    private var pendingPreviewConfig: RuntimeVoiceConfig? = null
    private var activeUtteranceId: String? = null
    private var previewUtteranceId: String? = null
    private var prefetchJob: Job? = null
    private var prefetchParentId: String = ""
    private var advanceJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var checkpointJob: Job? = null
    private var restoreJob: Job? = null
    private var narrationPlanJob: Job? = null
    private var narrationPrefetchJob: Job? = null
    private var narrationPlanningChapterId: String = ""
    private var narrationPreparedChapterId: String = ""
    private var manualNarrationChapterId: String = ""

    private fun diagnostic(
    name: String,
    severity: DiagnosticSeverity = DiagnosticSeverity.DEBUG,
    attributes: Map<String, String> = emptyMap(),
) {
    val snapshot = PlaybackQueueStore.state.value
    container.sourceDiagnostics.mark(
        name = name,
        category = DiagnosticCategory.RUNTIME,
        severity = severity,
        sourceId = snapshot.sourceId.ifBlank { "tts" },
        traceId = "tts:$playbackSessionId",
        attributes = attributes + mapOf(
            "storyId" to snapshot.storyId,
            "chapterId" to snapshot.chapterId,
            "unitId" to snapshot.currentUnitId.orEmpty(),
            "speechChunkIndex" to snapshot.speechChunkIndex.toString(),
        ),
    )
}

    private fun diagnosticFreesoundPlanStart(phase: String) {
        if (!StoryAudioModeRouter.usesAiFreesound(storyAudioSourceMode)) return
        val audio = AudioDirectionPreferences.currentSnapshot()
        diagnostic(
            "FREESOUND_MODE3_PLAN_START",
            DiagnosticSeverity.INFO,
            mapOf(
                "phase" to phase,
                "musicEnabled" to autoSceneMusicEnabled.toString(),
                "ambienceEnabled" to audio.ambienceEnabled.toString(),
                "sfxEnabled" to audio.soundEffectsEnabled.toString(),
            ),
        )
    }

    private fun diagnosticFreesoundPlanResult(
        phase: String,
        result: NarrationPlanCoordinator.Result?,
        error: Throwable? = null,
    ) {
        if (!StoryAudioModeRouter.usesAiFreesound(storyAudioSourceMode)) return
        diagnostic(
            "FREESOUND_MODE3_PLAN_RESULT",
            if (result == null || result.freesoundRetryRequired || error != null) DiagnosticSeverity.WARN else DiagnosticSeverity.INFO,
            mapOf(
                "phase" to phase,
                "resultPresent" to (result != null).toString(),
                "resolvedAssets" to (result?.freesoundResolvedAssets ?: 0).toString(),
                "musicPlanCreated" to (result?.musicPlanCreated ?: false).toString(),
                "audioPlanCreated" to (result?.audioPlanCreated ?: false).toString(),
                "freesoundPlanCreated" to (result?.freesoundPlanCreated ?: false).toString(),
                "retryRequired" to (result?.freesoundRetryRequired ?: false).toString(),
                "retryAttempts" to (result?.freesoundRetryAttempts ?: 0).toString(),
                "retryExhausted" to (result?.freesoundRetryExhausted ?: false).toString(),
                "traceCount" to (result?.freesoundDiagnostics?.size ?: 0).toString(),
                "warningCount" to (result?.warnings?.size ?: 0).toString(),
                "error" to (error?.message ?: "").take(220),
            ),
        )
        if (
            result != null &&
            result.freesoundResolvedAssets == 0 &&
            (result.freesoundRetryRequired || result.freesoundRetryExhausted)
        ) {
            diagnostic(
                "FREESOUND_MODE3_ZERO_AUDIO",
                DiagnosticSeverity.WARN,
                mapOf(
                    "phase" to phase,
                    "retryRequired" to result.freesoundRetryRequired.toString(),
                    "musicPlanCreated" to result.musicPlanCreated.toString(),
                    "audioPlanCreated" to result.audioPlanCreated.toString(),
                    "traceCount" to result.freesoundDiagnostics.size.toString(),
                    "firstTrace" to result.freesoundDiagnostics.firstOrNull().orEmpty().take(260),
                    "firstWarning" to result.warnings.firstOrNull().orEmpty().take(260),
                ),
            )
        }
        result?.freesoundDiagnostics.orEmpty().forEachIndexed { index, detail ->
            val stage = detail.substringBefore(' ').take(56).ifBlank { "TRACE" }
            // BASIC diagnostics keeps INFO, so normal Freesound stages stay visible without
            // inflating the warning count. Only actual failed/unresolved stages are warnings.
            val normalizedDetail = detail.uppercase(Locale.ROOT)
            val severity = if (
                normalizedDetail.contains("FAILED") ||
                normalizedDetail.contains("ERROR") ||
                normalizedDetail.contains("RETRY_EXHAUSTED") ||
                normalizedDetail.contains("NEED_UNRESOLVED")
            ) DiagnosticSeverity.WARN else DiagnosticSeverity.INFO
            diagnostic(
                "FREESOUND_$stage",
                severity,
                mapOf(
                    "phase" to phase,
                    "index" to (index + 1).toString(),
                    "detail" to detail.take(420),
                ),
            )
        }
    }

    private var transitionMessage: String? = null
    private var currentEnginePackage: String? = null
    private var pendingRoleEnginePackage: String? = null
    private val failedEnginePackages = mutableSetOf<String>()
    private var pendingConfigUseStoryProfile = true
    private var activeBaseVoice = RuntimeVoiceConfig(null, null, "vi-VN", 1f, 1f, 1f)
    private var voiceRoles: List<VoiceRoleEntity> = emptyList()
    private var voiceAssignments: Map<Int, ChapterVoiceAssignmentEntity> = emptyMap()
    private var xpkVoiceAssignments: Map<String, XpkPlaybackRuntime.VoiceAssignment> = emptyMap()
    private var sceneMusicCues: List<SceneMusicCueEntity> = emptyList()
    private var xpkSceneTrackByUnitId: Map<String, String> = emptyMap()
    private var sceneMusicTracks: Map<String, SceneMusicTrackEntity> = emptyMap()
    private var activeSceneTrackId: String? = null
    private var lastSceneMusicLookupTraceState: String = ""
    private var musicPreviewActive = false
    private var musicPreviewPlainWasPlaying = false
    private var musicPreviewSceneWasActive = false
    private var interruptionMode: AudioInterruptionMode = AudioInterruptionMode.PAUSE
    private var backgroundPlayer: MediaPlayer? = null
    private var sonicPlayer: MediaPlayer? = null
    private var activeSonicSynthesisId: String? = null
    private var activeSonicPlaybackId: String? = null
    private var activeSonicRawFile: File? = null
    private var activeSonicPcmFile: File? = null
    private var activeSonicOutputFile: File? = null
    private var activeSonicSpeed = 1f
    private var activeSonicPitch = 1f
    private var activeSonicVolume = 1f
    private var backgroundMusicUri: String? = null
    private var backgroundMusicVolume: Float = 0.18f
    private var backgroundMusicDuckFactor: Float = 0.63095734f
    private var backgroundMusicEnabled = false
    private var storyAudioSourceMode = StoryAudioSourceMode.AI_LOCAL
    private var backgroundMusicAttackMillis = 1850
    private var backgroundMusicReleaseMillis = 2050
    private var backgroundMusicTracks: List<SceneMusicTrackEntity> = emptyList()
    private val backgroundMusicShuffleBag = ArrayDeque<String>()
    private var headsetMultiClickEnabled = true
    private var pauseOnHeadsetDisconnect = true
    private var restorePlaybackAfterProcessDeath = true
    private var autoVoiceCastEnabled = false
    private var currentStoryAutoVoiceCastEnabled = false
    private var currentStoryExpressiveAdjustmentEnabled = false
    private var autoSceneMusicEnabled = false
    private var prefetchNarrationPlansEnabled = true
    private var narrationPrefetchWindowChapters = 2
    private var sceneMusicCrossfadeMillis = 1_600
    private var sceneMusicContinueAcrossChapters = true
    private var sceneMusicPlaybackMode = SceneMusicPlaybackMode.SEQUENTIAL
    private var sceneMusicTargetLufs = -24.0f
    private var sceneMusicAvoidRepeatWindow = 4
    private var sonicProcessingEnabled = true
    private var sonicDefaultSpeed = 1.0f
    private var sonicDefaultPitch = 1.0f
    private val recentSceneTrackIds = ArrayDeque<String>()
    @Volatile private var pronunciationRules: List<PronunciationEntity> = emptyList()
    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY &&
                pauseOnHeadsetDisconnect && PlaybackQueueStore.state.value.isPlaying
            ) {
                transitionMessage = "Đã tạm dừng vì tai nghe bị ngắt kết nối."
                diagnostic("TTS_HEADSET_DISCONNECTED_PAUSE", DiagnosticSeverity.WARN)
                pause()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        diagnostic("TTS_SERVICE_CREATED", DiagnosticSeverity.INFO)
        createNotificationChannel()
        audioManager = getSystemService(AudioManager::class.java)
        sceneMusicController = SceneMusicController(this, serviceScope) { message ->
            transitionMessage = message
            updateNotification()
        }
        ContextCompat.registerReceiver(
            this,
            becomingNoisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        serviceScope.launch {
            val settings = container.settingsRepository.snapshot()
            applyRuntimeSettings(settings)
            withContext(Dispatchers.Main) { initializeTts(settings.ttsEnginePackage) }
        }
        serviceScope.launch {
            container.libraryRepository.observePronunciations().collect { rules ->
                pronunciationRules = rules.filter(PronunciationEntity::enabled)
                pronunciationRevision = TtsAudioCache.sha256(
                    pronunciationRules.joinToString("\u001f") { "${it.original}=${it.replacement}:${it.updatedAt}" },
                )
            }
        }
        mediaSession = MediaSession(this, "NgheTruyenReader").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onStop() = stopPlayback()
                override fun onSkipToNext() = skip(1)
                override fun onSkipToPrevious() = skip(-1)
                override fun onFastForward() = skip(1)
                override fun onRewind() = skip(-1)
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val event = mediaButtonIntent.mediaButtonKeyEvent(Intent.EXTRA_KEY_EVENT) ?: return false
                    return handleMediaButtonEvent(event)
                }
            })
            val receiver = ComponentName(this@ReaderPlaybackService, ReaderMediaButtonReceiver::class.java)
            if (Build.VERSION.SDK_INT >= 31) {
                setMediaButtonBroadcastReceiver(receiver)
            } else {
                @Suppress("DEPRECATION")
                setMediaButtonReceiver(
                    PendingIntent.getBroadcast(
                        this@ReaderPlaybackService,
                        0,
                        Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(receiver),
                        PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
            }
            isActive = true
        }
        updateMediaState()
        restorePersistentSleepTimer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        val action = intent?.action
        diagnostic("TTS_COMMAND", attributes = mapOf("action" to (action ?: "RESTORE"), "startId" to startId.toString()))
        when (action) {
            null -> restoreCheckpointAndMaybePlay(playAfterRestore = false)
            ACTION_PLAY -> playOrRestore()
            ACTION_PAUSE -> pause()
            ACTION_TOGGLE -> if (PlaybackQueueStore.state.value.isPlaying) pause() else playOrRestore()
            ACTION_NEXT, ACTION_FORWARD -> skip(1)
            ACTION_PREVIOUS, ACTION_REWIND -> skip(-1)
            ACTION_STOP -> stopPlayback()
            ACTION_REFRESH -> refreshVoiceAndNotification()
            ACTION_APPLY_NARRATION_AND_PLAY -> {
                manualNarrationChapterId = PlaybackQueueStore.state.value.chapterId
                refreshVoiceAndNotification(playAfterRefresh = true)
            }
            ACTION_MUSIC_PREVIEW_BEGIN -> beginMusicPreview()
            ACTION_MUSIC_PREVIEW_END -> endMusicPreview()
            ACTION_SET_SLEEP_TIMER -> scheduleSleepTimer(intent.getIntExtra(EXTRA_SLEEP_MINUTES, 0))
            ACTION_CANCEL_SLEEP_TIMER -> cancelSleepTimer()
            ACTION_SLEEP_TIMER_EXPIRED -> expireSleepTimer()
            ACTION_PREVIEW -> preparePreview(intent.getStringExtra(EXTRA_PREVIEW_TEXT).orEmpty())
            ACTION_PREVIEW_ROLE -> prepareRolePreview(intent)
            ACTION_MEDIA_BUTTON -> intent.mediaButtonKeyEvent(EXTRA_MEDIA_KEY_EVENT)?.let(::handleMediaButtonEvent)
        }
        return if (PlaybackQueueStore.state.value.chapterId.isNotBlank() || action == null) START_STICKY else START_NOT_STICKY
    }

    private fun playOrRestore() {
        val snapshot = PlaybackQueueStore.state.value
        when (snapshot.preparationState) {
            PlaybackPreparationState.PREPARING -> {
                pendingPlay = true
                PlaybackQueueStore.setPlaying(false)
                transitionMessage = snapshot.preparationMessage ?: "Đang chuẩn bị bản dịch AI trước khi phát…"
                updateMediaState()
                updateNotification()
                return
            }
            PlaybackPreparationState.FAILED -> {
                pendingPlay = false
                PlaybackQueueStore.setPlaying(false)
                transitionMessage = snapshot.preparationMessage ?: "Không chuẩn bị được nội dung AI. Chọn BẢN GỐC hoặc thử lại trước khi phát."
                updateMediaState()
                updateNotification()
                return
            }
            PlaybackPreparationState.READY -> Unit
        }
        if (snapshot.currentSpeechText.isNullOrBlank()) {
            restoreCheckpointAndMaybePlay(playAfterRestore = true)
        } else {
            play()
        }
    }

    private fun handleMediaButtonEvent(event: KeyEvent): Boolean {
        val normalized = event.toMediaKeyEvent()
        if (!mediaButtonDeduplicator.accept(normalized, SystemClock.uptimeMillis())) return true
        val result = mediaButtonGestures.onKeyEvent(normalized, headsetMultiClickEnabled, mediaButtonMapping)
        if (result.cancelPendingFlush) {
            mediaButtonFlush?.let(mainHandler::removeCallbacks)
            mediaButtonFlush = null
        }
        result.immediate?.let(::executeMediaButtonCommand)
        result.flushAtMillis?.let { flushAt ->
            val runnable = Runnable {
                mediaButtonFlush = null
                mediaButtonGestures.flush(SystemClock.uptimeMillis(), mediaButtonMapping)?.let(::executeMediaButtonCommand)
            }
            mediaButtonFlush = runnable
            mainHandler.postAtTime(runnable, flushAt)
        }
        return result.immediate != null || result.flushAtMillis != null || result.cancelPendingFlush
    }

    private fun executeMediaButtonCommand(command: MediaButtonCommand) {
        when (command) {
            MediaButtonCommand.PLAY -> playOrRestore()
            MediaButtonCommand.PAUSE -> pause()
            MediaButtonCommand.TOGGLE -> if (PlaybackQueueStore.state.value.isPlaying) pause() else playOrRestore()
            MediaButtonCommand.NEXT, MediaButtonCommand.FORWARD -> skip(1)
            MediaButtonCommand.PREVIOUS, MediaButtonCommand.REWIND -> skip(-1)
            MediaButtonCommand.STOP -> stopPlayback()
        }
    }

    private fun Intent.mediaButtonKeyEvent(extraName: String): KeyEvent? =
        if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(extraName, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(extraName)
        }

    private fun KeyEvent.toMediaKeyEvent(): MediaKeyEvent = MediaKeyEvent(
        keyCode = keyCode,
        action = action,
        downTime = downTime,
        eventTime = eventTime,
        repeatCount = repeatCount,
        longPress = isLongPress,
    )

    private fun applyRuntimeSettings(settings: vn.nghetruyen.app.data.settings.AppSettings) {
        headsetMultiClickEnabled = settings.headsetMultiClickEnabled
        mediaButtonMapping = MediaButtonMapping.fromNames(
            settings.headsetSingleClickAction,
            settings.headsetDoubleClickAction,
            settings.headsetTripleClickAction,
            settings.headsetLongPressAction,
        )
        pauseOnHeadsetDisconnect = settings.pauseOnHeadsetDisconnect
        restorePlaybackAfterProcessDeath = settings.restorePlaybackAfterProcessDeath
        val previousAutoVoiceCastEnabled = autoVoiceCastEnabled
        autoVoiceCastEnabled = settings.autoVoiceCastEnabled
        if (previousAutoVoiceCastEnabled && !autoVoiceCastEnabled) {
            currentStoryAutoVoiceCastEnabled = false
            narrationPlanJob?.cancel()
            narrationPrefetchJob?.cancel()
            narrationPlanningChapterId = ""
            narrationPreparedChapterId = ""
            if (!PlaybackQueueStore.state.value.isPlaying) pendingPlay = false
            PlaybackQueueStore.setNarrationAutomation(
                stage = NarrationAutomationStage.IDLE,
                progress = 0f,
                message = null,
            )
        }
        backgroundMusicEnabled = settings.backgroundMusicEnabled
        backgroundMusicDuckFactor = settings.backgroundMusicDuckFactor
        backgroundMusicAttackMillis = settings.backgroundMusicAttackMillis.coerceIn(0, 2_000)
        backgroundMusicReleaseMillis = settings.backgroundMusicReleaseMillis.coerceIn(0, 5_000)
        sceneMusicController.setDuckTiming(backgroundMusicAttackMillis, backgroundMusicReleaseMillis)
        autoSceneMusicEnabled = settings.autoSceneMusicEnabled
        prefetchNarrationPlansEnabled = settings.prefetchNarrationPlansEnabled
        narrationPrefetchWindowChapters = settings.narrationPrefetchWindowChapters
        sceneMusicCrossfadeMillis = settings.sceneMusicCrossfadeMillis
        sceneMusicContinueAcrossChapters = settings.sceneMusicContinueAcrossChapters
        sceneMusicPlaybackMode = settings.sceneMusicPlaybackMode
        sceneMusicTargetLufs = settings.sceneMusicTargetLufs
        sceneMusicAvoidRepeatWindow = settings.sceneMusicAvoidRepeatWindow
        sonicProcessingEnabled = settings.sonicProcessingEnabled
        sonicDefaultSpeed = settings.sonicDefaultSpeed
        sonicDefaultPitch = settings.sonicDefaultPitch
        ttsCacheEnabled = settings.ttsCacheEnabled
        ttsCacheLimitMiB = settings.ttsCacheLimitMiB
        normalizeTtsVolumeEnabled = settings.normalizeTtsVolumeEnabled
        ttsTargetLufs = settings.ttsTargetLufs
        ttsCache = if (ttsCacheEnabled) {
            TtsAudioCache(File(cacheDir, "tts-audio-cache"), ttsCacheLimitMiB.toLong() * 1024L * 1024L)
        } else {
            null
        }
        ttsCache?.trim()
    }

    private fun restoreCheckpointAndMaybePlay(playAfterRestore: Boolean) {
        if (restoreJob?.isActive == true) {
            pendingPlay = pendingPlay || playAfterRestore
            return
        }
        restoreJob = serviceScope.launch {
            val settings = container.settingsRepository.snapshot()
            applyRuntimeSettings(settings)
            if (!restorePlaybackAfterProcessDeath) {
                withContext(Dispatchers.Main) {
                    transitionMessage = "Khôi phục phiên nghe đang tắt."
                    updateNotification()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return@launch
            }
            val checkpoint = container.libraryRepository.loadPlaybackCheckpoint()
            if (checkpoint == null) {
                withContext(Dispatchers.Main) {
                    transitionMessage = "Không có phiên nghe trước để khôi phục."
                    updateNotification()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return@launch
            }
            val persistedQueue = container.libraryRepository.loadPlaybackQueue()
                .filter { it.storyId == checkpoint.storyId }
                .sortedBy(PlaybackQueueChapterEntity::position)
            val persistedCurrent = persistedQueue.firstOrNull { it.chapterId == checkpoint.chapterId }
            val cached = container.libraryRepository.loadCachedChapter(checkpoint.chapterId)
            val content = cached?.let(ReaderDocumentNormalizer::normalize)
            if (content == null || content.paragraphs.isEmpty()) {
                withContext(Dispatchers.Main) {
                    transitionMessage = if (content == null) {
                        "Chương trước không còn trong bộ nhớ ngoại tuyến."
                    } else {
                        "Chương trước không còn nội dung có thể đọc."
                    }
                    updateNotification()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return@launch
            }
            val restoredIndex = ReaderPositionResolver.resolve(
                chapterId = content.chapter.id,
                paragraphCount = content.paragraphs.size,
                forcedParagraphIndex = checkpoint.paragraphIndex,
            )
            val restoredNextNavigation = checkpoint.nextChapterUrl
                ?: persistedCurrent?.nextChapterUrl
                ?: content.nextChapterUrl
            val restoredCursor = ChapterPageCursorCodec.decode(restoredNextNavigation)
            val restoredContent = content.copy(
                nextChapterUrl = restoredCursor?.nextChapterUrl ?: restoredNextNavigation
                    ?.takeUnless(ChapterPageCursorCodec::isEncoded),
                previousChapterUrl = checkpoint.previousChapterUrl ?: persistedCurrent?.previousChapterUrl ?: content.previousChapterUrl,
                nextChapterPageUrl = restoredCursor?.url,
                nextChapterPageStartIndex = restoredCursor?.startIndex,
            )
            val queuedNextRecord = persistedQueue
                .dropWhile { it.chapterId != checkpoint.chapterId }
                .drop(1)
                .firstOrNull()
            val queuedNext = queuedNextRecord
                ?.let { record ->
                    container.libraryRepository.loadCachedChapter(record.chapterId)?.let { cachedNext ->
                        val persistedCursor = ChapterPageCursorCodec.decode(record.nextChapterUrl)
                        ReaderDocumentNormalizer.normalize(cachedNext).copy(
                            nextChapterUrl = persistedCursor?.nextChapterUrl ?: record.nextChapterUrl
                                ?.takeUnless(ChapterPageCursorCodec::isEncoded),
                            previousChapterUrl = record.previousChapterUrl ?: cachedNext.previousChapterUrl,
                            nextChapterPageUrl = persistedCursor?.url,
                            nextChapterPageStartIndex = persistedCursor?.startIndex,
                        )
                    }
                }
            withContext(Dispatchers.Main) {
                if (PlaybackQueueStore.state.value.chapterId.isBlank()) {
                    PlaybackQueueStore.loadContent(
                        sourceId = checkpoint.sourceId,
                        content = restoredContent,
                        startIndex = restoredIndex,
                        keepPlaying = false,
                    )
                    PlaybackQueueStore.restoreSpeechPosition(restoredIndex, checkpoint.speechChunkIndex)
                    if (restoredContent.nextChapterPageUrl.isNullOrBlank()) {
                        queuedNext?.let { NextChapterCache.put(restoredContent.chapter.id, it) }
                    }
                    val sleepDeadline = (ReaderSleepTimerStore.get(this@ReaderPlaybackService)
                        ?: checkpoint.sleepTimerEndsAtMillis)
                        ?.takeIf { !SleepTimerPolicy.hasExpired(it, System.currentTimeMillis()) }
                    if (sleepDeadline != null) {
                        ReaderSleepTimerStore.set(this@ReaderPlaybackService, sleepDeadline)
                        ReaderSleepTimerAlarm.schedule(this@ReaderPlaybackService, sleepDeadline)
                        armSleepTimer(sleepDeadline)
                    } else {
                        PlaybackQueueStore.setSleepTimer(null)
                    }
                    transitionMessage = "Đã khôi phục vị trí nghe và hàng đợi gần nhất."
                    updateMediaState()
                    updateNotification()
                }
                pendingPlay = pendingPlay || playAfterRestore || checkpoint.wasPlaying
            }
            val configured = applyConfiguredVoice(useStoryProfile = true)
            withContext(Dispatchers.Main) {
                voiceSettingsReady = configured
                if (configured && pendingPlay) {
                    pendingPlay = false
                    play()
                }
            }
        }
    }

    private fun persistCheckpoint(wasPlaying: Boolean = PlaybackQueueStore.state.value.isPlaying, immediate: Boolean = false) {
        val snapshot = PlaybackQueueStore.state.value
        if (snapshot.chapterId.isBlank()) return
        checkpointJob?.cancel()
        checkpointJob = serviceScope.launch {
            if (!immediate) delay(180)
            container.libraryRepository.savePlaybackCheckpoint(
                PlaybackCheckpointEntity(
                    sourceId = snapshot.sourceId,
                    storyId = snapshot.storyId,
                    chapterId = snapshot.chapterId,
                    chapterIndex = snapshot.chapterIndex,
                    paragraphIndex = snapshot.paragraphIndex,
                    speechChunkIndex = snapshot.speechChunkIndex,
                    wasPlaying = wasPlaying,
                    activeSceneTrackId = sceneMusicController.activeTrackId,
                    nextChapterUrl = persistedNextNavigation(snapshot),
                    previousChapterUrl = snapshot.previousChapterUrl,
                    sleepTimerEndsAtMillis = ReaderSleepTimerStore.get(this@ReaderPlaybackService),
                    sessionId = playbackSessionId,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            persistReadingPosition(snapshot)
            persistPlaybackQueue(snapshot)
        }
    }

    private fun persistedNextNavigation(snapshot: PlaybackSnapshot): String? = persistedNextNavigation(
        nextChapterUrl = snapshot.nextChapterUrl,
        nextChapterPageUrl = snapshot.nextChapterPageUrl,
        nextChapterPageStartIndex = snapshot.nextChapterPageStartIndex,
    )

    private fun persistedNextNavigation(content: ChapterContent): String? = persistedNextNavigation(
        nextChapterUrl = content.nextChapterUrl,
        nextChapterPageUrl = content.nextChapterPageUrl,
        nextChapterPageStartIndex = content.nextChapterPageStartIndex,
    )

    private fun persistedNextNavigation(
        nextChapterUrl: String?,
        nextChapterPageUrl: String?,
        nextChapterPageStartIndex: Int?,
    ): String? = nextChapterPageUrl?.trim()?.takeIf(String::isNotBlank)?.let { pageUrl ->
            ChapterPageCursorCodec.encode(
                pageUrl,
                nextChapterPageStartIndex ?: 0,
                nextChapterUrl,
            )
        }
        ?: nextChapterUrl?.trim()?.takeIf(String::isNotBlank)

    private suspend fun persistReadingPosition(snapshot: PlaybackSnapshot) {
        if (snapshot.storyId.isBlank() || snapshot.chapterId.isBlank() || snapshot.paragraphs.isEmpty()) return
        val paragraphIndex = snapshot.paragraphIndex.coerceIn(0, snapshot.paragraphs.lastIndex)
        container.libraryRepository.saveReadingPosition(
            sourceId = snapshot.sourceId,
            storyTitle = "",
            chapter = ChapterSummary(
                id = snapshot.chapterId,
                storyId = snapshot.storyId,
                index = snapshot.chapterIndex,
                title = snapshot.chapterTitle,
                url = snapshot.chapterUrl,
            ),
            paragraphIndex = paragraphIndex,
            totalParagraphs = snapshot.paragraphs.size,
        )
    }

    private suspend fun persistPlaybackQueue(snapshot: PlaybackSnapshot) {
        if (snapshot.storyId.isBlank() || snapshot.chapterId.isBlank()) return
        val now = System.currentTimeMillis()
        val queue = mutableListOf(
            PlaybackQueueChapterEntity(
                position = 0,
                sourceId = snapshot.sourceId,
                storyId = snapshot.storyId,
                chapterId = snapshot.chapterId,
                chapterIndex = snapshot.chapterIndex,
                chapterTitle = snapshot.chapterTitle,
                chapterUrl = snapshot.chapterUrl,
                nextChapterUrl = persistedNextNavigation(snapshot),
                previousChapterUrl = snapshot.previousChapterUrl,
                updatedAt = now,
            ),
        )
        var lastChapterIndex = snapshot.chapterIndex
        for (position in 1 until MAX_PERSISTED_QUEUE_CHAPTERS) {
            val next = container.libraryRepository.loadNextCachedChapter(snapshot.storyId, lastChapterIndex)
                ?: break
            if (queue.any { it.chapterId == next.chapter.id }) break
            queue += PlaybackQueueChapterEntity(
                position = position,
                sourceId = snapshot.sourceId,
                storyId = snapshot.storyId,
                chapterId = next.chapter.id,
                chapterIndex = next.chapter.index,
                chapterTitle = next.chapter.title,
                chapterUrl = next.chapter.url,
                nextChapterUrl = persistedNextNavigation(next),
                previousChapterUrl = next.previousChapterUrl,
                updatedAt = now,
            )
            lastChapterIndex = next.chapter.index
        }
        container.libraryRepository.replacePlaybackQueue(queue)
    }

    private fun handleTtsInit(generation: Long, status: Int) {
        if (!ttsGenerationGuard.isCurrent(generation)) return
        initWatchdog?.let(mainHandler::removeCallbacks)
        initWatchdog = null
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            diagnostic("TTS_ENGINE_READY", DiagnosticSeverity.INFO, mapOf("engine" to currentEnginePackage.orEmpty()))
        } else {
            diagnostic("TTS_ENGINE_INIT_FAILED", DiagnosticSeverity.ERROR, mapOf("engine" to currentEnginePackage.orEmpty(), "status" to status.toString()))
        }
        if (!ttsReady && !currentEnginePackage.isNullOrBlank()) {
            failedEnginePackages += currentEnginePackage.orEmpty()
            pendingRoleEnginePackage = null
            transitionMessage = "Bộ máy TTS đã chọn không khởi tạo được; đang dùng mặc định hệ thống."
            initializeTts(null)
            return
        }
        if (ttsReady) {
            tts.setAudioAttributes(speechAudioAttributes())
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    diagnostic("TTS_UTTERANCE_START", attributes = mapOf("utteranceId" to utteranceId.orEmpty()))
                    if (!utteranceId.isNullOrBlank() && utteranceId == activeUtteranceId) {
                        speechCompletionMonitor.markStarted(utteranceId)
                    }
                }
                override fun onError(utteranceId: String?) {
                    diagnostic("TTS_UTTERANCE_ERROR", DiagnosticSeverity.ERROR, mapOf("utteranceId" to utteranceId.orEmpty()))
                    if (utteranceId == activeSonicSynthesisId) onSonicSynthesisCompleted(false)
                    else onSpeechCompleted(utteranceId, false)
                }
                override fun onDone(utteranceId: String?) {
                    diagnostic("TTS_UTTERANCE_DONE", attributes = mapOf("utteranceId" to utteranceId.orEmpty()))
                    if (utteranceId == activeSonicSynthesisId) onSonicSynthesisCompleted(true)
                    else onSpeechCompleted(utteranceId, true)
                }
            })
            serviceScope.launch {
                val roleEngineSwitch = pendingRoleEnginePackage != null
                val applied = applyConfiguredVoice(
                    useStoryProfile = pendingConfigUseStoryProfile && pendingPreviewText.isNullOrBlank(),
                    skipEngineCheck = roleEngineSwitch,
                )
                if (!applied) return@launch
                withContext(Dispatchers.Main) {
                    voiceSettingsReady = true
                    pendingRoleEnginePackage = null
                    val preview = pendingPreviewText
                    val previewConfig = pendingPreviewConfig
                    when {
                        !preview.isNullOrBlank() -> {
                            pendingPreviewText = null
                            pendingPreviewConfig = null
                            previewNow(preview, previewConfig ?: activeBaseVoice)
                        }
                        pendingPlay -> {
                            pendingPlay = false
                            val recovery = pendingRecoveryState ?: SpeechRecoveryState()
                            val forceNoSonic = pendingForceNoSonic
                            pendingRecoveryState = null
                            pendingForceNoSonic = false
                            play(recovery, forceNoSonic)
                        }
                        else -> updateNotification()
                    }
                }
            }
        } else {
            PlaybackQueueStore.setPlaying(false)
            transitionMessage = "Không khởi tạo được bộ máy TTS."
            updateNotification()
        }
    }

    private fun play(
        recoveryState: SpeechRecoveryState = SpeechRecoveryState(),
        forceNoSonic: Boolean = false,
    ) {
        val snapshot = PlaybackQueueStore.state.value
        if (snapshot.preparationState != PlaybackPreparationState.READY) {
            playOrRestore()
            return
        }
        if (snapshot.currentSpeechText.isNullOrBlank()) {
            stopPlayback()
            return
        }
        if (!ttsReady || !voiceSettingsReady) {
            pendingPlay = true
            return
        }
        if (prepareCurrentNarrationBeforePlayback(snapshot)) return
        if (!requestAudioFocus()) {
            diagnostic("TTS_AUDIO_FOCUS_FAILED", DiagnosticSeverity.WARN)
            pendingPlay = false
            PlaybackQueueStore.setPlaying(false)
            transitionMessage = "Không lấy được quyền phát âm thanh."
            persistCheckpoint(wasPlaying = false, immediate = true)
            updateMediaState()
            updateNotification()
            return
        }
        acquireWakeLock()
        transitionMessage = null

        val unitAssignment = snapshot.currentUnitId?.let(xpkVoiceAssignments::get)
        val legacyAssignment = if (unitAssignment == null) voiceAssignments[snapshot.paragraphIndex] else null
        val fixedNarrator = snapshot.currentFixedVoiceId == XpkVoiceCastSplitter.NARRATOR_ID
        val assignedRole = when {
            fixedNarrator -> voiceRoles.firstOrNull { it.enabled && it.isNarrator }
            unitAssignment?.voiceId == XpkVoiceCastSplitter.NARRATOR_ID -> voiceRoles.firstOrNull { it.enabled && it.isNarrator }
            unitAssignment != null -> voiceRoles.firstOrNull { it.enabled && it.id == unitAssignment.voiceId }
            legacyAssignment != null -> legacyAssignment.roleName.let { assigned ->
                voiceRoles.firstOrNull { it.enabled && it.roleName.equals(assigned, ignoreCase = true) }
            }
            else -> null
        }
        val speechText = snapshot.currentSpeechText.orEmpty()
        val fallbackResolved = VoiceRoleResolver.resolve(speechText, voiceRoles)
        val resolved = when {
            fixedNarrator -> ResolvedVoiceRole(assignedRole, speechText)
            assignedRole != null -> ResolvedVoiceRole(assignedRole, speechText)
            else -> fallbackResolved
        }
        val roleConfig = resolved.role?.toRuntimeVoice() ?: activeBaseVoice
        val expression = VoiceExpressionProcessor.resolve(resolved.spokenText, resolved.role)
        val speedAdjustPct = if (currentStoryExpressiveAdjustmentEnabled) {
            (unitAssignment?.speedAdjustPct ?: legacyAssignment?.speedAdjustPct ?: 0f).coerceIn(-100f, 100f)
        } else 0f
        val pitchAdjustPct = if (currentStoryExpressiveAdjustmentEnabled) {
            (unitAssignment?.pitchAdjustPct ?: legacyAssignment?.pitchAdjustPct ?: 0f).coerceIn(-100f, 100f)
        } else 0f
        val volumeAdjustPct = if (currentStoryExpressiveAdjustmentEnabled) {
            (unitAssignment?.volumeAdjustPct ?: legacyAssignment?.volumeAdjustPct ?: 0f).coerceIn(-100f, 100f)
        } else 0f
        val aiRateMultiplier = 1f + speedAdjustPct / 100f
        val aiPitchMultiplier = 1f + pitchAdjustPct / 100f
        val aiVolumeMultiplier = 1f + volumeAdjustPct / 100f
        val hasExplicitRole = resolved.role != null
        val config = roleConfig.copy(
            rate = if (roleConfig.sonicEnabled) 1f
            else (roleConfig.rate * expression.rateMultiplier * aiRateMultiplier).coerceIn(0.25f, 3f),
            pitch = if (roleConfig.sonicEnabled) 1f
            else (roleConfig.pitch * expression.pitchMultiplier * aiPitchMultiplier).coerceIn(0.5f, 2f),
            volume = (roleConfig.volume * expression.volumeMultiplier * aiVolumeMultiplier)
                .coerceIn(0f, if (roleConfig.sonicEnabled) 2f else 1f),
            sonicSpeed = if (roleConfig.sonicEnabled) {
                val selectedSpeed = if (hasExplicitRole) roleConfig.sonicSpeed else sonicDefaultSpeed
                (selectedSpeed * expression.sonicSpeedMultiplier * aiRateMultiplier).coerceIn(0.25f, 3f)
            } else 1f,
            sonicPitch = if (roleConfig.sonicEnabled) {
                val selectedPitch = if (hasExplicitRole) roleConfig.sonicPitch else sonicDefaultPitch
                (selectedPitch * expression.sonicPitchMultiplier * aiPitchMultiplier).coerceIn(0.5f, 2f)
            } else 1f,
        )
        val desiredEngine = config.enginePackage?.takeUnless(failedEnginePackages::contains)
            ?: activeBaseVoice.enginePackage?.takeUnless(failedEnginePackages::contains)
        if (desiredEngine != currentEnginePackage) {
            diagnostic("TTS_VOICE_ENGINE_SWITCH", DiagnosticSeverity.INFO, mapOf("from" to currentEnginePackage.orEmpty(), "to" to desiredEngine.orEmpty()))
            pendingRoleEnginePackage = desiredEngine ?: "__DEFAULT__"
            pendingConfigUseStoryProfile = true
            pendingPlay = true
            initializeTts(desiredEngine)
            return
        }
        applyRuntimeVoice(config)
        PlaybackQueueStore.updateVoice(config.rate, config.pitch, config.volume)
        PlaybackQueueStore.setPlaying(true)
        updateSceneMusicForUnit(snapshot.currentUnitId, snapshot.paragraphIndex)
        updateBackgroundMusic(ducked = true)
        val playbackPosition = snapshot.currentUnitId ?: "paragraph-${snapshot.paragraphIndex}"
        val utteranceId = "${snapshot.chapterId}:$playbackPosition:${snapshot.speechChunkIndex}:${System.nanoTime()}"
        activeUtteranceId = utteranceId
        val spokenText = PronunciationProcessor.apply(expression.text, pronunciationRules)
            .ifBlank { expression.text }
        val hasSonicTransform = config.sonicEnabled
        val useRenderedPipeline = !forceNoSonic &&
            (hasSonicTransform || ttsCacheEnabled || normalizeTtsVolumeEnabled)
        val renderConfig = if (hasSonicTransform) config else config.copy(sonicSpeed = 1f, sonicPitch = 1f, sonicEnabled = false)
        activeSpeechAttempt = ActiveSpeechAttempt(spokenText, renderConfig, useRenderedPipeline, recoveryState)
        completionGuard.begin(utteranceId)
        playbackHealth.chunkStarted(System.currentTimeMillis(), utteranceId)
        startSpeechWatchdog(utteranceId, spokenText, renderConfig, useRenderedPipeline)
        val speakResult = if (useRenderedPipeline) {
            synthesizeAndPlaySonic(spokenText, renderConfig, utteranceId)
        } else {
            val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, config.volume) }
            tts.speak(spokenText, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        }
        if (speakResult == TextToSpeech.ERROR) {
            recoverActiveSpeech("TTS_SUBMIT_REJECTED")
            return
        } else {
            maybePrefetchNextChapter(PlaybackQueueStore.state.value)
        }
        persistCheckpoint(wasPlaying = true)
        updateMediaState()
        updateNotification()
    }

    private fun startSpeechWatchdog(
        utteranceId: String,
        text: String,
        config: RuntimeVoiceConfig,
        usesSonic: Boolean,
    ) {
        speechWatchdog?.let(mainHandler::removeCallbacks)
        speechCompletionMonitor.begin(
            token = utteranceId,
            nowMillis = SystemClock.elapsedRealtime(),
            timeoutMillis = PlaybackWatchdogPolicy.speechTimeoutMillis(text.length, config.rate, usesSonic),
        )
        val watchdog = object : Runnable {
            override fun run() {
                if (activeUtteranceId != utteranceId || !PlaybackQueueStore.state.value.isPlaying) {
                    speechCompletionMonitor.cancel()
                    if (speechWatchdog === this) speechWatchdog = null
                    return
                }
                val outputActive = if (activeSonicPlaybackId == utteranceId) {
                    sonicPlayer?.runCatching { isPlaying }?.getOrDefault(false) == true
                } else {
                    ::tts.isInitialized && runCatching { tts.isSpeaking }.getOrDefault(false)
                }
                when (
                    speechCompletionMonitor.observe(
                        token = utteranceId,
                        nowMillis = SystemClock.elapsedRealtime(),
                        outputActive = outputActive,
                    )
                ) {
                    SpeechCompletionObservation.WAITING -> mainHandler.postDelayed(
                        this,
                        PlaybackWatchdogPolicy.COMPLETION_POLL_MILLIS,
                    )
                    SpeechCompletionObservation.COMPLETED -> {
                        if (speechWatchdog === this) speechWatchdog = null
                        diagnostic(
                            "TTS_COMPLETION_WATCHDOG_RECOVERY",
                            DiagnosticSeverity.WARN,
                            mapOf("utteranceId" to utteranceId, "rendered" to usesSonic.toString()),
                        )
                        onSpeechCompleted(utteranceId, true)
                    }
                    SpeechCompletionObservation.TIMED_OUT -> {
                        if (speechWatchdog === this) speechWatchdog = null
                        recoverActiveSpeech("TTS_SPEECH_TIMEOUT")
                    }
                    SpeechCompletionObservation.STALE -> {
                        if (speechWatchdog === this) speechWatchdog = null
                    }
                }
            }
        }
        speechWatchdog = watchdog
        mainHandler.postDelayed(
            watchdog,
            PlaybackWatchdogPolicy.COMPLETION_POLL_MILLIS,
        )
    }

    private fun cancelSpeechWatchdog() {
        speechWatchdog?.let(mainHandler::removeCallbacks)
        speechWatchdog = null
        speechCompletionMonitor.cancel()
    }

    private fun recoverActiveSpeech(code: String) {
        if (previewUtteranceId != null && previewUtteranceId == activeUtteranceId) {
            cancelSpeechWatchdog()
            completionGuard.cancel()
            clearSonicPlayback(deleteFiles = true)
            activeSpeechAttempt = null
            activeUtteranceId = null
            previewUtteranceId = null
            transitionMessage = "Không xử lý được Sonic cho đoạn nghe thử ($code)."
            releaseWakeLock()
            abandonAudioFocus()
            updateNotification()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val attempt = activeSpeechAttempt ?: run {
            failPlaybackSafely(code)
            return
        }
        cancelSpeechWatchdog()
        completionGuard.cancel()
        playbackHealth.chunkFailed(System.currentTimeMillis(), code)
        val action = PlaybackRecoveryPolicy.next(
            state = attempt.recovery,
            wasUsingSonic = attempt.usedSonic,
            selectedEnginePackage = currentEnginePackage,
        )
        playbackHealth.recovery(System.currentTimeMillis(), action)
        val nextState = PlaybackRecoveryPolicy.after(attempt.recovery, action)
        activeUtteranceId = null
        clearSonicPlayback(deleteFiles = true)
        if (::tts.isInitialized) runCatching { tts.stop() }
        when (action) {
            SpeechRecoveryAction.RETRY_WITHOUT_SONIC -> {
                transitionMessage = "Xử lý tệp giọng lỗi; đang phát lại trực tiếp bằng TTS."
                play(nextState, forceNoSonic = true)
            }
            SpeechRecoveryAction.RETRY_CURRENT_ENGINE -> {
                transitionMessage = "TTS lỗi; đang khởi tạo lại bộ máy hiện tại."
                pendingRecoveryState = nextState
                pendingForceNoSonic = attempt.recovery.sonicFallbackUsed || attempt.usedSonic
                pendingPlay = true
                initializeTts(currentEnginePackage)
            }
            SpeechRecoveryAction.FALLBACK_TO_DEFAULT_ENGINE -> {
                currentEnginePackage?.let(failedEnginePackages::add)
                transitionMessage = "Bộ máy TTS hiện tại không ổn định; đang dùng mặc định hệ thống."
                pendingRecoveryState = nextState
                pendingForceNoSonic = true
                pendingPlay = true
                initializeTts(null)
            }
            SpeechRecoveryAction.STOP_SAFELY -> failPlaybackSafely(code)
        }
        updateNotification()
    }

    private fun failPlaybackSafely(code: String) {
        cancelSpeechWatchdog()
        completionGuard.cancel()
        activeSpeechAttempt = null
        activeUtteranceId = null
        pendingPlay = false
        PlaybackQueueStore.setPlaying(false)
        updateBackgroundMusic(ducked = false, pause = true)
        releaseWakeLock()
        abandonAudioFocus()
        persistCheckpoint(wasPlaying = false, immediate = true)
        transitionMessage = "Đã dừng an toàn sau lỗi phát giọng ($code)."
        updateMediaState()
        updateNotification()
    }

    private fun synthesizeAndPlaySonic(text: String, config: RuntimeVoiceConfig, playbackId: String): Int {
        clearSonicPlayback(deleteFiles = true)
        val cacheKey = TtsAudioCache.Key(
            text = text,
            enginePackage = currentEnginePackage,
            voiceName = config.voiceName,
            languageTag = config.languageTag,
            rate = config.rate,
            pitch = config.pitch,
            volume = config.volume,
            sonicSpeed = config.sonicSpeed,
            sonicPitch = config.sonicPitch,
            pronunciationRevision = pronunciationRevision,
        )
        activeSonicCacheKey = cacheKey
        ttsCache?.get(cacheKey)?.let { cached ->
            activeSonicPlaybackId = playbackId
            activeSonicVolume = config.volume
            transitionMessage = "Đang phát đoạn giọng từ bộ nhớ đệm."
            startSonicPlayback(cached.audioFile, playbackId)
            return TextToSpeech.SUCCESS
        }
        val directory = File(cacheDir, "sonic-playback").apply { mkdirs() }
        val token = playbackId.hashCode().toUInt().toString(16)
        val raw = File(directory, "$token-raw.wav")
        val pcm = File(directory, "$token-pcm.wav")
        val output = File(directory, "$token-sonic.wav")
        val synthesisId = "sonic-synth:$playbackId"
        activeSonicSynthesisId = synthesisId
        activeSonicPlaybackId = playbackId
        activeSonicRawFile = raw
        activeSonicPcmFile = pcm
        activeSonicOutputFile = output
        activeSonicSpeed = config.sonicSpeed
        activeSonicPitch = config.sonicPitch
        activeSonicVolume = config.volume
        transitionMessage = "Đang dựng và chuẩn hóa giọng đọc…"
        updateNotification()
        return tts.synthesizeToFile(text, Bundle(), raw, synthesisId)
    }

    private fun onSonicSynthesisCompleted(success: Boolean) {
        val playbackId = activeSonicPlaybackId ?: return
        val raw = activeSonicRawFile
        val pcm = activeSonicPcmFile
        val output = activeSonicOutputFile
        activeSonicSynthesisId = null
        if (!success || raw == null || pcm == null || output == null) {
            clearSonicPlayback(deleteFiles = true)
            recoverActiveSpeech("SONIC_SYNTHESIS_FAILED")
            return
        }
        val speed = activeSonicSpeed.coerceIn(0.25f, 3f)
        val pitch = activeSonicPitch.coerceIn(0.5f, 2f)
        val volume = activeSonicVolume.coerceIn(0f, 2f)
        val sonicGain = if (activeSpeechAttempt?.config?.sonicEnabled == true) volume else 1f
        serviceScope.launch {
            var normalizationFailed = false
            val processed = runCatching {
                Pcm16WaveConverter.convert(raw, pcm, 1f)
                runCatching { normalizeRenderedSpeech(pcm) }
                    .onFailure { normalizationFailed = true }
                Pcm16WaveConverter.convert(pcm, raw, volume)
                SonicPcmProcessor.process(
                    raw,
                    output,
                    speed,
                    pitch,
                    activeSpeechAttempt?.config?.sonicAccurate ?: false,
                    gain = sonicGain,
                )
            }
            withContext(Dispatchers.Main) {
                if (activeUtteranceId != playbackId || activeSonicPlaybackId != playbackId) {
                    clearSonicPlayback(deleteFiles = true)
                    return@withContext
                }
                processed.onFailure {
                    transitionMessage = "Không xử lý được Sonic; đang thử TTS thường."
                    clearSonicPlayback(deleteFiles = true)
                    recoverActiveSpeech("SONIC_PROCESS_FAILED")
                }.onSuccess {
                    if (normalizationFailed) {
                        transitionMessage = "Không chuẩn hóa được âm lượng; đang dùng bản giọng an toàn."
                    }
                    activeSonicOutputFile = output
                    activeSonicCacheKey?.let { key -> runCatching { ttsCache?.put(key, output) } }
                    startSonicPlayback(output, playbackId)
                }
            }
        }
    }

    private fun normalizeRenderedSpeech(output: File): File {
        if (!normalizeTtsVolumeEnabled) return output
        val measured = PcmLoudnessEstimator.estimateLufs(output)
        val gain = PcmLoudnessEstimator.normalizationGain(measured, ttsTargetLufs).coerceIn(0.35f, 2f)
        if (kotlin.math.abs(gain - 1f) < 0.02f) return output
        val normalized = File(output.parentFile, "${output.nameWithoutExtension}-normalized.wav")
        Pcm16WaveConverter.convert(output, normalized, gain)
        if (output.exists() && !output.delete()) normalized.delete()
        check(normalized.renameTo(output)) { "Không commit được WAV đã chuẩn hóa." }
        return output
    }

    private fun startSonicPlayback(file: File, playbackId: String) {
        sonicPlayer?.release()
        sonicPlayer = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(speechAudioAttributes())
                setDataSource(this@ReaderPlaybackService, Uri.fromFile(file))
                setVolume(1f, 1f)
                setOnCompletionListener {
                    clearSonicPlayback(deleteFiles = true)
                    onSpeechCompleted(playbackId, true)
                }
                setOnErrorListener { _, _, _ ->
                    clearSonicPlayback(deleteFiles = true)
                    recoverActiveSpeech("SONIC_PLAYBACK_FAILED")
                    true
                }
                prepare()
                start()
                speechCompletionMonitor.markStarted(playbackId)
            }
        }.getOrElse {
            transitionMessage = "Không phát được đoạn Sonic đã xử lý."
            clearSonicPlayback(deleteFiles = true)
            recoverActiveSpeech("SONIC_PLAYER_PREPARE_FAILED")
            null
        }
        if (sonicPlayer != null) {
            transitionMessage = null
            updateNotification()
        }
    }

    private fun clearSonicPlayback(deleteFiles: Boolean) {
        sonicPlayer?.runCatching { stop() }
        sonicPlayer?.release()
        sonicPlayer = null
        activeSonicSynthesisId = null
        activeSonicPlaybackId = null
        if (deleteFiles) {
            listOf(activeSonicRawFile, activeSonicPcmFile, activeSonicOutputFile).forEach { it?.delete() }
        }
        activeSonicRawFile = null
        activeSonicPcmFile = null
        activeSonicOutputFile = null
        activeSonicSpeed = 1f
        activeSonicPitch = 1f
        activeSonicVolume = 1f
        activeSonicCacheKey = null
    }

    private fun prepareRolePreview(intent: Intent) {
        val rawText = intent.getStringExtra(EXTRA_PREVIEW_TEXT).orEmpty()
        val sonicEnabled = intent.getBooleanExtra(EXTRA_PREVIEW_SONIC_ENABLED, false)
        val systemVolume = intent.getFloatExtra(EXTRA_PREVIEW_VOLUME, 1f)
        val sonicVolume = intent.getFloatExtra(EXTRA_PREVIEW_SONIC_VOLUME, systemVolume)
        val baseConfig = RuntimeVoiceConfig(
            enginePackage = intent.getStringExtra(EXTRA_PREVIEW_ENGINE)?.takeIf(String::isNotBlank),
            voiceName = intent.getStringExtra(EXTRA_PREVIEW_VOICE)?.takeIf(String::isNotBlank),
            languageTag = intent.getStringExtra(EXTRA_PREVIEW_LANGUAGE).orEmpty().ifBlank { "vi-VN" },
            rate = if (sonicEnabled) 1f else intent.getFloatExtra(EXTRA_PREVIEW_RATE, 1f).coerceIn(0.25f, 3f),
            pitch = if (sonicEnabled) 1f else intent.getFloatExtra(EXTRA_PREVIEW_PITCH, 1f).coerceIn(0.5f, 2f),
            volume = (if (sonicEnabled) sonicVolume else systemVolume).coerceIn(0f, if (sonicEnabled) 2f else 1f),
            sonicSpeed = intent.getFloatExtra(EXTRA_PREVIEW_SONIC_SPEED, 1f).coerceIn(0.25f, 3f),
            sonicPitch = intent.getFloatExtra(EXTRA_PREVIEW_SONIC_PITCH, 1f).coerceIn(0.5f, 2f),
            sonicEnabled = sonicEnabled,
            sonicAccurate = intent.getBooleanExtra(EXTRA_PREVIEW_SONIC_ACCURATE, false),
        )
        val previewRole = VoiceRoleEntity(
            id = "preview",
            storyId = "preview",
            roleName = "preview",
            aliasesCsv = "",
            enginePackage = baseConfig.enginePackage,
            voiceName = baseConfig.voiceName,
            languageTag = baseConfig.languageTag,
            rate = baseConfig.rate,
            pitch = baseConfig.pitch,
            volume = baseConfig.volume,
            expression = intent.getStringExtra(EXTRA_PREVIEW_EXPRESSION).orEmpty().ifBlank { "NEUTRAL" },
            expressionStrength = intent.getFloatExtra(EXTRA_PREVIEW_EXPRESSION_STRENGTH, 0.5f).coerceIn(0f, 1f),
            sonicSpeed = baseConfig.sonicSpeed,
            sonicPitch = baseConfig.sonicPitch,
            isNarrator = false,
            enabled = true,
            updatedAt = System.currentTimeMillis(),
        )
        val expressive = VoiceExpressionProcessor.resolve(rawText, previewRole)
        preparePreview(
            expressive.text,
            baseConfig.copy(
                rate = if (baseConfig.sonicEnabled) 1f
                else (baseConfig.rate * expressive.rateMultiplier).coerceIn(0.25f, 3f),
                pitch = if (baseConfig.sonicEnabled) 1f
                else (baseConfig.pitch * expressive.pitchMultiplier).coerceIn(0.5f, 2f),
                volume = (baseConfig.volume * expressive.volumeMultiplier).coerceIn(0f, if (baseConfig.sonicEnabled) 2f else 1f),
                sonicSpeed = if (baseConfig.sonicEnabled) (baseConfig.sonicSpeed * expressive.sonicSpeedMultiplier).coerceIn(0.25f, 3f) else 1f,
                sonicPitch = if (baseConfig.sonicEnabled) (baseConfig.sonicPitch * expressive.sonicPitchMultiplier).coerceIn(0.5f, 2f) else 1f,
            ),
        )
    }

    private fun preparePreview(value: String, requestedConfig: RuntimeVoiceConfig? = null) {
        val text = value.trim().take(MAX_PREVIEW_TEXT_CHARS)
        if (text.isBlank()) {
            transitionMessage = "Không có văn bản để nghe thử."
            updateNotification()
            return
        }
        if (PlaybackQueueStore.state.value.isPlaying) {
            transitionMessage = "Hãy tạm dừng truyện trước khi nghe thử giọng."
            updateNotification()
            return
        }
        pendingPreviewText = text
        pendingPreviewConfig = requestedConfig
        val desiredEngine = requestedConfig?.enginePackage?.takeIf(String::isNotBlank)
        if (!ttsReady || desiredEngine != currentEnginePackage) {
            pendingRoleEnginePackage = desiredEngine ?: "__DEFAULT__"
            pendingConfigUseStoryProfile = false
            initializeTts(desiredEngine)
            return
        }
        serviceScope.launch {
            if (!applyConfiguredVoice(useStoryProfile = false)) return@launch
            withContext(Dispatchers.Main) {
                voiceSettingsReady = true
                pendingPreviewText = null
                pendingPreviewConfig = null
                previewNow(text, requestedConfig ?: activeBaseVoice)
            }
        }
    }

    private fun previewNow(value: String, config: RuntimeVoiceConfig = activeBaseVoice) {
        val text = value.trim().take(MAX_PREVIEW_TEXT_CHARS)
        if (text.isBlank()) {
            transitionMessage = "Không có văn bản để nghe thử."
            updateNotification()
            return
        }
        if (!ttsReady || !voiceSettingsReady) {
            pendingPreviewText = text
            pendingPreviewConfig = config
            return
        }
        if (PlaybackQueueStore.state.value.isPlaying) {
            transitionMessage = "Hãy tạm dừng truyện trước khi nghe thử giọng."
            updateNotification()
            return
        }
        if (!requestAudioFocus()) {
            transitionMessage = "Không lấy được quyền phát âm thanh để nghe thử."
            updateNotification()
            return
        }
        acquireWakeLock()
        applyRuntimeVoice(config)
        val spoken = PronunciationProcessor.apply(text, pronunciationRules).ifBlank { text }
        val utteranceId = "preview:${System.nanoTime()}"
        previewUtteranceId = utteranceId
        transitionMessage = "Đang nghe thử giọng…"
        val result = if (config.sonicEnabled) {
            activeUtteranceId = utteranceId
            activeSpeechAttempt = ActiveSpeechAttempt(spoken, config, usedSonic = true)
            synthesizeAndPlaySonic(spoken, config, utteranceId)
        } else {
            val previewParams = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, config.volume) }
            tts.speak(spoken, TextToSpeech.QUEUE_FLUSH, previewParams, utteranceId)
        }
        if (result == TextToSpeech.ERROR) {
            activeUtteranceId = null
            activeSpeechAttempt = null
            previewUtteranceId = null
            transitionMessage = "Bộ máy TTS từ chối đoạn nghe thử."
            releaseWakeLock()
            abandonAudioFocus()
        }
        updateNotification()
    }

    private fun pause() {
        resumeAfterTransientFocusLoss = false
        pauseInternal(abandonFocus = true)
    }

    private fun pauseInternal(abandonFocus: Boolean, preserveResumeIntent: Boolean = false) {
        pendingPlay = false
        pendingRecoveryState = null
        pendingForceNoSonic = false
        cancelSpeechWatchdog()
        completionGuard.cancel()
        activeSpeechAttempt = null
        activeUtteranceId = null
        clearSonicPlayback(deleteFiles = true)
        if (::tts.isInitialized) tts.stop()
        PlaybackQueueStore.setPlaying(false)
        updateBackgroundMusic(ducked = false, pause = true)
        releaseWakeLock()
        if (abandonFocus) abandonAudioFocus()
        persistCheckpoint(wasPlaying = preserveResumeIntent, immediate = true)
        updateMediaState()
        updateNotification()
    }

    private fun skip(delta: Int) {
        val wasPlaying = PlaybackQueueStore.state.value.isPlaying
        if (::tts.isInitialized) tts.stop()
        cancelSpeechWatchdog()
        completionGuard.cancel()
        activeSpeechAttempt = null
        clearSonicPlayback(deleteFiles = true)
        activeUtteranceId = null
        PlaybackQueueStore.moveBy(delta)
        persistCheckpoint(wasPlaying = wasPlaying)
        if (wasPlaying) play() else {
            maybePrefetchNextChapter(PlaybackQueueStore.state.value)
            updateMediaState()
            updateNotification()
        }
    }

    private fun onSpeechCompleted(utteranceId: String?, success: Boolean) {
        if (utteranceId != null && utteranceId == previewUtteranceId) {
            mainHandler.post {
                previewUtteranceId = null
                activeUtteranceId = null
                activeSpeechAttempt = null
                transitionMessage = if (success) "Đã phát mẫu giọng." else "Không phát được mẫu giọng."
                releaseWakeLock()
                abandonAudioFocus()
                updateNotification()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return
        }
        if (utteranceId == null || utteranceId != activeUtteranceId) return
        if (!completionGuard.consume(utteranceId)) return
        mainHandler.post {
            cancelSpeechWatchdog()
            activeUtteranceId = null
            if (!success) {
                recoverActiveSpeech("TTS_CALLBACK_ERROR")
                return@post
            }
            playbackHealth.chunkCompleted(System.currentTimeMillis(), utteranceId)
            activeSpeechAttempt = null
            if (!PlaybackQueueStore.state.value.isPlaying) {
                releaseWakeLock()
                return@post
            }

            val advancedInsideParagraph = PlaybackQueueStore.advanceSpeechChunk()
            if (advancedInsideParagraph) {
                persistCheckpoint(wasPlaying = true)
                maybePrefetchNextChapter(PlaybackQueueStore.state.value)
                play()
                return@post
            }
            val current = PlaybackQueueStore.state.value
            if (current.paragraphIndex >= current.paragraphs.lastIndex) {
                persistCheckpoint(wasPlaying = true)
                advanceAfterChapter(current)
            } else {
                PlaybackQueueStore.moveBy(1)
                persistCheckpoint(wasPlaying = true)
                maybePrefetchNextChapter(PlaybackQueueStore.state.value)
                play()
            }
        }
    }

    private fun maybePrefetchNextChapter(snapshot: PlaybackSnapshot) {
        if (snapshot.progressFraction < PREFETCH_THRESHOLD) return
        if (snapshot.chapterId.isBlank() || NextChapterCache.has(snapshot.chapterId)) return
        if (prefetchParentId == snapshot.chapterId && prefetchJob?.isActive == true) return
        if (
            snapshot.sourceId != "offline" &&
            snapshot.nextChapterUrl.isNullOrBlank() &&
            snapshot.nextChapterPageUrl.isNullOrBlank()
        ) return

        if (currentStoryAutoVoiceCastEnabled && prefetchNarrationPlansEnabled) {
            PlaybackQueueStore.setNarrationAutomation(
                stage = NarrationAutomationStage.NEXT_LOADING,
                progress = 0.15f,
                message = "Đã tới 75% chương. Đang tải chương tiếp theo để phân vai trước…",
            )
        }
        prefetchJob?.cancel()
        prefetchParentId = snapshot.chapterId
        prefetchJob = serviceScope.launch {
            val next = loadNextChapter(snapshot)
            if (next != null && PlaybackQueueStore.state.value.chapterId == snapshot.chapterId) {
                if (currentStoryAutoVoiceCastEnabled && prefetchNarrationPlansEnabled) {
                    PlaybackQueueStore.setNarrationAutomation(
                        stage = NarrationAutomationStage.NEXT_PLANNING,
                        progress = 0.45f,
                        message = "Đã tải ${next.chapter.title}. Đang phân vai chương tiếp theo…",
                    )
                }
                NextChapterCache.put(snapshot.chapterId, next)
                persistPlaybackQueue(snapshot)
                maybePrefetchNarrationPlans(next, snapshot.sourceId, snapshot.chapterId)
            } else if (currentStoryAutoVoiceCastEnabled && prefetchNarrationPlansEnabled &&
                PlaybackQueueStore.state.value.chapterId == snapshot.chapterId
            ) {
                PlaybackQueueStore.setNarrationAutomation(
                    stage = NarrationAutomationStage.FAILED,
                    progress = 1f,
                    message = "Không tải được chương tiếp theo để phân vai trước.",
                )
            }
        }
    }

    private fun advanceAfterChapter(snapshot: PlaybackSnapshot) {
        if (advanceJob?.isActive == true) {
            diagnostic(
                "TTS_CHAPTER_ADVANCE_ALREADY_RUNNING",
                DiagnosticSeverity.WARN,
                mapOf("fromChapterId" to snapshot.chapterId),
            )
            return
        }
        advanceJob = serviceScope.launch {
            diagnostic(
                "TTS_CHAPTER_ADVANCE_BEGIN",
                DiagnosticSeverity.INFO,
                mapOf("fromChapterId" to snapshot.chapterId),
            )
            try {
                val autoNext = container.settingsRepository.snapshot().autoPlayNextChapter
                if (!autoNext) {
                    withContext(Dispatchers.Main) {
                        transitionMessage = "Tự động chuyển chương đang tắt trong cài đặt."
                        stopPlayback()
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    transitionMessage = "Đang chuẩn bị chương tiếp theo…"
                    updateNotification()
                }
                val next = loadNextChapterForAdvance(snapshot)
                if (next == null) {
                    val hasSuccessor = NextChapterAdvancePolicy.hasRemoteSuccessor(
                        snapshot.sourceId,
                        snapshot.nextChapterUrl,
                        snapshot.nextChapterPageUrl,
                    )
                    withContext(Dispatchers.Main) {
                        if (PlaybackQueueStore.state.value.chapterId != snapshot.chapterId) return@withContext
                        if (hasSuccessor) {
                            transitionMessage = "Chưa tải được chương tiếp theo sau nhiều lần thử. Nhấn PHÁT để thử lại."
                            pauseInternal(abandonFocus = true)
                        } else {
                            transitionMessage = "Đã đọc hết chương cuối."
                            stopPlayback()
                        }
                    }
                    diagnostic(
                        if (hasSuccessor) "TTS_CHAPTER_ADVANCE_LOAD_FAILED" else "TTS_CHAPTER_ADVANCE_END_OF_STORY",
                        if (hasSuccessor) DiagnosticSeverity.ERROR else DiagnosticSeverity.INFO,
                        mapOf("fromChapterId" to snapshot.chapterId),
                    )
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    if (PlaybackQueueStore.state.value.chapterId != snapshot.chapterId) return@withContext
                    val currentVoice = PlaybackQueueStore.state.value
                    PlaybackQueueStore.loadContent(
                        sourceId = snapshot.sourceId,
                        content = next,
                        rate = currentVoice.rate,
                        pitch = currentVoice.pitch,
                        volume = currentVoice.volume,
                        keepPlaying = false,
                    )
                    prefetchParentId = ""
                    pendingPlay = true
                    transitionMessage = null
                    persistCheckpoint(wasPlaying = true)
                }
                if (PlaybackQueueStore.state.value.chapterId != next.chapter.id) return@launch
                val configured = applyConfiguredVoice(useStoryProfile = true)
                withContext(Dispatchers.Main) {
                    if (PlaybackQueueStore.state.value.chapterId != next.chapter.id) return@withContext
                    voiceSettingsReady = configured
                    if (configured && pendingPlay) {
                        pendingPlay = false
                        play()
                    } else {
                        updateMediaState()
                        updateNotification()
                    }
                }
                diagnostic(
                    "TTS_CHAPTER_ADVANCE_READY",
                    DiagnosticSeverity.INFO,
                    mapOf("fromChapterId" to snapshot.chapterId, "toChapterId" to next.chapter.id),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                diagnostic(
                    "TTS_CHAPTER_ADVANCE_FAILED",
                    DiagnosticSeverity.ERROR,
                    mapOf(
                        "fromChapterId" to snapshot.chapterId,
                        "error" to (error.message ?: error::class.java.simpleName).take(240),
                    ),
                )
                withContext(Dispatchers.Main) {
                    val current = PlaybackQueueStore.state.value
                    if (current.chapterId != snapshot.chapterId) {
                        pendingPlay = true
                        transitionMessage = "Đã mở chương tiếp theo; đang khôi phục giọng đọc…"
                        refreshVoiceAndNotification(playAfterRefresh = true)
                    } else {
                        transitionMessage = "Không thể tự chuyển chương. Nhấn PHÁT để thử lại."
                        pauseInternal(abandonFocus = true)
                    }
                }
            }
        }
    }

    private suspend fun loadNextChapterForAdvance(snapshot: PlaybackSnapshot): ChapterContent? {
        NextChapterCache.take(snapshot.chapterId)?.let { return it }
        val inFlightPrefetch = prefetchJob
        if (NextChapterAdvancePolicy.shouldAwaitPrefetch(
                chapterId = snapshot.chapterId,
                prefetchParentId = prefetchParentId,
                prefetchActive = inFlightPrefetch?.isActive == true,
            )
        ) {
            diagnostic(
                "TTS_CHAPTER_ADVANCE_WAIT_PREFETCH",
                DiagnosticSeverity.INFO,
                mapOf("fromChapterId" to snapshot.chapterId),
            )
            withTimeoutOrNull(NextChapterAdvancePolicy.PREFETCH_WAIT_MILLIS) {
                inFlightPrefetch?.join()
            }
            NextChapterCache.take(snapshot.chapterId)?.let { return it }
        }

        val attempts = if (NextChapterAdvancePolicy.hasRemoteSuccessor(
                snapshot.sourceId,
                snapshot.nextChapterUrl,
                snapshot.nextChapterPageUrl,
            )
        ) {
            NextChapterAdvancePolicy.LOAD_ATTEMPTS
        } else {
            1
        }
        repeat(attempts) { attempt ->
            val loaded = try {
                loadNextChapter(snapshot)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                diagnostic(
                    "TTS_CHAPTER_ADVANCE_LOAD_ERROR",
                    DiagnosticSeverity.WARN,
                    mapOf(
                        "attempt" to (attempt + 1).toString(),
                        "error" to (error.message ?: error::class.java.simpleName).take(180),
                    ),
                )
                null
            }
            if (loaded != null) return loaded
            NextChapterCache.take(snapshot.chapterId)?.let { return it }
            if (attempt + 1 < attempts) {
                diagnostic(
                    "TTS_CHAPTER_ADVANCE_LOAD_RETRY",
                    DiagnosticSeverity.WARN,
                    mapOf("attempt" to (attempt + 2).toString()),
                )
                delay(NextChapterAdvancePolicy.LOAD_RETRY_DELAY_MILLIS)
            }
        }
        return NextChapterCache.take(snapshot.chapterId)
    }

    private suspend fun loadNextChapter(snapshot: PlaybackSnapshot): ChapterContent? {
        if (snapshot.sourceId != "offline" && !snapshot.nextChapterPageUrl.isNullOrBlank()) {
            loadNextChapterFromCatalogPage(snapshot)?.let { return it }
        }

        val cachedByIndex = container.libraryRepository.loadNextCachedChapter(
            storyId = snapshot.storyId,
            chapterIndex = snapshot.chapterIndex,
        )
        if (cachedByIndex != null && isExpectedCachedSuccessor(snapshot, cachedByIndex)) {
            return chapterPageNavigation.enrich(ReaderDocumentNormalizer.normalize(cachedByIndex))
        }
        if (snapshot.sourceId == "offline") return null

        val nextUrl = snapshot.nextChapterUrl?.takeIf(String::isNotBlank) ?: return null
        val cachedByUrl = container.libraryRepository.loadCachedChapterByUrl(snapshot.storyId, nextUrl)
        if (cachedByUrl != null) {
            return chapterPageNavigation.enrich(
                NextChapterNormalizer.normalize(snapshot, nextUrl, cachedByUrl),
            )
        }
        val source = container.sourceRegistry.get(snapshot.sourceId) ?: return null
        return when (val result = source.chapter(nextUrl)) {
            is AppResult.Success -> chapterPageNavigation
                .enrich(NextChapterNormalizer.normalize(snapshot, nextUrl, result.value))
                .also { container.libraryRepository.cacheChapter(it) }
            is AppResult.Failure -> null
        }
    }

    private suspend fun loadNextChapterFromCatalogPage(snapshot: PlaybackSnapshot): ChapterContent? {
        val source = container.sourceRegistry.get(snapshot.sourceId) ?: return null
        var pageUrl = snapshot.nextChapterPageUrl?.trim()?.takeIf(String::isNotBlank) ?: return null
        var startIndex = snapshot.nextChapterPageStartIndex
            ?.coerceAtLeast(0)
            ?: (snapshot.chapterIndex + 1).coerceAtLeast(0)
        val visited = linkedSetOf<String>()

        repeat(MAX_CATALOG_PAGE_HOPS) { hop ->
            val pageIdentity = pageUrl.trim().trimEnd('/')
            if (!visited.add(pageIdentity)) {
                diagnostic(
                    "TTS_CHAPTER_CATALOG_PAGE_LOOP",
                    DiagnosticSeverity.ERROR,
                    mapOf("pageUrl" to pageUrl.take(180)),
                )
                return null
            }
            diagnostic(
                "TTS_CHAPTER_CATALOG_PAGE_LOAD",
                DiagnosticSeverity.INFO,
                mapOf("pageUrl" to pageUrl.take(180), "hop" to (hop + 1).toString()),
            )
            val pageResult = try {
                source.chapterPage(snapshot.storyId, pageUrl, startIndex)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                diagnostic(
                    "TTS_CHAPTER_CATALOG_PAGE_ERROR",
                    DiagnosticSeverity.WARN,
                    mapOf("error" to (error.message ?: error::class.java.simpleName).take(180)),
                )
                return null
            }
            val page = when (pageResult) {
                is AppResult.Success -> pageResult.value
                is AppResult.Failure -> {
                    diagnostic(
                        "TTS_CHAPTER_CATALOG_PAGE_FAILED",
                        DiagnosticSeverity.WARN,
                        mapOf("code" to pageResult.code, "message" to pageResult.message.take(180)),
                    )
                    return null
                }
            }
            val nextStartIndex = startIndex + page.chapters.size
            val candidates = ReaderChapterNavigation.readingOrder(
                page.chapters
                    .filterNot { candidate ->
                        candidate.id == snapshot.chapterId || (
                            candidate.url.isNotBlank() && snapshot.chapterUrl.isNotBlank() &&
                                candidate.url.trim().trimEnd('/') == snapshot.chapterUrl.trim().trimEnd('/')
                            )
                    }
                    .distinctBy { it.url.trim().trimEnd('/').ifBlank { it.id } },
            ).mapIndexed { offset, chapter ->
                chapter.copy(
                    storyId = snapshot.storyId,
                    index = ReaderChapterNavigation.sequenceNumber(chapter)
                        ?.takeIf { it > 0L }
                        ?.coerceAtMost(Int.MAX_VALUE.toLong())
                        ?.toInt()
                        ?.minus(1)
                        ?: (snapshot.chapterIndex + offset + 1),
                )
            }
            chapterPageNavigation.registerPage(
                storyId = snapshot.storyId,
                chapters = candidates,
                previousChapterUrl = snapshot.chapterUrl,
                nextPageUrl = page.nextPageUrl,
                nextPageStartIndex = nextStartIndex,
            )
            val successor = ReaderChapterNavigation.next(
                current = ChapterSummary(
                    id = snapshot.chapterId,
                    storyId = snapshot.storyId,
                    index = snapshot.chapterIndex,
                    title = snapshot.chapterTitle,
                    url = snapshot.chapterUrl,
                ),
                chapters = candidates,
                fallbackUrl = null,
            ) ?: candidates.firstOrNull()
                ?.takeIf { ReaderChapterNavigation.sequenceNumber(it) == null }
            if (successor != null) {
                val target = successor.url.ifBlank { successor.id }
                val cached = successor.url.takeIf(String::isNotBlank)?.let { url ->
                    container.libraryRepository.loadCachedChapterByUrl(snapshot.storyId, url)
                } ?: container.libraryRepository.loadCachedChapter(successor.id)
                val loaded = cached ?: when (val chapterResult = source.chapter(target)) {
                    is AppResult.Success -> chapterResult.value
                    is AppResult.Failure -> {
                        diagnostic(
                            "TTS_CHAPTER_AFTER_CATALOG_LOAD_FAILED",
                            DiagnosticSeverity.WARN,
                            mapOf("code" to chapterResult.code, "url" to target.take(180)),
                        )
                        return null
                    }
                }
                return normalizeCatalogSuccessor(snapshot, successor, loaded)
                    .also { container.libraryRepository.cacheChapter(it) }
            }

            val nextPage = page.nextPageUrl?.trim()?.takeIf(String::isNotBlank)
                ?.takeUnless { it.trim().trimEnd('/') in visited }
                ?: return null
            pageUrl = nextPage
            startIndex = nextStartIndex
        }
        diagnostic(
            "TTS_CHAPTER_CATALOG_PAGE_HOP_LIMIT",
            DiagnosticSeverity.ERROR,
            mapOf("fromChapterId" to snapshot.chapterId),
        )
        return null
    }

    private fun normalizeCatalogSuccessor(
        snapshot: PlaybackSnapshot,
        summary: ChapterSummary,
        content: ChapterContent,
    ): ChapterContent {
        val normalized = ReaderDocumentNormalizer.normalize(
            content.copy(
                chapter = content.chapter.copy(
                    storyId = snapshot.storyId,
                    index = summary.index,
                    title = content.chapter.title.ifBlank { summary.title },
                    url = content.chapter.url.ifBlank { summary.url },
                ),
                previousChapterUrl = content.previousChapterUrl
                    ?: snapshot.chapterUrl.takeIf(String::isNotBlank),
            ),
        )
        return chapterPageNavigation.enrich(normalized)
    }

    private fun isExpectedCachedSuccessor(
        snapshot: PlaybackSnapshot,
        candidate: ChapterContent,
    ): Boolean {
        val expectedUrl = snapshot.nextChapterUrl?.trim()?.trimEnd('/')
        if (!expectedUrl.isNullOrBlank()) {
            return candidate.chapter.url.trim().trimEnd('/') == expectedUrl ||
                candidate.chapter.id == snapshot.nextChapterUrl
        }
        return candidate.chapter.index == snapshot.chapterIndex + 1
    }

    private fun shouldPlanAutoSceneMusic(): Boolean =
        !StoryAudioModeRouter.usesManualLocal(storyAudioSourceMode) && autoSceneMusicEnabled

    private fun shouldPlanAutoStoryAudio(): Boolean {
        if (StoryAudioModeRouter.usesManualLocal(storyAudioSourceMode)) return false
        val audio = AudioDirectionPreferences.currentSnapshot()
        return autoSceneMusicEnabled || audio.ambienceEnabled || audio.soundEffectsEnabled
    }

    private fun prepareCurrentNarrationBeforePlayback(snapshot: PlaybackSnapshot): Boolean {
        val planAudioWithoutVoice = !currentStoryAutoVoiceCastEnabled && shouldPlanAutoStoryAudio()
        if (planAudioWithoutVoice && snapshot.chapterId.isNotBlank()) {
            if (narrationPreparedChapterId == snapshot.chapterId) return false
            pendingPlay = true
            PlaybackQueueStore.setPlaying(false)
            if (narrationPlanningChapterId == snapshot.chapterId && narrationPlanJob?.isActive == true) return true
            narrationPlanningChapterId = snapshot.chapterId
            narrationPlanJob?.cancel()
            narrationPlanJob = serviceScope.launch {
                PlaybackQueueStore.setNarrationAutomation(
                    stage = NarrationAutomationStage.CURRENT_PLANNING,
                    progress = 0.35f,
                    message = "Đang chuẩn bị âm thanh AI cho chương hiện tại.",
                )
                val content = container.libraryRepository.loadCachedChapter(snapshot.chapterId)
                val planResult = if (content == null) null else runCatching {
                    container.narrationPlanCoordinator.ensurePlans(
                        content = content,
                        voice = false,
                        music = shouldPlanAutoSceneMusic(),
                        activeTrackId = sceneMusicController.activeTrackId,
                    )
                }.getOrNull()
                val configured = applyConfiguredVoice(useStoryProfile = true)
                withContext(Dispatchers.Main) {
                    if (PlaybackQueueStore.state.value.chapterId != snapshot.chapterId) return@withContext
                    narrationPreparedChapterId = snapshot.chapterId
                    narrationPlanningChapterId = ""
                    voiceSettingsReady = configured
                    val warning = planResult?.warnings?.firstOrNull()?.takeIf(String::isNotBlank)
                    PlaybackQueueStore.setNarrationAutomation(
                        stage = NarrationAutomationStage.CURRENT_READY,
                        progress = 1f,
                        message = warning?.let { "Âm thanh AI đã chuẩn bị với cảnh báo: ${it.take(120)}" }
                            ?: "Đã chuẩn bị âm thanh AI cho chương hiện tại.",
                    )
                    transitionMessage = null
                    if (configured && pendingPlay) {
                        pendingPlay = false
                        play()
                    } else {
                        updateMediaState()
                        updateNotification()
                    }
                }
            }
            return true
        }
        if (!currentStoryAutoVoiceCastEnabled || snapshot.chapterId.isBlank()) return false
        if (narrationPreparedChapterId == snapshot.chapterId) return false
        pendingPlay = true
        PlaybackQueueStore.setPlaying(false)
        if (narrationPlanningChapterId == snapshot.chapterId && narrationPlanJob?.isActive == true) return true

        narrationPlanningChapterId = snapshot.chapterId
        narrationPlanJob?.cancel()
        narrationPlanJob = serviceScope.launch {
            var attempt = 0
            while (currentStoryAutoVoiceCastEnabled &&
                PlaybackQueueStore.state.value.chapterId == snapshot.chapterId &&
                attempt < MAX_NARRATION_ATTEMPTS
            ) {
                attempt += 1
                PlaybackQueueStore.setNarrationAutomation(
                    stage = NarrationAutomationStage.CURRENT_PLANNING,
                    progress = 0.35f,
                    message = if (attempt == 1) {
                        "Đang phân vai chương hiện tại."
                    } else {
                        "Đang thử phân vai lại lần $attempt."
                    },
                )
                transitionMessage = "Đang phân vai và chuẩn bị giọng đọc…"
                withContext(Dispatchers.Main) {
                    updateMediaState()
                    updateNotification()
                }

                diagnosticFreesoundPlanStart("voice_and_audio")
                val content = container.libraryRepository.loadCachedChapter(snapshot.chapterId)
                val planningAttempt = if (content == null) {
                    Result.failure(IllegalStateException("Không tải được chương để chuẩn bị phân vai."))
                } else {
                    runCatching {
                        // Do not reset/force here. If prefetch is still running, ensurePlans waits on
                        // the coordinator mutex and then reuses its completed transforms/assets.
                        container.narrationPlanCoordinator.ensurePlans(
                            content = content,
                            voice = true,
                            music = shouldPlanAutoSceneMusic(),
                            force = NarrationAutomaticPlanPolicy.FORCE_REGENERATION,
                            activeTrackId = sceneMusicController.activeTrackId,
                        )
                    }
                }
                val planResult = planningAttempt.getOrNull()
                diagnosticFreesoundPlanResult("voice_and_audio", planResult, planningAttempt.exceptionOrNull())
                val warnings = planResult?.warnings ?: listOf(
                    planningAttempt.exceptionOrNull()?.message ?: "Không chuẩn bị được phân vai TTS.",
                )
                val assignmentCount = if (content == null) 0
                else container.narrationPlanCoordinator.voicePlanAssignmentCount(content)

                if (planResult?.freesoundRetryExhausted == true) {
                    diagnostic(
                        "FREESOUND_MODE3_RETRY_EXHAUSTED",
                        DiagnosticSeverity.ERROR,
                        mapOf(
                            "attempts" to planResult.freesoundRetryAttempts.toString(),
                            "resolvedAssets" to planResult.freesoundResolvedAssets.toString(),
                        ),
                    )
                    withContext(Dispatchers.Main) {
                        if (PlaybackQueueStore.state.value.chapterId != snapshot.chapterId) return@withContext
                        narrationPlanningChapterId = ""
                        pendingPlay = false
                        PlaybackQueueStore.setPlaying(false)
                        PlaybackQueueStore.setNarrationAutomation(
                            stage = NarrationAutomationStage.FAILED,
                            progress = 1f,
                            message = "Freesound thất bại sau 3 lần. Không có kế hoạch âm thanh hợp lệ để phát.",
                        )
                        transitionMessage = "Freesound thất bại sau 3 lần; hãy phân vai lại để tạo lượt mới."
                        updateMediaState()
                        updateNotification()
                    }
                    return@launch
                }

                val mode3Incomplete = StoryAudioModeRouter.usesAiFreesound(storyAudioSourceMode) &&
                    planResult?.freesoundRetryRequired == true
                if (assignmentCount > 0 && !mode3Incomplete) {
                    PlaybackQueueStore.setNarrationAutomation(
                        stage = NarrationAutomationStage.CURRENT_APPLYING,
                        progress = 0.85f,
                        message = "Đã phân vai $assignmentCount mục. Đang áp dụng giọng${if (shouldPlanAutoStoryAudio()) " và âm thanh truyện" else ""}."
                    )
                    val configured = applyConfiguredVoice(useStoryProfile = true)
                    withContext(Dispatchers.Main) {
                        if (PlaybackQueueStore.state.value.chapterId != snapshot.chapterId) return@withContext
                        narrationPreparedChapterId = snapshot.chapterId
                        narrationPlanningChapterId = ""
                        voiceSettingsReady = configured
                        val musicApplied = hasSceneMusicPlan()
                        val mode3 = StoryAudioModeRouter.usesAiFreesound(storyAudioSourceMode)
                        val warning = warnings.firstOrNull()?.takeIf(String::isNotBlank)
                        val audioStatus = if (!mode3) {
                            if (musicApplied) " và đã áp dụng nhạc cảnh" else ""
                        } else {
                            FreesoundPlaybackStatusFormatter.format(
                                resultPresent = planResult != null,
                                downloadedAssets = planResult?.freesoundDownloadedAssets ?: 0,
                                reusedAssets = planResult?.freesoundReusedAssets ?: 0,
                                retryRequired = planResult?.freesoundRetryRequired ?: false,
                                audioLayersEnabled = shouldPlanAutoStoryAudio(),
                            )
                        }
                        PlaybackQueueStore.setNarrationAutomation(
                            stage = NarrationAutomationStage.CURRENT_READY,
                            progress = 1f,
                            message = "Đã phân vai xong $assignmentCount mục$audioStatus. Đang bắt đầu phát." +
                                warning?.let { " • ${it.take(140)}" }.orEmpty(),
                        )
                        if (mode3) {
                            diagnostic(
                                "FREESOUND_AUTO_PLAN_APPLIED",
                                if (planResult?.freesoundRetryRequired == true || warning != null) DiagnosticSeverity.WARN else DiagnosticSeverity.INFO,
                                mapOf(
                                    "resolvedAssets" to (planResult?.freesoundResolvedAssets ?: 0).toString(),
                                    "retryRequired" to (planResult?.freesoundRetryRequired ?: false).toString(),
                                    "musicApplied" to musicApplied.toString(),
                                    "audioPlanCreated" to (planResult?.audioPlanCreated ?: false).toString(),
                                    "warning" to warning.orEmpty().take(180),
                                ),
                            )
                        }
                        transitionMessage = warning?.take(180)
                        if (configured && pendingPlay) {
                            pendingPlay = false
                            play()
                        } else {
                            updateMediaState()
                            updateNotification()
                        }
                    }
                    return@launch
                }

                val castDisabled = warnings.any { warning ->
                    warning.contains("phân vai TTS đang tắt", ignoreCase = true)
                }
                if (castDisabled) {
                    withContext(Dispatchers.Main) {
                        narrationPlanningChapterId = ""
                        pendingPlay = false
                        PlaybackQueueStore.setPlaying(false)
                        PlaybackQueueStore.setNarrationAutomation(
                            stage = NarrationAutomationStage.FAILED,
                            progress = 1f,
                            message = "Phân vai TTS đang tắt cho truyện này. Chưa tự động phát.",
                        )
                        transitionMessage = warnings.firstOrNull()?.take(180)
                        updateMediaState()
                        updateNotification()
                    }
                    return@launch
                }

                val warningSuffix = warnings.firstOrNull()?.takeIf(String::isNotBlank)
                    ?.let { " ${it.take(120)}" }
                    .orEmpty()
                withContext(Dispatchers.Main) {
                    if (PlaybackQueueStore.state.value.chapterId != snapshot.chapterId) return@withContext
                    PlaybackQueueStore.setPlaying(false)
                    PlaybackQueueStore.setNarrationAutomation(
                        stage = NarrationAutomationStage.FAILED,
                        progress = 1f,
                        message = if (attempt >= MAX_NARRATION_ATTEMPTS) {
                            "Phân vai/Mode 3 thất bại sau $MAX_NARRATION_ATTEMPTS lần.$warningSuffix"
                        } else {
                            "Chưa chuẩn bị xong. Sẽ thử lại sau 5 giây (lần ${attempt + 1}/$MAX_NARRATION_ATTEMPTS).$warningSuffix"
                        },
                    )
                    transitionMessage = if (attempt >= MAX_NARRATION_ATTEMPTS) {
                        "Phân vai/Mode 3 thất bại sau $MAX_NARRATION_ATTEMPTS lần."
                    } else {
                        "Chưa chuẩn bị xong; đang chờ thử lại."
                    }
                    updateMediaState()
                    updateNotification()
                }
                if (attempt >= MAX_NARRATION_ATTEMPTS) {
                    pendingPlay = false
                    return@launch
                }
                delay(NARRATION_RETRY_DELAY_MS)
            }
            narrationPlanningChapterId = ""
        }
        return true
    }

    private fun maybePrefetchNarrationPlans(
        content: ChapterContent,
        sourceId: String,
        parentChapterId: String,
    ) {
        val planVoice = currentStoryAutoVoiceCastEnabled
        val planAudio = shouldPlanAutoStoryAudio()
        if (!prefetchNarrationPlansEnabled || (!planVoice && !planAudio)) return
        narrationPrefetchJob?.cancel()
        narrationPrefetchJob = serviceScope.launch {
            var current: ChapterContent? = content
            repeat(narrationPrefetchWindowChapters.coerceIn(1, 5)) { offset ->
                val chapter = current ?: return@repeat
                val attempt = runCatching {
                    // Prefetch is idempotent: reuse an existing valid plan and only create missing/
                    // stale pieces. This prevents repeated prefetches from redownloading assets.
                    container.narrationPlanCoordinator.ensurePlans(
                        content = chapter,
                        voice = planVoice,
                        music = shouldPlanAutoSceneMusic(),
                        force = NarrationAutomaticPlanPolicy.FORCE_REGENERATION,
                        activeTrackId = if (offset == 0) sceneMusicController.activeTrackId else null,
                    )
                }
                val result = attempt.getOrNull()
                if (offset == 0) {
                    val assignmentCount = if (!planVoice || result == null) {
                        0
                    } else {
                        try {
                            container.narrationPlanCoordinator.voicePlanAssignmentCount(chapter)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            diagnostic(
                                "TTS_NEXT_NARRATION_VALIDATION_FAILED",
                                DiagnosticSeverity.WARN,
                                mapOf("error" to (error.message ?: error::class.java.simpleName).take(180)),
                            )
                            0
                        }
                    }
                    val failed = result == null ||
                        (planVoice && assignmentCount <= 0) ||
                        (StoryAudioModeRouter.usesAiFreesound(storyAudioSourceMode) && result.freesoundRetryRequired)
                    val musicLabel = if (shouldPlanAutoSceneMusic()) " + nhạc cảnh" else ""
                    val baseMessage = when {
                        result == null -> "Không chuẩn bị AI trước được chương tiếp theo: ${chapter.chapter.title}."
                        !planVoice -> "Đã chuẩn bị âm thanh AI cho chương tiếp theo: ${chapter.chapter.title}."
                        assignmentCount <= 0 -> "Phân vai trước chưa tạo được mục giọng hợp lệ: ${chapter.chapter.title}."
                        failed -> "Phân vai trước chương tiếp theo chưa thành công: ${chapter.chapter.title}."
                        result.voicePlanCreated || result.musicPlanCreated || result.audioPlanCreated || result.freesoundPlanCreated ->
                            "Đã phân vai $assignmentCount mục$musicLabel cho chương tiếp theo: ${chapter.chapter.title}."
                        else -> "Chương tiếp theo đã có $assignmentCount mục phân vai$musicLabel hợp lệ: ${chapter.chapter.title}."
                    }
                    val warning = result?.warnings?.firstOrNull()?.takeIf(String::isNotBlank)
                        ?: attempt.exceptionOrNull()?.message
                    // Do not let a late prefetch result overwrite CURRENT_PLANNING/READY after the
                    // reader has already promoted this chapter into the foreground.
                    if (PlaybackQueueStore.state.value.chapterId == parentChapterId) {
                        PlaybackQueueStore.setNarrationAutomation(
                            stage = if (failed) NarrationAutomationStage.FAILED else NarrationAutomationStage.NEXT_READY,
                            progress = 1f,
                            message = baseMessage + warning?.let { " • ${it.take(120)}" }.orEmpty(),
                        )
                    }
                    if (failed) return@launch
                } else if (result == null) {
                    return@launch
                }
                current = loadNextChapter(
                    PlaybackQueueStore.state.value.copy(
                        sourceId = sourceId,
                        storyId = chapter.chapter.storyId,
                        chapterId = chapter.chapter.id,
                        chapterIndex = chapter.chapter.index,
                        chapterTitle = chapter.chapter.title,
                        chapterUrl = chapter.chapter.url,
                        paragraphs = chapter.paragraphs,
                        nextChapterUrl = chapter.nextChapterUrl,
                        previousChapterUrl = chapter.previousChapterUrl,
                        nextChapterPageUrl = chapter.nextChapterPageUrl,
                        nextChapterPageStartIndex = chapter.nextChapterPageStartIndex,
                    ),
                )
            }
        }
    }

    private fun stopPlayback() {
        pendingPlay = false
        pendingRecoveryState = null
        pendingForceNoSonic = false
        pendingPreviewText = null
        pendingPreviewConfig = null
        musicPreviewActive = false
        musicPreviewPlainWasPlaying = false
        musicPreviewSceneWasActive = false
        resumeAfterTransientFocusLoss = false
        cancelSpeechWatchdog()
        completionGuard.cancel()
        activeSpeechAttempt = null
        activeUtteranceId = null
        previewUtteranceId = null
        prefetchJob?.cancel()
        advanceJob?.cancel()
        sleepTimerJob?.cancel()
        initWatchdog?.let(mainHandler::removeCallbacks)
        checkpointJob?.cancel()
        narrationPlanJob?.cancel()
        narrationPrefetchJob?.cancel()
        narrationPlanningChapterId = ""
        narrationPreparedChapterId = ""
        backgroundMusicShuffleBag.clear()
        sleepTimerJob = null
        PlaybackQueueStore.setSleepTimer(null)
        ReaderSleepTimerAlarm.clear(this)
        prefetchJob = null
        advanceJob = null
        prefetchParentId = ""
        NextChapterCache.clear()
        chapterPageNavigation.clear()
        clearSonicPlayback(deleteFiles = true)
        if (::tts.isInitialized) tts.stop()
        PlaybackQueueStore.setPlaying(false)
        updateBackgroundMusic(ducked = false, pause = true)
        releaseWakeLock()
        abandonAudioFocus()
        updateMediaState()
        updateNotification()
        val snapshot = PlaybackQueueStore.state.value
        if (snapshot.chapterId.isBlank()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            checkpointJob = serviceScope.launch {
                runCatching {
                    container.libraryRepository.savePlaybackCheckpoint(
                        PlaybackCheckpointEntity(
                            sourceId = snapshot.sourceId,
                            storyId = snapshot.storyId,
                            chapterId = snapshot.chapterId,
                            chapterIndex = snapshot.chapterIndex,
                            paragraphIndex = snapshot.paragraphIndex,
                            speechChunkIndex = snapshot.speechChunkIndex,
                            wasPlaying = false,
                            activeSceneTrackId = sceneMusicController.activeTrackId,
                            nextChapterUrl = persistedNextNavigation(snapshot),
                            previousChapterUrl = snapshot.previousChapterUrl,
                            sleepTimerEndsAtMillis = ReaderSleepTimerStore.get(this@ReaderPlaybackService),
                            sessionId = playbackSessionId,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                    persistReadingPosition(snapshot)
                    persistPlaybackQueue(snapshot)
                }
                withContext(Dispatchers.Main) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun refreshVoiceAndNotification(playAfterRefresh: Boolean = false) {
        serviceScope.launch {
            if (playAfterRefresh) pendingPlay = true
            if (!applyConfiguredVoice(useStoryProfile = true)) return@launch
            withContext(Dispatchers.Main) {
                val snapshot = PlaybackQueueStore.state.value
                when {
                    playAfterRefresh && snapshot.preparationState == PlaybackPreparationState.READY -> {
                        pendingPlay = false
                        play()
                    }
                    pendingPlay && snapshot.preparationState == PlaybackPreparationState.READY -> {
                        pendingPlay = false
                        play()
                    }
                    snapshot.isPlaying -> play()
                    else -> updateNotification()
                }
            }
        }
    }

    private suspend fun applyConfiguredVoice(useStoryProfile: Boolean, skipEngineCheck: Boolean = false): Boolean {
        val settings = container.settingsRepository.snapshot()
        applyRuntimeSettings(settings)
        storyAudioSourceMode = container.storyAudioSourceModeStore.get()
        val storyId = PlaybackQueueStore.state.value.storyId
        val previousStoryAutoVoiceCastEnabled = currentStoryAutoVoiceCastEnabled
        currentStoryAutoVoiceCastEnabled = if (useStoryProfile && storyId.isNotBlank()) {
            container.narrationPlanCoordinator.shouldAutoVoiceCast(storyId)
        } else false
        currentStoryExpressiveAdjustmentEnabled = if (useStoryProfile && storyId.isNotBlank()) {
            container.narrationPlanCoordinator.expressiveAdjustmentEnabled(storyId)
        } else false
        if (previousStoryAutoVoiceCastEnabled && !currentStoryAutoVoiceCastEnabled) {
            narrationPlanJob?.cancel()
            narrationPrefetchJob?.cancel()
            narrationPlanningChapterId = ""
            narrationPreparedChapterId = ""
            if (!PlaybackQueueStore.state.value.isPlaying) pendingPlay = false
            PlaybackQueueStore.setNarrationAutomation(
                stage = NarrationAutomationStage.IDLE,
                progress = 0f,
                message = null,
            )
        }
        val profile = if (useStoryProfile && storyId.isNotBlank()) {
            container.libraryRepository.getStoryTtsProfile(storyId)
        } else null
        val desiredEngine = (profile?.enginePackage?.takeIf(String::isNotBlank)
            ?: settings.ttsEnginePackage?.takeIf(String::isNotBlank))
            ?.takeUnless(failedEnginePackages::contains)
        if (!ttsReady || (!skipEngineCheck && desiredEngine != currentEnginePackage)) {
            pendingConfigUseStoryProfile = useStoryProfile
            pendingPlay = pendingPlay || PlaybackQueueStore.state.value.isPlaying
            withContext(Dispatchers.Main) { initializeTts(desiredEngine) }
            return false
        }
        interruptionMode = settings.audioInterruptionMode
        if (hasAudioFocus || audioFocusRequest != null) abandonAudioFocus()
        audioFocusRequest = null
        val sonicEnabled = settings.sonicProcessingEnabled
        val config = RuntimeVoiceConfig(
            enginePackage = desiredEngine,
            voiceName = profile?.voiceName?.takeIf(String::isNotBlank) ?: settings.ttsVoiceName,
            languageTag = profile?.languageTag?.ifBlank { null } ?: settings.ttsLanguageTag,
            rate = if (sonicEnabled) 1f else profile?.rate ?: settings.ttsRate,
            pitch = if (sonicEnabled) 1f else profile?.pitch ?: settings.ttsPitch,
            volume = profile?.volume ?: settings.ttsVolume,
            sonicEnabled = sonicEnabled,
            sonicAccurate = settings.sonicAccurateMode,
        )
        activeBaseVoice = config
        val playbackSnapshot = PlaybackQueueStore.state.value
        val chapterId = playbackSnapshot.chapterId
        if (manualNarrationChapterId.isNotBlank() && manualNarrationChapterId != chapterId) {
            manualNarrationChapterId = ""
        }
        val voicePlanEnabled = currentStoryAutoVoiceCastEnabled || manualNarrationChapterId == chapterId
        voiceRoles = if (useStoryProfile && storyId.isNotBlank() && voicePlanEnabled) {
            container.narrationPlanCoordinator.effectiveVoiceRoles(storyId)
        } else emptyList()
        val originalChapter = if (useStoryProfile && chapterId.isNotBlank()) {
            container.libraryRepository.loadCachedChapter(chapterId)
        } else null
        val originalHash = originalChapter?.let { ChapterAiWorkflow.sha256(it.paragraphs) }
        val voicePlan = if (voicePlanEnabled && originalHash != null) {
            container.libraryRepository.getChapterTransform(chapterId, ChapterAiWorkflow.KIND_VOICE_CAST)
        } else null
        val validRuntimeUnitIds = playbackSnapshot.speechChunks.mapNotNull { it.unitId.takeIf(String::isNotBlank) }
        xpkVoiceAssignments = if (voicePlan != null && voicePlan.sourceSha256 == originalHash) {
            runCatching {
                XpkPlaybackRuntime.parseVoiceAssignments(voicePlan.transformedText, validRuntimeUnitIds)
            }.getOrDefault(emptyMap())
        } else emptyMap()
        voiceAssignments = if (voicePlan != null && voicePlan.sourceSha256 == originalHash) {
            container.libraryRepository.listVoiceAssignments(chapterId).associateBy { it.paragraphIndex }
        } else emptyMap()

        val enabledMusicTracks = if (useStoryProfile && chapterId.isNotBlank()) {
            container.libraryRepository.listEnabledSceneMusicTracks()
                .filter { AudioAssetClassifier.classify(it) == AudioAssetKind.MUSIC }
                .filter { track ->
                    !StoryAudioModeRouter.usesAiFreesound(storyAudioSourceMode) ||
                        (FreesoundImporter.soundIdFromManagedUri(track.uri) != null &&
                            FreesoundImporter.managedFileExists(applicationContext, track.uri))
                }
        } else emptyList()
        val musicSourceHash = originalChapter?.let { chapter ->
            container.narrationPlanCoordinator.musicSourceHashForPlayback(chapter, enabledMusicTracks)
        }
        val musicPlan = if (musicSourceHash != null) {
            container.libraryRepository.getChapterTransform(chapterId, ChapterAiWorkflow.KIND_SCENE_MUSIC)
        } else null
        val orderedMusicTracks = enabledMusicTracks.sortedWith(
            compareBy<SceneMusicTrackEntity> { it.orderIndex }.thenBy { it.title.lowercase() },
        )
        val previousTrackIds = backgroundMusicTracks.map(SceneMusicTrackEntity::id)
        backgroundMusicTracks = orderedMusicTracks
        if (previousTrackIds != orderedMusicTracks.map(SceneMusicTrackEntity::id)) backgroundMusicShuffleBag.clear()
        val musicPlanUsable = !StoryAudioModeRouter.usesManualLocal(storyAudioSourceMode) &&
            autoSceneMusicEnabled &&
            musicPlan != null &&
            musicPlan.sourceSha256 == musicSourceHash &&
            scenePlanMatchesCurrentSourceMode(musicPlan.transformedText)
        xpkSceneTrackByUnitId = if (musicPlanUsable) {
            runCatching {
                XpkPlaybackRuntime.parseSceneTimeline(
                    transformedText = musicPlan!!.transformedText,
                    validUnitIds = validRuntimeUnitIds,
                    validTrackIds = orderedMusicTracks.map(SceneMusicTrackEntity::id),
                )
            }.getOrDefault(emptyMap())
        } else emptyMap()
        sceneMusicCues = if (musicPlanUsable) {
            container.libraryRepository.listSceneMusicCues(chapterId)
        } else emptyList()
        sceneMusicTracks = if (hasSceneMusicPlan()) {
            orderedMusicTracks.associateBy { it.id }
        } else emptyMap()
        if (StoryAudioModeRouter.usesAiFreesound(storyAudioSourceMode)) {
            diagnostic(
                "FREESOUND_RUNTIME_MUSIC_PLAN_STATE",
                if (musicPlanUsable && hasSceneMusicPlan()) DiagnosticSeverity.INFO else DiagnosticSeverity.WARN,
                mapOf(
                    "enabledMusicTracks" to enabledMusicTracks.size.toString(),
                    "musicPlanPresent" to (musicPlan != null).toString(),
                    "sourceHashMatch" to (musicPlan != null && musicPlan.sourceSha256 == musicSourceHash).toString(),
                    "sourceModeMatch" to (musicPlan?.let { scenePlanMatchesCurrentSourceMode(it.transformedText) } == true).toString(),
                    "musicPlanUsable" to musicPlanUsable.toString(),
                    "unitAssignments" to xpkSceneTrackByUnitId.size.toString(),
                    "legacyCueRows" to sceneMusicCues.size.toString(),
                    "runtimeTracks" to sceneMusicTracks.size.toString(),
                ),
            )
        }
        activeSceneTrackId = sceneMusicController.activeTrackId
        lastSceneMusicLookupTraceState = ""
        PlaybackQueueStore.updateVoice(config.rate, config.pitch, config.volume)
        withContext(Dispatchers.Main) {
            applyRuntimeVoice(config)
            if (!hasSceneMusicPlan()) {
                if (StoryAudioModeRouter.allowsManualPlaylist(storyAudioSourceMode)) {
                    if (backgroundMusicEnabled && backgroundMusicTracks.isNotEmpty()) {
                        configureBackgroundMusic(null, false, 0f, settings.backgroundMusicDuckFactor)
                        val activeId = sceneMusicController.activeTrackId
                        if (activeId != null && backgroundMusicTracks.none { it.id == activeId }) {
                            sceneMusicController.stop(clearTrack = true)
                        }
                        if (PlaybackQueueStore.state.value.isPlaying) ensureBackgroundPlaylist(advance = false)
                        else sceneMusicController.pause()
                    } else {
                        sceneMusicController.stop(clearTrack = true)
                        activeSceneTrackId = null
                        configureBackgroundMusic(
                            settings.backgroundMusicUri,
                            settings.backgroundMusicEnabled,
                            settings.backgroundMusicVolume,
                            settings.backgroundMusicDuckFactor,
                        )
                    }
                } else {
                    sceneMusicController.stop(clearTrack = true)
                    activeSceneTrackId = null
                    configureBackgroundMusic(null, false, 0f, settings.backgroundMusicDuckFactor)
                }
            } else {
                configureBackgroundMusic(null, false, 0f, settings.backgroundMusicDuckFactor)
                val current = PlaybackQueueStore.state.value
                updateSceneMusicForUnit(current.currentUnitId, current.paragraphIndex)
            }
        }
        return true
    }

    private fun initializeTts(enginePackage: String?) {
        initWatchdog?.let(mainHandler::removeCallbacks)
        speechWatchdog?.let(mainHandler::removeCallbacks)
        completionGuard.cancel()
        if (::tts.isInitialized) {
            runCatching { tts.stop() }
            runCatching { tts.shutdown() }
        }
        currentEnginePackage = enginePackage?.takeIf(String::isNotBlank)
        ttsReady = false
        voiceSettingsReady = false
        val generation = ttsGenerationGuard.next()
        tts = if (currentEnginePackage == null) {
            TextToSpeech(this) { status -> handleTtsInit(generation, status) }
        } else {
            TextToSpeech(this, { status -> handleTtsInit(generation, status) }, currentEnginePackage)
        }
        val watchdog = Runnable {
            if (!ttsGenerationGuard.isCurrent(generation) || ttsReady) return@Runnable
            playbackHealth.chunkFailed(System.currentTimeMillis(), "TTS_INIT_TIMEOUT")
            val failed = currentEnginePackage
            if (!failed.isNullOrBlank()) {
                failedEnginePackages += failed
                transitionMessage = "Bộ máy TTS phản hồi quá chậm; đang chuyển sang mặc định."
                initializeTts(null)
            } else {
                pendingPlay = false
                PlaybackQueueStore.setPlaying(false)
                transitionMessage = "TTS mặc định không khởi tạo trong thời gian cho phép."
                updateNotification()
            }
        }
        initWatchdog = watchdog
        mainHandler.postDelayed(watchdog, PlaybackWatchdogPolicy.INIT_TIMEOUT_MILLIS)
    }

    private fun applyRuntimeVoice(config: RuntimeVoiceConfig) {
        val locale = Locale.forLanguageTag(config.languageTag.ifBlank { "vi-VN" })
        val languageResult = tts.setLanguage(locale)
        if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.getDefault())
            transitionMessage = "Ngôn ngữ ${config.languageTag} không còn khả dụng; đã dùng ngôn ngữ hệ thống."
        }
        val voices = tts.voices.orEmpty()
        val requested = config.voiceName?.let { name -> voices.firstOrNull { it.name == name } }
        val fallback = voices
            .filter { it.locale.language.equals(locale.language, ignoreCase = true) }
            .sortedWith(compareBy<android.speech.tts.Voice> { it.isNetworkConnectionRequired }.thenByDescending { it.quality })
            .firstOrNull()
        val selected = requested ?: fallback
        if (config.voiceName != null && requested == null) {
            transitionMessage = "Giọng đã chọn không còn tồn tại; đã dùng giọng ${selected?.name ?: "mặc định"}."
        }
        if (selected != null && tts.setVoice(selected) == TextToSpeech.ERROR) {
            transitionMessage = "Không dùng được giọng đã chọn; đã dùng giọng mặc định."
        }
        tts.setSpeechRate(config.rate.coerceIn(0.25f, 3.0f))
        tts.setPitch(config.pitch.coerceIn(0.5f, 2.0f))
    }

    private fun VoiceRoleEntity.toRuntimeVoice(): RuntimeVoiceConfig {
        val extra = ReferenceVoiceRoleExtras.load(this@ReaderPlaybackService, id)
        val sonic = extra.processingMethod == "sonic"
        return RuntimeVoiceConfig(
            enginePackage = enginePackage,
            voiceName = voiceName,
            languageTag = languageTag,
            rate = if (sonic) 1f else rate,
            pitch = if (sonic) 1f else pitch,
            volume = volume.coerceIn(0f, if (sonic) 2f else 1f),
            sonicSpeed = if (sonic) sonicSpeed.coerceIn(0.25f, 3f) else 1f,
            sonicPitch = if (sonic) sonicPitch.coerceIn(0.5f, 2f) else 1f,
            sonicEnabled = sonic,
            sonicAccurate = extra.sonicAccurate,
        )
    }

    private fun hasSceneMusicPlan(): Boolean = xpkSceneTrackByUnitId.isNotEmpty() || sceneMusicCues.isNotEmpty()

    private fun scenePlanMatchesCurrentSourceMode(transformedText: String): Boolean = runCatching {
        JSONObject(transformedText).optString("audio_source_mode").trim() == storyAudioSourceMode.name
    }.getOrDefault(false)

    private fun updateSceneMusicForUnit(unitId: String?, paragraphIndex: Int) {
        if (!hasSceneMusicPlan()) {
            if (StoryAudioModeRouter.allowsManualPlaylist(storyAudioSourceMode) &&
                backgroundMusicEnabled && backgroundMusicTracks.isNotEmpty()
            ) {
                ensureBackgroundPlaylist(advance = false)
            } else if (!StoryAudioModeRouter.allowsManualPlaylist(storyAudioSourceMode)) {
                sceneMusicController.stop(clearTrack = true)
                activeSceneTrackId = null
            }
            return
        }
        val canonicalPlanActive = xpkSceneTrackByUnitId.isNotEmpty()
        val legacyCue = if (canonicalPlanActive) null else sceneMusicCues.lastOrNull { it.startParagraph <= paragraphIndex }
        val requestedTrackId = if (canonicalPlanActive) unitId?.let(xpkSceneTrackByUnitId::get) else legacyCue?.trackId
        val snapshot = PlaybackQueueStore.state.value
        if (StoryAudioModeRouter.usesAiFreesound(storyAudioSourceMode)) {
            val lookupTraceState = "$canonicalPlanActive:${requestedTrackId.orEmpty()}"
            if (lookupTraceState != lastSceneMusicLookupTraceState) {
                diagnostic(
                    "FREESOUND_RUNTIME_MUSIC_LOOKUP",
                    DiagnosticSeverity.INFO,
                    mapOf(
                        "canonicalPlan" to canonicalPlanActive.toString(),
                        "requestedTrackId" to requestedTrackId.orEmpty(),
                        "unitId" to unitId.orEmpty(),
                        "paragraphIndex" to paragraphIndex.toString(),
                        "availableTracks" to sceneMusicTracks.size.toString(),
                    ),
                )
                lastSceneMusicLookupTraceState = lookupTraceState
            }
        }
        if (requestedTrackId == XpkSceneMusicParity.SILENCE_TRACK_ID) {
            if (StoryAudioModeRouter.usesAiFreesound(storyAudioSourceMode)) {
                diagnostic("FREESOUND_RUNTIME_MUSIC_SILENCE", DiagnosticSeverity.INFO)
            }
            sceneMusicController.stop(clearTrack = true)
            activeSceneTrackId = null
            transitionMessage = null
            return
        }
        val track = requestedTrackId?.let { selectedTrackId ->
            SceneMusicSelector.select(
                tracks = sceneMusicTracks.values,
                requestedTrackId = selectedTrackId,
                mood = legacyCue?.mood.orEmpty(),
                mode = sceneMusicPlaybackMode,
                recentTrackIds = recentSceneTrackIds,
                seed = "${snapshot.chapterId}:${unitId ?: paragraphIndex}:$selectedTrackId",
            )
        }
        if (requestedTrackId == null || track == null || !track.enabled) {
            if (StoryAudioModeRouter.usesAiFreesound(storyAudioSourceMode)) {
                diagnostic(
                    "FREESOUND_RUNTIME_MUSIC_MISSING",
                    DiagnosticSeverity.WARN,
                    mapOf(
                        "requestedTrackId" to requestedTrackId.orEmpty(),
                        "trackFound" to (track != null).toString(),
                        "trackEnabled" to (track?.enabled ?: false).toString(),
                        "availableTracks" to sceneMusicTracks.size.toString(),
                    ),
                )
            }
            if (canonicalPlanActive) {
                sceneMusicController.stop(clearTrack = true)
                activeSceneTrackId = null
                transitionMessage = "Không tìm thấy track_id mà kế hoạch AI yêu cầu."
            } else if (!sceneMusicContinueAcrossChapters) {
                sceneMusicController.stop(clearTrack = true)
                activeSceneTrackId = null
            } else {
                activeSceneTrackId = sceneMusicController.activeTrackId
            }
            return
        }
        backgroundPlayer?.runCatching { stop() }
        backgroundPlayer?.release()
        backgroundPlayer = null
        backgroundMusicUri = null
        if (sceneMusicController.activeTrackId == track.id && activeSceneTrackId == track.id) {
            sceneMusicController.setSpeaking(snapshot.isPlaying)
            return
        }
        val normalizationGain = if (
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
        val sceneVolume = legacyCue?.volume?.coerceIn(0f, 1f) ?: 1f
        val effectiveSceneVolume = (track.volume * sceneVolume * normalizationGain).coerceIn(0f, 1f)
        if (StoryAudioModeRouter.usesAiFreesound(storyAudioSourceMode)) {
            diagnostic(
                "FREESOUND_RUNTIME_MUSIC_PLAY",
                if (FreesoundImporter.managedFileExists(this, track.uri)) DiagnosticSeverity.INFO else DiagnosticSeverity.ERROR,
                mapOf(
                    "trackId" to track.id,
                    "soundId" to (FreesoundImporter.soundIdFromManagedUri(track.uri)?.toString() ?: ""),
                    "fileExists" to FreesoundImporter.managedFileExists(this, track.uri).toString(),
                    "normalizationVersion" to track.normalizationVersion.toString(),
                    "normalizationError" to track.normalizationError.take(160),
                    "volume" to effectiveSceneVolume.toString(),
                ),
            )
        }
        sceneMusicController.transition(
            trackId = track.id,
            uri = track.uri,
            volume = effectiveSceneVolume,
            duckFactor = backgroundMusicDuckFactor,
            crossfadeMillis = sceneMusicCrossfadeMillis,
        )
        sceneMusicController.setSpeaking(snapshot.isPlaying)
        if (activeSceneTrackId != track.id) {
            recentSceneTrackIds.remove(track.id)
            recentSceneTrackIds.addLast(track.id)
            while (recentSceneTrackIds.size > sceneMusicAvoidRepeatWindow.coerceAtLeast(0)) recentSceneTrackIds.removeFirst()
            serviceScope.launch { container.libraryRepository.markSceneMusicPlayed(track.id) }
        }
        activeSceneTrackId = track.id
    }

    private fun beginMusicPreview() {
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

    private fun trackNormalizationGain(track: SceneMusicTrackEntity): Float {
        if (track.normalizationVersion < PcmLoudnessEstimator.VERSION || track.normalizationError.isNotBlank()) return 1f
        if (!track.loudnessLufsEstimate.isFinite() || !track.peakDbfs.isFinite()) return 1f
        val gainDb = PcmLoudnessEstimator.calculateNormalization(
            track.loudnessLufsEstimate,
            track.peakDbfs,
            sceneMusicTargetLufs,
        ).gainDb
        return PcmLoudnessEstimator.gainDbToLinear(gainDb)
    }

    private fun chooseBackgroundPlaylistTrack(advance: Boolean): SceneMusicTrackEntity? {
        val tracks = backgroundMusicTracks.filter(SceneMusicTrackEntity::enabled)
        if (tracks.isEmpty()) return null
        val currentId = sceneMusicController.activeTrackId
        if (!advance) return tracks.firstOrNull { it.id == currentId } ?: tracks.first()
        if (sceneMusicPlaybackMode == SceneMusicPlaybackMode.SHUFFLE && tracks.size > 1) {
            val valid = tracks.associateBy(SceneMusicTrackEntity::id)
            while (backgroundMusicShuffleBag.isNotEmpty() &&
                (backgroundMusicShuffleBag.first() !in valid || backgroundMusicShuffleBag.first() == currentId)
            ) {
                backgroundMusicShuffleBag.removeFirst()
            }
            if (backgroundMusicShuffleBag.isEmpty()) {
                tracks.map(SceneMusicTrackEntity::id)
                    .filter { it != currentId }
                    .shuffled()
                    .forEach(backgroundMusicShuffleBag::addLast)
            }
            val nextId = if (backgroundMusicShuffleBag.isEmpty()) null else backgroundMusicShuffleBag.removeFirst()
            return nextId?.let(valid::get) ?: tracks.first()
        }
        val currentIndex = tracks.indexOfFirst { it.id == currentId }
        val nextIndex = if (currentIndex < 0) 0 else (currentIndex + 1) % tracks.size
        return tracks[nextIndex]
    }

    private fun ensureBackgroundPlaylist(advance: Boolean): Boolean {
        if (!StoryAudioModeRouter.allowsManualPlaylist(storyAudioSourceMode)) return false
        if (!backgroundMusicEnabled || backgroundMusicTracks.isEmpty() || hasSceneMusicPlan()) return false
        val track = chooseBackgroundPlaylistTrack(advance) ?: return false
        val tracks = backgroundMusicTracks.filter(SceneMusicTrackEntity::enabled)
        val current = sceneMusicController.activeTrackId
        if (!advance && current == track.id) {
            sceneMusicController.setDuckTiming(backgroundMusicAttackMillis, backgroundMusicReleaseMillis)
            sceneMusicController.keepCurrent(backgroundMusicDuckFactor)
            sceneMusicController.resume()
            activeSceneTrackId = current
            return true
        }
        sceneMusicController.setDuckTiming(backgroundMusicAttackMillis, backgroundMusicReleaseMillis)
        sceneMusicController.transition(
            trackId = track.id,
            uri = track.uri,
            volume = (track.volume * trackNormalizationGain(track)).coerceIn(0f, 1f),
            duckFactor = backgroundMusicDuckFactor,
            crossfadeMillis = if (advance) 3_000 else 420,
            looping = tracks.size == 1,
            onCompletion = if (tracks.size > 1) {
                {
                    if (StoryAudioModeRouter.allowsManualPlaylist(storyAudioSourceMode) &&
                        PlaybackQueueStore.state.value.isPlaying && backgroundMusicEnabled && !hasSceneMusicPlan()
                    ) {
                        ensureBackgroundPlaylist(advance = true)
                    }
                }
            } else null,
        )
        sceneMusicController.setSpeaking(PlaybackQueueStore.state.value.isPlaying)
        activeSceneTrackId = track.id
        serviceScope.launch { container.libraryRepository.markSceneMusicPlayed(track.id) }
        return true
    }

    private fun configureBackgroundMusic(uri: String?, enabled: Boolean, volume: Float, duckFactor: Float) {
        backgroundMusicVolume = volume.coerceIn(0f, 0.6f)
        backgroundMusicDuckFactor = duckFactor.coerceIn(0.05f, 1f)
        val desired = uri?.takeIf { enabled && it.isNotBlank() }
        if (desired == backgroundMusicUri && backgroundPlayer != null) {
            updateBackgroundMusic(ducked = PlaybackQueueStore.state.value.isPlaying)
            return
        }
        backgroundPlayer?.runCatching { stop() }
        backgroundPlayer?.release()
        backgroundPlayer = null
        backgroundMusicUri = desired
        if (desired == null) return
        val player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                setDataSource(this@ReaderPlaybackService, Uri.parse(desired))
                isLooping = true
                setVolume(backgroundMusicVolume, backgroundMusicVolume)
                setOnPreparedListener { prepared ->
                    if (PlaybackQueueStore.state.value.isPlaying) {
                        val level = backgroundMusicVolume * backgroundMusicDuckFactor
                        prepared.setVolume(level, level)
                        runCatching { prepared.start() }
                    }
                }
                setOnErrorListener { failed, _, _ ->
                    runCatching { failed.reset() }
                    transitionMessage = "Không phát được nhạc nền đã chọn."
                    updateNotification()
                    true
                }
                prepareAsync()
            }
        }.getOrElse {
            transitionMessage = "Không mở được tệp nhạc nền đã chọn."
            updateNotification()
            return
        }
        backgroundPlayer = player
    }

    private fun updateBackgroundMusic(ducked: Boolean, pause: Boolean = false) {
        val usesTrackLibrary = StoryAudioModeRouter.allowsManualPlaylist(storyAudioSourceMode) &&
            backgroundMusicEnabled && backgroundMusicTracks.isNotEmpty()
        if (hasSceneMusicPlan() || sceneMusicController.activeTrackId != null || usesTrackLibrary) {
            sceneMusicController.setDuckTiming(backgroundMusicAttackMillis, backgroundMusicReleaseMillis)
            if (pause) {
                sceneMusicController.pause()
            } else {
                if (!hasSceneMusicPlan() && sceneMusicController.activeTrackId == null && usesTrackLibrary) {
                    ensureBackgroundPlaylist(advance = false)
                }
                sceneMusicController.setSpeaking(ducked)
                sceneMusicController.resume()
            }
        }
        val player = backgroundPlayer ?: return
        runCatching {
            if (pause) {
                if (player.isPlaying) player.pause()
                return@runCatching
            }
            val level = if (ducked) backgroundMusicVolume * backgroundMusicDuckFactor else backgroundMusicVolume
            player.setVolume(level, level)
            if (!player.isPlaying && PlaybackQueueStore.state.value.isPlaying) player.start()
        }.onFailure {
            transitionMessage = "Không điều khiển được nhạc nền đã chọn."
        }
    }

    private fun scheduleSleepTimer(minutes: Int) {
        val now = System.currentTimeMillis()
        val deadline = SleepTimerPolicy.deadlineFromMinutes(now, minutes)
        if (deadline == null) {
            cancelSleepTimer()
            return
        }
        ReaderSleepTimerStore.set(this, deadline)
        ReaderSleepTimerAlarm.schedule(this, deadline)
        armSleepTimer(deadline)
        transitionMessage = "Hẹn giờ ngủ: ${minutes.coerceIn(1, SleepTimerPolicy.MAX_MINUTES)} phút"
        updateNotification()
    }

    private fun restorePersistentSleepTimer() {
        val deadline = ReaderSleepTimerStore.get(this) ?: return
        if (SleepTimerPolicy.hasExpired(deadline, System.currentTimeMillis())) {
            expireSleepTimer()
        } else {
            ReaderSleepTimerAlarm.schedule(this, deadline)
            armSleepTimer(deadline)
        }
    }

    private fun armSleepTimer(deadline: Long) {
        sleepTimerJob?.cancel()
        PlaybackQueueStore.setSleepTimer(deadline)
        val remaining = SleepTimerPolicy.remainingMillis(deadline, System.currentTimeMillis()) ?: 0L
        sleepTimerJob = serviceScope.launch {
            delay(remaining)
            withContext(Dispatchers.Main) { expireSleepTimer() }
        }
    }

    private fun expireSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        ReaderSleepTimerAlarm.clear(this)
        PlaybackQueueStore.setSleepTimer(null)
        transitionMessage = "Đã hết giờ ngủ."
        stopPlayback()
    }

    private fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        ReaderSleepTimerAlarm.clear(this)
        PlaybackQueueStore.setSleepTimer(null)
        transitionMessage = null
        updateNotification()
    }

    private fun speechAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true
        val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(speechAudioAttributes())
            .setWillPauseWhenDucked(interruptionMode == AudioInterruptionMode.PAUSE)
            .setOnAudioFocusChangeListener { change ->
                mainHandler.post { handleAudioFocusChange(change) }
            }
            .build()
            .also { audioFocusRequest = it }
        hasAudioFocus = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasAudioFocus
    }

    private fun handleAudioFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                if (resumeAfterTransientFocusLoss) {
                    resumeAfterTransientFocusLoss = false
                    play()
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasAudioFocus = false
                if (interruptionMode == AudioInterruptionMode.PAUSE) {
                    resumeAfterTransientFocusLoss = PlaybackQueueStore.state.value.isPlaying
                    pauseInternal(abandonFocus = false, preserveResumeIntent = true)
                } else {
                    resumeAfterTransientFocusLoss = false
                    transitionMessage = "Đang tiếp tục đọc khi âm thanh khác phát xen."
                    updateNotification()
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (interruptionMode == AudioInterruptionMode.PAUSE) {
                    resumeAfterTransientFocusLoss = PlaybackQueueStore.state.value.isPlaying
                    hasAudioFocus = false
                    pauseInternal(abandonFocus = false, preserveResumeIntent = true)
                } else {
                    transitionMessage = "Đang tiếp tục đọc khi âm thanh khác phát xen."
                    updateBackgroundMusic(ducked = true)
                    updateNotification()
                }
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeAfterTransientFocusLoss = false
                hasAudioFocus = false
                pauseInternal(abandonFocus = true)
            }
        }
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        hasAudioFocus = false
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val manager = getSystemService(PowerManager::class.java)
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:reader").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun updateMediaState() {
        val snapshot = PlaybackQueueStore.state.value
        val state = when {
            snapshot.chapterId.isBlank() -> PlaybackState.STATE_NONE
            snapshot.preparationState == PlaybackPreparationState.PREPARING -> PlaybackState.STATE_BUFFERING
            snapshot.isPlaying -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        mediaSession.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, snapshot.chapterTitle.ifBlank { "Nghe Truyện" })
                .putString(MediaMetadata.METADATA_KEY_ALBUM, snapshot.storyId)
                .putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, snapshot.paragraphIndex.toLong() + 1L)
                .putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, snapshot.paragraphs.size.toLong())
                .build(),
        )
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND or PlaybackState.ACTION_STOP,
                )
                .setState(state, snapshot.paragraphIndex.toLong(), if (snapshot.isPlaying) 1f else 0f)
                .build(),
        )
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val snapshot = PlaybackQueueStore.state.value
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val toggleAction = if (snapshot.isPlaying) ACTION_PAUSE else ACTION_PLAY
        val toggleLabel = if (snapshot.isPlaying) "Tạm dừng" else "Phát"
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_reader)
            .setContentTitle(snapshot.chapterTitle.ifBlank { "Nghe Truyện" })
            .setContentText(
                transitionMessage
                    ?: snapshot.preparationMessage
                    ?: snapshot.currentSpeechText?.take(120)
                    ?: "Mở ứng dụng để chọn chương cần đọc.",
            )
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(snapshot.isPlaying)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(notificationAction(ACTION_PREVIOUS, "Lùi"))
            .addAction(notificationAction(toggleAction, toggleLabel))
            .addAction(notificationAction(ACTION_NEXT, "Tiến"))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .build()
    }

    private fun notificationAction(action: String, title: String): Notification.Action {
        val pending = PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, ReaderPlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(R.drawable.ic_stat_reader, title, pending).build()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.playback_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Điều khiển đọc truyện bằng giọng nói"
                setSound(null, null)
            },
        )
    }

    override fun onDestroy() {
        pendingPlay = false
        pendingPreviewText = null
        pendingPreviewConfig = null
        musicPreviewActive = false
        musicPreviewPlainWasPlaying = false
        musicPreviewSceneWasActive = false
        resumeAfterTransientFocusLoss = false
        activeUtteranceId = null
        previewUtteranceId = null
        prefetchJob?.cancel()
        advanceJob?.cancel()
        sleepTimerJob?.cancel()
        initWatchdog?.let(mainHandler::removeCallbacks)
        speechWatchdog?.let(mainHandler::removeCallbacks)
        completionGuard.cancel()
        checkpointJob?.cancel()
        restoreJob?.cancel()
        narrationPlanJob?.cancel()
        narrationPrefetchJob?.cancel()
        chapterPageNavigation.clear()
        mediaButtonFlush?.let(mainHandler::removeCallbacks)
        mediaButtonGestures.reset()
        PlaybackQueueStore.setSleepTimer(ReaderSleepTimerStore.get(this))
        clearSonicPlayback(deleteFiles = true)
        ttsCache?.trim()
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        backgroundPlayer?.runCatching { stop() }
        backgroundPlayer?.release()
        backgroundPlayer = null
        if (::sceneMusicController.isInitialized) sceneMusicController.release()
        runCatching { unregisterReceiver(becomingNoisyReceiver) }
        PlaybackQueueStore.setPlaying(false)
        releaseWakeLock()
        abandonAudioFocus()
        if (::mediaSession.isInitialized) mediaSession.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "reader_playback"
        private const val NOTIFICATION_ID = 3011
        private const val PREFETCH_THRESHOLD = 0.75f
        private const val NARRATION_RETRY_DELAY_MS = 5_000L
        private const val MAX_NARRATION_ATTEMPTS = 3
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L
        private const val MAX_PREVIEW_TEXT_CHARS = 320
        private const val MAX_PERSISTED_QUEUE_CHAPTERS = 5
        private const val MAX_CATALOG_PAGE_HOPS = 30

        const val ACTION_PLAY = "vn.nghetruyen.action.PLAY"
        const val ACTION_PAUSE = "vn.nghetruyen.action.PAUSE"
        const val ACTION_TOGGLE = "vn.nghetruyen.action.TOGGLE"
        const val ACTION_NEXT = "vn.nghetruyen.action.NEXT"
        const val ACTION_PREVIOUS = "vn.nghetruyen.action.PREVIOUS"
        const val ACTION_FORWARD = "vn.nghetruyen.action.FORWARD"
        const val ACTION_REWIND = "vn.nghetruyen.action.REWIND"
        const val ACTION_STOP = "vn.nghetruyen.action.STOP"
        const val ACTION_REFRESH = "vn.nghetruyen.action.REFRESH"
        const val ACTION_APPLY_NARRATION_AND_PLAY = "vn.nghetruyen.action.APPLY_NARRATION_AND_PLAY"
        const val ACTION_MUSIC_PREVIEW_BEGIN = "vn.nghetruyen.action.MUSIC_PREVIEW_BEGIN"
        const val ACTION_MUSIC_PREVIEW_END = "vn.nghetruyen.action.MUSIC_PREVIEW_END"
        const val ACTION_SET_SLEEP_TIMER = "vn.nghetruyen.action.SET_SLEEP_TIMER"
        const val ACTION_CANCEL_SLEEP_TIMER = "vn.nghetruyen.action.CANCEL_SLEEP_TIMER"
        const val ACTION_SLEEP_TIMER_EXPIRED = "vn.nghetruyen.action.SLEEP_TIMER_EXPIRED"
        const val ACTION_PREVIEW = "vn.nghetruyen.action.PREVIEW"
        const val ACTION_PREVIEW_ROLE = "vn.nghetruyen.action.PREVIEW_ROLE"
        const val ACTION_MEDIA_BUTTON = "vn.nghetruyen.action.MEDIA_BUTTON"
        private const val EXTRA_MEDIA_KEY_EVENT = "media_key_event"
        private const val EXTRA_SLEEP_MINUTES = "sleep_minutes"
        private const val EXTRA_PREVIEW_TEXT = "preview_text"
        private const val EXTRA_PREVIEW_ENGINE = "preview_engine"
        private const val EXTRA_PREVIEW_VOICE = "preview_voice"
        private const val EXTRA_PREVIEW_LANGUAGE = "preview_language"
        private const val EXTRA_PREVIEW_RATE = "preview_rate"
        private const val EXTRA_PREVIEW_PITCH = "preview_pitch"
        private const val EXTRA_PREVIEW_VOLUME = "preview_volume"
        private const val EXTRA_PREVIEW_SONIC_VOLUME = "preview_sonic_volume"
        private const val EXTRA_PREVIEW_SONIC_SPEED = "preview_sonic_speed"
        private const val EXTRA_PREVIEW_SONIC_PITCH = "preview_sonic_pitch"
        private const val EXTRA_PREVIEW_SONIC_ENABLED = "preview_sonic_enabled"
        private const val EXTRA_PREVIEW_SONIC_ACCURATE = "preview_sonic_accurate"
        private const val EXTRA_PREVIEW_EXPRESSION = "preview_expression"
        private const val EXTRA_PREVIEW_EXPRESSION_STRENGTH = "preview_expression_strength"

        fun command(context: Context, action: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ReaderPlaybackService::class.java).setAction(action),
            )
        }

        fun mediaButton(context: Context, event: KeyEvent) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ReaderPlaybackService::class.java)
                    .setAction(ACTION_MEDIA_BUTTON)
                    .putExtra(EXTRA_MEDIA_KEY_EVENT, event),
            )
        }

        fun preview(context: Context, text: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ReaderPlaybackService::class.java)
                    .setAction(ACTION_PREVIEW)
                    .putExtra(EXTRA_PREVIEW_TEXT, text.take(MAX_PREVIEW_TEXT_CHARS)),
            )
        }

        fun previewRole(context: Context, text: String, draft: VoiceRoleDraft) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ReaderPlaybackService::class.java)
                    .setAction(ACTION_PREVIEW_ROLE)
                    .putExtra(EXTRA_PREVIEW_TEXT, text.take(MAX_PREVIEW_TEXT_CHARS))
                    .putExtra(EXTRA_PREVIEW_ENGINE, draft.enginePackage.orEmpty())
                    .putExtra(EXTRA_PREVIEW_VOICE, draft.voiceName.orEmpty())
                    .putExtra(EXTRA_PREVIEW_LANGUAGE, draft.languageTag)
                    .putExtra(EXTRA_PREVIEW_RATE, draft.rate)
                    .putExtra(EXTRA_PREVIEW_PITCH, draft.pitch)
                    .putExtra(EXTRA_PREVIEW_VOLUME, draft.volume)
                    .putExtra(EXTRA_PREVIEW_SONIC_VOLUME, draft.sonicVolume)
                    .putExtra(EXTRA_PREVIEW_SONIC_SPEED, draft.sonicSpeed)
                    .putExtra(EXTRA_PREVIEW_SONIC_PITCH, draft.sonicPitch)
                    .putExtra(EXTRA_PREVIEW_SONIC_ENABLED, draft.processingMethod == "sonic")
                    .putExtra(EXTRA_PREVIEW_SONIC_ACCURATE, draft.sonicAccurate)
                    .putExtra(EXTRA_PREVIEW_EXPRESSION, draft.expression.name)
                    .putExtra(EXTRA_PREVIEW_EXPRESSION_STRENGTH, draft.expressionStrength),
            )
        }

        fun setSleepTimer(context: Context, minutes: Int?) {
            val intent = Intent(context, ReaderPlaybackService::class.java).apply {
                action = if (minutes == null) ACTION_CANCEL_SLEEP_TIMER else ACTION_SET_SLEEP_TIMER
                minutes?.let { putExtra(EXTRA_SLEEP_MINUTES, it) }
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
