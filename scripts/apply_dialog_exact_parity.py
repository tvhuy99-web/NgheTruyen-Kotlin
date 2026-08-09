#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one occurrence, found {count}: {old[:160]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


# Voice profile editor: mirror the reference dialog field set and labels.
role = "app/src/main/java/vn/nghetruyen/app/ui/components/GlobalVoiceRoleEditorDialog.kt"
replace_once(
    role,
    '    val dialogTitle = title ?: if (draft.originalRoleId == null) "THÊM GIỌNG" else "SỬA HỒ SƠ GIỌNG"\n',
    '    val dialogTitle = title ?: "HỒ SƠ GIỌNG TTS"\n',
)
replace_once(
    role,
    '''                OutlinedTextField(
                    value = draft.aliases,
                    onValueChange = { onDraftChange(draft.copy(aliases = it.take(500))) },
                    label = { Text("Bí danh") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                )
                if (!draft.isNarrator) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Bật", Modifier.weight(1f))
                        Switch(
                            checked = draft.enabled,
                            onCheckedChange = { onDraftChange(draft.copy(enabled = it)) },
                        )
                    }
                }
''',
    '''                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (draft.isNarrator) "Người kể chuyện luôn được bật" else "Bật hồ sơ này", Modifier.weight(1f))
                    Switch(
                        checked = if (draft.isNarrator) true else draft.enabled,
                        onCheckedChange = { if (!draft.isNarrator) onDraftChange(draft.copy(enabled = it)) },
                        enabled = !draft.isNarrator,
                    )
                }
''',
)
replace_once(role, 'CompactVoiceValueRow("Cao độ TTS", draft.pitch, 0.5f, 2f)', 'CompactVoiceValueRow("Cao độ", draft.pitch, 0.5f, 2f)')
replace_once(role, '                Text("LƯU")\n', '                Text("LƯU HỒ SƠ")\n')
replace_once(role, '                    TextButton(onClick = onDelete) { Text("XÓA") }\n', '                    TextButton(onClick = onDelete) { Text("XÓA HỒ SƠ") }\n')

# Global voice-cast settings: exact switch and explanatory copy.
personal = "app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt"
replace_once(
    personal,
    '''            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Bật mặc định", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Switch(checked = state.autoVoiceCastEnabled, onCheckedChange = onAutoVoiceCastChange)
            }
            roles.forEach { role ->
''',
    '''            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Bật mặc định cho truyện dùng cấu hình chung", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Switch(checked = state.autoVoiceCastEnabled, onCheckedChange = onAutoVoiceCastChange)
            }
            Text(
                "Bộ hồ sơ này là tiêu chuẩn dùng chung. Chỉ các hồ sơ đang bật, có ngôn ngữ và đã chọn giọng hợp lệ mới được gửi cho AI; có thể dùng giọng mặc định của bộ đọc.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            roles.forEach { role ->
''',
)
# Cache capacity is a discrete allowed set, so use a selector rather than +/- buttons.
replace_once(personal, ') {\n    var sceneModeExpanded by remember { mutableStateOf(false) }\n', ') {\n    var sceneModeExpanded by remember { mutableStateOf(false) }\n    var ttsCacheExpanded by remember { mutableStateOf(false) }\n')
replace_once(
    personal,
    '''            SettingSwitch("Cache TTS/Sonic", state.ttsCacheEnabled, onTtsCacheEnabledChange)
            Text("Giới hạn cache TTS: ${state.ttsCacheLimitMiB} MiB")
            Row(Modifier.fillMaxWidth()) {
                Button({ onTtsCacheLimitChange(state.ttsCacheLimitMiB / 2) }, Modifier.weight(1f).padding(2.dp)) { Text("CACHE -") }
                Button({ onTtsCacheLimitChange(state.ttsCacheLimitMiB * 2) }, Modifier.weight(1f).padding(2.dp)) { Text("CACHE +") }
            }
''',
    '''            SettingSwitch("Cache TTS/Sonic", state.ttsCacheEnabled, onTtsCacheEnabledChange)
            Text("Giới hạn cache TTS", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 7.dp))
            Box(Modifier.fillMaxWidth()) {
                Button(onClick = { ttsCacheExpanded = true }, modifier = Modifier.fillMaxWidth()) { Text("${state.ttsCacheLimitMiB} MiB") }
                DropdownMenu(expanded = ttsCacheExpanded, onDismissRequest = { ttsCacheExpanded = false }) {
                    listOf(16, 32, 64, 128, 256, 512).forEach { value ->
                        DropdownMenuItem(text = { Text("$value MiB") }, onClick = { ttsCacheExpanded = false; onTtsCacheLimitChange(value) })
                    }
                }
            }
''',
)

# Story-specific AI dialog: same visibility/enabled structure as reference.
story = "app/src/main/java/vn/nghetruyen/app/ui/screens/StoryDetailScreen.kt"
replace_once(
    story,
    '''    if (advancedMode == "ai") {
        var modeExpanded by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { advancedMode = null },
            title = { Text("THIẾT LẬP AI CHO TRUYỆN NÀY") },
            text = {
                Column(Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState())) {
                    Text("AI RIÊNG CHO TRUYỆN", fontWeight = FontWeight.Bold)
                    Text(detail.story.title, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
''',
    '''    if (advancedMode == "ai") {
        var modeExpanded by remember { mutableStateOf(false) }
        val effectiveStoryAiMode = when (aiMode) {
            "TRANSLATE" -> "TRANSLATE"
            "IMPROVE" -> "IMPROVE"
            else -> if (state.aiOnline.mode.equals("improve", ignoreCase = true)) "IMPROVE" else "TRANSLATE"
        }
        AlertDialog(
            onDismissRequest = { advancedMode = null },
            title = { Text("AI RIÊNG CHO TRUYỆN") },
            text = {
                Column(Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState())) {
                    Text(detail.story.title, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
''',
)
replace_once(
    story,
    '''                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Tự động dịch", Modifier.weight(1f))
                        Switch(aiAutoRun, { aiAutoRun = it }, enabled = aiMode == "TRANSLATE")
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Lời nhắc riêng", Modifier.weight(1f))
                        Switch(aiCustomPrompts, { aiCustomPrompts = it })
                    }
                    if (aiCustomPrompts) {
                        Text("Lời nhắc dịch", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                        OutlinedTextField(aiTranslatePrompt, { aiTranslatePrompt = it.take(16_000) }, minLines = 5, modifier = Modifier.fillMaxWidth())
                        Text("Lời nhắc VietPhrase", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                        OutlinedTextField(aiImprovePrompt, { aiImprovePrompt = it.take(16_000) }, minLines = 5, modifier = Modifier.fillMaxWidth())
                    }
''',
    '''                    if (effectiveStoryAiMode == "TRANSLATE") {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Tự động dịch khi mở chương", Modifier.weight(1f))
                            Switch(aiAutoRun, { aiAutoRun = it })
                        }
                        Text(
                            "Chỉ dùng với chế độ Dịch chương gốc. Nếu đang ở chế độ TTS và TTS đã bật, bản dịch sẽ tự đọc. Ở chế độ Văn bản, công cụ chỉ dịch và hiển thị, không tự bật TTS.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 10.dp),
                        )
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Dùng lời nhắc riêng cho truyện này", Modifier.weight(1f))
                        Switch(aiCustomPrompts, { aiCustomPrompts = it })
                    }
                    Text("Lời nhắc riêng khi dịch", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    Text("Biến: {{CHAPTER_TITLE}}, {{CHAPTER_TEXT}}", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        aiTranslatePrompt,
                        { aiTranslatePrompt = it.take(16_000) },
                        minLines = 8,
                        enabled = aiCustomPrompts,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Lời nhắc riêng khi cải thiện VietPhrase", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    Text("Biến: {{SOURCE_TITLE}}, {{SOURCE_TEXT}}, {{VIETPHRASE_TITLE}}, {{VIETPHRASE_TEXT}}", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        aiImprovePrompt,
                        { aiImprovePrompt = it.take(16_000) },
                        minLines = 10,
                        enabled = aiCustomPrompts,
                        modifier = Modifier.fillMaxWidth(),
                    )
''',
)

# Story voice-cast dialog: add the same structural help text and exact labels.
replace_once(
    story,
    '''                    DropdownMenu(expanded = modeExpanded, onDismissRequest = { modeExpanded = false }) {
                        listOf(
                            StoryVoiceCastMode.GLOBAL to "Dùng cấu hình chung",
                            StoryVoiceCastMode.PRIVATE to "Dùng cấu hình riêng cho truyện này",
                            StoryVoiceCastMode.OFF to "Tắt phân vai cho truyện này",
                        ).forEach { (value, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { voiceMode = value; modeExpanded = false }) }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
''',
    '''                    DropdownMenu(expanded = modeExpanded, onDismissRequest = { modeExpanded = false }) {
                        listOf(
                            StoryVoiceCastMode.GLOBAL to "Dùng cấu hình chung",
                            StoryVoiceCastMode.PRIVATE to "Dùng cấu hình riêng cho truyện này",
                            StoryVoiceCastMode.OFF to "Tắt phân vai cho truyện này",
                        ).forEach { (value, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { voiceMode = value; modeExpanded = false }) }
                    }
                    Text(
                        when (voiceMode) {
                            StoryVoiceCastMode.PRIVATE -> "Truyện sử dụng một bộ hồ sơ độc lập, kể cả khi công tắc cấu hình chung đang tắt."
                            StoryVoiceCastMode.OFF -> "Không sử dụng phân vai AI cho truyện này. Bộ hồ sơ riêng đã tạo trước đó vẫn được giữ lại."
                            else -> "Truyện dùng bộ hồ sơ trong cài đặt chung và phụ thuộc công tắc bật mặc định ở đó."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
''',
)
replace_once(
    story,
    '''                    if (expressive && voiceMode != StoryVoiceCastMode.OFF) {
                        ReferencePercentSlider("Giới hạn tốc độ", speedLimit) { speedLimit = it }
''',
    '''                    if (expressive && voiceMode != StoryVoiceCastMode.OFF) {
                        Text(
                            "AI xử lý phân vai và ba thông số phần trăm trong cùng một lượt. Không dùng nhãn buồn, vui hay tức giận. Chỉ lời thoại trực tiếp được đổi giọng và thông số; lời kể cùng nội tâm luôn giữ giọng Người kể chuyện ở thông số gốc. Mức AI trả về được áp trực tiếp trong giới hạn bên dưới. Âm lượng chỉ có thể tăng khi mức gốc còn dưới 100%.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 5.dp, bottom = 6.dp),
                        )
                        ReferencePercentSlider("Giới hạn tốc độ", speedLimit) { speedLimit = it }
''',
)
replace_once(
    story,
    '''                    if (voiceMode == StoryVoiceCastMode.PRIVATE) {
''',
    '''                    Text(
                        "Thứ tự luôn là: tải chương → dịch tự động (nếu bật) → phân vai → TTS. Vì vậy hai tác vụ AI không chạy chồng lên nhau.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    if (voiceMode == StoryVoiceCastMode.PRIVATE) {
''',
)
replace_once(
    story,
    '''                    OutlinedTextField(
                        voiceNote,
                        { voiceNote = it.take(4_000) },
                        placeholder = { Text("Ghi chú cho AI") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
''',
    '''                    OutlinedTextField(
                        voiceNote,
                        { voiceNote = it.take(4_000) },
                        placeholder = { Text("Ví dụ: thông báo trong ngoặc vuông thuộc vai Hệ thống; lời truyền âm dùng giọng của người phát...") },
                        minLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Tên, mô tả và cách tổ chức nhân vật do người dùng quyết định. Ứng dụng chỉ cung cấp cấu trúc hồ sơ, ID ổn định và giọng Người kể chuyện bắt buộc.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
''',
)
replace_once(
    story,
    '''            text = { OutlinedTextField(draft, { draft = it.take(8_000) }, minLines = 8, modifier = Modifier.fillMaxWidth()) },
''',
    '''            text = {
                Column {
                    Text(
                        "Đây là phần hướng dẫn ngữ cảnh được gửi cho AI. Giới hạn ba thanh, ID, mã giọng và cấu trúc JSON vẫn được ứng dụng bảo vệ riêng, nên nội dung ở đây không thể mở khóa vượt giới hạn.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    OutlinedTextField(
                        draft,
                        { draft = it.take(8_000) },
                        placeholder = { Text("Nhập hướng dẫn cách AI chọn phần trăm tốc độ, cao độ và âm lượng theo ngữ cảnh...") },
                        minLines = 12,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
''',
)
replace_once(story, 'ReferenceActionButton("THÊM VAI", { roleDraft = defaultRoleDraft(); showVoiceRoleDialog = true }', 'ReferenceActionButton("THÊM VAI HOẶC NHÂN VẬT", { roleDraft = defaultRoleDraft(); showVoiceRoleDialog = true }')
replace_once(story, 'ReferenceActionButton("SAO CHÉP TỪ CẤU HÌNH CHUNG", { copyGlobalConfirm = true }', 'ReferenceActionButton("SAO CHÉP LẠI TỪ CẤU HÌNH CHUNG", { copyGlobalConfirm = true }')

print("DIALOG_EXACT_PARITY_APPLIED")
