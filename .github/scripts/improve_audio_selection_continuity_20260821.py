from pathlib import Path


def patch(path: str, old: str, new: str, marker: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if marker in text:
        print(f"SKIP {path}: {marker}")
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected 1 occurrence, found {count}; marker={marker!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")
    print(f"PATCH {path}: {marker}")


# 1) Preserve every valid local ambience layer across a chapter boundary, rather than silently
# truncating the legacy transport field to two layers. Also carry exact Mode-3 search queries so
# the next chapter can reuse the same managed Freesound asset when the scene really continues.
ai_services = "app/src/main/java/vn/nghetruyen/app/ai/AiServices.kt"
patch(
    ai_services,
    '''    /** Legacy single field used by the prompt transport; multiple ids are pipe-delimited internally. */
    var incomingAmbienceId: String? = null,
    /** Up to two ambience layers that were active at the end of the previous chapter. */
    val incomingAmbienceIds: List<String> = emptyList(),
) {
    init {
        val normalized = incomingAmbienceIds
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .take(2)
        if (normalized.isNotEmpty()) incomingAmbienceId = normalized.joinToString("|")
    }
}''',
    '''    /** Legacy single field used by the prompt transport; multiple ids are pipe-delimited internally. */
    var incomingAmbienceId: String? = null,
    /** Ambience layers that were active at the end of the previous chapter. Each is only a candidate. */
    val incomingAmbienceIds: List<String> = emptyList(),
    /** Exact canonical Mode-3 MUSIC query reaching the previous chapter boundary, if any. */
    val incomingFreesoundMusicQuery: String? = null,
    /** Exact canonical Mode-3 AMBIENCE queries reaching the previous chapter boundary. */
    val incomingFreesoundAmbienceQueries: List<String> = emptyList(),
) {
    init {
        val normalized = incomingAmbienceIds
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
        if (normalized.isNotEmpty()) incomingAmbienceId = normalized.joinToString("|")
    }
}''',
    "incomingFreesoundAmbienceQueries",
)


# 2) Build explicit continuity candidates from the previous chapter's persisted Mode-3 requirements.
# Reusing the exact canonical query hits FreesoundAutoQueryCache and therefore preserves the exact
# managed asset, while the prompt remains free to drop/replace it when the current chapter changes.
coordinator = "app/src/main/java/vn/nghetruyen/app/ai/NarrationPlanCoordinator.kt"
patch(
    coordinator,
    '''    private data class FreesoundApplyResult(
        val musicCreated: Boolean,
        val audioCreated: Boolean,
        val resolvedAssets: Int,
        val warnings: List<String>,
        val retryableFailure: Boolean = false,
        val diagnostics: List<String> = emptyList(),
        val attempts: Int = 0,
        val retryExhausted: Boolean = false,
    )''',
    '''    private data class FreesoundApplyResult(
        val musicCreated: Boolean,
        val audioCreated: Boolean,
        val resolvedAssets: Int,
        val warnings: List<String>,
        val retryableFailure: Boolean = false,
        val diagnostics: List<String> = emptyList(),
        val attempts: Int = 0,
        val retryExhausted: Boolean = false,
    )

    private data class FreesoundContinuityContext(
        val musicQuery: String? = null,
        val ambienceQueries: List<String> = emptyList(),
    )''',
    "private data class FreesoundContinuityContext",
)
patch(
    coordinator,
    '''        val baseContext = buildContinuityContext(content, activeTrackId, musicTracks)
        val incomingAmbienceIds = if (localAiMode) buildIncomingAmbienceIds(content, ambienceTracks) else emptyList()
        val context = baseContext.copy(
            incomingAmbienceId = incomingAmbienceIds.firstOrNull(),
            incomingAmbienceIds = incomingAmbienceIds,
        )''',
    '''        val baseContext = buildContinuityContext(content, activeTrackId, musicTracks)
        val incomingAmbienceIds = if (localAiMode) buildIncomingAmbienceIds(content, ambienceTracks) else emptyList()
        val freesoundContinuity = if (freesoundMode) buildFreesoundContinuityContext(content) else FreesoundContinuityContext()
        val context = baseContext.copy(
            incomingAmbienceId = incomingAmbienceIds.firstOrNull(),
            incomingAmbienceIds = incomingAmbienceIds,
            incomingFreesoundMusicQuery = freesoundContinuity.musicQuery,
            incomingFreesoundAmbienceQueries = freesoundContinuity.ambienceQueries,
        )''',
    "incomingFreesoundMusicQuery = freesoundContinuity.musicQuery",
)
patch(
    coordinator,
    '''    private fun isExpectedAudioSourceMode(transformedText: String, expected: StoryAudioSourceMode): Boolean = runCatching {''',
    '''    private suspend fun buildFreesoundContinuityContext(content: ChapterContent): FreesoundContinuityContext {
        val previous = library.loadPreviousCachedChapter(content.chapter.storyId, content.chapter.index)
            ?: return FreesoundContinuityContext()
        val transform = library.getChapterTransform(previous.chapter.id, KIND_FREESOUND_AUTO_AUDIO)
            ?: return FreesoundContinuityContext()
        if (!isCurrentTimelineTransform(transform.transformedText, FREESOUND_AUTO_ENGINE, previous)) {
            return FreesoundContinuityContext()
        }
        val root = runCatching { JSONObject(transform.transformedText) }.getOrNull()
            ?: return FreesoundContinuityContext()
        if (root.optString("audio_source_mode") != StoryAudioSourceMode.AI_FREESOUND.name) {
            return FreesoundContinuityContext()
        }
        val enabledKinds = buildSet {
            val stored = root.optJSONArray("enabled_kinds")
            if (stored != null) {
                for (index in 0 until stored.length()) {
                    runCatching { AudioAssetKind.valueOf(stored.optString(index).trim()) }
                        .getOrNull()
                        ?.let(::add)
                }
            }
        }.ifEmpty { AudioAssetKind.entries.toSet() }
        val units = XpkVoiceCastSplitter.buildUnits(previous.chapter.title, chapterBody(previous))
        val unitIds = units.map { it.id }
        val finalUnitId = unitIds.lastOrNull() ?: return FreesoundContinuityContext()
        val order = unitIds.withIndex().associate { it.value to it.index }
        val requirements = runCatching {
            FreesoundAutoRequirementCodec.parse(root, unitIds, enabledKinds)
        }.getOrDefault(emptyList())
        val boundaryRows = requirements.filter { requirement ->
            requirement.kind in setOf(AudioAssetKind.MUSIC, AudioAssetKind.AMBIENCE) &&
                requirement.endUnitId == finalUnitId
        }
        val priority = compareBy<FreesoundAutoRequirement> {
            it.importance != FreesoundRequirementImportance.REQUIRED
        }.thenByDescending { requirement ->
            requirement.startUnitId?.let(order::get) ?: -1
        }
        val musicQuery = boundaryRows
            .filter { it.kind == AudioAssetKind.MUSIC }
            .sortedWith(priority)
            .firstOrNull()
            ?.query
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val ambienceQueries = boundaryRows
            .filter { it.kind == AudioAssetKind.AMBIENCE }
            .sortedWith(priority)
            .map { it.query.trim() }
            .filter(String::isNotBlank)
            .distinct()
        return FreesoundContinuityContext(
            musicQuery = musicQuery,
            ambienceQueries = ambienceQueries,
        )
    }

    private fun isExpectedAudioSourceMode(transformedText: String, expected: StoryAudioSourceMode): Boolean = runCatching {''',
    "private suspend fun buildFreesoundContinuityContext",
)


# 3) Send those exact continuity candidates into the unified sound-director prompt.
narration_services = "app/src/main/java/vn/nghetruyen/app/ai/XpkNarrationAiServices.kt"
patch(
    narration_services,
    '''            previousChapterTail = request.context.previousChapterEnding,
            incomingAmbienceId = request.context.incomingAmbienceId,
            ambienceCatalog = ambienceCatalog.takeIf { request.includeAmbience },''',
    '''            previousChapterTail = request.context.previousChapterEnding,
            incomingAmbienceId = request.context.incomingAmbienceId,
            incomingFreesoundMusicQuery = request.context.incomingFreesoundMusicQuery,
            incomingFreesoundAmbienceQueries = request.context.incomingFreesoundAmbienceQueries,
            ambienceCatalog = ambienceCatalog.takeIf { request.includeAmbience },''',
    "incomingFreesoundMusicQuery = request.context.incomingFreesoundMusicQuery",
)


prompt = "app/src/main/java/vn/nghetruyen/app/ai/XpkUnifiedNarrationPrompt.kt"
patch(
    prompt,
    '''        previousChapterTail: String = "",
        incomingAmbienceId: String? = null,
        ambienceCatalog: CatalogBundle? = null,''',
    '''        previousChapterTail: String = "",
        incomingAmbienceId: String? = null,
        incomingFreesoundMusicQuery: String? = null,
        incomingFreesoundAmbienceQueries: List<String> = emptyList(),
        ambienceCatalog: CatalogBundle? = null,''',
    "incomingFreesoundMusicQuery: String? = null",
)
patch(
    prompt,
    '''            if (includeFreesoundAudioRequirements) {
                add("Mode 3 dùng cùng logic đạo diễn MUSIC/AMBIENCE/SFX của Mode 2; khác biệt duy nhất là không gửi catalog local (asset trên máy) và không yêu cầu AI chọn track_id. AI chỉ mô tả nhu cầu tìm kiếm, ứng dụng tự tìm/tải/chuẩn hóa sau phản hồi.")
                add("FREESOUND_REQUIREMENTS chỉ mô tả âm thanh cần tìm; không chọn ID, tên file hoặc URL. Không tạo một nhu cầu mới cho mỗi lần lặp cùng loại âm thanh; cùng âm thanh phải dùng cùng query để tái sử dụng asset.")
            }''',
    '''            if (includeFreesoundAudioRequirements) {
                add("Mode 3 dùng cùng logic đạo diễn MUSIC/AMBIENCE/SFX của Mode 2; khác biệt duy nhất là không gửi catalog local (asset trên máy) và không yêu cầu AI chọn track_id. AI chỉ mô tả nhu cầu tìm kiếm, ứng dụng tự tìm/tải/chuẩn hóa sau phản hồi.")
                add("FREESOUND_REQUIREMENTS chỉ mô tả âm thanh cần tìm; không chọn ID, tên file hoặc URL. Không tạo một nhu cầu mới cho mỗi lần lặp cùng loại âm thanh; cùng âm thanh phải dùng cùng query để tái sử dụng asset.")
                add("Khi có nhiều cách mô tả đều hợp, ưu tiên query mô tả đúng nguồn âm/sắc thái cụ thể nhất và dễ tìm nhất; không chọn từ quá chung chỉ vì phổ biến. Nếu không có lựa chọn đủ sát nội dung, im lặng tốt hơn một âm sai cảnh.")
            }''',
    "im lặng tốt hơn một âm sai cảnh",
)
patch(
    prompt,
    '''        val continuityBlock = if (
            includeSceneMusic || includeAmbience ||
            (includeFreesoundAudioRequirements && freesoundRequirementKinds.any { it == AudioAssetKind.MUSIC || it == AudioAssetKind.AMBIENCE })
        ) {
            """
                CONTINUITY_CONTEXT CHUNG — CHỈ ĐỂ HIỂU ĐIỂM NỐI CHƯƠNG:
                PREVIOUS_CHAPTER_TAIL:
                ${previousChapterTail.trim().ifBlank { "Không có ngữ cảnh chương trước." }.takeLast(3_500)}

                Không tạo cue bằng ID lấy từ phần trên. Không để nội dung chương trước ghi đè bằng chứng của chương hiện tại.
            """.trimIndent()
        } else ""''',
    '''        val freesoundMusicContinuity = incomingFreesoundMusicQuery.orEmpty().trim().ifBlank { "NONE" }
        val freesoundAmbienceContinuity = incomingFreesoundAmbienceQueries
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString("\n") { "- $it" }
            .ifBlank { "- NONE" }
        val continuityBlock = if (
            includeSceneMusic || includeAmbience ||
            (includeFreesoundAudioRequirements && freesoundRequirementKinds.any { it == AudioAssetKind.MUSIC || it == AudioAssetKind.AMBIENCE })
        ) {
            buildString {
                appendLine("CONTINUITY_CONTEXT CHUNG — CHỈ ĐỂ HIỂU ĐIỂM NỐI CHƯƠNG:")
                appendLine("PREVIOUS_CHAPTER_TAIL:")
                appendLine(previousChapterTail.trim().ifBlank { "Không có ngữ cảnh chương trước." }.takeLast(3_500))
                if (includeFreesoundAudioRequirements) {
                    appendLine()
                    appendLine("MODE3_PREVIOUS_BOUNDARY_MUSIC_QUERY: $freesoundMusicContinuity")
                    appendLine("MODE3_PREVIOUS_BOUNDARY_AMBIENCE_QUERIES:")
                    appendLine(freesoundAmbienceContinuity)
                    appendLine()
                    appendLine("Các query Mode 3 trên là ỨNG VIÊN continuity, không phải lệnh bắt buộc giữ. Đầu tiên xét chương hiện tại xem cùng trạng thái MUSIC hoặc cùng nguồn AMBIENCE vật lý có thật sự tiếp diễn không.")
                    appendLine("Nếu thực sự tiếp diễn, giữ NGUYÊN VĂN query tương ứng để ứng dụng tái sử dụng đúng asset đã tải thay vì tìm một biến thể mới chỉ vì sang chương. Nếu bối cảnh/trạng thái đã đổi, bỏ query cũ và chọn query mới hoặc im lặng ngay.")
                    appendLine("Với AMBIENCE, đánh giá từng lớp độc lập: có thể giữ một số lớp, bỏ một số lớp và thêm lớp mới. Không kế thừa SFX chỉ vì nó xuất hiện ở cuối chương trước.")
                }
                appendLine()
                append("Không tạo cue bằng ID lấy từ phần trên. Không để nội dung chương trước ghi đè bằng chứng của chương hiện tại.")
            }.trim()
        } else ""''',
    "MODE3_PREVIOUS_BOUNDARY_AMBIENCE_QUERIES",
)
patch(
    prompt,
    '''                appendLine("- Nếu các UNIT sau không nhắc lại nguồn âm nhưng scene vật lý vẫn liên tục và không có bằng chứng nguồn đã dừng, tiếp tục ambience qua các UNIT đó; chỉ đổi/dừng ở biến cố môi trường thật sự.")''',
    '''                appendLine("- Nếu các UNIT sau không nhắc lại nguồn âm nhưng scene vật lý vẫn liên tục và không có bằng chứng nguồn đã dừng, tiếp tục ambience qua các UNIT đó; chỉ đổi/dừng ở biến cố môi trường thật sự.")
                appendLine("- Ở ranh giới chương cũng áp dụng đúng quy tắc này: sang chương mới KHÔNG phải là lý do đổi ambience. Nếu vẫn cùng núi tuyết, hang động, mưa, gió, sông, đám đông... thì ưu tiên giữ nguồn phù hợp đang có; nhưng không được giữ nếu chương mới cho thấy nguồn/cảnh đã thay đổi hoặc im lặng hợp lý hơn.")''',
    "Ở ranh giới chương cũng áp dụng đúng quy tắc này",
)


# 4) Tighten remote selection quality. A relaxed query may broaden discovery, but the selected
# sound still has to overlap enough with the ORIGINAL semantic query. This prevents a generic one-word
# match from winning via category/duration/popularity bonuses.
resolver = "app/src/main/java/vn/nghetruyen/app/freesound/FreesoundAutoAudioResolver.kt"
patch(
    resolver,
    '''                sound = result.page.results
                    .mapIndexed { index, sound -> sound to scoreCandidate(need, sound, index) }
                    .filter { it.first.preferredPreviewUrl != null }
                    .maxByOrNull { it.second }
                    ?.takeIf { it.second >= REMOTE_MIN_SCORE }
                    ?.first,''',
    '''                sound = result.page.results
                    .mapIndexed { index, sound -> sound to scoreCandidate(need, sound, index) }
                    .filter { (sound, _) -> sound.preferredPreviewUrl != null && candidateMeetsLexicalFloor(need, sound) }
                    .maxByOrNull { it.second }
                    ?.takeIf { it.second >= REMOTE_MIN_SCORE }
                    ?.first,''',
    "candidateMeetsLexicalFloor(need, sound)",
)
patch(
    resolver,
    '''        private const val SEARCH_PAGE_SIZE = 15
        private const val REMOTE_MIN_SCORE = 0.22''',
    '''        private const val SEARCH_PAGE_SIZE = 15
        private const val REMOTE_MIN_SCORE = 0.22
        private const val REMOTE_MIN_LEXICAL_COVERAGE = 0.50''',
    "REMOTE_MIN_LEXICAL_COVERAGE",
)
patch(
    resolver,
    '''        internal fun scoreCandidate(
            need: FreesoundAutoSearchNeed,
            sound: FreesoundSound,
            rankIndex: Int,
        ): Double {
            val queryNorm = FreesoundAutoRequirementAggregator.normalizeQuery(need.query)
            val queryTokens = FreesoundAutoRequirementAggregator.queryTokens(queryNorm)
            if (queryTokens.isEmpty()) return 0.0
            val titleNorm = FreesoundAutoRequirementAggregator.normalizeQuery(sound.name)
            val descriptionNorm = FreesoundAutoRequirementAggregator.normalizeQuery(sound.description)
            val tagNorm = FreesoundAutoRequirementAggregator.normalizeQuery(sound.tags.joinToString(" "))
            val titleTokens = FreesoundAutoRequirementAggregator.queryTokens(titleNorm)
            val descriptionTokens = FreesoundAutoRequirementAggregator.queryTokens(descriptionNorm)
            val tagTokens = FreesoundAutoRequirementAggregator.queryTokens(tagNorm)
            fun coverage(tokens: Set<String>): Double =
                queryTokens.count(tokens::contains).toDouble() / queryTokens.size.toDouble()
            val titleCoverage = coverage(titleTokens)
            val descriptionCoverage = coverage(descriptionTokens)
            val tagCoverage = coverage(tagTokens)
            val lexicalCoverage = max(titleCoverage, max(tagCoverage * 0.96, descriptionCoverage * 0.78))
            if (lexicalCoverage <= 0.0) return 0.0''',
    '''        internal fun candidateLexicalCoverage(
            need: FreesoundAutoSearchNeed,
            sound: FreesoundSound,
        ): Double {
            val queryTokens = FreesoundAutoRequirementAggregator.queryTokens(
                FreesoundAutoRequirementAggregator.normalizeQuery(need.query),
            )
            if (queryTokens.isEmpty()) return 0.0
            fun coverage(text: String): Double {
                val tokens = FreesoundAutoRequirementAggregator.queryTokens(
                    FreesoundAutoRequirementAggregator.normalizeQuery(text),
                )
                return queryTokens.count(tokens::contains).toDouble() / queryTokens.size.toDouble()
            }
            val titleCoverage = coverage(sound.name)
            val descriptionCoverage = coverage(sound.description)
            val tagCoverage = coverage(sound.tags.joinToString(" "))
            return max(titleCoverage, max(tagCoverage * 0.96, descriptionCoverage * 0.78))
        }

        internal fun candidateMeetsLexicalFloor(
            need: FreesoundAutoSearchNeed,
            sound: FreesoundSound,
        ): Boolean = candidateLexicalCoverage(need, sound) >= REMOTE_MIN_LEXICAL_COVERAGE

        internal fun scoreCandidate(
            need: FreesoundAutoSearchNeed,
            sound: FreesoundSound,
            rankIndex: Int,
        ): Double {
            val queryNorm = FreesoundAutoRequirementAggregator.normalizeQuery(need.query)
            val queryTokens = FreesoundAutoRequirementAggregator.queryTokens(queryNorm)
            if (queryTokens.isEmpty()) return 0.0
            val titleNorm = FreesoundAutoRequirementAggregator.normalizeQuery(sound.name)
            val descriptionNorm = FreesoundAutoRequirementAggregator.normalizeQuery(sound.description)
            val tagNorm = FreesoundAutoRequirementAggregator.normalizeQuery(sound.tags.joinToString(" "))
            val lexicalCoverage = candidateLexicalCoverage(need, sound)
            if (lexicalCoverage <= 0.0) return 0.0''',
    "internal fun candidateLexicalCoverage",
)


# 5) Regression tests for unbounded local ambience continuity, explicit Mode-3 continuity semantics,
# and rejection of semantically weak relaxed candidates.
context_test = "app/src/test/java/vn/nghetruyen/app/ai/NarrationPlanContextTest.kt"
patch(
    context_test,
    '''        assertEquals(listOf("rain", "forest", "ignored-third", "rain"), context.incomingAmbienceIds)
        assertEquals("rain|forest", context.incomingAmbienceId)''',
    '''        assertEquals(listOf("rain", "forest", "ignored-third", "rain"), context.incomingAmbienceIds)
        assertEquals("rain|forest|ignored-third", context.incomingAmbienceId)''',
    "rain|forest|ignored-third",
)

prompt_test = "app/src/test/java/vn/nghetruyen/app/ai/XpkAudioPromptQualityTest.kt"
patch(
    prompt_test,
    '''            includeFreesoundAudioRequirements = true,
            freesoundRequirementKinds = setOf(vn.nghetruyen.app.audio.AudioAssetKind.MUSIC, vn.nghetruyen.app.audio.AudioAssetKind.AMBIENCE, vn.nghetruyen.app.audio.AudioAssetKind.SFX),
        )
        assertTrue(prompt.contains("ưu tiên 2 từ"))''',
    '''            includeFreesoundAudioRequirements = true,
            freesoundRequirementKinds = setOf(vn.nghetruyen.app.audio.AudioAssetKind.MUSIC, vn.nghetruyen.app.audio.AudioAssetKind.AMBIENCE, vn.nghetruyen.app.audio.AudioAssetKind.SFX),
            previousChapterTail = "Nhân vật vẫn đứng trên sườn núi tuyết trong gió mạnh.",
            incomingFreesoundMusicQuery = "dark fantasy cinematic",
            incomingFreesoundAmbienceQueries = listOf("snow wind", "mountain wind"),
        )
        assertTrue(prompt.contains("ưu tiên 2 từ"))''',
    "incomingFreesoundAmbienceQueries = listOf(\"snow wind\", \"mountain wind\")",
)
patch(
    prompt_test,
    '''        assertTrue(prompt.contains("không gửi catalog local", ignoreCase = true))
        assertFalse(prompt.contains("TRACK_CATALOG"))''',
    '''        assertTrue(prompt.contains("không gửi catalog local", ignoreCase = true))
        assertTrue(prompt.contains("MODE3_PREVIOUS_BOUNDARY_MUSIC_QUERY: dark fantasy cinematic"))
        assertTrue(prompt.contains("- snow wind"))
        assertTrue(prompt.contains("- mountain wind"))
        assertTrue(prompt.contains("không phải lệnh bắt buộc giữ", ignoreCase = true))
        assertTrue(prompt.contains("giữ NGUYÊN VĂN query"))
        assertTrue(prompt.contains("sang chương mới KHÔNG phải là lý do đổi ambience"))
        assertTrue(prompt.contains("im lặng tốt hơn một âm sai cảnh"))
        assertFalse(prompt.contains("TRACK_CATALOG"))''',
    "MODE3_PREVIOUS_BOUNDARY_MUSIC_QUERY: dark fantasy cinematic",
)

auto_test = "app/src/test/java/vn/nghetruyen/app/freesound/FreesoundAutoAudioTest.kt"
patch(
    auto_test,
    '''    @Test
    fun requirementCachePayloadContainsNoRightsMetadataContract() {''',
    '''    @Test
    fun relaxedSearchStillRejectsAWeakGenericSemanticMatch() {
        val need = FreesoundAutoSearchNeed(
            kind = AudioAssetKind.AMBIENCE,
            query = "snow mountain wind",
            importance = FreesoundRequirementImportance.REQUIRED,
            usages = emptyList(),
        )
        val weak = FreesoundSound(
            id = 77,
            name = "wind",
            description = "strong wind",
            durationSeconds = 60.0,
            previewHqMp3 = "https://cdn.example/wind.mp3",
            previewHqOgg = null,
            category = "Soundscapes",
            tags = listOf("wind"),
            avgRating = 5.0,
            numRatings = 100,
            numDownloads = 100000,
        )
        val strong = weak.copy(
            id = 78,
            name = "snow mountain wind",
            description = "cold wind on snowy mountain",
            tags = listOf("snow", "mountain", "wind"),
        )
        assertFalse(FreesoundAutoAudioResolver.candidateMeetsLexicalFloor(need, weak))
        assertTrue(FreesoundAutoAudioResolver.candidateMeetsLexicalFloor(need, strong))
        assertTrue(
            FreesoundAutoAudioResolver.scoreCandidate(need, strong, 4) >
                FreesoundAutoAudioResolver.scoreCandidate(need, weak, 0),
        )
    }

    @Test
    fun requirementCachePayloadContainsNoRightsMetadataContract() {''',
    "fun relaxedSearchStillRejectsAWeakGenericSemanticMatch()",
)

print("Audio selection + cross-chapter continuity patch prepared.")
