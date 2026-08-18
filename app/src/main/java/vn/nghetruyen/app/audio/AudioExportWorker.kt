package vn.nghetruyen.app.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import vn.nghetruyen.app.MainActivity
import vn.nghetruyen.app.NgheTruyenApplication
import vn.nghetruyen.app.R
import vn.nghetruyen.app.ai.ChapterAiWorkflow
import vn.nghetruyen.app.ai.NarrationPlanCoordinator
import vn.nghetruyen.app.ai.XpkVoiceCastPrompt
import vn.nghetruyen.app.core.model.AudioExportFormat
import vn.nghetruyen.app.core.model.DownloadState
import vn.nghetruyen.app.data.local.AudioExportJobEntity
import vn.nghetruyen.app.data.local.ChapterEntity
import vn.nghetruyen.app.data.local.SceneMusicTrackEntity
import vn.nghetruyen.app.data.local.StoryTtsProfileEntity
import vn.nghetruyen.app.data.local.VoiceRoleEntity
import vn.nghetruyen.app.data.settings.AppSettings
import vn.nghetruyen.app.playback.PronunciationProcessor
import vn.nghetruyen.app.playback.VoiceRoleResolver
import vn.nghetruyen.app.playback.VoiceExpressionProcessor
import vn.nghetruyen.app.playback.XpkPlaybackRuntime
import vn.nghetruyen.app.ui.reference.ReferenceVoiceRoleExtras
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

private data class ExportChunk(
    val text: String,
    val chapterId: String,
    val paragraphIndex: Int,
    val unitId: String,
)

private data class ExportVoiceAssignment(
    val voiceId: String,
    val speedAdjustPct: Float,
    val pitchAdjustPct: Float,
    val volumeAdjustPct: Float,
)

private data class ExportMusicScene(
    val startUnitId: String,
    val endUnitId: String,
    val trackId: String,
)

private data class ChapterPlan(
    val chapter: ChapterEntity,
    val segmentIndices: List<Int>,
    val startFrame: Long,
    val endFrameExclusive: Long,
)

private data class MusicPlan(
    val scenes: Map<String, List<ExportMusicScene>>,
    val audioDirections: Map<String, AmbienceSfxPlan>,
    val tracks: Map<String, SceneMusicTrackEntity>,
)


class AudioExportWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val container = (appContext.applicationContext as NgheTruyenApplication).container

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID).orEmpty()
        if (jobId.isBlank()) return Result.failure()
        val job = container.libraryRepository.getAudioExportJob(jobId) ?: return Result.failure()
        container.sourceDiagnostics.mark(
            name = "AUDIO_EXPORT_STARTED",
            sourceId = "audio-export",
            traceId = "audio-export:$jobId",
            severity = vn.nghetruyen.source.diagnostics.DiagnosticSeverity.INFO,
            attributes = mapOf("storyId" to job.storyId, "format" to job.outputFormat, "scope" to job.scope, "packaging" to job.packaging),
        )
        val outputFormat = runCatching { AudioExportFormat.valueOf(job.outputFormat) }.getOrDefault(AudioExportFormat.WAV)
        val packaging = runCatching { AudioExportPackaging.valueOf(job.packaging) }.getOrDefault(AudioExportPackaging.SINGLE_FILE)
        createNotificationChannel()

        val workDir = File(applicationContext.filesDir, "audio-export/$jobId")
        var completedSuccessfully = false
        return try {
            container.libraryRepository.updateAudioExportJob(
                job.copy(state = DownloadState.RUNNING.name, errorMessage = null, updatedAt = System.currentTimeMillis()),
            )
            setForeground(createForegroundInfo(jobId, job.storyTitle, outputFormat, 0, 0, "Đang chuẩn bị dữ liệu…"))

            val chapters = loadChapters(job)
            if (chapters.isEmpty()) throw IOException("Chưa có nội dung chương để xuất. Hãy mở hoặc tải truyện trước.")

            val rules = container.libraryRepository.listEnabledPronunciations()
            val settings = container.settingsRepository.snapshot()
            val roles = container.narrationPlanCoordinator.effectiveVoiceRoles(job.storyId)
            val contentByChapter = LinkedHashMap<String, vn.nghetruyen.app.core.model.ChapterContent>()
            val assignmentsByChapter = LinkedHashMap<String, Map<String, ExportVoiceAssignment>>()
            val autoVoice = container.narrationPlanCoordinator.shouldAutoVoiceCast(job.storyId)
            for (chapter in chapters) {
                val content = container.libraryRepository.loadCachedChapter(chapter.id) ?: continue
                contentByChapter[chapter.id] = content
                // One coordinator owns every active layer. Export may request music even when live
                // background music is currently off; Ambience/SFX still obey their independent switches.
                runCatching {
                    container.narrationPlanCoordinator.ensurePlans(
                        content = content,
                        voice = autoVoice,
                        music = job.includeSceneMusic,
                    )
                }
                assignmentsByChapter[chapter.id] = loadVoiceAssignments(content)
            }
            val chunks = buildChunks(chapters, contentByChapter)
            if (chunks.isEmpty()) throw IOException("Nội dung truyện không có văn bản để tổng hợp.")
            if (chunks.size > MAX_SYNTHESIS_SEGMENTS) {
                throw IOException("Bản xuất có quá nhiều UNIT. Hãy chia truyện thành nhiều khoảng chương.")
            }

            val profile = container.libraryRepository.getStoryTtsProfile(job.storyId)
            val musicPlan = loadMusicPlan(job, chapters, contentByChapter)
            val fingerprint = exportFingerprint(job, chunks, settings, profile, roles, rules, musicPlan, assignmentsByChapter)
            val fingerprintFile = File(workDir, "source.sha256")
            if (!fingerprintFile.isFile || fingerprintFile.readText().trim() != fingerprint) {
                workDir.deleteRecursively()
                workDir.mkdirs()
                fingerprintFile.writeText(fingerprint)
            } else workDir.mkdirs()

            container.libraryRepository.updateAudioExportJob(
                job.copy(
                    sourceFingerprint = fingerprint,
                    stage = "SYNTHESIZING",
                    state = DownloadState.RUNNING.name,
                    totalSegments = chunks.size,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            val normalizedSegments = synthesizeChunks(
                job = job,
                chunks = chunks,
                assignmentsByChapter = assignmentsByChapter,
                roles = roles,
                settings = settings,
                profile = profile,
                rules = rules,
                workDir = workDir,
                outputFormat = outputFormat,
            )
            val chapterPlans = buildChapterPlans(chapters, chunks, normalizedSegments)

            if (packaging == AudioExportPackaging.ONE_FILE_PER_CHAPTER) {
                exportOneFilePerChapter(
                    job = job,
                    format = outputFormat,
                    chapters = chapterPlans,
                    chunks = chunks,
                    segments = normalizedSegments,
                    musicPlan = musicPlan,
                    settings = settings,
                    workDir = workDir,
                )
            } else {
                exportSingleFile(
                    job = job,
                    format = outputFormat,
                    chapters = chapterPlans,
                    chunks = chunks,
                    segments = normalizedSegments,
                    musicPlan = musicPlan,
                    settings = settings,
                    workDir = workDir,
                )
            }

            container.libraryRepository.updateAudioExportProgress(
                jobId, chunks.size, chunks.size, DownloadState.COMPLETED, null,
            )
            completedSuccessfully = true
            container.sourceDiagnostics.mark(
                name = "AUDIO_EXPORT_COMPLETED",
                sourceId = "audio-export",
                traceId = "audio-export:$jobId",
                severity = vn.nghetruyen.source.diagnostics.DiagnosticSeverity.INFO,
                attributes = mapOf("storyId" to job.storyId, "segments" to chunks.size.toString(), "format" to outputFormat.name),
            )
            Result.success(workDataOf(KEY_DESTINATION_URI to job.destinationUri))
        } catch (cancelled: CancellationException) {
            container.sourceDiagnostics.mark(
                name = "AUDIO_EXPORT_CANCELLED",
                sourceId = "audio-export",
                traceId = "audio-export:$jobId",
                severity = vn.nghetruyen.source.diagnostics.DiagnosticSeverity.WARN,
                attributes = mapOf("storyId" to job.storyId),
            )
            withContext(NonCancellable) {
                val latest = container.libraryRepository.getAudioExportJob(jobId)
                container.libraryRepository.updateAudioExportProgress(
                    jobId,
                    latest?.completedSegments ?: 0,
                    latest?.totalSegments ?: 0,
                    DownloadState.CANCELLED,
                    "Đã hủy xuất âm thanh.",
                )
            }
            throw cancelled
        } catch (error: Throwable) {
            val message = error.message?.take(500) ?: "Không xuất được tệp âm thanh."
            container.sourceDiagnostics.mark(
                name = "AUDIO_EXPORT_RUNTIME_ERROR",
                sourceId = "audio-export",
                traceId = "audio-export:$jobId",
                severity = vn.nghetruyen.source.diagnostics.DiagnosticSeverity.ERROR,
                attributes = mapOf("storyId" to job.storyId, "error" to message, "type" to error.javaClass.simpleName, "attempt" to runAttemptCount.toString()),
            )
            val latest = container.libraryRepository.getAudioExportJob(jobId)
            val retryable = runAttemptCount < MAX_RETRIES && error is IOException && !isStopped
            container.libraryRepository.updateAudioExportProgress(
                jobId,
                latest?.completedSegments ?: 0,
                latest?.totalSegments ?: 0,
                if (retryable) DownloadState.QUEUED else DownloadState.FAILED,
                message,
            )
            if (retryable) Result.retry() else Result.failure(workDataOf(KEY_ERROR to message))
        } finally {
            if (completedSuccessfully) workDir.deleteRecursively()
            else File(workDir, "raw-partial.wav").delete()
        }
    }

    private suspend fun loadChapters(job: AudioExportJobEntity): List<ChapterEntity> =
        if (job.chapterId.isNullOrBlank()) {
            container.libraryRepository.listExportableChapters(
                job.storyId,
                job.startChapterIndex.coerceAtLeast(0),
                job.endChapterIndex.coerceAtLeast(job.startChapterIndex.coerceAtLeast(0)),
            )
        } else {
            listOfNotNull(container.libraryRepository.getChapter(job.chapterId)).filter { !it.content.isNullOrBlank() }
        }

    private fun buildChunks(
        chapters: List<ChapterEntity>,
        contentByChapter: Map<String, vn.nghetruyen.app.core.model.ChapterContent>,
    ): List<ExportChunk> = buildList {
        for (chapter in chapters) {
            val content = contentByChapter[chapter.id] ?: continue
            XpkPlaybackRuntime.buildSpeechTimeline(chapter.title, content.paragraphs).forEach { unit ->
                val text = unit.text.trim()
                if (text.isNotBlank() && unit.unitId.isNotBlank()) {
                    add(
                        ExportChunk(
                            text = text,
                            chapterId = chapter.id,
                            paragraphIndex = if (unit.unitId == "TITLE-U01") -1 else unit.paragraphIndex,
                            unitId = unit.unitId,
                        ),
                    )
                }
            }
        }
    }

    private suspend fun loadVoiceAssignments(
        content: vn.nghetruyen.app.core.model.ChapterContent,
    ): Map<String, ExportVoiceAssignment> {
        // Legacy paragraph API `listVoiceAssignments` is intentionally not used here. Export reads
        // the canonical UNIT transform so realtime playback and audiobook export share one timeline.
        val transform = container.libraryRepository.getChapterTransform(content.chapter.id, ChapterAiWorkflow.KIND_VOICE_CAST)
            ?: return emptyMap()
        if (transform.sourceSha256 != ChapterAiWorkflow.sha256(content.paragraphs)) return emptyMap()
        return runCatching {
            val root = JSONObject(transform.transformedText)
            if (root.optInt("timeline_fingerprint_version", 0) != XpkPlaybackRuntime.TIMELINE_FINGERPRINT_VERSION) return@runCatching emptyMap()
            val expected = XpkPlaybackRuntime.timelineFingerprint(content.chapter.title, content.paragraphs)
            if (root.optString("timeline_fingerprint").trim() != expected) return@runCatching emptyMap()
            val source = root.optJSONArray("assignments") ?: return@runCatching emptyMap()
            buildMap {
                for (index in 0 until source.length()) {
                    val row = source.optJSONObject(index) ?: continue
                    val id = row.optString("id").trim()
                    val voice = row.optString("voice").trim()
                    if (id.isBlank() || voice.isBlank() || containsKey(id)) continue
                    put(
                        id,
                        ExportVoiceAssignment(
                            voiceId = voice,
                            speedAdjustPct = finiteFloat(row.opt("speed_adjust_pct")),
                            pitchAdjustPct = finiteFloat(row.opt("pitch_adjust_pct")),
                            volumeAdjustPct = finiteFloat(row.opt("volume_adjust_pct")),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyMap())
    }

    private suspend fun synthesizeChunks(
        job: AudioExportJobEntity,
        chunks: List<ExportChunk>,
        assignmentsByChapter: Map<String, Map<String, ExportVoiceAssignment>>,
        roles: List<VoiceRoleEntity>,
        settings: AppSettings,
        profile: StoryTtsProfileEntity?,
        rules: List<vn.nghetruyen.app.data.local.PronunciationEntity>,
        workDir: File,
        outputFormat: AudioExportFormat,
    ): List<File> {
        val normalizedSegments = ArrayList<File>(chunks.size)
        val expressiveAdjustmentEnabled = container.narrationPlanCoordinator.expressiveAdjustmentEnabled(job.storyId)
        val baseVoice = baseVoice(settings, profile)
        val baseEngine = profile?.enginePackage?.takeIf(String::isNotBlank)
            ?: settings.ttsEnginePackage?.takeIf(String::isNotBlank)
        val roleByPromptId = roles.associateBy(XpkVoiceCastPrompt::promptVoiceId)
        var synthesizer: TtsFileSynthesizer? = null
        var activeEngine: String? = null
        var configured: TtsSynthesisVoice? = null
        try {
            chunks.forEachIndexed { index, chunk ->
                if (isStopped) throw CancellationException("Đã hủy xuất âm thanh.")
                val normalizedOutput = File(workDir, "pcm-${index.toString().padStart(6, '0')}.wav")
                val reusable = normalizedOutput.isFile && runCatching { WaveFileAssembler.inspect(normalizedOutput) }.isSuccess
                if (!reusable) {
                    val assigned = assignmentsByChapter[chunk.chapterId]?.get(chunk.unitId)
                    val assignedRole = assigned?.voiceId?.let(roleByPromptId::get)
                    val resolved = if (assignedRole != null) {
                        vn.nghetruyen.app.playback.ResolvedVoiceRole(assignedRole, chunk.text)
                    } else VoiceRoleResolver.resolve(chunk.text, roles)
                    val role = resolved.role
                    val expression = VoiceExpressionProcessor.resolve(resolved.spokenText, role)
                    val roleVoice = role?.toSynthesisVoice() ?: baseVoice
                    val aiRateMultiplier = if (expressiveAdjustmentEnabled) {
                        1f + (assigned?.speedAdjustPct ?: 0f).coerceIn(-100f, 100f) / 100f
                    } else 1f
                    val aiPitchMultiplier = if (expressiveAdjustmentEnabled) {
                        1f + (assigned?.pitchAdjustPct ?: 0f).coerceIn(-100f, 100f) / 100f
                    } else 1f
                    val aiVolumeMultiplier = if (expressiveAdjustmentEnabled) {
                        1f + (assigned?.volumeAdjustPct ?: 0f).coerceIn(-100f, 100f) / 100f
                    } else 1f
                    val voice = roleVoice.copy(
                        rate = (roleVoice.rate * expression.rateMultiplier * aiRateMultiplier).coerceIn(0.25f, 3f),
                        pitch = (roleVoice.pitch * expression.pitchMultiplier * aiPitchMultiplier).coerceIn(0.5f, 2f),
                    )
                    val desiredEngine = role?.enginePackage?.takeIf(String::isNotBlank) ?: baseEngine
                    if (synthesizer == null || desiredEngine != activeEngine) {
                        synthesizer?.close()
                        synthesizer = TtsFileSynthesizer(applicationContext, desiredEngine)
                        activeEngine = desiredEngine
                        configured = null
                    }
                    if (voice != configured) {
                        synthesizer!!.configure(voice)
                        configured = voice
                    }
                    val spoken = PronunciationProcessor.apply(expression.text, rules).ifBlank { expression.text }
                    val rawOutput = File(workDir, "raw-${index.toString().padStart(6, '0')}.wav")
                    val pcmOutput = File(workDir, "gain-${index.toString().padStart(6, '0')}.wav")
                    synthesizer!!.synthesize(spoken, rawOutput, "${job.id}:$index")
                    val roleExtra = role?.let { ReferenceVoiceRoleExtras.load(applicationContext, it.id) }
                    val useSonic = roleExtra?.processingMethod?.equals("sonic") ?: settings.sonicProcessingEnabled
                    val accurateSonic = roleExtra?.sonicAccurate ?: settings.sonicAccurateMode
                    val volume = ((role?.volume ?: profile?.volume ?: settings.ttsVolume) * expression.volumeMultiplier * aiVolumeMultiplier)
                        .coerceIn(0f, if (useSonic) 2f else 1f)
                    Pcm16WaveConverter.convert(rawOutput, pcmOutput, volume)
                    val sonicSpeed = if (useSonic) {
                        ((role?.sonicSpeed ?: settings.sonicDefaultSpeed) * expression.sonicSpeedMultiplier).coerceIn(0.25f, 3f)
                    } else 1f
                    val sonicPitch = if (useSonic) {
                        ((role?.sonicPitch ?: settings.sonicDefaultPitch) * expression.sonicPitchMultiplier).coerceIn(0.5f, 2f)
                    } else 1f
                    if (useSonic) {
                        SonicPcmProcessor.process(pcmOutput, normalizedOutput, sonicSpeed, sonicPitch, accurateSonic)
                    } else {
                        pcmOutput.copyTo(normalizedOutput, overwrite = true)
                    }
                    rawOutput.delete()
                    pcmOutput.delete()
                }
                normalizedSegments += normalizedOutput
                val completed = index + 1
                container.libraryRepository.updateAudioExportProgress(job.id, completed, chunks.size, DownloadState.RUNNING, null)
                container.sourceDiagnostics.mark(
                    name = "AUDIO_EXPORT_SEGMENT_COMPLETED",
                    sourceId = "audio-export",
                    traceId = "audio-export:${job.id}",
                    attributes = mapOf("segment" to completed.toString(), "total" to chunks.size.toString(), "reused" to reusable.toString(), "unitId" to chunk.unitId),
                )
                setProgress(workDataOf(KEY_COMPLETED to completed, KEY_TOTAL to chunks.size))
                setForeground(
                    createForegroundInfo(
                        job.id,
                        job.storyTitle,
                        outputFormat,
                        completed,
                        chunks.size,
                        if (reusable) "Khôi phục UNIT $completed/${chunks.size}" else "Đang tổng hợp UNIT $completed/${chunks.size}",
                    ),
                )
            }
        } finally {
            synthesizer?.close()
        }
        return normalizedSegments
    }

    private fun buildChapterPlans(
        chapters: List<ChapterEntity>,
        chunks: List<ExportChunk>,
        segments: List<File>,
    ): List<ChapterPlan> {
        val starts = LongArray(segments.size + 1)
        for (index in segments.indices) {
            val wave = WaveFileAssembler.inspect(segments[index])
            starts[index + 1] = starts[index] + wave.dataLength / wave.blockAlign
        }
        return chapters.mapNotNull { chapter ->
            val indices = chunks.indices.filter { chunks[it].chapterId == chapter.id }
            if (indices.isEmpty()) null else ChapterPlan(
                chapter = chapter,
                segmentIndices = indices,
                startFrame = starts[indices.first()],
                endFrameExclusive = starts[indices.last() + 1],
            )
        }
    }

    private suspend fun loadMusicPlan(
        job: AudioExportJobEntity,
        chapters: List<ChapterEntity>,
        contentByChapter: Map<String, vn.nghetruyen.app.core.model.ChapterContent>,
    ): MusicPlan {
        val scenes = linkedMapOf<String, List<ExportMusicScene>>()
        val audioDirections = linkedMapOf<String, AmbienceSfxPlan>()

        for (chapter in chapters) {
            val content = contentByChapter[chapter.id] ?: continue
            if (job.includeSceneMusic) {
                val transform = container.libraryRepository.getChapterTransform(chapter.id, ChapterAiWorkflow.KIND_SCENE_MUSIC)
                val chapterScenes = runCatching {
                    if (transform == null) return@runCatching emptyList()
                    val root = JSONObject(transform.transformedText)
                    val expected = XpkPlaybackRuntime.timelineFingerprint(content.chapter.title, content.paragraphs)
                    if (root.optInt("timeline_fingerprint_version", 0) != XpkPlaybackRuntime.TIMELINE_FINGERPRINT_VERSION ||
                        root.optString("timeline_fingerprint").trim() != expected
                    ) return@runCatching emptyList()
                    val array = root.optJSONArray("music_scenes") ?: return@runCatching emptyList()
                    buildList {
                        for (index in 0 until array.length()) {
                            val row = array.optJSONObject(index) ?: continue
                            val start = row.optString("start_id").trim()
                            val end = row.optString("end_id").trim()
                            val track = row.optString("track_id").trim()
                            if (start.isNotBlank() && end.isNotBlank() && track.isNotBlank()) {
                                add(ExportMusicScene(start, end, track))
                            }
                        }
                    }
                }.getOrDefault(emptyList())
                if (chapterScenes.isNotEmpty()) scenes[chapter.id] = chapterScenes
            }
            container.narrationPlanCoordinator.loadAudioDirectionPlan(content)?.let { plan ->
                if (plan.ambienceScenes.isNotEmpty() || plan.soundEffectCues.isNotEmpty()) {
                    audioDirections[chapter.id] = plan
                }
            }
        }

        val ids = buildSet {
            scenes.values.flatten().forEach { add(it.trackId) }
            audioDirections.values.forEach { plan ->
                plan.ambienceScenes.forEach { add(it.ambienceId) }
                plan.soundEffectCues.forEach { add(it.effectId) }
            }
        }
        val tracks = ids.mapNotNull { id -> container.libraryRepository.getSceneMusicTrack(id)?.takeIf { it.enabled } }
            .associateBy(SceneMusicTrackEntity::id)
        return MusicPlan(scenes, audioDirections, tracks)
    }

    private suspend fun exportSingleFile(
        job: AudioExportJobEntity,
        format: AudioExportFormat,
        chapters: List<ChapterPlan>,
        chunks: List<ExportChunk>,
        segments: List<File>,
        musicPlan: MusicPlan,
        settings: AppSettings,
        workDir: File,
    ) {
        updateStage(job.id, "ASSEMBLING")
        val narration = File(workDir, "combined-narration.wav")
        if (!validWave(narration)) WaveFileAssembler.assemble(segments, narration)
        val mixed = mixIfRequested(job, chapters, chunks, segments, narration, musicPlan, settings, workDir, "combined")
        val wave = WaveFileAssembler.inspect(mixed)
        val markers = if (job.chapterMarkers && format == AudioExportFormat.MP3) chapters.map { chapter ->
            Id3v23Writer.Chapter(
                title = chapter.chapter.title,
                startTimeMs = chapter.startFrame * 1_000L / wave.sampleRate,
                endTimeMs = chapter.endFrameExclusive * 1_000L / wave.sampleRate,
            )
        } else emptyList()
        val finalFile = encode(
            sourceWav = mixed,
            format = format,
            target = File(workDir, "combined.${format.extension}"),
            metadata = Id3v23Writer.Metadata(
                title = if (job.chapterId.isNullOrBlank()) job.storyTitle else chapters.firstOrNull()?.chapter?.title.orEmpty(),
                artist = job.author,
                album = job.storyTitle,
                chapters = markers,
            ),
            job = job,
        )
        setForeground(createForegroundInfo(job.id, job.storyTitle, format, job.totalSegments, job.totalSegments, "Đang ghi tệp ${format.name}…"))
        writeDestination(Uri.parse(job.destinationUri), finalFile, format)
    }

    private suspend fun exportOneFilePerChapter(
        job: AudioExportJobEntity,
        format: AudioExportFormat,
        chapters: List<ChapterPlan>,
        chunks: List<ExportChunk>,
        segments: List<File>,
        musicPlan: MusicPlan,
        settings: AppSettings,
        workDir: File,
    ) {
        val treeUri = Uri.parse(job.destinationUri)
        chapters.forEachIndexed { chapterOffset, plan ->
            if (isStopped) throw CancellationException("Đã hủy xuất âm thanh.")
            updateStage(job.id, "CHAPTER_${chapterOffset + 1}_OF_${chapters.size}")
            setForeground(
                createForegroundInfo(
                    job.id,
                    job.storyTitle,
                    format,
                    chapterOffset,
                    chapters.size,
                    "Đang xuất chương ${chapterOffset + 1}/${chapters.size}",
                ),
            )
            val chapterSegments = plan.segmentIndices.map(segments::get)
            val narration = File(workDir, "chapter-${plan.chapter.chapterIndex.toString().padStart(6, '0')}-narration.wav")
            if (!validWave(narration)) WaveFileAssembler.assemble(chapterSegments, narration)
            val localChunks = plan.segmentIndices.map(chunks::get)
            val mixed = mixIfRequested(
                job = job,
                chapters = listOf(
                    plan.copy(
                        startFrame = 0L,
                        endFrameExclusive = WaveFileAssembler.inspect(narration).dataLength / WaveFileAssembler.inspect(narration).blockAlign,
                    ),
                ),
                chunks = localChunks,
                segments = chapterSegments,
                narration = narration,
                musicPlan = musicPlan,
                settings = settings,
                workDir = workDir,
                filePrefix = "chapter-${plan.chapter.chapterIndex.toString().padStart(6, '0')}",
            )
            val finalFile = encode(
                sourceWav = mixed,
                format = format,
                target = File(workDir, "chapter-${plan.chapter.chapterIndex.toString().padStart(6, '0')}.${format.extension}"),
                metadata = Id3v23Writer.Metadata(
                    title = plan.chapter.title,
                    artist = job.author,
                    album = job.storyTitle,
                ),
                job = job,
            )
            val name = "${(plan.chapter.chapterIndex + 1).toString().padStart(5, '0')}-${safeFileName(plan.chapter.title)}.${format.extension}"
            val destination = findOrCreateDocument(treeUri, format.mimeType, name)
            writeDestination(destination, finalFile, format)
        }
    }

    private suspend fun mixIfRequested(
        job: AudioExportJobEntity,
        chapters: List<ChapterPlan>,
        chunks: List<ExportChunk>,
        segments: List<File>,
        narration: File,
        musicPlan: MusicPlan,
        settings: AppSettings,
        workDir: File,
        filePrefix: String,
    ): File {
        val directionSettings = AudioDirectionPreferences.currentSnapshot()
        val hasMusic = job.includeSceneMusic && musicPlan.scenes.isNotEmpty()
        val hasDirection = (directionSettings.ambienceEnabled || directionSettings.soundEffectsEnabled) &&
            musicPlan.audioDirections.isNotEmpty()
        if ((!hasMusic && !hasDirection) || musicPlan.tracks.isEmpty()) return narration

        updateStage(job.id, "MIXING_AUDIO_DIRECTOR")
        val reference = WaveFileAssembler.inspect(narration)
        val starts = LongArray(segments.size + 1)
        for (index in segments.indices) {
            val wave = WaveFileAssembler.inspect(segments[index])
            starts[index + 1] = starts[index] + wave.dataLength / wave.blockAlign
        }
        val decoded = mutableMapOf<String, File>()
        val layers = mutableListOf<SceneMixLayer>()
        val unitIndex = chunks.withIndex().associate { (index, chunk) -> "${chunk.chapterId}\u0000${chunk.unitId}" to index }

        fun decode(track: SceneMusicTrackEntity): File = decoded.getOrPut(track.id) {
            File(workDir, "audio-${safeFileName(track.id)}-${reference.sampleRate}-${reference.channelCount}.wav").also { target ->
                if (!validWave(target)) {
                    AndroidAudioTrackDecoder.decodeToWave(
                        context = applicationContext,
                        uri = Uri.parse(track.uri),
                        targetSampleRate = reference.sampleRate.toInt(),
                        targetChannels = reference.channelCount,
                        destination = target,
                    )
                }
            }
        }

        fun normalizationGain(track: SceneMusicTrackEntity, requestedTarget: Float): Float = if (
            PcmLoudnessEstimator.isReady(
                version = track.normalizationVersion,
                error = track.normalizationError,
                loudnessLufs = track.loudnessLufsEstimate,
                targetLufs = requestedTarget,
                storedTargetLufs = track.normalizationTargetLufs,
                gainDb = track.normalizationGainDb,
            )
        ) {
            PcmLoudnessEstimator.gainDbToLinear(track.normalizationGainDb)
        } else 1f

        fun interval(chapterId: String, startUnitId: String, endUnitId: String, chapter: ChapterPlan): Pair<Long, Long>? {
            val startIndex = unitIndex["$chapterId\u0000$startUnitId"] ?: return null
            val endIndex = unitIndex["$chapterId\u0000$endUnitId"] ?: return null
            if (endIndex < startIndex) return null
            val startFrame = starts[startIndex].coerceAtLeast(chapter.startFrame)
            val endFrame = starts[endIndex + 1].coerceAtMost(chapter.endFrameExclusive)
            return if (endFrame > startFrame) startFrame to endFrame else null
        }

        for (chapter in chapters) {
            if (job.includeSceneMusic) {
                musicPlan.scenes[chapter.chapter.id].orEmpty().forEach { scene ->
                    val track = musicPlan.tracks[scene.trackId]
                        ?.takeIf { AudioAssetClassifier.classify(it) == AudioAssetKind.MUSIC }
                        ?: return@forEach
                    val (layerStart, layerEnd) = interval(
                        chapter.chapter.id,
                        scene.startUnitId,
                        scene.endUnitId,
                        chapter,
                    ) ?: return@forEach
                    val gain = track.volume.coerceIn(0f, 1f) *
                        normalizationGain(track, settings.sceneMusicTargetLufs) *
                        settings.backgroundMusicDuckFactor.coerceIn(0.05f, 1f)
                    layers += SceneMixLayer(
                        sourceWav = decode(track),
                        startFrame = layerStart,
                        endFrameExclusive = layerEnd,
                        volume = gain.coerceIn(0f, 1f),
                        fadeFrames = (reference.sampleRate * settings.sceneMusicCrossfadeMillis.coerceIn(0, 8_000) / 1_000L).toInt(),
                        looping = true,
                    )
                }
            }

            val audioPlan = musicPlan.audioDirections[chapter.chapter.id] ?: continue
            if (directionSettings.ambienceEnabled) {
                audioPlan.ambienceScenes.forEach { scene ->
                    val track = musicPlan.tracks[scene.ambienceId]
                        ?.takeIf { AudioAssetClassifier.classify(it) == AudioAssetKind.AMBIENCE }
                        ?: return@forEach
                    val (layerStart, layerEnd) = interval(
                        chapter.chapter.id,
                        scene.startUnitId,
                        scene.endUnitId,
                        chapter,
                    ) ?: return@forEach
                    val gain = directionSettings.ambienceMasterVolume *
                        track.volume.coerceIn(0f, 1f) *
                        normalizationGain(track, track.normalizationTargetLufs) *
                        settings.backgroundMusicDuckFactor.coerceIn(0.05f, 1f)
                    layers += SceneMixLayer(
                        sourceWav = decode(track),
                        startFrame = layerStart,
                        endFrameExclusive = layerEnd,
                        volume = gain.coerceIn(0f, 1f),
                        fadeFrames = (reference.sampleRate * 600L / 1_000L).toInt(),
                        looping = true,
                    )
                }
            }
            if (directionSettings.soundEffectsEnabled) {
                audioPlan.soundEffectCues.forEach { cue ->
                    val track = musicPlan.tracks[cue.effectId]
                        ?.takeIf { AudioAssetClassifier.classify(it) == AudioAssetKind.SFX }
                        ?: return@forEach
                    val startIndex = unitIndex["${chapter.chapter.id}\u0000${cue.unitId}"] ?: return@forEach
                    val layerStart = starts[startIndex].coerceAtLeast(chapter.startFrame)
                    if (layerStart >= chapter.endFrameExclusive) return@forEach
                    val gain = directionSettings.soundEffectsMasterVolume *
                        track.volume.coerceIn(0f, 1f) *
                        normalizationGain(track, track.normalizationTargetLufs)
                    layers += SceneMixLayer(
                        sourceWav = decode(track),
                        startFrame = layerStart,
                        endFrameExclusive = chapter.endFrameExclusive,
                        volume = gain.coerceIn(0f, 1f),
                        fadeFrames = (reference.sampleRate * 8L / 1_000L).toInt(),
                        looping = false,
                    )
                }
            }
        }
        if (layers.isEmpty()) return narration
        val mixed = File(workDir, "$filePrefix-mixed.wav")
        if (!validWave(mixed)) Pcm16SceneMixer.mix(narration, layers, mixed)
        return mixed
    }

    private suspend fun encode(
        sourceWav: File,
        format: AudioExportFormat,
        target: File,
        metadata: Id3v23Writer.Metadata,
        job: AudioExportJobEntity,
    ): File = when (format) {
        AudioExportFormat.WAV -> sourceWav
        AudioExportFormat.M4A -> target.also {
            updateStage(job.id, "ENCODING_M4A")
            if (!it.isFile || it.length() == 0L) M4aAacEncoder.encode(sourceWav, it, bitrateFor(sourceWav))
        }
        AudioExportFormat.MP3 -> target.also {
            updateStage(job.id, "ENCODING_MP3")
            if (!it.isFile || it.length() == 0L) Mp3LameEncoder.encode(sourceWav, it, metadata)
        }
    }

    private suspend fun updateStage(jobId: String, stage: String) {
        val current = container.libraryRepository.getAudioExportJob(jobId) ?: return
        container.libraryRepository.updateAudioExportJob(current.copy(stage = stage, updatedAt = System.currentTimeMillis()))
    }

    private fun validWave(file: File): Boolean = file.isFile && runCatching { WaveFileAssembler.inspect(file) }.isSuccess

    private fun baseVoice(settings: AppSettings, profile: StoryTtsProfileEntity?): TtsSynthesisVoice = TtsSynthesisVoice(
        voiceName = profile?.voiceName?.takeIf(String::isNotBlank) ?: settings.ttsVoiceName,
        languageTag = profile?.languageTag?.ifBlank { null } ?: settings.ttsLanguageTag,
        rate = profile?.rate ?: settings.ttsRate,
        pitch = profile?.pitch ?: settings.ttsPitch,
    )

    private fun VoiceRoleEntity.toSynthesisVoice(): TtsSynthesisVoice = TtsSynthesisVoice(
        voiceName = voiceName,
        languageTag = languageTag,
        rate = rate,
        pitch = pitch,
    )

    private fun exportFingerprint(
        job: AudioExportJobEntity,
        chunks: List<ExportChunk>,
        settings: AppSettings,
        profile: StoryTtsProfileEntity?,
        roles: List<VoiceRoleEntity>,
        pronunciationRules: List<vn.nghetruyen.app.data.local.PronunciationEntity>,
        musicPlan: MusicPlan,
        assignmentsByChapter: Map<String, Map<String, ExportVoiceAssignment>>,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: String) {
            digest.update(value.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        val direction = AudioDirectionPreferences.currentSnapshot()
        add(
            listOf(
                job.outputFormat,
                job.scope,
                job.startChapterIndex,
                job.endChapterIndex,
                job.includeSceneMusic,
                job.packaging,
                job.chapterMarkers,
                direction.ambienceEnabled,
                direction.soundEffectsEnabled,
                direction.ambienceMasterVolume,
                direction.soundEffectsMasterVolume,
            ).joinToString("|"),
        )
        add(
            listOf(
                settings.ttsEnginePackage, settings.ttsVoiceName, settings.ttsLanguageTag,
                settings.ttsRate, settings.ttsPitch, settings.ttsVolume, settings.sonicProcessingEnabled,
                settings.sonicDefaultSpeed, settings.sonicDefaultPitch, settings.sceneMusicTargetLufs,
                settings.backgroundMusicDuckFactor,
            ).joinToString("|"),
        )
        pronunciationRules.sortedBy { it.original }.forEach {
            add(listOf(it.original, it.replacement, it.enabled).joinToString("|"))
        }
        add(listOf(profile?.enginePackage, profile?.voiceName, profile?.languageTag, profile?.rate, profile?.pitch, profile?.volume).joinToString("|"))
        roles.sortedBy { it.id }.forEach {
            val extra = ReferenceVoiceRoleExtras.load(applicationContext, it.id)
            add(
                listOf(
                    it.id, it.enginePackage, it.voiceName, it.languageTag, it.rate, it.pitch, it.volume,
                    it.expression, it.expressionStrength, it.sonicSpeed, it.sonicPitch, it.enabled,
                    extra.processingMethod, extra.sonicAccurate,
                ).joinToString("|"),
            )
        }
        chunks.forEach { add("${it.chapterId}|${it.unitId}|${it.paragraphIndex}|${it.text}") }
        assignmentsByChapter.toSortedMap().forEach { (chapterId, assignments) ->
            assignments.toSortedMap().forEach { (unitId, assignment) ->
                add("V|$chapterId|$unitId|${assignment.voiceId}|${assignment.speedAdjustPct}|${assignment.pitchAdjustPct}|${assignment.volumeAdjustPct}")
            }
        }
        musicPlan.scenes.toSortedMap().forEach { (chapterId, scenes) ->
            scenes.forEach { add("M|$chapterId|${it.startUnitId}|${it.endUnitId}|${it.trackId}") }
        }
        musicPlan.audioDirections.toSortedMap().forEach { (chapterId, plan) ->
            plan.ambienceScenes.forEach { add("A|$chapterId|${it.startUnitId}|${it.endUnitId}|${it.ambienceId}") }
            plan.soundEffectCues.forEach { add("S|$chapterId|${it.unitId}|${it.effectId}") }
        }
        musicPlan.tracks.toSortedMap().values.forEach {
            add(
                listOf(
                    it.id, it.uri, it.volume, it.enabled, it.loudnessLufsEstimate,
                    it.normalizationTargetLufs, it.normalizationGainDb, it.normalizationVersion, it.normalizationError,
                    it.playCount, it.lastPlayedAt, it.orderIndex, it.updatedAt,
                ).joinToString("|"),
            )
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun finiteFloat(value: Any?): Float {
        val parsed = when (value) {
            is Number -> value.toFloat()
            null -> 0f
            else -> value.toString().trim().replace(',', '.').toFloatOrNull() ?: 0f
        }
        return if (parsed.isFinite()) parsed else 0f
    }

    private fun bitrateFor(wav: File): Int = when (WaveFileAssembler.inspect(wav).channelCount) {
        1 -> 64_000
        else -> 128_000
    }

    private suspend fun writeDestination(destination: Uri, source: File, format: AudioExportFormat) = withContext(Dispatchers.IO) {
        val resolver = applicationContext.contentResolver
        resolver.openOutputStream(destination, "wt")?.use { output ->
            source.inputStream().buffered().use { input -> input.copyTo(output, COPY_BUFFER_BYTES) }
        } ?: throw IOException("Không mở được tệp đích để ghi ${format.name}.")
    }

    private fun findOrCreateDocument(treeUri: Uri, mimeType: String, displayName: String): Uri {
        val resolver = applicationContext.contentResolver
        val parentId = DocumentsContract.getTreeDocumentId(treeUri)
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (cursor.getString(nameColumn) == displayName) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idColumn))
                }
            }
        }
        return DocumentsContract.createDocument(resolver, parent, mimeType, displayName)
            ?: throw IOException("Không tạo được tệp $displayName trong thư mục đã chọn.")
    }

    private fun safeFileName(value: String): String {
        val ascii = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(80)
        return ascii.ifBlank { "chuong" }
    }

    private fun createForegroundInfo(
        jobId: String,
        storyTitle: String,
        format: AudioExportFormat,
        completed: Int,
        total: Int,
        stage: String,
    ): ForegroundInfo {
        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val contentIntent = PendingIntent.getActivity(
            applicationContext,
            jobId.hashCode(),
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_reader)
            .setContentTitle("Xuất ${format.name}: ${storyTitle.take(70)}")
            .setContentText(stage)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(total == 0 || completed < total)
            .setProgress(total.coerceAtLeast(0), completed.coerceAtLeast(0), total <= 0)
            .addAction(Notification.Action.Builder(R.drawable.ic_stat_reader, "Hủy", cancelIntent).build())
            .build()
        return ForegroundInfo(
            NOTIFICATION_ID_BASE + (jobId.hashCode() and 0x0fff),
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
        )
    }

    private fun createNotificationChannel() {
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Xuất sách nói", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        const val KEY_JOB_ID = "audio_export_job_id"
        const val KEY_COMPLETED = "completed"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"
        const val KEY_DESTINATION_URI = "destination_uri"
        private const val CHANNEL_ID = "audio_export"
        private const val NOTIFICATION_ID_BASE = 9_200
        private const val MAX_RETRIES = 1
        private const val MAX_SYNTHESIS_SEGMENTS = 20_000
        private const val COPY_BUFFER_BYTES = 64 * 1024
    }
}
