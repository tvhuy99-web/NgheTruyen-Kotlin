from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def replace_once(path: Path, old: str, new: str, label: str):
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_expected(path: Path, old: str, new: str, expected: int, label: str):
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != expected:
        raise RuntimeError(f"{label}: expected {expected} matches, found {count}")
    path.write_text(text.replace(old, new), encoding="utf-8")


def replace_between(path: Path, start: str, end: str, replacement: str, label: str):
    text = path.read_text(encoding="utf-8")
    i = text.find(start)
    if i < 0:
        raise RuntimeError(f"{label}: start marker not found")
    j = text.find(end, i)
    if j < 0:
        raise RuntimeError(f"{label}: end marker not found")
    text = text[:i] + replacement + text[j:]
    path.write_text(text, encoding="utf-8")


# 1) Prompt: generate short, searchable Freesound terms instead of prose-like descriptions.
prompt = ROOT / "app/src/main/java/vn/nghetruyen/app/ai/XpkUnifiedNarrationPrompt.kt"
new_block = '''    private fun freesoundRequirementBlock(kinds: Set<AudioAssetKind>): String {\n        val enabled = AudioAssetKind.entries.filter(kinds::contains)\n        val kindNames = enabled.joinToString(", ") { it.name }\n        return """\n            MODULE FREESOUND AUTO — CHỈ XÁC ĐỊNH NHU CẦU TÌM KIẾM:\n            Các lớp được phép trong lượt này: $kindNames.\n\n            1. Không chọn asset local, Freesound ID, tên file, tác giả, license, URL, timestamp hoặc metadata nguồn. Chỉ tạo query tiếng Anh dạng từ khóa tìm kiếm.\n            2. MỖI query ưu tiên 2 từ, chỉ dùng 3 từ khi từ thứ ba thực sự giúp phân biệt. Tuyệt đối không viết câu tự nhiên dài và không quá 3 search term hữu ích. Freesound mặc định coi các term là bắt buộc, vì vậy mỗi từ thừa đều làm giảm mạnh khả năng có kết quả.\n            3. Viết query bằng từ tiếng Anh phổ biến mà người đăng âm thanh thực tế có khả năng dùng trong tên/tag/mô tả. Dùng chữ thường, không tên nhân vật, địa danh hư cấu, thuật ngữ cốt truyện hoặc khái niệm trừu tượng không nghe được.\n            4. Đặt từ khóa âm học/nguồn âm quan trọng nhất trước. Bỏ a/an/the, with/on/in/of/to/for, very, single, sound, audio, effect và các từ trang trí không giúp tìm kiếm.\n            5. MUSIC: tối đa ${FreesoundAutoRequirementAggregator.MAX_MUSIC_SEARCHES} query khác nhau. Query ưu tiên mood + nhạc cụ/phong cách nghe được, ví dụ “tense guqin”, “sad flute”, “epic drums”. Tránh query kiểu “dark cultivation tension music” hoặc mô tả tình tiết truyện. Mỗi usage dùng start_id và end_id; không cần phủ kín chương.\n            6. AMBIENCE: tối đa ${FreesoundAutoRequirementAggregator.MAX_AMBIENCE_SEARCHES} query khác nhau. Query ưu tiên nguồn âm vật lý kéo dài + môi trường khi cần, ví dụ “forest wind”, “heavy rain”, “cave water”. Không thêm từ ambience/sound/audio nếu hai từ đã đủ. Mỗi usage dùng start_id và end_id; cho phép tối đa hai lớp tương thích chồng nhau.\n            7. SFX: tối đa ${FreesoundAutoRequirementAggregator.MAX_SFX_SEARCHES} query khác nhau. Query ưu tiên vật/chất liệu + hành động âm học ngắn, ví dụ “debris crash”, “wood thud”, “brush writing”, “sword clash”. Mỗi usage dùng unit_id; chỉ thêm stop_unit_id, repeat_count, cadence và loop_until_stop khi thật sự cần.\n            8. Nếu cùng một loại âm thanh được dùng nhiều lần, giữ CHÍNH XÁC cùng chuỗi query ở các usage để ứng dụng chỉ tìm/tải một asset rồi tái sử dụng.\n            9. Nếu 2 từ đã mô tả đúng nguồn âm thì KHÔNG thêm từ thứ ba. Khi phân vân giữa từ mô tả cảm xúc/cốt truyện và từ mô tả âm nghe được, luôn chọn từ mô tả âm nghe được.\n            10. importance chỉ là REQUIRED hoặc OPTIONAL. REQUIRED chỉ dành cho âm thanh có vai trò nghe rõ ràng đối với cảnh; không lạm dụng REQUIRED.\n            11. Không tối đa hóa số query. Một chương ít âm thanh có thể trả mảng rỗng.\n            12. Với MUSIC/AMBIENCE, object chỉ có kind, query, importance, start_id, end_id. Với SFX, object bắt buộc có kind, query, importance, unit_id và chỉ thêm stop_unit_id, repeat_count, cadence, loop_until_stop khi cần.\n        """.trimIndent()\n    }\n\n'''
replace_between(
    prompt,
    '    private fun freesoundRequirementBlock(kinds: Set<AudioAssetKind>): String {\n',
    '    private fun ambiencePromptBlock(',
    new_block,
    "freesound prompt block",
)

# 2) Parser/aggregator safety: canonicalize overlong AI queries and prefer the shortest equivalent query.
requirements = ROOT / "app/src/main/java/vn/nghetruyen/app/freesound/FreesoundAutoRequirements.kt"
replace_once(
    requirements,
    '''                val query = oneLine(row.optString("query")).take(MAX_QUERY_CHARS)\n                require(query.isNotBlank()) { "$JSON_KEY[$index] thiếu query Freesound." }\n''',
    '''                val query = canonicalSearchQuery(oneLine(row.optString("query")).take(MAX_QUERY_CHARS), kind)\n                require(query.isNotBlank()) { "$JSON_KEY[$index] thiếu query Freesound." }\n''',
    "canonical query parse",
)
replace_once(
    requirements,
    '''    private fun oneLine(value: String): String = value.replace(Regex("\\\\s+"), " ").trim()\n''',
    '''    internal fun canonicalSearchQuery(value: String, kind: AudioAssetKind): String {\n        val normalized = FreesoundAutoRequirementAggregator.normalizeQuery(value)\n        if (normalized.isBlank()) return ""\n        val tokens = normalized.split(' ')\n            .map(String::trim)\n            .filter { it.length >= 2 && it !in QUERY_STOPWORDS }\n            .filterNot { token -> token in QUERY_GENERIC_TERMS && token != "music" && kind == AudioAssetKind.MUSIC }\n        if (tokens.isEmpty()) return normalized.split(' ').filter(String::isNotBlank).takeLast(MAX_QUERY_TERMS).joinToString(" ")\n        return tokens.takeLast(MAX_QUERY_TERMS).joinToString(" ")\n    }\n\n    private fun oneLine(value: String): String = value.replace(Regex("\\\\s+"), " ").trim()\n\n    private val QUERY_STOPWORDS = setOf(\n        "a", "an", "the", "with", "on", "in", "of", "to", "for", "from", "by", "into", "onto",\n        "very", "single", "one", "some", "and", "or",\n    )\n    private val QUERY_GENERIC_TERMS = setOf("sound", "audio", "effect", "ambience")\n    private const val MAX_QUERY_TERMS = 3\n''',
    "canonical query helper",
)
replace_once(
    requirements,
    '''                    val representative = group.maxByOrNull { queryTokens(normalizeQuery(it.query)).size } ?: group.first()\n''',
    '''                    val representative = group.minWithOrNull(\n                        compareBy<FreesoundAutoRequirement> { queryTokens(normalizeQuery(it.query)).size }\n                            .thenBy { normalizeQuery(it.query).length },\n                    ) ?: group.first()\n''',
    "shortest representative query",
)

# 3) Retry strategy now becomes 3 terms -> 2 terms -> 1 term after the prompt/parser already keep queries concise.
resolver = ROOT / "app/src/main/java/vn/nghetruyen/app/freesound/FreesoundAutoAudioResolver.kt"
replace_once(
    resolver,
    '''            val keep = if (retryAttempt == 2) 3 else 2\n            return tokens.takeLast(keep.coerceAtMost(tokens.size)).joinToString(" ").ifBlank { original }\n''',
    '''            val keep = if (retryAttempt == 2) 2 else 1\n            return tokens.takeLast(keep.coerceAtMost(tokens.size)).joinToString(" ").ifBlank { original }\n''',
    "retry query narrowing",
)
replace_once(
    resolver,
    '''                retryAttempt == 2 -> "RELAXED_3_TERMS"\n                else -> "RELAXED_2_TERMS"\n''',
    '''                retryAttempt == 2 -> "RELAXED_2_TERMS"\n                else -> "RELAXED_1_TERM"\n''',
    "retry strategy labels",
)

# 4) Decoder: allow Mode-3 analysis to stop after a bounded audio window instead of decoding minutes of audio.
decoder = ROOT / "app/src/main/java/vn/nghetruyen/app/audio/AndroidAudioTrackDecoder.kt"
replace_once(
    decoder,
    '''        targetChannels: Int,\n        destination: File,\n    ) {\n        require(targetSampleRate in 8_000..192_000)\n        require(targetChannels in 1..2)\n''',
    '''        targetChannels: Int,\n        destination: File,\n        maxDecodeDurationUs: Long? = null,\n    ) {\n        require(targetSampleRate in 8_000..192_000)\n        require(targetChannels in 1..2)\n        require(maxDecodeDurationUs == null || maxDecodeDurationUs > 0L)\n''',
    "decoder duration parameter",
)
replace_once(
    decoder,
    '''                            val size = extractor.readSampleData(inputBuffer, 0)\n                            if (size < 0) {\n                                codec.queueInputBuffer(\n                                    inputIndex,\n                                    0,\n                                    0,\n                                    0L,\n                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,\n                                )\n                                inputDone = true\n                            } else {\n                                codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime.coerceAtLeast(0L), 0)\n                                extractor.advance()\n                            }\n''',
    '''                            val sampleTimeUs = extractor.sampleTime.coerceAtLeast(0L)\n                            val size = extractor.readSampleData(inputBuffer, 0)\n                            val durationReached = maxDecodeDurationUs != null && sampleTimeUs >= maxDecodeDurationUs\n                            if (size < 0 || durationReached) {\n                                codec.queueInputBuffer(\n                                    inputIndex,\n                                    0,\n                                    0,\n                                    sampleTimeUs,\n                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,\n                                )\n                                inputDone = true\n                            } else {\n                                codec.queueInputBuffer(inputIndex, 0, size, sampleTimeUs, 0)\n                                extractor.advance()\n                            }\n''',
    "decoder duration cutoff",
)

# 5) Scene analysis: Mode-3 Freesound uses a representative bounded window; local/manual normalization remains full-file.
worker = ROOT / "app/src/main/java/vn/nghetruyen/app/audio/SceneMusicAnalysisWorker.kt"
replace_once(
    worker,
    '''        val forceRemeasure = inputData.getBoolean(KEY_FORCE_REMEASURE, false)\n\n        if (forceRemeasure) {\n''',
    '''        val forceRemeasure = inputData.getBoolean(KEY_FORCE_REMEASURE, false)\n        val fastFreesound = inputData.getBoolean(KEY_FAST_FREESOUND, false)\n\n        if (forceRemeasure) {\n''',
    "worker fast flag",
)
replace_once(
    worker,
    '''                AndroidAudioTrackDecoder.decodeToWave(\n                    context = applicationContext,\n                    uri = Uri.parse(track.uri),\n                    targetSampleRate = 44_100,\n                    targetChannels = 2,\n                    destination = temp,\n                )\n''',
    '''                AndroidAudioTrackDecoder.decodeToWave(\n                    context = applicationContext,\n                    uri = Uri.parse(track.uri),\n                    targetSampleRate = 44_100,\n                    targetChannels = 2,\n                    destination = temp,\n                    maxDecodeDurationUs = if (fastFreesound) fastAnalysisDurationUs(kind) else null,\n                )\n''',
    "worker bounded decode",
)
replace_once(
    worker,
    '''        private const val KEY_FORCE_REMEASURE = "force_remeasure"\n        private const val KEY_LOUDNESS = "loudness_lufs"\n''',
    '''        private const val KEY_FORCE_REMEASURE = "force_remeasure"\n        private const val KEY_FAST_FREESOUND = "fast_freesound"\n        private const val KEY_LOUDNESS = "loudness_lufs"\n''',
    "worker fast key",
)
replace_once(
    worker,
    '''        fun enqueue(\n            context: Context,\n            trackId: String,\n            targetLufs: Float? = null,\n            forceRemeasure: Boolean = false,\n        ): UUID {\n            val data = Data.Builder()\n                .putString(KEY_TRACK_ID, trackId)\n                .putBoolean(KEY_FORCE_REMEASURE, forceRemeasure)\n''',
    '''        internal fun fastAnalysisDurationUs(kind: AudioAssetKind): Long = when (kind) {\n            AudioAssetKind.MUSIC -> 45_000_000L\n            AudioAssetKind.AMBIENCE -> 30_000_000L\n            AudioAssetKind.SFX -> 15_000_000L\n        }\n\n        fun enqueue(\n            context: Context,\n            trackId: String,\n            targetLufs: Float? = null,\n            forceRemeasure: Boolean = false,\n            fastFreesound: Boolean = false,\n        ): UUID {\n            val data = Data.Builder()\n                .putString(KEY_TRACK_ID, trackId)\n                .putBoolean(KEY_FORCE_REMEASURE, forceRemeasure)\n                .putBoolean(KEY_FAST_FREESOUND, fastFreesound)\n''',
    "worker enqueue fast mode",
)

# 6) Only Mode-3 Freesound imports use fast bounded analysis. Existing local/manual workflows keep full precision.
importer = ROOT / "app/src/main/java/vn/nghetruyen/app/freesound/FreesoundImporter.kt"
replace_expected(
    importer,
    '''                targetLufs = normalizationTargetLufs,\n            )\n''',
    '''                targetLufs = normalizationTargetLufs,\n                fastFreesound = true,\n            )\n''',
    2,
    "freesound fast normalization enqueue",
)
replace_once(
    importer,
    '''        private const val NORMALIZATION_POLL_MS = 300L\n''',
    '''        private const val NORMALIZATION_POLL_MS = 120L\n''',
    "normalization poll interval",
)

# 7) Regression tests for the new query contract and fast normalization policy.
mode3_test = ROOT / "app/src/test/java/vn/nghetruyen/app/freesound/FreesoundMode3RegressionTest.kt"
text = mode3_test.read_text(encoding="utf-8")
method = '''\n    @Test\n    fun canonicalSearchQueryCapsProseToThreeUsefulTerms() {\n        val query = FreesoundAutoRequirementCodec.canonicalSearchQuery(\n            "heavy landing thud on wood",\n            AudioAssetKind.SFX,\n        )\n        assertEquals("landing thud wood", query)\n        assertTrue(query.split(' ').size <= 3)\n    }\n\n    @Test\n    fun aggregatorPrefersShorterEquivalentQuery() {\n        val long = FreesoundAutoRequirement(\n            kind = AudioAssetKind.SFX,\n            query = "debris wall crash",\n            unitId = "U1",\n        )\n        val short = long.copy(query = "debris crash", unitId = "U2")\n        val need = FreesoundAutoRequirementAggregator.aggregate(listOf(long, short)).single()\n        assertEquals("debris crash", need.query)\n    }\n'''
if "canonicalSearchQueryCapsProseToThreeUsefulTerms" not in text:
    pos = text.rfind("}\n")
    if pos < 0:
        raise RuntimeError("mode3 regression test closing brace not found")
    text = text[:pos] + method + text[pos:]
mode3_test.write_text(text, encoding="utf-8")

prompt_test = ROOT / "app/src/test/java/vn/nghetruyen/app/ai/XpkAudioPromptQualityTest.kt"
text = prompt_test.read_text(encoding="utf-8")
method = '''\n    @Test\n    fun freesoundPromptRequiresShortSearchableQueries() {\n        val base = XpkVoiceCastPrompt.build(\n            title = "Freesound",\n            body = "Gió rít qua rừng. Một khối đá đổ sập.",\n            profiles = emptyList(),\n            storyNote = "",\n            expressiveAdjustment = false,\n            speedLimitPct = 0,\n            pitchLimitPct = 0,\n            volumeLimitPct = 0,\n            expressionPrompt = "",\n            includeVoiceCast = false,\n            includeSceneMusic = false,\n            includeAudioDirection = true,\n        )\n        val prompt = XpkUnifiedNarrationPrompt.compose(\n            base = base,\n            title = "Freesound",\n            includeVoiceCast = false,\n            includeSceneMusic = false,\n            includeAmbience = false,\n            includeSoundEffects = false,\n            ambienceTracks = emptyList(),\n            soundEffectTracks = emptyList(),\n            includeFreesoundAudioRequirements = true,\n            freesoundRequirementKinds = setOf(vn.nghetruyen.app.audio.AudioAssetKind.MUSIC, vn.nghetruyen.app.audio.AudioAssetKind.AMBIENCE, vn.nghetruyen.app.audio.AudioAssetKind.SFX),\n        )\n        assertTrue(prompt.contains("ưu tiên 2 từ"))\n        assertTrue(prompt.contains("không quá 3 search term"))\n        assertTrue(prompt.contains("Freesound mặc định coi các term là bắt buộc"))\n        assertTrue(prompt.contains("debris crash"))\n        assertTrue(prompt.contains("forest wind"))\n    }\n'''
if "freesoundPromptRequiresShortSearchableQueries" not in text:
    pos = text.rfind("}\n")
    if pos < 0:
        raise RuntimeError("prompt test closing brace not found")
    text = text[:pos] + method + text[pos:]
prompt_test.write_text(text, encoding="utf-8")

audio_test = ROOT / "app/src/test/java/vn/nghetruyen/app/audio/Mode3FastNormalizationPolicyTest.kt"
audio_test.parent.mkdir(parents=True, exist_ok=True)
audio_test.write_text('''package vn.nghetruyen.app.audio\n\nimport org.junit.Assert.assertEquals\nimport org.junit.Assert.assertTrue\nimport org.junit.Test\n\nclass Mode3FastNormalizationPolicyTest {\n    @Test\n    fun mode3AnalysisWindowsAreBoundedByKind() {\n        assertEquals(45_000_000L, SceneMusicAnalysisWorker.fastAnalysisDurationUs(AudioAssetKind.MUSIC))\n        assertEquals(30_000_000L, SceneMusicAnalysisWorker.fastAnalysisDurationUs(AudioAssetKind.AMBIENCE))\n        assertEquals(15_000_000L, SceneMusicAnalysisWorker.fastAnalysisDurationUs(AudioAssetKind.SFX))\n        assertTrue(SceneMusicAnalysisWorker.fastAnalysisDurationUs(AudioAssetKind.AMBIENCE) < 60_000_000L)\n    }\n}\n''', encoding="utf-8")

print("Mode 3 V11 prompt + fast normalization patch applied successfully.")
