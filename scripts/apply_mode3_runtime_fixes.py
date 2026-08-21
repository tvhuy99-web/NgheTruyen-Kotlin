from pathlib import Path


def read(path: str) -> str:
    return Path(path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8")


def replace_once(path: str, old: str, new: str) -> None:
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected exactly 1 match, found {count}\n--- needle ---\n{old[:800]}")
    write(path, text.replace(old, new, 1))


# 1) Compact, truthful Mode-3 status formatting shared by manual/automatic/prefetch flows.
write(
    "app/src/main/java/vn/nghetruyen/app/playback/FreesoundPlaybackStatusFormatter.kt",
    '''package vn.nghetruyen.app.playback

internal object FreesoundPlaybackStatusFormatter {
    fun format(
        resultPresent: Boolean,
        downloadedAssets: Int,
        reusedAssets: Int,
        retryRequired: Boolean,
        audioLayersEnabled: Boolean,
    ): String {
        if (!audioLayersEnabled) return ""
        val parts = buildList {
            val downloaded = downloadedAssets.coerceAtLeast(0)
            val reused = reusedAssets.coerceAtLeast(0)
            if (downloaded > 0) add("$downloaded tải mới")
            if (reused > 0) add("$reused bộ nhớ tạm")
            if (!resultPresent) add("chưa có kế hoạch âm thanh")
            else if (retryRequired) add("còn thiếu")
        }
        return if (parts.isEmpty()) "" else " • ${parts.joinToString(" • ")}"
    }
}
''',
)

write(
    "app/src/main/java/vn/nghetruyen/app/playback/NarrationAutomationStatusFormatter.kt",
    '''package vn.nghetruyen.app.playback

internal object NarrationAutomationStatusFormatter {
    fun ready(
        assignmentCount: Int,
        resultPresent: Boolean,
        downloadedAssets: Int,
        reusedAssets: Int,
        retryRequired: Boolean,
        audioLayersEnabled: Boolean,
        prefix: String? = null,
        beginPlayback: Boolean = false,
        warning: String? = null,
    ): String = buildString {
        prefix?.trim()?.trimEnd('.')?.takeIf(String::isNotBlank)?.let {
            append(it).append(". ")
        }
        append("Đã phân vai xong ").append(assignmentCount.coerceAtLeast(0)).append(" mục")
        append(
            FreesoundPlaybackStatusFormatter.format(
                resultPresent = resultPresent,
                downloadedAssets = downloadedAssets,
                reusedAssets = reusedAssets,
                retryRequired = retryRequired,
                audioLayersEnabled = audioLayersEnabled,
            ),
        )
        if (beginPlayback) append(". Đang bắt đầu phát")
        warning?.trim()?.takeIf(String::isNotBlank)?.let {
            append(" • Cảnh báo: ").append(it.take(140))
        }
        append('.')
    }
}
''',
)

# 2) Correct new-download vs cache accounting and cap OGG+MP3 to one total deadline.
importer = "app/src/main/java/vn/nghetruyen/app/freesound/FreesoundImporter.kt"
replace_once(
    importer,
    "import java.io.File\nimport java.util.Locale",
    "import java.io.File\nimport java.io.InterruptedIOException\nimport java.util.Locale",
)
replace_once(
    importer,
    '''data class FreesoundImportResult(
    val trackId: String,
    val uri: String,
    val title: String,
    val downloadElapsedMs: Long = 0L,
    val normalizationElapsedMs: Long = 0L,
)
''',
    '''data class FreesoundImportResult(
    val trackId: String,
    val uri: String,
    val title: String,
    val downloadElapsedMs: Long = 0L,
    val normalizationElapsedMs: Long = 0L,
    val downloadedNewFile: Boolean = true,
    val reusedExistingFile: Boolean = false,
)

class FreesoundImportException(
    message: String,
    val retryable: Boolean = false,
    val downloadedNewFile: Boolean = false,
    val trackId: String? = null,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
''',
)
replace_once(
    importer,
    '''                    downloadElapsedMs = 0L,
                    normalizationElapsedMs = normalizationElapsedMs,
                ),
''',
    '''                    downloadElapsedMs = 0L,
                    normalizationElapsedMs = normalizationElapsedMs,
                    downloadedNewFile = false,
                    reusedExistingFile = true,
                ),
''',
)
replace_once(
    importer,
    '''            if (shouldPreserveImportedFile(error)) {
                // The download is already valid. Keep it plus the marker so a later Mode-3
                // resolve can resume normalization instead of downloading the same sound again.
                Result.failure(error)
            } else {
''',
    '''            if (shouldPreserveImportedFile(error)) {
                // The file existed before this attempt. Keep it and report retryability without
                // misclassifying a normalization resume as a newly downloaded asset.
                Result.failure(
                    FreesoundImportException(
                        message = error.message ?: "Chuẩn hóa tệp Freesound cần thử lại.",
                        retryable = true,
                        downloadedNewFile = false,
                        trackId = track.id,
                        cause = error,
                    ),
                )
            } else {
''',
)
replace_once(
    importer,
    '''        var lastError: Throwable? = null
        for (previewUrl in candidates) {
            val attempt = importPreviewCandidate(
                sound = sound,
                kind = kind,
                normalizationTargetLufs = normalizationTargetLufs,
                previewUrl = previewUrl,
                directory = directory,
            )
            if (attempt.isSuccess) return attempt
            val error = attempt.exceptionOrNull()
            if (error is CancellationException) throw error
            if (error is FreesoundNormalizationException && error.retryable) return attempt
            lastError = error
        }

        return Result.failure(
            IllegalStateException(
                buildString {
                    append("Không nhập được preview HQ Freesound")
                    if (candidates.size > 1) append(" bằng cả OGG và MP3")
                    lastError?.message?.takeIf(String::isNotBlank)?.let { append(": $it") }
                    append('.')
                },
                lastError,
            ),
        )
''',
    '''        val deadlineNanos = System.nanoTime() + PREVIEW_IMPORT_BUDGET_MS * 1_000_000L
        var lastError: Throwable? = null
        var attempted = 0
        for (previewUrl in candidates) {
            val remainingMs = ((deadlineNanos - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)
            if (remainingMs < MIN_PREVIEW_ATTEMPT_MS) {
                lastError = FreesoundImportException(
                    "Preview Freesound vượt quá thời gian tải chung ${PREVIEW_IMPORT_BUDGET_MS / 1_000L} giây.",
                    retryable = true,
                )
                break
            }
            attempted += 1
            val attempt = importPreviewCandidate(
                sound = sound,
                kind = kind,
                normalizationTargetLufs = normalizationTargetLufs,
                previewUrl = previewUrl,
                directory = directory,
                callTimeoutMs = remainingMs.coerceAtMost(PER_PREVIEW_MAX_CALL_MS),
            )
            if (attempt.isSuccess) return attempt
            val error = attempt.exceptionOrNull()
            if (error is CancellationException) throw error
            if (error is FreesoundImportException && error.downloadedNewFile) return attempt
            if (error is FreesoundNormalizationException && error.retryable) return attempt
            lastError = error
        }

        val message = buildString {
            append("Không nhập được preview HQ Freesound")
            if (attempted > 1) append(" bằng cả OGG và MP3")
            lastError?.message?.takeIf(String::isNotBlank)?.let { append(": $it") }
            append('.')
        }
        return Result.failure(
            FreesoundImportException(
                message = message,
                retryable = isRetryableImportFailure(lastError),
                downloadedNewFile = (lastError as? FreesoundImportException)?.downloadedNewFile == true,
                trackId = (lastError as? FreesoundImportException)?.trackId,
                cause = lastError,
            ),
        )
''',
)
replace_once(
    importer,
    '''        previewUrl: String,
        directory: File,
    ): Result<FreesoundImportResult> {
''',
    '''        previewUrl: String,
        directory: File,
        callTimeoutMs: Long,
    ): Result<FreesoundImportResult> {
''',
)
replace_once(
    importer,
    '''            httpClient.newCall(request).execute().use { response ->
''',
    '''            val boundedClient = httpClient.newBuilder()
                .connectTimeout(minOf(8_000L, callTimeoutMs).coerceAtLeast(500L), TimeUnit.MILLISECONDS)
                .readTimeout(minOf(10_000L, callTimeoutMs).coerceAtLeast(500L), TimeUnit.MILLISECONDS)
                .callTimeout(callTimeoutMs.coerceAtLeast(500L), TimeUnit.MILLISECONDS)
                .build()
            boundedClient.newCall(request).execute().use { response ->
''',
)
replace_once(
    importer,
    '''                    downloadElapsedMs = downloadElapsedMs,
                    normalizationElapsedMs = normalizationElapsedMs,
                ),
''',
    '''                    downloadElapsedMs = downloadElapsedMs,
                    normalizationElapsedMs = normalizationElapsedMs,
                    downloadedNewFile = true,
                    reusedExistingFile = false,
                ),
''',
)
replace_once(
    importer,
    '''        } catch (error: Throwable) {
            partFile.delete()
            if (!shouldPreserveImportedFile(error)) {
                savedTrackId?.let { runCatching { repository.deleteSceneMusicTrack(it) } }
                finalFile.delete()
                markerFile.delete()
            }
            Result.failure(error)
        }
''',
    '''        } catch (error: Throwable) {
            partFile.delete()
            val preserve = shouldPreserveImportedFile(error)
            if (!preserve) {
                savedTrackId?.let { runCatching { repository.deleteSceneMusicTrack(it) } }
                finalFile.delete()
                markerFile.delete()
            }
            val surfaced = when {
                preserve -> FreesoundImportException(
                    message = error.message ?: "Chuẩn hóa tệp Freesound cần thử lại.",
                    retryable = true,
                    downloadedNewFile = savedTrackId != null,
                    trackId = savedTrackId,
                    cause = error,
                )
                isRetryableImportFailure(error) -> FreesoundImportException(
                    message = error.message ?: "Kết nối tải preview Freesound tạm thời thất bại.",
                    retryable = true,
                    cause = error,
                )
                else -> error
            }
            Result.failure(surfaced)
        }
''',
)
replace_once(
    importer,
    '''        private const val NORMALIZATION_POLL_MS = 120L
        private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
''',
    '''        private const val NORMALIZATION_POLL_MS = 120L
        private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        internal const val PREVIEW_IMPORT_BUDGET_MS = 15_000L
        private const val PER_PREVIEW_MAX_CALL_MS = 12_000L
        private const val MIN_PREVIEW_ATTEMPT_MS = 800L
''',
)
replace_once(
    importer,
    '''        internal fun shouldPreserveImportedFile(error: Throwable): Boolean =
            error is FreesoundNormalizationException && error.retryable

        internal fun extensionForPreviewUrl(url: String): String {
''',
    '''        internal fun shouldPreserveImportedFile(error: Throwable): Boolean =
            error is FreesoundNormalizationException && error.retryable

        internal fun isRetryableImportFailure(error: Throwable?): Boolean =
            generateSequence(error) { it.cause }.any { candidate ->
                val message = candidate.message.orEmpty()
                candidate is InterruptedIOException ||
                    message.contains("timeout", ignoreCase = true) ||
                    Regex("HTTP\\s+(?:429|5\\d\\d)", RegexOption.IGNORE_CASE).containsMatchIn(message)
            }

        internal fun extensionForPreviewUrl(url: String): String {
''',
)

# 3) Resolver: track files created in this operation even when normalization asks for a retry,
#    classify transient preview errors as retryable, and reject overlong ambience candidates.
resolver = "app/src/main/java/vn/nghetruyen/app/freesound/FreesoundAutoAudioResolver.kt"
replace_once(
    resolver,
    '''                    .filter { (sound, _) -> sound.preferredPreviewUrl != null && candidateMeetsLexicalFloor(need, sound) }
''',
    '''                    .filter { (sound, _) ->
                        sound.preferredPreviewUrl != null &&
                            candidateMeetsLexicalFloor(need, sound) &&
                            candidateMeetsDurationLimit(need, sound)
                    }
''',
)
replace_once(
    resolver,
    '''                        if (preexistingSoundTrackByIndex[seed.index] == null) imported += result.trackId else normalizationResumes += 1
''',
    '''                        if (result.downloadedNewFile) imported += result.trackId
                        else if (result.reusedExistingFile || preexistingSoundTrackByIndex[seed.index] != null) normalizationResumes += 1
''',
)
replace_once(
    resolver,
    '''                        if (error is FreesoundNormalizationException && error.retryable) retryableFailure = true
                        diagnostics += "IMPORT_FAILED kind=${need.kind.name} soundId=${remote.id} elapsedMs=$importElapsedMs retryable=${error is FreesoundNormalizationException && error.retryable} errorType=${error?.javaClass?.simpleName.orEmpty()} error=${message.take(220)}"
                        liveDiagnostic(
                            traceId,
                            "FREESOUND_IMPORT_FAILED",
                            DiagnosticSeverity.WARN,
                            baseAttributes + mapOf(
                                "kind" to need.kind.name,
                                "soundId" to remote.id.toString(),
                                "elapsedMs" to importElapsedMs.toString(),
                                "retryable" to (error is FreesoundNormalizationException && error.retryable).toString(),
''',
    '''                        val retryableImport = when (error) {
                            is FreesoundImportException -> error.retryable
                            is FreesoundNormalizationException -> error.retryable
                            else -> FreesoundImporter.isRetryableImportFailure(error)
                        }
                        if (error is FreesoundImportException && error.downloadedNewFile) {
                            error.trackId?.let(imported::add)
                        }
                        retryableFailure = retryableFailure || retryableImport
                        diagnostics += "IMPORT_FAILED kind=${need.kind.name} soundId=${remote.id} elapsedMs=$importElapsedMs retryable=$retryableImport errorType=${error?.javaClass?.simpleName.orEmpty()} error=${message.take(220)}"
                        liveDiagnostic(
                            traceId,
                            "FREESOUND_IMPORT_FAILED",
                            DiagnosticSeverity.WARN,
                            baseAttributes + mapOf(
                                "kind" to need.kind.name,
                                "soundId" to remote.id.toString(),
                                "elapsedMs" to importElapsedMs.toString(),
                                "retryable" to retryableImport.toString(),
''',
)
replace_once(
    resolver,
    '''        internal fun candidateMeetsLexicalFloor(
            need: FreesoundAutoSearchNeed,
            sound: FreesoundSound,
        ): Boolean = candidateLexicalCoverage(need, sound) >= REMOTE_MIN_LEXICAL_COVERAGE

        internal fun scoreCandidate(
''',
    '''        internal fun candidateMeetsLexicalFloor(
            need: FreesoundAutoSearchNeed,
            sound: FreesoundSound,
        ): Boolean = candidateLexicalCoverage(need, sound) >= REMOTE_MIN_LEXICAL_COVERAGE

        internal fun candidateMeetsDurationLimit(
            need: FreesoundAutoSearchNeed,
            sound: FreesoundSound,
        ): Boolean = when (need.kind) {
            AudioAssetKind.MUSIC -> sound.durationSeconds in 10.0..480.0
            AudioAssetKind.AMBIENCE -> sound.durationSeconds in 8.0..180.0
            AudioAssetKind.SFX -> sound.durationSeconds in 0.05..20.0
        }

        internal fun scoreCandidate(
''',
)
replace_once(
    resolver,
    '''                AudioAssetKind.AMBIENCE -> when (sound.durationSeconds) {
                    in 30.0..180.0 -> 0.12
                    in 15.0..300.0 -> 0.07
                    in 10.0..<15.0 -> 0.02
                    else -> -0.10
                }
''',
    '''                AudioAssetKind.AMBIENCE -> when (sound.durationSeconds) {
                    in 20.0..120.0 -> 0.14
                    in 10.0..180.0 -> 0.05
                    in 8.0..<10.0 -> 0.01
                    else -> -0.30
                }
''',
)

# 4) Make diagnostics anomaly-only instead of echoing all normal success/cache/download stages.
service = "app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt"
replace_once(
    service,
    '''        result?.freesoundDiagnostics.orEmpty().forEachIndexed { index, detail ->
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
''',
    '''        result?.freesoundDiagnostics.orEmpty()
            .filter { detail ->
                val normalized = detail.uppercase(Locale.ROOT)
                normalized.contains("FAILED") ||
                    normalized.contains("ERROR") ||
                    normalized.contains("RETRY_EXHAUSTED") ||
                    normalized.contains("NEED_UNRESOLVED") ||
                    normalized.contains("CACHE_STALE") ||
                    normalized.contains("NO_SELECTION")
            }
            .forEachIndexed { index, detail ->
                val stage = detail.substringBefore(' ').take(56).ifBlank { "TRACE" }
                diagnostic(
                    "FREESOUND_$stage",
                    DiagnosticSeverity.WARN,
                    mapOf(
                        "phase" to phase,
                        "index" to (index + 1).toString(),
                        "detail" to detail.take(420),
                    ),
                )
            }
''',
)

# Automatic current-chapter success: use the exact same status formatter as manual/prefetch.
replace_once(
    service,
    '''                        val musicApplied = hasSceneMusicPlan()
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
''',
    '''                        val musicApplied = hasSceneMusicPlan()
                        val mode3 = StoryAudioModeRouter.usesAiFreesound(storyAudioSourceMode)
                        val warning = warnings.firstOrNull()?.takeIf(String::isNotBlank)
                        PlaybackQueueStore.setNarrationAutomation(
                            stage = NarrationAutomationStage.CURRENT_READY,
                            progress = 1f,
                            message = NarrationAutomationStatusFormatter.ready(
                                assignmentCount = assignmentCount,
                                resultPresent = planResult != null,
                                downloadedAssets = planResult?.freesoundDownloadedAssets ?: 0,
                                reusedAssets = planResult?.freesoundReusedAssets ?: 0,
                                retryRequired = planResult?.freesoundRetryRequired ?: false,
                                audioLayersEnabled = mode3 && shouldPlanAutoStoryAudio(),
                                beginPlayback = true,
                                warning = warning,
                            ),
                        )
''',
)

# Prefetch: same body/status, with only a next-chapter prefix.
old_prefetch = '''                    val musicLabel = if (shouldPlanAutoSceneMusic()) " + nhạc cảnh" else ""
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
'''
new_prefetch = '''                    val warning = result?.warnings?.firstOrNull()?.takeIf(String::isNotBlank)
                        ?: attempt.exceptionOrNull()?.message
                    val mode3 = StoryAudioModeRouter.usesAiFreesound(storyAudioSourceMode)
                    val baseMessage = when {
                        result == null -> "Không chuẩn bị AI trước được chương tiếp theo: ${chapter.chapter.title}." +
                            warning?.let { " • ${it.take(120)}" }.orEmpty()
                        !planVoice -> "Đã tải xong chương tiếp theo: ${chapter.chapter.title}. Đã chuẩn bị âm thanh AI." +
                            warning?.let { " • ${it.take(120)}" }.orEmpty()
                        assignmentCount <= 0 -> "Phân vai trước chưa tạo được mục giọng hợp lệ: ${chapter.chapter.title}." +
                            warning?.let { " • ${it.take(120)}" }.orEmpty()
                        failed -> "Phân vai trước chương tiếp theo chưa thành công: ${chapter.chapter.title}." +
                            warning?.let { " • ${it.take(120)}" }.orEmpty()
                        else -> NarrationAutomationStatusFormatter.ready(
                            assignmentCount = assignmentCount,
                            resultPresent = true,
                            downloadedAssets = result.freesoundDownloadedAssets,
                            reusedAssets = result.freesoundReusedAssets,
                            retryRequired = result.freesoundRetryRequired,
                            audioLayersEnabled = mode3 && planAudio,
                            prefix = "Đã tải xong chương tiếp theo: ${chapter.chapter.title}",
                            beginPlayback = false,
                            warning = warning,
                        )
                    }
'''
replace_once(service, old_prefetch, new_prefetch)
replace_once(
    service,
    '''                            message = baseMessage + warning?.let { " • ${it.take(120)}" }.orEmpty(),
''',
    '''                            message = baseMessage,
''',
)

# 5) Manual voice cast uses the same formatter and the same Freesound counters.
vm = "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt"
replace_once(
    vm,
    '''import vn.nghetruyen.app.playback.NarrationAutomationStage
''',
    '''import vn.nghetruyen.app.playback.NarrationAutomationStage
import vn.nghetruyen.app.playback.NarrationAutomationStatusFormatter
''',
)
replace_once(
    vm,
    '''                    PlaybackQueueStore.setNarrationAutomation(
                        stage = NarrationAutomationStage.CURRENT_READY,
                        progress = 1f,
                        message = "Đã phân vai xong $assignmentCount mục. Đang bắt đầu phát.",
                    )
''',
    '''                    PlaybackQueueStore.setNarrationAutomation(
                        stage = NarrationAutomationStage.CURRENT_READY,
                        progress = 1f,
                        message = NarrationAutomationStatusFormatter.ready(
                            assignmentCount = assignmentCount,
                            resultPresent = result != null,
                            downloadedAssets = result?.freesoundDownloadedAssets ?: 0,
                            reusedAssets = result?.freesoundReusedAssets ?: 0,
                            retryRequired = result?.freesoundRetryRequired ?: false,
                            audioLayersEnabled = container.narrationPlanCoordinator.storyAudioSourceMode() ==
                                vn.nghetruyen.app.audio.StoryAudioSourceMode.AI_FREESOUND,
                            beginPlayback = true,
                            warning = result?.warnings?.firstOrNull(),
                        ),
                    )
''',
)

# 6) vBook browser: detect Cloudflare/anti-bot interstitial during selector waits and fail early.
vbook = "source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookJsRuntime.kt"
marker = '''private class BrowserCompatObject(
'''
challenge_helper = '''internal object VBookBrowserChallengeDetector {
    fun isChallenge(html: String): Boolean {
        if (html.isBlank()) return false
        val normalized = Jsoup.parse(html).text().lowercase(Locale.ROOT)
        return normalized.contains("cloudflare") ||
            normalized.contains("thực hiện xác minh bảo mật") ||
            normalized.contains("security verification") ||
            normalized.contains("checking your browser") ||
            normalized.contains("verify you are human") ||
            normalized.contains("chờ một chút") && normalized.contains("bảo mật")
    }
}

'''
replace_once(vbook, marker, challenge_helper + marker)
replace_once(
    vbook,
    '''    private fun waitSelector(raw: Any?, timeoutMs: Long): Any {
        val selectors = stringList(raw)
        if (selectors.isEmpty()) return false
        val perSelector = (timeoutMs / selectors.size).coerceAtLeast(250L)
        selectors.forEach { selector ->
            val response = brokers.browser.execute(manifest, SourceBrowserRequest(
                sourceId = manifest.id,
                action = SourceBrowserAction.WAIT_SELECTOR,
                selector = selector,
                timeoutMs = perSelector,
                traceId = request.traceId,
            ))
            if (response is SourcePlatformResult.Success) {
                lastUrl = response.value.finalUrl ?: lastUrl
                return selector
            }
        }
        return false
    }
''',
    '''    private fun waitSelector(raw: Any?, timeoutMs: Long): Any {
        val selectors = stringList(raw)
        if (selectors.isEmpty()) return false
        val deadline = clockMs() + timeoutMs
        do {
            selectors.forEach { selector ->
                val remaining = (deadline - clockMs()).coerceAtLeast(100L)
                val response = brokers.browser.execute(manifest, SourceBrowserRequest(
                    sourceId = manifest.id,
                    action = SourceBrowserAction.WAIT_SELECTOR,
                    selector = selector,
                    timeoutMs = minOf(750L, remaining),
                    traceId = request.traceId,
                ))
                if (response is SourcePlatformResult.Success) {
                    lastUrl = response.value.finalUrl ?: lastUrl
                    return selector
                }
                if (clockMs() >= deadline) return false
            }
            val snapshot = brokers.browser.execute(manifest, SourceBrowserRequest(
                sourceId = manifest.id,
                action = SourceBrowserAction.DOM_SNAPSHOT,
                timeoutMs = minOf(1_000L, (deadline - clockMs()).coerceAtLeast(100L)),
                traceId = request.traceId,
            ))
            if (snapshot is SourcePlatformResult.Success) {
                lastUrl = snapshot.value.finalUrl ?: lastUrl
                lastHtml = snapshot.value.value.orEmpty()
                if (VBookBrowserChallengeDetector.isChallenge(lastHtml)) {
                    error("BROWSER_ANTIBOT_CHALLENGE: Trang nguồn đang yêu cầu xác minh bảo mật/Cloudflare.")
                }
            }
        } while (clockMs() < deadline)
        return false
    }
''',
)

# 7) Regression tests for the shared status and anti-bot detector.
write(
    "app/src/test/java/vn/nghetruyen/app/playback/FreesoundPlaybackStatusFormatterTest.kt",
    '''package vn.nghetruyen.app.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class FreesoundPlaybackStatusFormatterTest {
    @Test
    fun compactCountsOnly() {
        assertEquals(
            " • 5 tải mới • 3 bộ nhớ tạm",
            FreesoundPlaybackStatusFormatter.format(true, 5, 3, false, true),
        )
    }

    @Test
    fun normalZeroCountsStaySilent() {
        assertEquals("", FreesoundPlaybackStatusFormatter.format(true, 0, 0, false, true))
    }

    @Test
    fun abnormalStateRemainsVisible() {
        assertEquals(
            " • 1 tải mới • còn thiếu",
            FreesoundPlaybackStatusFormatter.format(true, 1, 0, true, true),
        )
    }

    @Test
    fun disabledLayersStaySilent() {
        assertEquals("", FreesoundPlaybackStatusFormatter.format(false, 2, 4, true, false))
    }
}
''',
)
write(
    "app/src/test/java/vn/nghetruyen/app/playback/NarrationAutomationStatusFormatterTest.kt",
    '''package vn.nghetruyen.app.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class NarrationAutomationStatusFormatterTest {
    @Test
    fun automaticAndManualShareSameBody() {
        assertEquals(
            "Đã phân vai xong 12 mục • 5 tải mới • 3 bộ nhớ tạm. Đang bắt đầu phát.",
            NarrationAutomationStatusFormatter.ready(
                assignmentCount = 12,
                resultPresent = true,
                downloadedAssets = 5,
                reusedAssets = 3,
                retryRequired = false,
                audioLayersEnabled = true,
                beginPlayback = true,
            ),
        )
    }

    @Test
    fun prefetchOnlyAddsNextChapterPrefix() {
        assertEquals(
            "Đã tải xong chương tiếp theo: Chương 2. Đã phân vai xong 12 mục • 5 tải mới • 3 bộ nhớ tạm.",
            NarrationAutomationStatusFormatter.ready(
                assignmentCount = 12,
                resultPresent = true,
                downloadedAssets = 5,
                reusedAssets = 3,
                retryRequired = false,
                audioLayersEnabled = true,
                prefix = "Đã tải xong chương tiếp theo: Chương 2",
            ),
        )
    }
}
''',
)
write(
    "source-vbook/src/test/kotlin/vn/nghetruyen/source/vbook/VBookBrowserChallengeDetectorTest.kt",
    '''package vn.nghetruyen.source.vbook

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VBookBrowserChallengeDetectorTest {
    @Test
    fun detectsCloudflareVerificationPage() {
        assertTrue(
            VBookBrowserChallengeDetector.isChallenge(
                "<html><title>Chờ một chút...</title><body>Cloudflare - Thực hiện xác minh bảo mật</body></html>",
            ),
        )
    }

    @Test
    fun ignoresNormalStoryPage() {
        assertFalse(
            VBookBrowserChallengeDetector.isChallenge(
                "<html><body><h3 class='title'>Tên truyện</h3><div>Nội dung</div></body></html>",
            ),
        )
    }
}
''',
)

print("Applied Mode-3 latency/status/anti-bot fixes")
