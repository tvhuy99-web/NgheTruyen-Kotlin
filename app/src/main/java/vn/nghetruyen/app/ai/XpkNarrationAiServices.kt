package vn.nghetruyen.app.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import vn.nghetruyen.app.NgheTruyenApplication
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.core.model.GLOBAL_VOICE_PROFILE_STORY_ID
import vn.nghetruyen.app.data.local.VoiceRoleEntity
import vn.nghetruyen.app.data.repository.LibraryRepository
import vn.nghetruyen.app.data.settings.AiOnlineSettings
import vn.nghetruyen.app.data.settings.AiProvider
import vn.nghetruyen.app.data.settings.SettingsRepository
import vn.nghetruyen.app.freesound.Mode3LocalContextBinder
import vn.nghetruyen.app.ui.reference.ReferenceVoiceRoleExtras
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.min

data class AiAuxiliaryJsonResult(
    val content: String,
    val provider: String,
    val model: String,
)

/**
 * Canonical XPK chapter-director transport. Voice, scene music, ambience, SFX and Mode-3
 * Freesound requirements share one prompt, one provider request, one quota reservation and one parser.
 */
class XpkNarrationAiServices(
    context: Context,
    private val settingsRepository: SettingsRepository,
    private val credentialStore: AiCredentialStore,
    private val requestGovernor: AiRequestGovernor,
    private val libraryRepository: LibraryRepository,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .dns(AiPublicDns)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) {
    private val appContext = context.applicationContext

    suspend fun planVoiceCast(
        storyId: String,
        chapterId: String,
        chapterTitle: String,
        rawText: String,
    ): AppResult<VoiceCastPlan> {
        val request = NarrationPlanRequest(
            storyId = storyId,
            chapterId = chapterId,
            chapterTitle = chapterTitle,
            rawText = rawText,
            includeVoiceCast = true,
            includeSceneMusic = false,
        )
        return when (val result = planNarration(request)) {
            is AppResult.Failure -> result
            is AppResult.Success -> AppResult.Success(result.value.voiceCast)
        }
    }

    /**
     * Auxiliary structured-JSON entry point that deliberately reuses the exact transport,
     * provider/model resolution, credentials, endpoint fallback, timeout and quota governor
     * used by production narration/voice-cast planning.
     */
    suspend fun completeAuxiliaryJson(
        storyId: String,
        prompt: String,
    ): AppResult<AiAuxiliaryJsonResult> {
        val clean = prompt.trim()
        if (clean.isBlank()) return failure("AI_EMPTY_INPUT", "Yêu cầu AI đang trống.")
        if (clean.length > MAX_PROMPT_CHARS) {
            return failure("AI_INPUT_TOO_LARGE", "Yêu cầu AI vượt giới hạn gửi trong một lượt.")
        }
        val config = resolveConfiguration(storyId)
        validateConfiguration(config)?.let { return it }
        val traceId = "ai-aux:${UUID.randomUUID()}"
        val startedNanos = System.nanoTime()
        diagnostic(
            "AI_AUXILIARY_JSON_START",
            DiagnosticSeverity.INFO,
            attributes = mapOf(
                "storyId" to storyId,
                "provider" to config.provider.name,
                "model" to config.model,
                "inputChars" to clean.length.toString(),
            ),
            traceId = traceId,
        )
        return when (val response = chat(clean, config)) {
            is AppResult.Failure -> {
                diagnostic(
                    "AI_AUXILIARY_JSON_FAILED",
                    DiagnosticSeverity.WARN,
                    attributes = mapOf(
                        "storyId" to storyId,
                        "provider" to config.provider.name,
                        "model" to config.model,
                        "elapsedMs" to ((System.nanoTime() - startedNanos) / 1_000_000L).toString(),
                        "code" to response.code,
                        "message" to response.message.take(300),
                    ),
                    traceId = traceId,
                )
                response
            }
            is AppResult.Success -> {
                diagnostic(
                    "AI_AUXILIARY_JSON_COMPLETED",
                    DiagnosticSeverity.INFO,
                    attributes = mapOf(
                        "storyId" to storyId,
                        "provider" to config.provider.name,
                        "model" to config.model,
                        "elapsedMs" to ((System.nanoTime() - startedNanos) / 1_000_000L).toString(),
                        "outputChars" to response.value.length.toString(),
                    ),
                    traceId = traceId,
                )
                AppResult.Success(
                    AiAuxiliaryJsonResult(
                        content = response.value,
                        provider = config.provider.name,
                        model = config.model,
                    ),
                )
            }
        }
    }

    suspend fun planNarration(request: NarrationPlanRequest): AppResult<NarrationPlan> {
        val narrationTraceId = "ai-narration:${UUID.randomUUID()}"
        diagnostic(
            "AI_NARRATION_PLAN_START",
            attributes = mapOf(
                "storyId" to request.storyId,
                "chapterId" to request.chapterId,
                "voiceCast" to request.includeVoiceCast.toString(),
                "sceneMusic" to request.includeSceneMusic.toString(),
                "ambience" to request.includeAmbience.toString(),
                "sfx" to request.includeSoundEffects.toString(),
                "freesoundAuto" to request.includeFreesoundAudioRequirements.toString(),
                "freesoundKinds" to request.freesoundRequirementKinds.joinToString(",") { it.name },
                "inputChars" to request.rawText.length.toString(),
            ),
            traceId = narrationTraceId,
        )
        val rawText = request.rawText.trim()
        if (rawText.isBlank()) return failure("AI_EMPTY_INPUT", "Chương không có nội dung để lập kế hoạch kể chuyện.")
        if (rawText.length > MAX_PLAN_CHARS) return failure("AI_INPUT_TOO_LARGE", "Chương quá dài để lập kế hoạch kể chuyện trong một lượt.")
        if (
            !request.includeVoiceCast && !request.includeSceneMusic && !request.includeAmbience &&
            !request.includeSoundEffects && !request.includeFreesoundAudioRequirements
        ) {
            return failure("AI_PLAN_EMPTY", "Không có hạng mục kể chuyện nào được yêu cầu.")
        }
        if (request.includeFreesoundAudioRequirements && request.freesoundRequirementKinds.isEmpty()) {
            return failure("AI_FREESOUND_KINDS_EMPTY", "Chế độ Freesound tự động chưa bật lớp âm thanh nào.")
        }
        if (
            request.includeFreesoundAudioRequirements &&
            (request.includeSceneMusic || request.includeAmbience || request.includeSoundEffects)
        ) {
            return failure(
                "AI_AUDIO_SOURCE_MODE_MIXED",
                "Mode 3 không được gửi catalog MUSIC/AMBIENCE/SFX local hoặc dùng bộ quy tắc chọn track_id của Mode 2.",
            )
        }
        if (request.includeSceneMusic && request.tracks.isEmpty()) {
            return failure("AI_TRACKS_EMPTY", "Chưa có tệp nhạc cảnh đang bật.")
        }
        if (request.includeAmbience && request.ambienceTracks.isEmpty()) {
            return failure("AI_AMBIENCE_EMPTY", "Chưa có tệp âm thanh môi trường đang bật.")
        }
        if (request.includeSoundEffects && request.soundEffectTracks.isEmpty()) {
            return failure("AI_SFX_EMPTY", "Chưa có tệp hiệu ứng âm thanh đang bật.")
        }

        val config = resolveConfiguration(request.storyId)
        validateConfiguration(config)?.let { return it }
        val storyVoice = StoryVoiceCastReferenceCodec.decode(config.voiceCastNote)
        val profiles = if (request.includeVoiceCast) {
            when (storyVoice.mode) {
                StoryVoiceCastMode.OFF -> emptyList()
                StoryVoiceCastMode.PRIVATE -> libraryRepository.listVoiceRoles(request.storyId).filter(VoiceRoleEntity::enabled)
                StoryVoiceCastMode.GLOBAL -> libraryRepository.listVoiceRoles(GLOBAL_VOICE_PROFILE_STORY_ID).filter(VoiceRoleEntity::enabled)
            }
        } else emptyList()

        if (request.includeVoiceCast) {
            if (profiles.size > MAX_VOICE_PROFILES) {
                return failure("VOICE_PROFILES_TOO_MANY", "Tối đa 10 giọng")
            }
            if (profiles.none(VoiceRoleEntity::isNarrator)) {
                return failure("VOICE_NARRATOR_MISSING", "Chưa có hồ sơ Người kể chuyện hợp lệ.")
            }
            if (profiles.none { !it.isNarrator }) {
                return failure("VOICE_PROFILES_INSUFFICIENT", "Cần ít nhất một hồ sơ giọng nhân vật ngoài Người kể chuyện để phân vai.")
            }
        }

        val profileSettingsById = if (request.includeVoiceCast) {
            profiles.associate { role ->
                val extra = ReferenceVoiceRoleExtras.load(appContext, role.id)
                val sonic = extra.processingMethod == "sonic"
                role.id to XpkVoiceCastPrompt.PromptProfileSettings(
                    processingMethod = if (sonic) "sonic" else "system",
                    speed = if (sonic) extra.sonicSpeed ?: role.sonicSpeed else extra.systemRate ?: role.rate,
                    pitch = if (sonic) extra.sonicPitch ?: role.sonicPitch else extra.systemPitch ?: role.pitch,
                    volume = if (sonic) extra.sonicVolume ?: role.volume else extra.systemVolume ?: role.volume,
                )
            }
        } else emptyMap()

        val ambienceCatalog = if (request.includeAmbience) {
            XpkUnifiedNarrationPrompt.buildCatalog(
                request.ambienceTracks,
                "ambience\u0000${request.storyId}\u0000${request.chapterId}",
            )
        } else XpkUnifiedNarrationPrompt.CatalogBundle(emptyList(), emptyMap())
        val sfxCatalog = if (request.includeSoundEffects) {
            XpkUnifiedNarrationPrompt.buildCatalog(
                request.soundEffectTracks,
                "sfx\u0000${request.storyId}\u0000${request.chapterId}",
            )
        } else XpkUnifiedNarrationPrompt.CatalogBundle(emptyList(), emptyMap())

        val storyNote = StoryVoiceCastReferenceCodec.userNote(config.voiceCastNote)
        val bundle = XpkVoiceCastPrompt.build(
            title = request.chapterTitle,
            body = rawText,
            profiles = profiles,
            storyNote = storyNote,
            expressiveAdjustment = config.expressiveAdjustment,
            speedLimitPct = config.expressionSpeedLimitPct,
            pitchLimitPct = config.expressionPitchLimitPct,
            volumeLimitPct = config.expressionVolumeLimitPct,
            expressionPrompt = config.expressionPrompt,
            includeVoiceCast = request.includeVoiceCast,
            includeSceneMusic = request.includeSceneMusic,
            tracks = request.tracks,
            context = request.context,
            profileSettingsById = profileSettingsById,
            includeAudioDirection = request.includeAmbience || request.includeSoundEffects || request.includeFreesoundAudioRequirements,
        )
        val validSceneTrackIds = if (request.includeSceneMusic) bundle.sceneTrackIds else emptyList()
        val ambienceAliasToId = if (request.includeAmbience) ambienceCatalog.aliasToId else emptyMap()
        val sfxAliasToId = if (request.includeSoundEffects) sfxCatalog.aliasToId else emptyMap()
        val validAmbienceIds = ambienceAliasToId.values.toSet()
        val validSfxIds = sfxAliasToId.values.toSet()

        if (request.includeSceneMusic && validSceneTrackIds.isEmpty()) {
            return failure("AI_TRACKS_EMPTY", "Không có bài nhạc cảnh hợp lệ để gửi AI.")
        }
        if (request.includeAmbience && validAmbienceIds.isEmpty()) {
            return failure("AI_AMBIENCE_EMPTY", "Không có asset AMBIENCE hợp lệ để gửi AI.")
        }
        if (request.includeSoundEffects && validSfxIds.isEmpty()) {
            return failure("AI_SFX_EMPTY", "Không có asset SFX hợp lệ để gửi AI.")
        }

        if (
            request.includeVoiceCast && bundle.dialogueIds.isEmpty() &&
            !request.includeSceneMusic && !request.includeAmbience && !request.includeSoundEffects &&
            !request.includeFreesoundAudioRequirements
        ) {
            return AppResult.Success(
                NarrationPlan(
                    voiceCast = VoiceCastPlan(configuredRoles(profiles), emptyList()),
                    musicCues = emptyList(),
                ),
            )
        }

        val prompt = XpkUnifiedNarrationPrompt.compose(
            base = bundle,
            title = request.chapterTitle,
            includeVoiceCast = request.includeVoiceCast,
            includeSceneMusic = request.includeSceneMusic,
            includeAmbience = request.includeAmbience,
            includeSoundEffects = request.includeSoundEffects,
            ambienceTracks = request.ambienceTracks,
            soundEffectTracks = request.soundEffectTracks,
            previousChapterTail = request.context.previousChapterEnding,
            incomingAmbienceId = request.context.incomingAmbienceId,
            incomingFreesoundMusicQuery = request.context.incomingFreesoundMusicQuery,
            incomingFreesoundAmbienceQueries = request.context.incomingFreesoundAmbienceQueries,
            ambienceCatalog = ambienceCatalog.takeIf { request.includeAmbience },
            sfxCatalog = sfxCatalog.takeIf { request.includeSoundEffects },
            includeFreesoundAudioRequirements = request.includeFreesoundAudioRequirements,
            freesoundRequirementKinds = request.freesoundRequirementKinds,
        )
        if (prompt.length > MAX_PROMPT_CHARS) {
            return failure("AI_INPUT_TOO_LARGE", "Bản chép đạo diễn âm thanh vượt giới hạn gửi AI.")
        }

        return when (val response = chat(prompt, config)) {
            is AppResult.Failure -> response
            is AppResult.Success -> runCatching {
                val parsed = AiLineProtocol.parseXpkNarration(
                    response.value,
                    AiLineProtocol.XpkParseOptions(
                        validDialogueIds = bundle.dialogueIds,
                        validUnitIds = bundle.unitIds,
                        validVoiceIds = bundle.voiceIds,
                        validTrackIds = validSceneTrackIds,
                        validAmbienceIds = validAmbienceIds,
                        validSfxIds = validSfxIds,
                        includeVoiceCast = request.includeVoiceCast,
                        includeSceneMusic = request.includeSceneMusic,
                        includeAmbience = request.includeAmbience,
                        includeSoundEffects = request.includeSoundEffects,
                        includeFreesoundAudioRequirements = request.includeFreesoundAudioRequirements,
                        freesoundRequirementKinds = request.freesoundRequirementKinds,
                        speedLimitPct = config.expressionSpeedLimitPct.toFloat(),
                        pitchLimitPct = config.expressionPitchLimitPct.toFloat(),
                        volumeLimitPct = config.expressionVolumeLimitPct.toFloat(),
                        expressiveAdjustment = config.expressiveAdjustment,
                        incomingTrackId = request.context.activeTrackId,
                        dialogueGroupByUnitId = bundle.units
                            .mapNotNull { unit -> unit.dialogueGroupId?.takeIf(String::isNotBlank)?.let { unit.id to it } }
                            .toMap(),
                        trackAliasToId = bundle.sceneTrackAliasToId,
                        ambienceAliasToId = ambienceAliasToId,
                        sfxAliasToId = sfxAliasToId,
                    ),
                )
                val roleByPromptId = profiles.associateBy(XpkVoiceCastPrompt::promptVoiceId)
                val normalizedVoice = if (request.includeVoiceCast) {
                    parsed.voiceCast.copy(
                        roles = configuredRoles(profiles),
                        assignments = parsed.voiceCast.assignments.map { assignment ->
                            assignment.copy(character = roleByPromptId[assignment.voiceId]?.roleName.orEmpty())
                        },
                    )
                } else parsed.voiceCast
                val contextualFreesoundRequirements = if (request.includeFreesoundAudioRequirements) {
                    Mode3LocalContextBinder.attach(parsed.freesoundRequirements, bundle.units)
                } else parsed.freesoundRequirements
                parsed.copy(
                    voiceCast = normalizedVoice,
                    freesoundRequirements = contextualFreesoundRequirements,
                )
            }.fold(
                {
                    diagnostic(
                        "AI_NARRATION_PLAN_COMPLETED",
                        DiagnosticSeverity.INFO,
                        mapOf(
                            "storyId" to request.storyId,
                            "chapterId" to request.chapterId,
                            "voiceCast" to request.includeVoiceCast.toString(),
                            "sceneMusic" to request.includeSceneMusic.toString(),
                            "ambience" to request.includeAmbience.toString(),
                            "sfx" to request.includeSoundEffects.toString(),
                            "freesoundAuto" to request.includeFreesoundAudioRequirements.toString(),
                            "freesoundRequirements" to it.freesoundRequirements.size.toString(),
                        ),
                        traceId = narrationTraceId,
                    )
                    AppResult.Success(it)
                },
                { failure("AI_BAD_RESPONSE", it.message ?: "Kết quả kế hoạch kể chuyện không hợp lệ.", it) },
            )
        }
    }

    private fun configuredRoles(profiles: List<VoiceRoleEntity>): List<VoiceRole> = profiles.map { role ->
        VoiceRole(
            character = role.roleName,
            aliases = role.aliasesCsv.split(',').map(String::trim).filter(String::isNotBlank).take(20),
            expression = role.expression,
        )
    }

    private suspend fun resolveConfiguration(storyId: String): EffectiveConfig {
        val global = settingsRepository.snapshot().aiOnline
        val profile = storyId.takeIf(String::isNotBlank)?.let { libraryRepository.getStoryAiProfile(it) }
        val provider = if (profile?.overrideProvider == true) {
            runCatching { AiProvider.valueOf(profile.provider) }.getOrDefault(global.provider)
        } else global.provider
        return EffectiveConfig(
            global = global,
            provider = provider,
            endpoint = profile?.endpoint?.takeIf { profile.overrideProvider && it.isNotBlank() } ?: global.endpoint,
            model = profile?.model?.takeIf { profile.overrideProvider && it.isNotBlank() } ?: global.model,
            temperature = profile?.temperature?.takeIf { it in 0f..2f } ?: global.temperature,
            voiceCastNote = profile?.voiceCastNote.orEmpty(),
            expressiveAdjustment = profile?.expressiveAdjustment ?: false,
            expressionPrompt = profile?.expressionPrompt.orEmpty(),
            expressionSpeedLimitPct = profile?.expressionSpeedLimitPct?.coerceIn(0, 100) ?: 10,
            expressionPitchLimitPct = profile?.expressionPitchLimitPct?.coerceIn(0, 100) ?: 10,
            expressionVolumeLimitPct = profile?.expressionVolumeLimitPct?.coerceIn(0, 100) ?: 10,
        )
    }

    private fun validateConfiguration(config: EffectiveConfig): AppResult.Failure? {
        if (!config.global.enabled) return failure("AI_DISABLED", "AI online đang tắt.")
        if (config.provider == AiProvider.OPENAI_COMPATIBLE) {
            AiEndpointPolicy.validate(config.endpoint).exceptionOrNull()?.let {
                return failure("AI_ENDPOINT_INVALID", it.message ?: "Endpoint AI không hợp lệ.")
            }
        }
        if (config.model.isBlank()) return failure("AI_MODEL_MISSING", "Chưa cấu hình model AI.")
        return null
    }

    private suspend fun chat(prompt: String, config: EffectiveConfig): AppResult<String> = withContext(Dispatchers.IO) {
        val apiKey = when (config.provider) {
            AiProvider.GEMINI -> credentialStore.apiKey(config.provider)?.trim()?.takeIf(String::isNotBlank)
                ?: return@withContext failure("AI_KEY_MISSING", "Chưa lưu API key cho Gemini.")
            AiProvider.OPENAI_COMPATIBLE -> credentialStore.apiKey(config.provider)?.trim().orEmpty()
        }
        val permit = when (val reserved = requestGovernor.reserve(prompt.length)) {
            is AppResult.Failure -> return@withContext reserved
            is AppResult.Success -> reserved.value
        }
        val requests = runCatching { buildRequests(config, apiKey, prompt) }.getOrElse {
            requestGovernor.finish(permit, 0, 0, "AI_CONFIGURATION_INVALID")
            return@withContext failure("AI_CONFIGURATION_INVALID", it.message ?: "Cấu hình AI không hợp lệ.", it)
        }
        val timeoutMillis = config.global.timeoutMillis.coerceAtLeast(10_000)
        val callClient = client.newBuilder()
            .connectTimeout(minOf(30_000, timeoutMillis).toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .build()
        var lastFailure: AppResult.Failure? = null
        requests.forEachIndexed { index, requestData ->
            try {
                val request = Request.Builder()
                    .url(requestData.url)
                    .header("Accept", "application/json")
                    .apply { requestData.headers.forEach { (name, value) -> header(name, value) } }
                    .post(requestData.body.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                val response = callClient.newCall(request).execute()
                response.use {
                    if (response.isRedirect) {
                        lastFailure = failure("AI_REDIRECT_BLOCKED", "Endpoint AI trả redirect; yêu cầu URL API trực tiếp.")
                        return@forEachIndexed
                    }
                    val raw = response.body?.charStream()?.use { reader ->
                        buildString {
                            val buffer = CharArray(8_192)
                            while (length <= MAX_RESPONSE_CHARS) {
                                val count = reader.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_CHARS + 1 - length))
                                if (count < 0) break
                                append(buffer, 0, count)
                            }
                        }
                    }.orEmpty()
                    if (raw.length > MAX_RESPONSE_CHARS) {
                        lastFailure = failure("AI_RESPONSE_TOO_LARGE", "Phản hồi AI vượt giới hạn an toàn.")
                        return@forEachIndexed
                    }
                    if (!response.isSuccessful) {
                        lastFailure = failure("AI_HTTP_${response.code}", extractError(raw)?.take(400) ?: "Nhà cung cấp AI trả lỗi HTTP ${response.code}.")
                        val canFallback = config.provider == AiProvider.OPENAI_COMPATIBLE &&
                            response.code in OPENAI_ENDPOINT_FALLBACK_HTTP_CODES && index < requests.lastIndex
                        if (canFallback) return@forEachIndexed
                        requestGovernor.finish(permit, 0, 0, lastFailure?.code)
                        return@withContext lastFailure!!
                    }
                    val content = runCatching { extractContent(config.provider, raw) }.getOrElse {
                        lastFailure = failure("AI_BAD_RESPONSE", it.message ?: "Không đọc được phản hồi AI.", it)
                        ""
                    }
                    if (content.isNotBlank()) {
                        requestGovernor.finish(permit, content.length, 0, null)
                        return@withContext AppResult.Success(content.trim())
                    }
                }
            } catch (error: IOException) {
                lastFailure = failure("AI_NETWORK_ERROR", error.message ?: "Không kết nối được nhà cung cấp AI.", error)
            } catch (error: Exception) {
                lastFailure = failure("AI_NETWORK_ERROR", error.message ?: "Không kết nối được nhà cung cấp AI.", error)
            }
        }
        val result = lastFailure ?: failure("AI_EMPTY_RESPONSE", "AI trả về nội dung trống.")
        requestGovernor.finish(permit, 0, 0, result.code)
        result
    }

    private fun buildRequests(config: EffectiveConfig, apiKey: String, prompt: String): List<AiRequest> = when (config.provider) {
        AiProvider.GEMINI -> {
            val model = config.model.removePrefix("models/").trim()
            require(GEMINI_MODEL_PATTERN.matches(model)) { "Tên model Gemini chứa ký tự không hợp lệ." }
            val body = JSONObject()
                .put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt))),
                    ),
                )
                .put(
                    "generationConfig",
                    JSONObject()
                        .put("temperature", config.temperature.toDouble())
                        .put("responseMimeType", "application/json"),
                )
                .toString()
            listOf(
                AiRequest(
                    url = "$GEMINI_API_BASE/models/$model:generateContent",
                    headers = mapOf("x-goog-api-key" to apiKey),
                    body = body,
                ),
            )
        }
        AiProvider.OPENAI_COMPATIBLE -> {
            val headers = buildMap { if (apiKey.isNotBlank()) put("Authorization", "Bearer $apiKey") }
            openAiCandidateUrls(config.endpoint).map { url ->
                val path = url.substringBefore('?').substringBefore('#').trimEnd('/').lowercase()
                val body = if (path.endsWith("/responses")) {
                    JSONObject().put("model", config.model).put("input", prompt).toString()
                } else {
                    JSONObject()
                        .put("model", config.model)
                        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                        .put("temperature", config.temperature.toDouble())
                        .toString()
                }
                AiRequest(url, headers, body)
            }
        }
    }

    private fun openAiCandidateUrls(value: String): List<String> {
        val original = value.trim()
        if (original.isBlank()) return emptyList()
        val tailIndex = original.indexOfFirst { it == '?' || it == '#' }
        val path = if (tailIndex >= 0) original.substring(0, tailIndex) else original
        val tail = if (tailIndex >= 0) original.substring(tailIndex) else ""
        val base = path.trimEnd('/')
        val out = mutableListOf<String>()
        val seen = linkedSetOf<String>()
        fun append(candidate: String) {
            val clean = candidate.trimEnd('/')
            if (clean.isNotBlank() && seen.add(clean + tail)) out += clean + tail
        }
        append(base)
        when {
            base.endsWith("/responses", ignoreCase = true) -> append(base.dropLast("/responses".length) + "/chat/completions")
            base.endsWith("/chat/completions", ignoreCase = true) -> append(base.dropLast("/chat/completions".length) + "/responses")
            else -> {
                append("$base/chat/completions")
                append("$base/responses")
                if (!base.endsWith("/v1", ignoreCase = true)) {
                    append("$base/v1/chat/completions")
                    append("$base/v1/responses")
                }
            }
        }
        return out
    }

    private fun extractContent(provider: AiProvider, raw: String): String = when (provider) {
        AiProvider.GEMINI -> {
            val root = JSONObject(raw)
            root.optJSONObject("error")?.optString("message")?.takeIf(String::isNotBlank)?.let { error(it) }
            val candidate = root.optJSONArray("candidates")?.optJSONObject(0)
                ?: error(root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty().ifBlank { "Gemini không trả candidate." })
            val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: error("Gemini không trả nội dung.")
            buildString {
                for (index in 0 until parts.length()) {
                    parts.optJSONObject(index)?.optString("text")?.takeIf(String::isNotBlank)?.let {
                        if (isNotEmpty()) append('\n')
                        append(it)
                    }
                }
            }
        }
        AiProvider.OPENAI_COMPATIBLE -> extractOpenAiContent(raw)
    }

    private fun extractOpenAiContent(raw: String): String {
        val root = JSONObject(raw)
        root.optJSONObject("error")?.optString("message")?.takeIf(String::isNotBlank)?.let { error(it) }
        root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.opt("content")?.let { content ->
            when (content) {
                is String -> if (content.isNotBlank()) return content
                is JSONArray -> {
                    val text = buildString {
                        for (index in 0 until content.length()) {
                            content.optJSONObject(index)?.optString("text")?.takeIf(String::isNotBlank)?.let {
                                if (isNotEmpty()) append('\n')
                                append(it)
                            }
                        }
                    }
                    if (text.isNotBlank()) return text
                }
            }
        }
        root.optString("output_text").takeIf(String::isNotBlank)?.let { return it }
        val output = root.optJSONArray("output") ?: JSONArray()
        val text = buildString {
            for (outputIndex in 0 until output.length()) {
                val parts = output.optJSONObject(outputIndex)?.optJSONArray("content") ?: continue
                for (partIndex in 0 until parts.length()) {
                    parts.optJSONObject(partIndex)?.optString("text")?.takeIf(String::isNotBlank)?.let {
                        if (isNotEmpty()) append('\n')
                        append(it)
                    }
                }
            }
        }
        return text.takeIf(String::isNotBlank) ?: error("OpenAI-compatible API không trả nội dung")
    }

    private fun extractError(raw: String): String? = runCatching {
        JSONObject(raw).optJSONObject("error")?.optString("message")?.takeIf(String::isNotBlank)
    }.getOrNull()

    private data class EffectiveConfig(
        val global: AiOnlineSettings,
        val provider: AiProvider,
        val endpoint: String,
        val model: String,
        val temperature: Float,
        val voiceCastNote: String,
        val expressiveAdjustment: Boolean,
        val expressionPrompt: String,
        val expressionSpeedLimitPct: Int,
        val expressionPitchLimitPct: Int,
        val expressionVolumeLimitPct: Int,
    )

    private data class AiRequest(
        val url: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private fun diagnostic(
        name: String,
        severity: DiagnosticSeverity = DiagnosticSeverity.DEBUG,
        attributes: Map<String, String> = emptyMap(),
        traceId: String? = null,
    ) {
        (appContext as? NgheTruyenApplication)?.container?.sourceDiagnostics?.mark(
            name = name,
            category = DiagnosticCategory.RUNTIME,
            severity = severity,
            sourceId = "ai",
            traceId = traceId ?: "app:${UUID.randomUUID()}",
            attributes = attributes,
        )
    }

    private fun failure(code: String, message: String, cause: Throwable? = null): AppResult.Failure {
        diagnostic(
            "AI_NARRATION_FAILURE",
            DiagnosticSeverity.WARN,
            mapOf("code" to code, "message" to message.take(500), "cause" to (cause?.javaClass?.simpleName ?: "")),
        )
        return AppResult.Failure(code, message, cause)
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val GEMINI_API_BASE = "https://generativelanguage.googleapis.com/v1beta"
        private val GEMINI_MODEL_PATTERN = Regex("^[A-Za-z0-9._-]+$")
        private val OPENAI_ENDPOINT_FALLBACK_HTTP_CODES = setOf(404, 405)
        private const val MAX_PLAN_CHARS = 60_000
        private const val MAX_PROMPT_CHARS = 160_000
        private const val MAX_RESPONSE_CHARS = 2_000_000
        private const val MAX_VOICE_PROFILES = 10
    }
}
