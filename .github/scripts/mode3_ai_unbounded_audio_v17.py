from pathlib import Path
import re

ROOT = Path('.')


def replace_once(path: str, old: str, new: str) -> None:
    p = ROOT / path
    text = p.read_text(encoding='utf-8')
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{path}: expected exactly one match, got {count}: {old[:120]!r}')
    p.write_text(text.replace(old, new, 1), encoding='utf-8')


def replace_regex_once(path: str, pattern: str, replacement: str) -> None:
    p = ROOT / path
    text = p.read_text(encoding='utf-8')
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.MULTILINE | re.DOTALL)
    if count != 1:
        raise SystemExit(f'{path}: regex expected exactly one match, got {count}: {pattern[:120]!r}')
    p.write_text(updated, encoding='utf-8')


models = 'app/src/main/java/vn/nghetruyen/app/audio/AudioDirectionModels.kt'
requirements = 'app/src/main/java/vn/nghetruyen/app/freesound/FreesoundAutoRequirements.kt'
director = 'app/src/main/java/vn/nghetruyen/app/ai/XpkAmbienceSfxDirector.kt'
prompt = 'app/src/main/java/vn/nghetruyen/app/ai/XpkUnifiedNarrationPrompt.kt'
music = 'app/src/main/java/vn/nghetruyen/app/ai/XpkSceneMusicParity.kt'
runtime = 'app/src/main/java/vn/nghetruyen/app/playback/AudioDirectionRuntime.kt'
ambience_controller = 'app/src/main/java/vn/nghetruyen/app/playback/SceneAmbienceController.kt'
sfx_controller = 'app/src/main/java/vn/nghetruyen/app/playback/SceneSfxController.kt'
test_path = 'app/src/test/java/vn/nghetruyen/app/ai/UnboundedAudioDirectionPolicyTest.kt'

# ---------------------------------------------------------------------------
# One semantic policy from AI -> validation -> plan builder -> playback.
# MUSIC keeps exactly one state at a time. AMBIENCE/SFX have no app-level quota.
# ---------------------------------------------------------------------------
replace_once(
    models,
    '''/**
 * One logical ambience interval. Two rows may overlap on the same UNIT range to represent the
 * PRIMARY + SECONDARY ambience layers. Keeping one id per row preserves the persisted/export format
 * while allowing the runtime and mixer to compose at most two compatible environmental layers.
 */''',
    '''/**
 * One logical ambience interval. Any number of physically distinct, compatible ambience rows may
 * overlap on the same UNIT range when the story really contains those simultaneous sources.
 * Keeping one id per row preserves the persisted/export format without imposing a directing quota.
 */''',
)
replace_once(
    models,
    '''object AudioDirectionLimits {
    const val MAX_CONCURRENT_AMBIENCE = 2
    const val MAX_CONCURRENT_SFX = 3
    const val MAX_SFX_REPEAT_COUNT = 16
    const val MIN_AMBIENCE_SCENE_UNITS = 2
}''',
    '''object AudioDirectionLimits {
    // Compatibility sentinels only. AI-authored AMBIENCE/SFX are not quantity-capped by the app.
    const val MAX_CONCURRENT_AMBIENCE = Int.MAX_VALUE
    const val MAX_CONCURRENT_SFX = Int.MAX_VALUE
    const val MAX_SFX_REPEAT_COUNT = Int.MAX_VALUE
    const val MIN_AMBIENCE_SCENE_UNITS = 1
}''',
)

# ---------------------------------------------------------------------------
# Freesound requirements: no raw-item/per-kind quota and no repeat ceiling.
# Identical/similar queries are still grouped so reuse saves network/data.
# ---------------------------------------------------------------------------
replace_once(requirements, '    private const val MAX_RAW_REQUIREMENTS = 80\n', '')
replace_once(
    requirements,
    '        require(source.length() <= MAX_RAW_REQUIREMENTS) { "AI trả quá nhiều nhu cầu âm thanh Freesound." }\n',
    '',
)
replace_once(
    requirements,
    '''                        val requestedMinSpan = when (kind) {
                            AudioAssetKind.MUSIC -> MIN_MUSIC_REQUIREMENT_UNITS
                            AudioAssetKind.AMBIENCE -> AudioDirectionLimits.MIN_AMBIENCE_SCENE_UNITS
                            else -> 1
                        }.coerceAtMost(validUnitIds.size.coerceAtLeast(1))''',
    '''                        // No artificial minimum duration: a one-UNIT region is valid when the
                        // story really changes there. AI decides duration from narrative evidence.
                        val requestedMinSpan = 1''',
)
replace_once(
    requirements,
    '                        require(repeat in 1..AudioDirectionLimits.MAX_SFX_REPEAT_COUNT) { "$JSON_KEY[$index] có repeat_count ngoài giới hạn." }',
    '                        require(repeat >= 1) { "$JSON_KEY[$index] có repeat_count phải lớn hơn hoặc bằng 1." }',
)
replace_once(
    requirements,
    '''    private const val MIN_MUSIC_REQUIREMENT_UNITS = 2
    private const val MAX_QUERY_TERMS = 3''',
    '''    private const val MAX_QUERY_TERMS = 3''',
)
replace_once(
    requirements,
    '''object FreesoundAutoRequirementAggregator {
    const val MAX_MUSIC_SEARCHES = 3
    const val MAX_AMBIENCE_SEARCHES = 6
    const val MAX_SFX_SEARCHES = 15

    fun aggregate(requirements: List<FreesoundAutoRequirement>): List<FreesoundAutoSearchNeed> {
        if (requirements.isEmpty()) return emptyList()
        return AudioAssetKind.entries.flatMap { kind ->
            val limit = when (kind) {
                AudioAssetKind.MUSIC -> MAX_MUSIC_SEARCHES
                AudioAssetKind.AMBIENCE -> MAX_AMBIENCE_SEARCHES
                AudioAssetKind.SFX -> MAX_SFX_SEARCHES
            }
            val kindRows = requirements.filter { it.kind == kind }''',
    '''object FreesoundAutoRequirementAggregator {
    // Kept for source compatibility with older diagnostics/tests. They no longer cap AI output.
    const val MAX_MUSIC_SEARCHES = Int.MAX_VALUE
    const val MAX_AMBIENCE_SEARCHES = Int.MAX_VALUE
    const val MAX_SFX_SEARCHES = Int.MAX_VALUE

    fun aggregate(requirements: List<FreesoundAutoRequirement>): List<FreesoundAutoSearchNeed> {
        if (requirements.isEmpty()) return emptyList()
        return AudioAssetKind.entries.flatMap { kind ->
            val kindRows = requirements.filter { it.kind == kind }''',
)
replace_once(
    requirements,
    '''                .take(limit)
                .map { group ->''',
    '''                .map { group ->''',
)

# ---------------------------------------------------------------------------
# Mode 2/local validator: quantity is not a validity criterion anymore.
# Structural/timeline/asset-id rules remain intact.
# ---------------------------------------------------------------------------
replace_once(
    director,
    ''' * Ambience keeps the v1 persisted schema (one ambience_id per row), but two rows may overlap to
 * express two compatible logical ambience layers. Existing two-field SFX cues remain readable;
 * rhythm/span fields are optional extensions of that persisted schema.''',
    ''' * Ambience keeps the v1 persisted schema (one ambience_id per row), while any number of compatible
 * rows may overlap when the scene contains multiple real environmental sources. Existing two-field
 * SFX cues remain readable; rhythm/span fields are optional extensions of that persisted schema.''',
)
replace_once(
    director,
    '''        val sfxArray = root.optJSONArray("sfx_cues") ?: JSONArray()
        val maxSfx = maxSfxForUnits(validUnitIds.size)
        require(sfxArray.length() <= maxSfx) { "AI trả quá nhiều SFX cho một chương." }
        val soundEffectCues = mutableListOf<SoundEffectCue>()
        val usedSignatures = hashSetOf<String>()''',
    '''        val sfxArray = root.optJSONArray("sfx_cues") ?: JSONArray()
        val soundEffectCues = mutableListOf<SoundEffectCue>()''',
)
replace_once(
    director,
    '''            require(repeatCount in 1..AudioDirectionLimits.MAX_SFX_REPEAT_COUNT) {
                "sfx_cues[$index] có repeat_count ngoài giới hạn."
            }''',
    '''            require(repeatCount >= 1) {
                "sfx_cues[$index] có repeat_count phải lớn hơn hoặc bằng 1."
            }''',
)
replace_regex_once(
    director,
    r'''\n            val signature = listOf\(\n                unitId,\n                effectId,\n                stopUnitId\.orEmpty\(\),\n                repeatCount\.toString\(\),\n                cadence\.name,\n                loopUntilStop\.toString\(\),\n            \)\.joinToString\("\\\|"\)\n            require\(usedSignatures\.add\(signature\)\) \{ "sfx_cues\[\$index\] lặp lại đúng cùng một cue\." \}''',
    '',
)
replace_once(
    director,
    '''    private fun maxSfxForUnits(unitCount: Int): Int = ((unitCount + 3) / 4).coerceIn(4, 48)
''',
    '',
)

# ---------------------------------------------------------------------------
# Unified AI prompt: AI decides exact amount. No quotas for ambience/SFX.
# ---------------------------------------------------------------------------
replace_once(
    prompt,
    '                add("Không tối đa hóa số lớp, cue hoặc truy vấn. Chỉ yêu cầu âm thanh khi có giá trị nghe rõ ràng; không có nhu cầu ở một lớp hoàn toàn hợp lệ.")',
    '                add("Không có quota cố định cho số lớp AMBIENCE, số scene, số SFX cue, số hiệu ứng đồng thời, số lần lặp hoặc số query. Số lượng phải do chính nội dung quyết định: có thể là 0 hoặc nhiều mục. Không thêm âm thanh chỉ để tăng số lượng; cũng không bỏ âm thanh hợp lý chỉ để giữ ít mục.")',
)
replace_once(
    prompt,
    '            if (includeSoundEffects) add(sfxPromptBlock(sfx.items, ((base.unitIds.size + 3) / 4).coerceIn(4, 48)))',
    '            if (includeSoundEffects) add(sfxPromptBlock(sfx.items))',
)
replace_once(
    prompt,
    '                appendLine("- Giữ cùng nhu cầu nhạc trong toàn vùng còn phù hợp. Vùng MUSIC nằm giữa chương phải bền ít nhất 2 UNIT; không tạo đoạn một UNIT phản ứng quá nhanh.")',
    '                appendLine("- Giữ cùng nhu cầu nhạc trong toàn vùng còn phù hợp. Không có số UNIT tối thiểu hay quota số cảnh; một vùng ngắn vẫn hợp lệ nếu đúng ranh giới kể chuyện thực sự.")',
)
replace_once(
    prompt,
    '                appendLine("- Tại một UNIT chỉ có một trạng thái MUSIC. Không tạo hai query MUSIC khác nhau chồng lên cùng khoảng timeline. Khoảng không có requirement MUSIC nghĩa là im lặng.")',
    '                appendLine("- MUSIC là lớp duy nhất bị giới hạn: tại mọi UNIT chỉ được có tối đa MỘT bài/trạng thái MUSIC. Không tạo hai MUSIC chồng nhau. AI tự quyết định dùng ít, nhiều, đổi bài hay im lặng; continuity chương trước chỉ là tùy chọn, không bắt buộc dùng lại bài cũ.")',
)
replace_once(
    prompt,
    '                appendLine("- Một UNIT có thể có 0, 1 hoặc tối đa 2 lớp ambience tương thích. Hai lớp chỉ chồng khi bổ sung nhau và không mô tả trùng cùng nguồn âm.")',
    '                appendLine("- Không giới hạn số lớp AMBIENCE đồng thời. Có thể chồng 3, 4, 5 hoặc nhiều nguồn nếu chúng thực sự cùng tồn tại và bổ sung nhau, ví dụ mưa + gió + rừng + suối + sấm xa. Không nhân đôi cùng một nguồn chỉ để làm dày âm.")',
)
replace_once(
    prompt,
    '                appendLine("- Ambience phải đủ bền, tối thiểu theo cùng giới hạn của Mode 2; không tạo một lớp chỉ cho một UNIT thoáng qua nếu timeline có nhiều UNIT.")',
    '                appendLine("- Không có độ dài tối thiểu cho AMBIENCE. Một lớp có thể chỉ tồn tại một UNIT hoặc kéo dài nhiều cảnh, miễn ranh giới bắt đầu/dừng đúng với nguồn âm thực tế trong truyện.")',
)
replace_once(
    prompt,
    '                appendLine("- Tối đa 3 SFX đồng thời trên một UNIT, kể cả cue kéo dài từ trước. Không tạo lớp thừa để đạt quota.")',
    '                appendLine("- Không giới hạn số SFX đồng thời trên một UNIT. Nếu nhiều sự kiện thực sự xảy ra cùng lúc thì tạo đủ cue cần thiết; không bỏ cue hợp lý vì số lượng và không thêm cue vô nghĩa để làm dày âm.")',
)
replace_once(
    prompt,
    '''            5. MUSIC: tối đa ${FreesoundAutoRequirementAggregator.MAX_MUSIC_SEARCHES} query khác nhau. Mỗi usage dùng start_id và end_id; không cần phủ kín chương. Không tạo hai MUSIC khác nhau chồng nhau.
            6. AMBIENCE: tối đa ${FreesoundAutoRequirementAggregator.MAX_AMBIENCE_SEARCHES} query khác nhau. Mỗi usage dùng start_id và end_id; cho phép tối đa hai lớp tương thích chồng nhau.
            7. SFX: tối đa ${FreesoundAutoRequirementAggregator.MAX_SFX_SEARCHES} query khác nhau. Mỗi usage dùng unit_id; chỉ thêm stop_unit_id, repeat_count, cadence và loop_until_stop khi thật sự cần.''',
    '''            5. MUSIC: không có quota số query hay số vùng, nhưng tại mỗi UNIT chỉ được có tối đa MỘT MUSIC. Mỗi usage dùng start_id và end_id; khoảng không có MUSIC là im lặng. Bài chương trước chỉ là ứng viên continuity tùy chọn.
            6. AMBIENCE: không có quota số query, số scene hay số lớp đồng thời. Mỗi nguồn vật lý độc lập dùng một usage start_id/end_id và có thể chồng với bất kỳ số nguồn tương thích nào khi cảnh thực sự có chúng.
            7. SFX: không có quota số query, số cue, số cue đồng thời hay repeat_count. Mỗi usage dùng unit_id; COUNTED REPEAT phải phản ánh đúng số hành động được mô tả (ví dụ 10 cú đấm = repeat_count=10). Chỉ thêm stop_unit_id, cadence và loop_until_stop khi thật sự cần.''',
)
replace_once(
    prompt,
    '''            11. Không tối đa hóa số query. Một chương ít âm thanh có thể trả mảng rỗng.
            12. Với MUSIC/AMBIENCE, object chỉ có kind, query, importance, start_id, end_id. Với SFX, object bắt buộc có kind, query, importance, unit_id và chỉ thêm stop_unit_id, repeat_count, cadence, loop_until_stop khi cần.''',
    '''            11. Không có quota số query. Một chương ít âm thanh có thể trả mảng rỗng; một chương dày đặc âm thanh có thể trả nhiều mục. Chất lượng và đúng ngữ cảnh là tiêu chí duy nhất.
            12. Với MUSIC/AMBIENCE, object chỉ có kind, query, importance, start_id, end_id. Với SFX, object bắt buộc có kind, query, importance, unit_id và chỉ thêm stop_unit_id, repeat_count, cadence, loop_until_stop khi cần.''',
)
replace_once(prompt, '            .distinct()\n            .take(2)\n', '            .distinct()\n')
replace_once(
    prompt,
    '            2. Một UNIT có thể có 0, 1 hoặc tối đa 2 ambience đồng thời. Hai ambience chỉ được chồng khi cùng thuộc một cảnh, thực sự bổ sung nhau và không mô tả trùng cùng nguồn âm.',
    '            2. Một UNIT có thể có 0, 1 hoặc bất kỳ số lớp ambience đồng thời nào mà cảnh thực sự cần. Ví dụ mưa + gió + rừng + suối + sấm xa đều có thể cùng tồn tại nếu đều là nguồn nghe được trong cảnh.',
)
replace_once(
    prompt,
    '            3. Khi cần 2 lớp, biểu diễn bằng 2 phần tử ambience_scenes có khoảng start_id/end_id chồng nhau. Tuyệt đối không quá 2 lớp trên cùng UNIT và không lặp cùng ambience_id trên cùng UNIT.',
    '            3. Mỗi nguồn môi trường độc lập dùng một phần tử ambience_scenes; cho phép bất kỳ số khoảng start_id/end_id chồng nhau. Không lặp cùng ambience_id chỉ để tăng âm lượng hoặc số lớp.',
)
replace_once(
    prompt,
    '            5. Ambience phải có độ bền tối thiểu: không tạo một cảnh ambience chỉ cho một UNIT thoáng qua, trừ khi toàn bộ timeline chỉ có một UNIT. Một câu nhắc tới mưa, gió, rừng, tiếng người... chưa đủ để bật/tắt lớp môi trường.',
    '            5. Không có độ dài tối thiểu cho một ambience_scene. Một UNIT cũng hợp lệ nếu nguồn âm thực sự chỉ tồn tại ở đó; ngược lại phải kéo dài scene qua mọi UNIT mà nguồn âm còn tồn tại dù văn bản không nhắc lại.',
)
replace_once(
    prompt,
    '            12. INCOMING_AMBIENCE_IDS là tối đa hai mã số tạm của các lớp đang hoạt động ở cuối chương trước, đã ánh xạ theo AMBIENCE_CATALOG hiện tại. Đánh giá từng lớp độc lập; không ưu tiên giữ chỉ vì continuity.',
    '            12. INCOMING_AMBIENCE_IDS là các mã số tạm của những lớp đang hoạt động ở cuối chương trước và còn ánh xạ được trong AMBIENCE_CATALOG hiện tại. Có thể có nhiều lớp. Đánh giá từng lớp độc lập; continuity chỉ là gợi ý tùy chọn.',
)
replace_once(
    prompt,
    '    private fun sfxPromptBlock(tracks: List<PromptAsset>, maxSfx: Int): String = """',
    '    private fun sfxPromptBlock(tracks: List<PromptAsset>): String = """',
)
replace_once(
    prompt,
    '        5. COUNTED REPEAT: repeat_count từ 2 đến 16, loop_until_stop=false và cadence là VERY_FAST/FAST/NORMAL/SLOW. Nếu truyện nói rõ “đập năm phát” thì dùng repeat_count=5; không tạo 5 cue rời cho cùng chuỗi hành động.',
    '        5. COUNTED REPEAT: repeat_count là đúng số lần hành động được mô tả, từ 2 trở lên, không có trần nhân tạo; loop_until_stop=false và cadence là VERY_FAST/FAST/NORMAL/SLOW. “Đấm mười cú” phải là repeat_count=10; không giảm số và không tạo 10 cue rời cho cùng chuỗi hành động.',
)
replace_once(
    prompt,
    '        8. Một UNIT có thể bắt đầu 0, 1, 2 hoặc tối đa 3 SFX độc lập. Cho phép chồng khi câu chuyện thực sự có nhiều nguồn cùng lúc, ví dụ ngựa phi + ngựa hí; không tạo lớp thừa chỉ để đạt số lượng.',
    '        8. Một UNIT có thể bắt đầu 0, 1 hoặc bất kỳ số SFX độc lập nào nếu câu chuyện thực sự có nhiều nguồn cùng lúc. Ví dụ ngựa phi + ngựa hí + kiếm va + vật rơi có thể chồng; số lượng do nội dung quyết định.',
)
replace_once(
    prompt,
    '        9. Giữ đúng thứ tự timeline. Nhiều cue có cùng unit_id được phép và sẽ bắt đầu đồng thời; tổng số cue đang sống trên cùng UNIT, kể cả cue có stop_unit_id từ trước, không được vượt 3.',
    '        9. Giữ đúng thứ tự timeline. Nhiều cue có cùng unit_id được phép và sẽ bắt đầu đồng thời; cue kéo dài từ trước cũng được chồng với cue mới nếu cả hai còn đúng ở thời điểm đó. Không có quota đồng thời.',
)
replace_once(
    prompt,
    '        11. Không có SFX được biểu diễn bằng việc không tạo cue. Tuyệt đối không trả effect_id="NONE". MAX_SFX_CUES_THIS_CHAPTER chỉ là TRẦN an toàn, không phải quota.',
    '        11. Không có SFX được biểu diễn bằng việc không tạo cue. Tuyệt đối không trả effect_id="NONE". Không có trần số SFX trong chương; chỉ tạo đúng những cue có bằng chứng và giá trị nghe.',
)
replace_once(
    prompt,
    '''
        MAX_SFX_CUES_THIS_CHAPTER: $maxSfx
        SFX_CATALOG (effect_id_số | mô tả):''',
    '''
        SFX_CATALOG (effect_id_số | mô tả):''',
)

# ---------------------------------------------------------------------------
# MUSIC: still exactly one track/state at a time, but no minimum scene length and
# no forced cross-chapter reuse. Current chapter evidence always decides.
# ---------------------------------------------------------------------------
replace_once(music, '    private const val MIN_MIDDLE_SCENE_UNITS = 2\n', '    private const val MIN_MIDDLE_SCENE_UNITS = 1\n')
replace_once(
    music,
    '            8. Ổn định quan trọng hơn phản ứng theo từng câu: không đổi vì một câu thoại, một cảm xúc thoáng qua, một động tác ngắn, một SFX đơn lẻ hoặc một từ khóa. Một cảnh nhạc/im lặng nằm giữa chương phải kéo dài ít nhất $MIN_MIDDLE_SCENE_UNITS UNIT; nếu thay đổi không đủ bền thì giữ trạng thái hiện tại.',
    '            8. Ổn định quan trọng hơn phản ứng máy móc theo từ khóa, nhưng không có số UNIT tối thiểu cho một cảnh. Một vùng một UNIT vẫn hợp lệ nếu đúng tại UNIT đó đã bắt đầu một trạng thái kể chuyện thực sự khác.',
)
replace_once(
    music,
    '            12. Có thể giữ INCOMING_TRACK_ID qua một phần hoặc toàn bộ chương, đổi khỏi nó ngay đầu chương, dùng lại một bài sau khi đã chuyển qua bài khác, hoặc xen khoảng $SILENCE_PROMPT_ID khi phù hợp.',
    '            12. INCOMING_TRACK_ID chỉ là lựa chọn continuity TÙY CHỌN. Chương mới tuyệt đối không bắt buộc dùng lại bài chương trước; có thể giữ, đổi ngay, quay lại một bài cũ hoặc dùng $SILENCE_PROMPT_ID hoàn toàn theo nội dung chương hiện tại.',
)
replace_once(
    music,
    '            21. Kiểm tra không có cảnh nhạc/im lặng giữa chương chỉ tồn tại một UNIT; nếu có, bỏ ranh giới phản ứng quá nhanh và gộp vào trạng thái ổn định phù hợp hơn.',
    '            21. Kiểm tra mỗi ranh giới MUSIC phản ánh một thay đổi kể chuyện thực sự; không kéo dài hoặc gộp cảnh chỉ để đạt một số UNIT tối thiểu vì không có quota độ dài.',
)
replace_once(
    music,
    '            - Cảnh nhạc hoặc im lặng nằm giữa chương không được ngắn hơn $MIN_MIDDLE_SCENE_UNITS UNIT.',
    '            - Không có độ dài tối thiểu hoặc quota số music_scene; ràng buộc duy nhất về lớp là tại một thời điểm chỉ có một track_id/trạng thái MUSIC.',
)

# ---------------------------------------------------------------------------
# Playback runtime: preserve every valid ambience/SFX cue. cueKey prevents a
# state emission from triggering the same planned cue twice; heuristic cooldown
# no longer deletes legitimate closely-spaced actions.
# ---------------------------------------------------------------------------
replace_once(runtime, 'import vn.nghetruyen.app.audio.AudioDirectionLimits\n', '')
replace_once(runtime, '    private val lastEffectAtMillis = linkedMapOf<String, Long>()\n', '')
replace_once(
    runtime,
    '''                if (scene.ambienceId !in ids && ids.size < AudioDirectionLimits.MAX_CONCURRENT_AMBIENCE) {
                    ids += scene.ambienceId
                }''',
    '''                if (scene.ambienceId !in ids) ids += scene.ambienceId''',
)
replace_once(runtime, '        lastEffectAtMillis.clear()\n', '')
replace_once(runtime, '            .take(AudioDirectionLimits.MAX_CONCURRENT_AMBIENCE)\n', '')
replace_once(
    runtime,
    '''        val maxConcurrent = minOf(
            settings.maxConcurrentSfx,
            AudioDirectionLimits.MAX_CONCURRENT_SFX,
        ).coerceAtLeast(1)
        val candidates = mutableListOf<RuntimeSfxCue>()''',
    '''        val candidates = mutableListOf<RuntimeSfxCue>()''',
)
replace_once(
    runtime,
    '''                    "candidateCount" to candidates.size.toString(),
                    "maxConcurrent" to maxConcurrent.toString(),''',
    '''                    "candidateCount" to candidates.size.toString(),
                    "appLevelConcurrencyQuota" to "none",''',
)
replace_once(runtime, '        val now = System.currentTimeMillis()\n        var startedAny = false\n        candidates.take(maxConcurrent).forEach { runtimeCue ->', '        var startedAny = false\n        candidates.forEach { runtimeCue ->')
replace_once(
    runtime,
    '''            val explicitlyRhythmic = cue.repeatCount > 1 || cue.loopUntilStop
            val sameEffectLast = lastEffectAtMillis[cue.effectId] ?: 0L
            val cooldown = maxOf(settings.minimumSfxGapMillis, settings.sameEffectCooldownMillis)
            if (!explicitlyRhythmic && now - sameEffectLast < cooldown) return@forEach

            val started = sfxController.play(''',
    '''            val started = sfxController.play(''',
)
replace_once(runtime, '                maxConcurrent = maxConcurrent,\n', '                maxConcurrent = Int.MAX_VALUE,\n')
replace_once(
    runtime,
    '''            if (started) {
                startedAny = true
                lastEffectAtMillis[cue.effectId] = now
            }''',
    '''            if (started) startedAny = true''',
)
replace_regex_once(
    runtime,
    r'''\n        if \(lastEffectAtMillis\.size > MAX_EFFECT_HISTORY\) \{\n            val cutoff = now - settings\.sameEffectCooldownMillis \* 2\n            lastEffectAtMillis\.entries\.removeAll \{ it\.value < cutoff \}\n        \}\n''',
    '\n',
)
# clearPreparedPlan contains a second history clear in some revisions.
p = ROOT / runtime
text = p.read_text(encoding='utf-8').replace('        lastEffectAtMillis.clear()\n', '')
p.write_text(text, encoding='utf-8')

# ---------------------------------------------------------------------------
# Ambience mixer: no layer count clipping. Use equal-power-ish scaling so many
# legitimate layers do not simply sum to clipping; this changes mix level, not quota.
# ---------------------------------------------------------------------------
replace_once(ambience_controller, 'import kotlin.math.roundToInt\n', 'import kotlin.math.roundToInt\nimport kotlin.math.sqrt\n')
replace_once(ambience_controller, 'import vn.nghetruyen.app.audio.AudioDirectionLimits\n', '')
replace_once(
    ambience_controller,
    '''/**
 * Voice-first ambience bus with at most two logical layers.''',
    '''/**
 * Voice-first ambience bus with any number of logical layers selected by the AI.''',
)
replace_once(ambience_controller, '            .take(AudioDirectionLimits.MAX_CONCURRENT_AMBIENCE)\n', '')
replace_once(
    ambience_controller,
    '        val mixScale = if (requested.size > 1) DUAL_LAYER_SCALE else 1f',
    '        val mixScale = if (requested.size <= 1) 1f else 1f / sqrt(requested.size.toFloat())',
)
replace_once(ambience_controller, '        private const val DUAL_LAYER_SCALE = 0.78f\n', '')

# ---------------------------------------------------------------------------
# SFX player: no concurrent-player eviction and no repeat ceiling. Repeats are
# scheduled one-at-a-time so a large exact repeatCount does not allocate N callbacks.
# maxConcurrent remains only as a source-compatible legacy parameter and is ignored.
# ---------------------------------------------------------------------------
replace_once(sfx_controller, 'import vn.nghetruyen.app.audio.AudioDirectionLimits\n', '')
replace_once(sfx_controller, '/** Bounded foreground-SFX player with cue-scoped stop/repeat/loop control. */', '/** Foreground-SFX player with cue-scoped stop/repeat/loop control and no app-level quantity quota. */')
replace_once(sfx_controller, '    @Synchronized\n    fun play(\n', '    @Suppress("UNUSED_PARAMETER")\n    @Synchronized\n    fun play(\n')
replace_once(
    sfx_controller,
    '''        val key = cueKey.ifBlank { "one-shot:${System.nanoTime()}" }
        val limit = maxConcurrent.coerceIn(1, AudioDirectionLimits.MAX_CONCURRENT_SFX)
        val safeRepeatCount = repeatCount.coerceIn(1, AudioDirectionLimits.MAX_SFX_REPEAT_COUNT)
        val safeInterval = repeatIntervalMillis.coerceIn(120L, 2_000L)

        if (loopUntilStopped) stopCue(key)
        val started = startOne(asset, masterVolume, limit, key, looping = loopUntilStopped)
        if (!started) return false
        if (!loopUntilStopped && safeRepeatCount > 1) {
            for (repeatIndex in 1 until safeRepeatCount) {
                lateinit var task: Runnable
                task = Runnable {
                    synchronized(this@SceneSfxController) {
                        pendingCallbacks[key]?.let { callbacks ->
                            callbacks.remove(task)
                            if (callbacks.isEmpty()) pendingCallbacks.remove(key)
                        }
                        startOne(asset, masterVolume, limit, key, looping = false)
                    }
                }
                pendingCallbacks.getOrPut(key) { mutableListOf() }.add(task)
                handler.postDelayed(task, safeInterval * repeatIndex)
            }
        }
        return true''',
    '''        val key = cueKey.ifBlank { "one-shot:${System.nanoTime()}" }
        val safeRepeatCount = repeatCount.coerceAtLeast(1)
        val safeInterval = repeatIntervalMillis.coerceIn(120L, 2_000L)

        if (loopUntilStopped) stopCue(key)
        val started = startOne(asset, masterVolume, key, looping = loopUntilStopped)
        if (!started) return false
        if (!loopUntilStopped && safeRepeatCount > 1) {
            scheduleRepeat(
                asset = asset,
                masterVolume = masterVolume,
                cueKey = key,
                remaining = safeRepeatCount - 1,
                intervalMillis = safeInterval,
            )
        }
        return true''',
)
replace_once(
    sfx_controller,
    '''    @Synchronized
    fun stopCue(cueKey: String) {''',
    '''    private fun scheduleRepeat(
        asset: AudioDirectionAsset,
        masterVolume: Float,
        cueKey: String,
        remaining: Int,
        intervalMillis: Long,
    ) {
        if (remaining <= 0) return
        lateinit var task: Runnable
        task = Runnable {
            synchronized(this@SceneSfxController) {
                pendingCallbacks[cueKey]?.let { callbacks ->
                    callbacks.remove(task)
                    if (callbacks.isEmpty()) pendingCallbacks.remove(cueKey)
                }
                startOne(asset, masterVolume, cueKey, looping = false)
                scheduleRepeat(asset, masterVolume, cueKey, remaining - 1, intervalMillis)
            }
        }
        pendingCallbacks.getOrPut(cueKey) { mutableListOf() }.add(task)
        handler.postDelayed(task, intervalMillis)
    }

    @Synchronized
    fun stopCue(cueKey: String) {''',
)
replace_once(
    sfx_controller,
    '''    private fun startOne(
        asset: AudioDirectionAsset,
        masterVolume: Float,
        limit: Int,
        cueKey: String,
        looping: Boolean,
    ): Boolean {
        while (activePlayers.size >= limit) releaseOldest()''',
    '''    private fun startOne(
        asset: AudioDirectionAsset,
        masterVolume: Float,
        cueKey: String,
        looping: Boolean,
    ): Boolean {''',
)

# ---------------------------------------------------------------------------
# Regression tests: 5 ambience layers, 8 simultaneous SFX, exact repeat 25,
# >80 Freesound rows and >old 15-SFX search cap all remain intact.
# ---------------------------------------------------------------------------
p = ROOT / test_path
p.parent.mkdir(parents=True, exist_ok=True)
p.write_text(r'''package vn.nghetruyen.app.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.app.audio.AudioAssetKind
import vn.nghetruyen.app.audio.AudioDirectionLimits
import vn.nghetruyen.app.freesound.FreesoundAutoRequirement
import vn.nghetruyen.app.freesound.FreesoundAutoRequirementAggregator
import vn.nghetruyen.app.freesound.FreesoundAutoRequirementCodec

class UnboundedAudioDirectionPolicyTest {
    @Test
    fun localPlanAcceptsManyAmbienceLayersSimultaneousSfxAndExactRepeat() {
        val unit = "P0001-U01"
        val ambience = (1..5).map { "a$it" }.toSet()
        val sfx = (1..8).map { "s$it" }.toSet()
        val root = JSONObject()
        root.put("ambience_scenes", JSONArray().also { rows ->
            ambience.forEach { id ->
                rows.put(JSONObject().put("start_id", unit).put("end_id", unit).put("ambience_id", id))
            }
        })
        root.put("sfx_cues", JSONArray().also { rows ->
            sfx.forEachIndexed { index, id ->
                rows.put(
                    JSONObject()
                        .put("unit_id", unit)
                        .put("effect_id", id)
                        .put("repeat_count", if (index == 0) 25 else 1),
                )
            }
        })

        val plan = XpkAmbienceSfxDirector.parseAndValidate(
            raw = root.toString(),
            validUnitIds = listOf(unit),
            validAmbienceIds = ambience,
            validSfxIds = sfx,
            ambienceEnabled = true,
            soundEffectsEnabled = true,
        )
        assertEquals(5, plan.ambienceScenes.size)
        assertEquals(8, plan.soundEffectCues.size)
        assertEquals(25, plan.soundEffectCues.first().repeatCount)
    }

    @Test
    fun freesoundParserAndAggregatorDoNotApplyOldQuantityCaps() {
        val unit = "P0001-U01"
        val rows = JSONArray()
        repeat(100) { index ->
            rows.put(
                JSONObject()
                    .put("kind", "SFX")
                    .put("query", "impact hit $index")
                    .put("importance", "OPTIONAL")
                    .put("unit_id", unit)
                    .put("repeat_count", if (index == 0) 40 else 1),
            )
        }
        val parsed = FreesoundAutoRequirementCodec.parse(
            root = JSONObject().put(FreesoundAutoRequirementCodec.JSON_KEY, rows),
            validUnitIds = listOf(unit),
            enabledKinds = setOf(AudioAssetKind.SFX),
        )
        assertEquals(100, parsed.size)
        assertEquals(40, parsed.first().repeatCount)
        assertEquals(100, FreesoundAutoRequirementAggregator.aggregate(parsed).size)
    }

    @Test
    fun compatibilityLimitsNoLongerClipAiAuthoredAudio() {
        assertEquals(Int.MAX_VALUE, AudioDirectionLimits.MAX_CONCURRENT_AMBIENCE)
        assertEquals(Int.MAX_VALUE, AudioDirectionLimits.MAX_CONCURRENT_SFX)
        assertEquals(Int.MAX_VALUE, AudioDirectionLimits.MAX_SFX_REPEAT_COUNT)
        assertEquals(1, AudioDirectionLimits.MIN_AMBIENCE_SCENE_UNITS)
        assertTrue(FreesoundAutoRequirementAggregator.MAX_SFX_SEARCHES > 1_000_000)
    }
}
''', encoding='utf-8')

print('Mode 3 V17 unbounded AI audio policy applied: MUSIC one-at-a-time; AMBIENCE/SFX quotas removed; long files unchanged.')
