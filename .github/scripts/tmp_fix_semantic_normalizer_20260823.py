from pathlib import Path
import re


def replace_once(path: str, old: str, new: str, label: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 occurrence, found {count} in {path}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")
    print("PATCHED", label)


# 1) Description normalizer: title + current stored description only. No network lookup.
normalizer = "app/src/main/java/vn/nghetruyen/app/ui/components/AudioDescriptionNormalizationDialog.kt"
p = Path(normalizer)
text = p.read_text(encoding="utf-8")
text = text.replace("import vn.nghetruyen.app.freesound.FreesoundImporter\n", "")
text = text.replace(
    "import vn.nghetruyen.source.diagnostics.DiagnosticSeverity\n",
    "import vn.nghetruyen.source.diagnostics.DiagnosticSeverity\n"
    "import vn.nghetruyen.source.diagnostics.DiagnosticOperationContract\n"
    "import vn.nghetruyen.source.diagnostics.DiagnosticOperationState\n",
)
p.write_text(text, encoding="utf-8")

replace_once(
    normalizer,
    '''                traceId = traceId,\n                attributes = attributes,\n''',
    '''                traceId = traceId,\n                attributes = (when (name) {\n                    "AUDIO_DESCRIPTION_APPLY_START" -> DiagnosticOperationContract.attributes(\n                        id = traceId,\n                        kind = "AUDIO_DESCRIPTION_APPLY",\n                        flow = "runtime",\n                        state = DiagnosticOperationState.STARTED,\n                        stage = name,\n                    )\n                    "AUDIO_DESCRIPTION_APPLIED" -> DiagnosticOperationContract.attributes(\n                        id = traceId,\n                        kind = "AUDIO_DESCRIPTION_APPLY",\n                        flow = "runtime",\n                        state = DiagnosticOperationState.COMPLETED,\n                        stage = name,\n                    )\n                    else -> emptyMap()\n                }) + attributes,\n''',
    "terminal AUDIO_DESCRIPTION_APPLY diagnostics",
)

replace_once(
    normalizer,
    '''                    val current = audioDescriptionText(track.tagsCsv)\n                    var source = current\n                    var refreshed = false\n                    if (\n                        source.isBlank() ||\n                        (mode == AudioDescriptionNormalizationScope.ALL_LIBRARY &&\n                            audioDescriptionIsVietnameseStructured(track.tagsCsv))\n                    ) {\n                        val soundId = FreesoundImporter.soundIdFromManagedUri(track.uri)\n                        if (soundId != null) {\n                            val original = application.container.freesoundClient.sound(soundId)?.description.orEmpty().trim()\n                            if (original.isNotBlank()) {\n                                source = original\n                                refreshed = true\n                            }\n                        }\n                    }\n                    if (source.isBlank()) {\n                        errors += "${track.title}: không có mô tả nguồn để chuẩn hóa."\n                    } else {\n                        prepared += PreparedDescription(\n                            track = track,\n                            kind = AudioAssetClassifier.classify(track),\n                            source = source,\n                            refreshed = refreshed,\n                        )\n                    }\n''',
    '''                    val current = audioDescriptionText(track.tagsCsv)\n                    // Offline/cache-only by design: use the title plus description already stored.\n                    // Blank descriptions are still eligible, but the prompt forces conservative title-only inference.\n                    prepared += PreparedDescription(\n                        track = track,\n                        kind = AudioAssetClassifier.classify(track),\n                        source = current,\n                        refreshed = false,\n                    )\n''',
    "offline title + stored-description normalization input",
)

replace_once(
    normalizer,
    '''                Text("Mô tả gốc được gửi cho cùng AI/provider/model đang dùng để phân vai. AI chỉ chuyển thành metadata tiếng Việt theo cấu trúc: Sắc thái / Dùng / Tránh; không được tự bịa nhạc cụ, vật liệu hay nguồn âm nếu mô tả gốc không nói.")''',
    '''                Text("AI dùng tên + mô tả hiện có để viết lại theo Sắc thái / Dùng / Tránh, ưu tiên điểm phân biệt thật giữa các âm gần nhau. Chức năng này không tìm Internet và không tự bịa chi tiết khi cả tên lẫn mô tả đều không hỗ trợ.")''',
    "normalizer help text",
)
replace_once(
    normalizer,
    '''                    Text("TOÀN BỘ: với file Freesound, ứng dụng cố lấy lại mô tả gốc bằng sound ID trước khi AI chuẩn hóa lại. File local không có mô tả nguồn sẽ được liệt kê để bổ sung, không cho AI đoán từ tên.")''',
    '''                    Text("TOÀN BỘ: AI xem lại cả tên và mô tả đang lưu của từng file. Mục mô tả trống vẫn được xử lý thận trọng từ tên; nếu tên cũng mơ hồ, mô tả phải giữ mức khái quát và không khẳng định chi tiết chưa biết.")''',
    "all-library offline explanation",
)

prompt_pattern = re.compile(
    r"private fun descriptionBatchPrompt\(items: List<PreparedDescription>\): String \{.*?\n\}\n\nprivate fun parseDescriptionBatch",
    re.S,
)
prompt_replacement = r'''private fun descriptionBatchPrompt(items: List<PreparedDescription>): String {
    val payload = JSONArray().also { array ->
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.track.id)
                    .put("kind", item.kind.name)
                    .put("title", item.track.title.take(160))
                    .put("existing_description", item.source.take(4_000)),
            )
        }
    }
    return """
Bạn là biên tập viên metadata âm thanh cho bộ chọn semantic của ứng dụng đọc truyện.

Bạn KHÔNG có Internet và KHÔNG được giả vờ đã nghe file. Với mỗi mục, chỉ dùng hai bằng chứng được cung cấp: title và existing_description. Cả hai đều là manh mối; mô tả hiện có có thể đúng, quá chung, trùng mẫu hoặc sai một phần, vì vậy không được mặc định tin tuyệt đối một trường. Khi hai trường khác nhau, chọn cách diễn giải cụ thể nhất mà dữ liệu hiện có thực sự hỗ trợ; nếu không đủ chắc chắn, viết bảo thủ hơn thay vì bịa.

MỤC TIÊU QUAN TRỌNG: description sau khi viết phải tự đứng độc lập vì bộ chọn LOCAL KHÔNG dùng title để matching. Description phải giúp phân biệt file này với các file gần giống cùng nhóm, không chỉ lặp một mẫu chung.

Cách làm cho MỖI mục:
1. Xác định loại âm cần mô tả từ kind.
2. Từ title + existing_description, rút ra các đặc điểm đáng tin: nguồn âm/nhạc cụ/vật liệu/hành động, cường độ, nhịp, khoảng cách, môi trường hoặc cấu trúc thời gian.
3. Giữ 1-3 đặc điểm PHÂN BIỆT nhất so với các âm gần loại. Ví dụ: gần/xa, nhẹ/mạnh, một phát/chuỗi/loop, gió thường/gió qua cáp, đấm vật lý/đấm ma thuật, orchestral heroic/orchestral dark, mưa trên kính/mưa ngoài rừng.
4. Dùng trường Dùng cho tình huống phù hợp thật sự; không viết quá rộng tới mức mọi file cùng loại đều giống nhau.
5. Dùng trường Tránh để nêu các biến thể dễ bị chọn nhầm nhưng khác file này. Không nhồi từ khóa và không mô tả cốt truyện.
6. Nếu existing_description đã tốt và cụ thể, giữ thông tin đúng rồi chỉ làm gọn/chuẩn hơn; không thay đổi chỉ để khác câu chữ.
7. Nếu title rất cụ thể còn mô tả cũ chung chung, được dùng title để làm mô tả chính xác hơn. Nếu title chung chung còn mô tả cụ thể, ưu tiên chi tiết cụ thể của mô tả.
8. Nếu cả title và mô tả đều không xác định được một chi tiết, tuyệt đối không tự khẳng định chi tiết đó.

Quy tắc theo kind:
- MUSIC: ưu tiên cảm xúc, nhịp/cường độ, hướng phát triển và kiểu phối khí; chỉ nêu nhạc cụ khi title hoặc mô tả hỗ trợ. Phân biệt rõ heroic/dark/sad/romantic/tension/action/fantasy/chill và buildup/loop/đột ngột khi có bằng chứng.
- AMBIENCE: ưu tiên nguồn môi trường vật lý + nơi chốn + thời điểm/thời tiết + khoảng cách/mật độ. Phân biệt gió/mưa/nước/biển/rừng/côn trùng/đám đông/giao thông/nội thất và field-recording với sound-design khi có bằng chứng.
- SFX: ưu tiên nguồn + hành động + vật liệu + cường độ/cấu trúc. Phân biệt đấm/đá/ngã/rơi, kiếm vung/va/chém trúng, điện hum/zap/arc, nổ thường/cinematic/phép, whoosh nhanh/dài/nặng, kính nứt/vỡ, đá va/lăn/sạt.

Mỗi description phải là MỘT DÒNG, tối đa 280 ký tự để luôn an toàn dưới giới hạn lưu 300 ký tự, đúng cấu trúc:
Sắc thái: <đặc điểm nghe được và điểm phân biệt>; Dùng: <tình huống phù hợp>; Tránh: <biến thể gần giống nhưng không đúng>

Không thêm ID, tên file, tên truyện, nhân vật, lời quảng cáo, nguồn tải hay giải thích ngoài ba trường. Không dùng markdown.

Chỉ trả JSON hợp lệ:
{"items":[{"id":"...","description":"Sắc thái: ...; Dùng: ...; Tránh: ..."}]}

INPUT:
${payload}
""".trimIndent()
}

private fun parseDescriptionBatch'''
text = Path(normalizer).read_text(encoding="utf-8")
text2, count = prompt_pattern.subn(prompt_replacement, text, count=1)
if count != 1:
    raise SystemExit(f"description prompt replacement count={count}")
Path(normalizer).write_text(text2, encoding="utf-8")
print("PATCHED high-quality description normalization prompt")

replace_once(
    normalizer,
    '''        val description = row.optString("description").replace(Regex("\\s+"), " ").trim().take(300)\n        val lower = description.lowercase()''',
    '''        val description = row.optString("description").replace(Regex("\\s+"), " ").trim()\n        if (description.length > 300) continue\n        val lower = description.lowercase()''',
    "reject overlong AI descriptions instead of truncating structure",
)


# 2) Semantic matcher: penalize source contradictions and unrequested specializations.
matcher = "app/src/main/java/vn/nghetruyen/app/freesound/Mode3LibraryAssetMatcher.kt"
replace_once(
    matcher,
    '''        "magic", "magical", "mind", "mental", "psychic",\n''',
    '''        "magic", "magical", "fantasy", "mind", "mental", "psychic",\n''',
    "fantasy modifier for core-token selection",
)
replace_once(
    matcher,
    '''        "ICE" to setOf("ice", "icy", "frozen", "freezing", "freeze", "bang", "băng", "dong bang", "đóng băng"),\n''',
    '''        "ICE" to setOf("ice", "icy", "frozen", "freezing", "freeze", "bang", "băng", "dong bang", "đóng băng"),\n        "MAGIC" to setOf("magic", "magical", "spell", "sorcery", "enchant", "enchantment", "rune", "mana", "aura", "fantasy", "phep", "phép", "ma thuat", "ma thuật", "linh luc", "linh lực"),\n        "SCI_FI" to setOf("sci fi", "scifi", "spaceship", "starship", "spacecraft", "cruiser", "laser", "pew", "blaster", "cyber", "futuristic", "phi thuyen", "phi thuyền", "tau vu tru", "tàu vũ trụ"),\n        "ENERGY" to setOf("energy", "beam", "charge", "charging", "pulse", "nang luong", "năng lượng"),\n        "EARTH" to setOf("earth", "ground", "soil", "earth magic", "dat", "đất", "tho", "thổ", "tho phap", "thổ pháp"),\n        "ENGINE" to setOf("engine", "motor", "reactor", "turbine", "engine room", "machine hum", "dong co", "động cơ", "may moc", "máy móc"),\n''',
    "magic sci-fi energy earth engine concepts",
)
replace_once(
    matcher,
    '''        "EARTHQUAKE", "STONE", "DEBRIS", "ICE",\n''',
    '''        "EARTHQUAKE", "STONE", "DEBRIS", "ICE", "MAGIC", "SCI_FI", "ENERGY", "EARTH", "ENGINE",\n''',
    "new audible source concepts",
)

replace_once(
    matcher,
    '''    private fun sourceConflictConfidence(required: Set<String>, candidate: Set<String>): Double {\n        val requiredSources = required.filterTo(linkedSetOf(), AUDIBLE_SOURCE_CONCEPTS::contains)\n        val candidateSources = candidate.filterTo(linkedSetOf(), AUDIBLE_SOURCE_CONCEPTS::contains)\n        if (requiredSources.isEmpty() || candidateSources.isEmpty()) return 0.0\n        if (requiredSources.any(candidateSources::contains)) return 0.0\n        fun specificity(size: Int): Double = when (size) {\n            1 -> 1.0\n            2 -> 0.86\n            else -> 0.70\n        }\n        return ((specificity(requiredSources.size) + specificity(candidateSources.size)) / 2.0)\n            .coerceIn(0.0, 1.0)\n    }\n''',
    '''    private fun sourceConflictConfidence(required: Set<String>, candidate: Set<String>): Double {\n        val requiredSources = required.filterTo(linkedSetOf(), AUDIBLE_SOURCE_CONCEPTS::contains)\n        val candidateSources = candidate.filterTo(linkedSetOf(), AUDIBLE_SOURCE_CONCEPTS::contains)\n        if (requiredSources.isEmpty() || candidateSources.isEmpty()) return 0.0\n        val matched = requiredSources.count(candidateSources::contains)\n        if (matched >= requiredSources.size) return 0.0\n        fun specificity(size: Int): Double = when (size) {\n            1 -> 1.0\n            2 -> 0.86\n            else -> 0.70\n        }\n        if (matched == 0) {\n            return ((specificity(requiredSources.size) + specificity(candidateSources.size)) / 2.0)\n                .coerceIn(0.0, 1.0)\n        }\n        val missingRatio = 1.0 - matched.toDouble() / requiredSources.size.toDouble()\n        return (0.55 + missingRatio * 0.35).coerceIn(0.0, 0.80)\n    }\n\n    private fun specializationConflictConfidence(required: Set<String>, candidate: Set<String>): Double {\n        val requiredSpecial = required.filterTo(linkedSetOf(), SPECIALIZATION_CONCEPTS::contains)\n        val candidateSpecial = candidate.filterTo(linkedSetOf(), SPECIALIZATION_CONCEPTS::contains)\n        if (candidateSpecial.isEmpty()) return 0.0\n        if (requiredSpecial.isEmpty()) return 0.88\n        if (requiredSpecial.none(candidateSpecial::contains)) return 0.95\n        val unexpected = candidateSpecial - requiredSpecial\n        return if (unexpected.isEmpty()) 0.0 else 0.55\n    }\n''',
    "partial source and specialization conflict",
)

replace_once(
    matcher,
    '''        val sourceCoverage = sourceConceptCoverage(profile.requiredConcepts, candidateConcepts)\n        val sourceConflict = sourceConflictConfidence(profile.requiredConcepts, candidateConcepts)\n        val conceptContext = conceptCoverage(profile.requiredConcepts, candidateConcepts)\n        val fit = commonSemanticFit(''',
    '''        val sourceCoverage = sourceConceptCoverage(profile.requiredConcepts, candidateConcepts)\n        val sourceConflict = sourceConflictConfidence(profile.requiredConcepts, candidateConcepts)\n        val specializationConflict = specializationConflictConfidence(profile.requiredConcepts, candidateConcepts)\n        val titleConcepts = audibleConcepts(sound.name)\n        val titleSourceConflict = sourceConflictConfidence(profile.requiredConcepts, titleConcepts)\n        val titleSpecializationConflict = specializationConflictConfidence(profile.requiredConcepts, titleConcepts)\n        val identityConflict = max(titleSpecializationConflict, titleSourceConflict * 0.75)\n        val conceptContext = conceptCoverage(profile.requiredConcepts, candidateConcepts)\n        val baseFit = commonSemanticFit(''',
    "remote field-aware conflicts",
)
replace_once(
    matcher,
    '''  hasStructuredContext = profile.hintAware && profile.requiredConcepts.isNotEmpty(),\n        ).coerceIn(0.0, 1.0)\n        val qualified = lexicalQualified &&\n  sourceConflict < HARD_SOURCE_CONFLICT_CONFIDENCE &&\n  fit >= minimumSelectionFit(need.kind)''',
    '''  hasStructuredContext = profile.hintAware && profile.requiredConcepts.isNotEmpty(),\n        )\n        val fit = (\n            baseFit -\n                sourceConflict * REMOTE_SOURCE_CONFLICT_PENALTY -\n                specializationConflict * REMOTE_SPECIALIZATION_CONFLICT_PENALTY -\n                identityConflict * REMOTE_IDENTITY_CONFLICT_PENALTY\n            ).coerceIn(0.0, 1.0)\n        val qualified = lexicalQualified &&\n  sourceConflict < HARD_SOURCE_CONFLICT_CONFIDENCE &&\n  specializationConflict < HARD_SOURCE_CONFLICT_CONFIDENCE &&\n  identityConflict < REMOTE_HARD_IDENTITY_CONFLICT &&\n  fit >= minimumSelectionFit(need.kind)''',
    "remote conflict penalties and veto",
)

replace_once(
    matcher,
    '''        val sourceCoverage = sourceConceptCoverage(requiredConcepts, indexed.audibleConcepts)\n        val sourceConflict = sourceConflictConfidence(requiredConcepts, indexed.audibleConcepts)\n''',
    '''        val sourceCoverage = sourceConceptCoverage(requiredConcepts, indexed.audibleConcepts)\n        val sourceConflict = sourceConflictConfidence(requiredConcepts, indexed.audibleConcepts)\n        val specializationConflict = specializationConflictConfidence(requiredConcepts, indexed.audibleConcepts)\n''',
    "local specialization conflict",
)
replace_once(
    matcher,
    '''      sourceConflict * SOFT_SOURCE_CONFLICT_PENALTY -\n      repetitionPenalty * 0.50''',
    '''      sourceConflict * SOFT_SOURCE_CONFLICT_PENALTY -\n      specializationConflict * SOFT_SPECIALIZATION_CONFLICT_PENALTY -\n      repetitionPenalty * 0.50''',
    "local specialization penalty",
)
replace_once(
    matcher,
    '''  conflict = avoidCoverage,\n  sourceConflictConfidence = sourceConflict,\n        )''',
    '''  conflict = avoidCoverage,\n  sourceConflictConfidence = sourceConflict,\n  specializationConflictConfidence = specializationConflict,\n        )''',
    "pass local specialization conflict",
)
replace_once(
    matcher,
    '''        sourceConflictConfidence: Double,\n    ): String {\n        val reasons = buildList {\n  if (sourceConflictConfidence >= HARD_SOURCE_CONFLICT_CONFIDENCE) {\n      add("sourceConceptMismatch>=${HARD_SOURCE_CONFLICT_CONFIDENCE}")\n  }''',
    '''        sourceConflictConfidence: Double,\n        specializationConflictConfidence: Double,\n    ): String {\n        val reasons = buildList {\n  if (sourceConflictConfidence >= HARD_SOURCE_CONFLICT_CONFIDENCE) {\n      add("sourceConceptMismatch>=${HARD_SOURCE_CONFLICT_CONFIDENCE}")\n  }\n  if (specializationConflictConfidence >= HARD_SOURCE_CONFLICT_CONFIDENCE) {\n      add("unrequestedSpecialization>=${HARD_SOURCE_CONFLICT_CONFIDENCE}")\n  }''',
    "hard reject unwanted local specialization",
)
replace_once(
    matcher,
    '''    private const val SOFT_SOURCE_CONFLICT_PENALTY = 0.18\n''',
    '''    private const val SOFT_SOURCE_CONFLICT_PENALTY = 0.30\n    private const val SOFT_SPECIALIZATION_CONFLICT_PENALTY = 0.44\n    private const val REMOTE_SOURCE_CONFLICT_PENALTY = 0.30\n    private const val REMOTE_SPECIALIZATION_CONFLICT_PENALTY = 0.46\n    private const val REMOTE_IDENTITY_CONFLICT_PENALTY = 0.34\n    private const val REMOTE_HARD_IDENTITY_CONFLICT = 0.90\n''',
    "semantic conflict penalty constants",
)
replace_once(
    matcher,
    '''    private val FIREARM_CONTEXT_TERMS = setOf(''',
    '''    private val SPECIALIZATION_CONCEPTS = setOf(\n        "MAGIC", "SCI_FI", "FIRE", "ICE", "LIGHTNING", "WATER", "EARTH", "GUNFIRE", "ENGINE",\n    )\n\n    private val FIREARM_CONTEXT_TERMS = setOf(''',
    "specialization concept set",
)


# 3) Refresh normalized DB row after import worker succeeds.
resolver = "app/src/main/java/vn/nghetruyen/app/freesound/FreesoundAutoAudioResolver.kt"
replace_once(
    resolver,
    '''                        val result = import.getOrThrow()\n                        resolvedTrack = knownTracks.firstOrNull { it.id == result.trackId && it.enabled }''',
    '''                        val result = import.getOrThrow()\n                        knownTracks = runCatching { existingTracksProvider() }.getOrDefault(knownTracks)\n                        resolvedTrack = knownTracks.firstOrNull { it.id == result.trackId && it.enabled }''',
    "refresh normalized track snapshot after import",
)


# 4) Exact-title managed Freesound reuse must not bypass stale normalization.
importer = "app/src/main/java/vn/nghetruyen/app/freesound/FreesoundImporter.kt"
replace_once(
    importer,
    '''            if (incomingTitleKey.isNotBlank() && exactTitleTrack != null) {\n                return@withLock Result.success(''',
    '''            if (incomingTitleKey.isNotBlank() && exactTitleTrack != null) {\n                if (\n                    rawSoundIdFromManagedUri(exactTitleTrack.uri) != null &&\n                    managedFileExists(appContext, exactTitleTrack.uri) &&\n                    !hasValidNormalization(exactTitleTrack)\n                ) {\n                    return@withLock resumeExistingNormalization(\n                        track = exactTitleTrack,\n                        normalizationTargetLufs = normalizationTargetLufs,\n                    )\n                }\n                return@withLock Result.success(''',
    "resume stale normalization on exact-title managed reuse",
)

print("ALL SOURCE PATCHES APPLIED")
