from pathlib import Path
import re

ROOT = Path('.')


def edit(path: str, transform):
    p = ROOT / path
    old = p.read_text(encoding='utf-8')
    new = transform(old)
    if new == old:
        raise SystemExit(f'No changes produced for {path}')
    p.write_text(new, encoding='utf-8')
    print(f'updated {path}')


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f'missing target: {label}')
    return text.replace(old, new, 1)


def regex_once(text: str, pattern: str, repl: str, label: str) -> str:
    new, count = re.subn(pattern, repl, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f'{label}: expected 1 match, got {count}')
    return new


def voice_editor(text: str) -> str:
    text = replace_once(
        text,
        'import androidx.compose.material3.OutlinedTextField\n',
        'import androidx.compose.material3.OutlinedTextField\nimport androidx.compose.material3.Slider\n',
        'Slider import',
    )
    old = '''@Composable
private fun CompactVoiceValueRow(
    label: String,
    value: Float,
    minimum: Float,
    maximum: Float,
    step: Float = 0.05f,
    percent: Boolean = false,
    onChange: (Float) -> Unit,
) {
    val safeValue = value.coerceIn(minimum, maximum)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (percent) "$label ${"%.0f".format(safeValue * 100)}%" else "$label ${"%.2f".format(safeValue)}×",
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onChange((safeValue - step).coerceAtLeast(minimum)) }) { Text("−") }
        TextButton(onClick = { onChange((safeValue + step).coerceAtMost(maximum)) }) { Text("+") }
    }
}'''
    new = '''@Composable
private fun CompactVoiceValueRow(
    label: String,
    value: Float,
    minimum: Float,
    maximum: Float,
    percent: Boolean = false,
    onChange: (Float) -> Unit,
) {
    val safeValue = value.coerceIn(minimum, maximum)
    Text(
        if (percent) "$label ${"%.0f".format(safeValue * 100)}%" else "$label ${"%.2f".format(safeValue)}×",
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 5.dp),
    )
    Slider(
        value = safeValue,
        onValueChange = { onChange(it.coerceIn(minimum, maximum)) },
        valueRange = minimum..maximum,
        modifier = Modifier.fillMaxWidth(),
    )
}'''
    return replace_once(text, old, new, 'voice numeric controls')


def personal(text: str) -> str:
    old_roles = '''            roles.forEach { role ->
                Card(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Column(Modifier.padding(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (role.isNarrator) "Người kể chuyện" else role.roleName, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            Switch(
                                checked = role.enabled,
                                onCheckedChange = { onGlobalVoiceRoleEnabledChange(role.id, it) },
                                enabled = !role.isNarrator,
                            )
                        }
                        Text((role.enginePackage ?: "Hệ thống") + " • " + (role.voiceName ?: "Mặc định"), style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth()) {
                            Button(onClick = {
                                editDraft = VoiceRoleDraft(
                                    roleName = role.roleName,
                                    originalRoleId = role.id,
                                    aliases = role.aliasesCsv,
                                    description = role.description,
                                    isNarrator = role.isNarrator,
                                    enginePackage = role.enginePackage,
                                    voiceName = role.voiceName,
                                    languageTag = role.languageTag,
                                    rate = role.rate,
                                    pitch = role.pitch,
                                    volume = role.volume,
                                    expression = runCatching { VoiceExpression.valueOf(role.expression) }.getOrDefault(VoiceExpression.NEUTRAL),
                                    expressionStrength = role.expressionStrength,
                                    sonicSpeed = role.sonicSpeed,
                                    sonicPitch = role.sonicPitch,
                                    enabled = role.enabled,
                                )
                            }, modifier = Modifier.weight(1f).padding(2.dp)) { Text("SỬA") }
                        }
                    }
                }
            }
'''
    new_roles = '''            roles.forEach { role ->
                ReferenceActionButton(
                    text = (if (role.enabled) "" else "TẮT • ") +
                        (if (role.isNarrator) "Người kể chuyện" else role.roleName) + "\\n" +
                        (role.enginePackage ?: "Hệ thống") + " • " + (role.voiceName ?: "Mặc định"),
                    onClick = {
                        editDraft = VoiceRoleDraft(
                            roleName = role.roleName,
                            originalRoleId = role.id,
                            aliases = role.aliasesCsv,
                            description = role.description,
                            isNarrator = role.isNarrator,
                            enginePackage = role.enginePackage,
                            voiceName = role.voiceName,
                            languageTag = role.languageTag,
                            rate = role.rate,
                            pitch = role.pitch,
                            volume = role.volume,
                            expression = runCatching { VoiceExpression.valueOf(role.expression) }.getOrDefault(VoiceExpression.NEUTRAL),
                            expressionStrength = role.expressionStrength,
                            sonicSpeed = role.sonicSpeed,
                            sonicPitch = role.sonicPitch,
                            enabled = role.enabled,
                        )
                    },
                    normalColor = ReferencePanelBackground,
                    normalContentColor = ReferenceText,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                )
            }
'''
    text = replace_once(text, old_roles, new_roles, 'global voice list')

    old_subpage = '''    Column(Modifier.fillMaxSize().background(ReferenceScreenBackground).verticalScroll(rememberScrollState())) {
        ReferenceActionButton(
            text = backLabel,
            onClick = onBack,
            normalColor = ReferenceGray,
            accessibilityLabel = backLabel.lowercase(),
            modifier = Modifier.fillMaxWidth().padding(4.dp),
        )
        ScreenHeading(title)
        content()
    }'''
    new_subpage = '''    Column(Modifier.fillMaxSize().background(ReferenceScreenBackground).verticalScroll(rememberScrollState())) {
        ScreenHeading(title)
        content()
    }'''
    text = replace_once(text, old_subpage, new_subpage, 'personal subpage back button')

    old_menu_back = '''        if (backLabel != null && onBack != null) {
            ReferenceActionButton(
                text = backLabel,
                onClick = onBack,
                normalColor = ReferenceGray,
                accessibilityLabel = backLabel.lowercase(),
                modifier = Modifier.fillMaxWidth().padding(4.dp),
            )
        }
'''
    if old_menu_back in text:
        text = text.replace(old_menu_back, '', 1)

    replacements = {
        '"TAI NGHE, SONIC & TỰ ĐỘNG"': '"TAI NGHE & TỰ ĐỘNG"',
        '"QUẢN LÝ XUẤT SÁCH NÓI"': '"XUẤT SÁCH NÓI"',
        '"CHẨN ĐOÁN & HIỆU NĂNG"': '"CHẨN ĐOÁN"',
        '"NHẬT KÝ SAO LƯU VÀ KHÔI PHỤC"': '"NHẬT KÝ SAO LƯU"',
    }
    for old, new in replacements.items():
        text = text.replace(old, new)
    return text


def story_detail(text: str) -> str:
    text = text.replace('        ReferenceActionButton("QUAY LẠI", onBack, normalColor = ReferenceGray, modifier = Modifier.fillMaxWidth().padding(4.dp))\n', '', 1)

    old_private = '''                    privateRoles.forEach { role ->
                        ReferenceActionButton(
                            text = role.roleName + "\\n" + (role.enginePackage ?: "Hệ thống") + " • " + (role.voiceName ?: "Mặc định"),
                            onClick = {
                                roleDraft = role.toDraft(context)
                                showVoiceRoleDialog = true
                            },
                            normalColor = ReferencePanelBackground,
                            normalContentColor = ReferenceText,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        )
                    }
'''
    new_private = '''                    privateRoles.forEach { role ->
                        ReferenceActionButton(
                            text = (if (role.enabled) "" else "TẮT • ") + role.roleName + "\\n" +
                                (role.enginePackage ?: "Hệ thống") + " • " + (role.voiceName ?: "Mặc định"),
                            onClick = {
                                roleDraft = role.toDraft(context)
                                showVoiceRoleDialog = true
                            },
                            normalColor = ReferencePanelBackground,
                            normalContentColor = ReferenceText,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        )
                    }
'''
    text = replace_once(text, old_private, new_private, 'private voice list')

    long_blocks = [
        '''                    Text(
                        "Chỉ dùng với chế độ Dịch chương gốc. Nếu đang ở chế độ TTS và TTS đã bật, bản dịch sẽ tự đọc. Ở chế độ Văn bản, công cụ chỉ dịch và hiển thị, không tự bật TTS.",
                        style = MaterialTheme.typography.bodySmall,
                    )
''',
        '''                        Text("Biến: {{CHAPTER_TITLE}}, {{CHAPTER_TEXT}}", style = MaterialTheme.typography.bodySmall)
''',
        '''                        Text("Biến: {{SOURCE_TITLE}}, {{SOURCE_TEXT}}, {{VIETPHRASE_TITLE}}, {{VIETPHRASE_TEXT}}", style = MaterialTheme.typography.bodySmall)
''',
        '''                    Text(
                        when (voiceMode) {
                            StoryVoiceCastMode.PRIVATE -> "Truyện sử dụng một bộ hồ sơ độc lập, kể cả khi công tắc cấu hình chung đang tắt."
                            StoryVoiceCastMode.OFF -> "Không sử dụng phân vai AI cho truyện này. Bộ hồ sơ riêng đã tạo trước đó vẫn được giữ lại."
                            StoryVoiceCastMode.GLOBAL -> "Truyện dùng bộ hồ sơ trong cài đặt chung và phụ thuộc công tắc bật mặc định ở đó."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
''',
        '''                        Text(
                            "AI xử lý phân vai và ba thông số phần trăm trong cùng một lượt. Không dùng nhãn buồn, vui hay tức giận. Chỉ lời thoại trực tiếp được đổi giọng và thông số; lời kể cùng nội tâm luôn giữ giọng Người kể chuyện ở thông số gốc. Mức AI trả về được áp trực tiếp trong giới hạn bên dưới. Âm lượng chỉ có thể tăng khi mức gốc còn dưới 100%.",
                            style = MaterialTheme.typography.bodySmall,
                        )
''',
        '''                    Text(
                        "Thứ tự luôn là: tải chương → dịch tự động (nếu bật) → phân vai → TTS. Vì vậy hai tác vụ AI không chạy chồng lên nhau.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
''',
        '''                    Text(
                        "Tên, mô tả và cách tổ chức nhân vật do người dùng quyết định. Ứng dụng chỉ cung cấp cấu trúc hồ sơ, ID ổn định và giọng Người kể chuyện bắt buộc.",
                        style = MaterialTheme.typography.bodySmall,
                    )
''',
    ]
    for block in long_blocks:
        text = text.replace(block, '')

    labels = {
        '"Tự động dịch khi mở chương"': '"Tự động dịch"',
        '"Dùng lời nhắc riêng cho truyện này"': '"Lời nhắc riêng"',
        '"Lời nhắc riêng khi dịch"': '"Lời nhắc dịch"',
        '"Lời nhắc riêng khi cải thiện VietPhrase"': '"Lời nhắc VietPhrase"',
        '"Tự động phân vai rồi đọc khi mở chương ở chế độ TTS"': '"Tự động phân vai"',
        '"AI tự điều chỉnh tốc độ, cao độ và âm lượng"': '"AI điều chỉnh giọng"',
        '"XEM / SỬA HƯỚNG DẪN THÔNG SỐ"': '"HƯỚNG DẪN AI"',
        '"Ghi chú chung bổ sung cho AI"': '"Ghi chú AI"',
        'placeholder = { Text("Ví dụ: thông báo trong ngoặc vuông thuộc vai Hệ thống; lời truyền âm dùng giọng của người phát...") }': 'placeholder = { Text("Ghi chú cho AI") }',
        '"THIẾT LẬP BỘ GIỌNG RIÊNG"': '"BỘ GIỌNG RIÊNG"',
        '"THÊM VAI HOẶC NHÂN VẬT"': '"THÊM VAI"',
        '"SAO CHÉP LẠI TỪ CẤU HÌNH CHUNG"': '"SAO CHÉP TỪ CẤU HÌNH CHUNG"',
    }
    for old, new in labels.items():
        text = text.replace(old, new)

    text = text.replace(
        'text = { Text("Thay toàn bộ bộ giọng riêng hiện tại bằng một bản sao mới của cấu hình chung? Sau khi sao chép, hai bộ vẫn độc lập.") },',
        'text = { Text("Thay bộ giọng riêng bằng cấu hình chung?") },',
    )
    text = text.replace(
        'text = { Text("Khôi phục tên và mô tả của 7 hồ sơ mẫu? Cấu hình âm thanh và các hồ sơ tùy chỉnh sẽ được giữ lại trong giới hạn 10 hồ sơ.") },',
        'text = { Text("Khôi phục 7 hồ sơ mẫu?") },',
    )
    text = text.replace(
        'text = { Text("Xóa hồ sơ “${role.roleName}”? Kết quả cũ dùng hồ sơ này sẽ trở về Người kể chuyện.") },',
        'text = { Text("Xóa hồ sơ “${role.roleName}”?") },',
    )
    return text


def explore(text: str) -> str:
    text = text.replace('accessibilityLabel = "Mở tìm kiếm truyện",', 'accessibilityLabel = "TÌM KIẾM",')
    text = text.replace(
        'accessibilityLabel = "Chọn nguồn truyện đang khám phá. Hiện tại ${selectedSource?.displayName ?: "chưa chọn"}",',
        'accessibilityLabel = "NGUỒN: ${selectedSource?.displayName ?: "CHƯA CHỌN"}",',
    )
    text = text.replace('accessibilityLabel = "Danh mục Trang chủ",', 'accessibilityLabel = "TRANG CHỦ",')
    text = text.replace('accessibilityLabel = "Danh mục $category",', 'accessibilityLabel = category,')
    text = text.replace('"Đã nhận phản hồi ${state.searchedSourceCount}/${state.totalSearchSourceCount} nguồn",', '"NGUỒN: ${state.searchedSourceCount}/${state.totalSearchSourceCount}",')
    text = text.replace('accessibilityLabel = "Tải thêm truyện, trang ${state.explorePage + 1}",', 'accessibilityLabel = "TẢI THÊM • TRANG ${state.explorePage + 1}",')
    text = text.replace('"Nhập tên truyện hoặc dán link..."', '"Tên truyện hoặc link"')
    text = text.replace('"Gom truyện trùng tên"', '"Gom truyện trùng"')
    return text


def library(text: str) -> str:
    text = text.replace('accessibilityLabel = "Tủ truyện, ${label.lowercase()}",', 'accessibilityLabel = label,')
    text = text.replace('"Nhập tên truyện, chương hoặc vài ký tự liên quan"', '"Tên truyện hoặc chương"')
    text = text.replace('"Nhập tên truyện hoặc vài ký tự liên quan"', '"Tên truyện"')
    text = text.replace('"Nhập tên truyện, nguồn hoặc vài ký tự liên quan"', '"Tên truyện hoặc nguồn"')
    return text


def reader(text: str) -> str:
    text = replace_once(
        text,
        '    var showReaderOptions by remember(content.chapter.id) { mutableStateOf(false) }\n',
        '    var showReaderOptions by remember(content.chapter.id) { mutableStateOf(false) }\n    var showReaderToolsDialog by remember { mutableStateOf(false) }\n    var showAiToolsDialog by remember { mutableStateOf(false) }\n',
        'reader submenu state',
    )
    text = replace_once(
        text,
        '    var showMusicDialog by remember { mutableStateOf(false) }\n',
        '    var showMusicDialog by remember { mutableStateOf(false) }\n    var musicAdvanced by remember { mutableStateOf(false) }\n',
        'music advanced state',
    )

    options = '''
    if (showReaderOptions) {
        AlertDialog(
            onDismissRequest = { showReaderOptions = false },
            title = { Text("TÙY CHỌN ĐỌC") },
            text = { Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                ReaderMenuButton("DANH SÁCH CHƯƠNG") { showReaderOptions = false; onBackToChapters() }
                ReaderMenuButton("TÌM TRONG CHƯƠNG") { showReaderOptions = false; searchDraft = ""; showSearchDialog = true }
                ReaderMenuButton("HẸN GIỜ NGỦ • ${state.sleepTimerStatus}") { showReaderOptions = false; showSleepDialog = true }
                ReaderMenuButton("NHẠC NỀN") { showReaderOptions = false; musicAdvanced = false; showMusicDialog = true }
                ReaderMenuButton("XUẤT ÂM THANH") { showReaderOptions = false; showExportDialog = true }
                ReaderMenuButton("CHẾ ĐỘ ĐỌC • ${if (textMode) "VĂN BẢN" else "TTS"}") { showReaderOptions = false; showReaderModeDialog = true }
                ReaderMenuButton("CÀI ĐẶT TTS") { showReaderOptions = false; showTtsDialog = true }
                ReaderMenuButton("AI & CHUYỂN NGỮ") { showReaderOptions = false; showAiToolsDialog = true }
                ReaderMenuButton("KHÁC") { showReaderOptions = false; showReaderToolsDialog = true }
            } },
            confirmButton = { TextButton(onClick = { showReaderOptions = false }) { Text("ĐÓNG") } },
        )
    }

    if (showReaderToolsDialog) {
        AlertDialog(
            onDismissRequest = { showReaderToolsDialog = false },
            title = { Text("KHÁC") },
            text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                ReaderMenuButton("LƯU VỊ TRÍ") { showReaderToolsDialog = false; onSaveReadingPosition() }
                ReaderMenuButton("ĐÁNH DẤU ĐOẠN ${activeIndex + 1}") { showReaderToolsDialog = false; onBookmark() }
                ReaderMenuButton(if (activeNote == null) "GHI CHÚ ĐOẠN ${activeIndex + 1}" else "SỬA GHI CHÚ ĐOẠN ${activeIndex + 1}") {
                    showReaderToolsDialog = false
                    noteDraft = activeNote?.text.orEmpty()
                    showNoteDialog = true
                }
                if (textMode) ReaderMenuButton("HIỂN THỊ") { showReaderToolsDialog = false; showDisplayDialog = true }
                ReaderMenuButton("SAO CHÉP") { showReaderToolsDialog = false; showCopyDialog = true }
                ReaderMenuButton("THÔNG TIN") { showReaderToolsDialog = false; showChapterInfoDialog = true }
            } },
            confirmButton = { TextButton(onClick = { showReaderToolsDialog = false; showReaderOptions = true }) { Text("ĐÓNG") } },
        )
    }

    if (showAiToolsDialog) {
        AlertDialog(
            onDismissRequest = { showAiToolsDialog = false },
            title = { Text("AI & CHUYỂN NGỮ") },
            text = { Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                ReaderMenuButton("AI CHO TRUYỆN") { showAiToolsDialog = false; onOpenStoryAiOptions() }
                ReaderMenuButton("PHÂN VAI TTS") { showAiToolsDialog = false; onOpenStoryVoiceCastOptions() }
                if (state.vietPhraseRules.isNotEmpty() || state.vietPhraseDictionaryStates.isNotEmpty()) {
                    ReaderMenuButton("ÁP DỤNG VIETPHRASE") { showAiToolsDialog = false; onApplyVietPhrase() }
                    ReaderMenuButton("CẢI THIỆN VIETPHRASE") { showAiToolsDialog = false; onImproveVietPhrase() }
                }
                ReaderMenuButton("LẬP NHẠC CẢNH") { showAiToolsDialog = false; onPlanSceneMusic() }
                ReaderMenuButton("PHÂN VAI + NHẠC") { showAiToolsDialog = false; onPlanNarration() }
                if (state.chapterTextMode == ChapterTextMode.AI_TRANSLATION || state.playback.preparationState == PlaybackPreparationState.FAILED) {
                    ReaderMenuButton("KHÔI PHỤC BẢN GỐC") { showAiToolsDialog = false; onShowOriginal() }
                }
                if (state.vietPhraseRules.isNotEmpty() || state.vietPhraseDictionaryStates.isNotEmpty()) {
                    ReaderMenuButton("NHẬT KÝ VIETPHRASE") { showAiToolsDialog = false; createVietPhraseDiagnostic() }
                }
            } },
            confirmButton = { TextButton(onClick = { showAiToolsDialog = false; showReaderOptions = true }) { Text("ĐÓNG") } },
        )
    }

    if (showReaderModeDialog) {'''
    text = regex_once(
        text,
        r'\n    if \(showReaderOptions\) \{.*?\n    if \(showReaderModeDialog\) \{',
        options,
        'reader options grouping',
    )

    music = '''
    if (showMusicDialog) {
        AlertDialog(
            onDismissRequest = { showMusicDialog = false; musicAdvanced = false },
            title = { Text(if (musicAdvanced) "NHẠC NỀN • NÂNG CAO" else "NHẠC NỀN") },
            text = {
                Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
                    if (!musicAdvanced) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Bật nhạc nền", Modifier.weight(1f))
                            Switch(musicEnabled, { musicEnabled = it })
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("AI đổi nhạc", Modifier.weight(1f))
                            Switch(musicAi, { musicAi = it })
                        }
                        Text("Chế độ phát", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                        Row(Modifier.fillMaxWidth()) {
                            TextButton({ musicMode = SceneMusicPlaybackMode.SEQUENTIAL }, Modifier.weight(1f)) { Text((if (musicMode == SceneMusicPlaybackMode.SEQUENTIAL) "✓ " else "") + "LẦN LƯỢT") }
                            TextButton({ musicMode = SceneMusicPlaybackMode.SHUFFLE }, Modifier.weight(1f)) { Text((if (musicMode == SceneMusicPlaybackMode.SHUFFLE) "✓ " else "") + "NGẪU NHIÊN") }
                            TextButton({ musicMode = SceneMusicPlaybackMode.SMART_AVOID_REPEAT }, Modifier.weight(1f)) { Text((if (musicMode == SceneMusicPlaybackMode.SMART_AVOID_REPEAT) "✓ " else "") + "TRÁNH LẶP") }
                        }
                        if (musicMode == SceneMusicPlaybackMode.SMART_AVOID_REPEAT) {
                            ValueStepper(
                                "Tránh lặp",
                                "$musicAvoidRepeatWindow bài",
                                { musicAvoidRepeatWindow = (musicAvoidRepeatWindow - 1).coerceAtLeast(0) },
                                { musicAvoidRepeatWindow = (musicAvoidRepeatWindow + 1).coerceAtMost(20) },
                            )
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Giữ qua chương", Modifier.weight(1f))
                            Switch(musicContinueAcrossChapters, { musicContinueAcrossChapters = it })
                        }
                        ReaderMenuButton("DANH SÁCH NHẠC • ${state.sceneMusicTracks.size}") {
                            val rows = state.sceneMusicTracks.sortedWith(compareBy<SceneMusicTrackEntity> { it.orderIndex }.thenBy { it.title.lowercase() })
                            musicLibraryDraft = rows.mapIndexed { index, row -> row.copy(orderIndex = index) }
                            musicLibraryBaselineIds = rows.mapTo(linkedSetOf()) { it.id }
                            musicSearch = ""
                            showMusicLibrary = true
                        }
                        ReaderMenuButton("NÂNG CAO") { musicAdvanced = true }
                    } else {
                        ValueStepper(
                            "Crossfade",
                            "$musicCrossfadeMs ms",
                            { musicCrossfadeMs = (musicCrossfadeMs - 400).coerceAtLeast(0) },
                            { musicCrossfadeMs = (musicCrossfadeMs + 400).coerceAtMost(8_000) },
                        )
                        Text("Chuẩn hóa ${"%.1f".format(musicTargetLufs)} LUFS", fontWeight = FontWeight.SemiBold)
                        Slider(musicTargetLufs, { musicTargetLufs = it }, valueRange = -36f..-18f, steps = 35)
                        Text("Giảm giọng ${"%.1f".format(musicDuckDb)} dB", fontWeight = FontWeight.SemiBold)
                        Slider(musicDuckDb, { musicDuckDb = it }, valueRange = 0f..24f, steps = 47)
                        Text("Attack $musicAttackMs ms", fontWeight = FontWeight.SemiBold)
                        Slider(musicAttackMs.toFloat(), { musicAttackMs = it.toInt() }, valueRange = 0f..2000f, steps = 39)
                        Text("Release $musicReleaseMs ms", fontWeight = FontWeight.SemiBold)
                        Slider(musicReleaseMs.toFloat(), { musicReleaseMs = it.toInt() }, valueRange = 0f..5000f, steps = 99)
                        ReaderMenuButton("CHUẨN HÓA KHO NHẠC") {
                            state.sceneMusicTracks.forEach { SceneMusicAnalysisWorker.enqueue(context, it.id) }
                            onMessage("Đã đưa kho nhạc vào hàng đợi chuẩn hóa.")
                        }
                    }
                }
            },
            confirmButton = {
                if (musicAdvanced) {
                    TextButton(onClick = { musicAdvanced = false }) { Text("XONG") }
                } else {
                    TextButton(onClick = {
                        scope.launch {
                            val settings = app.container.settingsRepository
                            settings.setBackgroundMusicEnabled(musicEnabled)
                            settings.setAutoSceneMusicEnabled(musicAi)
                            settings.setSceneMusicPlaybackMode(musicMode)
                            settings.setSceneMusicCrossfadeMillis(musicCrossfadeMs)
                            settings.setSceneMusicContinueAcrossChapters(musicContinueAcrossChapters)
                            settings.setSceneMusicAvoidRepeatWindow(musicAvoidRepeatWindow)
                            settings.setSceneMusicTargetLufs(musicTargetLufs)
                            settings.setBackgroundMusicDuckFactor(10.0.pow(-musicDuckDb / 20.0).toFloat())
                            settings.setBackgroundMusicAttackMillis(musicAttackMs)
                            settings.setBackgroundMusicReleaseMillis(musicReleaseMs)
                            ReaderPlaybackService.command(context, ReaderPlaybackService.ACTION_REFRESH)
                            onMessage("Đã lưu nhạc nền.")
                            showMusicDialog = false
                        }
                    }) { Text("LƯU") }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (musicAdvanced) musicAdvanced = false else showMusicDialog = false
                }) { Text(if (musicAdvanced) "HỦY" else "ĐÓNG") }
            },
        )
    }

    if (showMusicLibrary) {'''
    text = regex_once(
        text,
        r'\n    if \(showMusicDialog\) \{.*?\n    if \(showMusicLibrary\) \{',
        music,
        'scene music compact dialog',
    )

    old_stats = '''                Text(
                    "Kho nhạc: ${musicLibraryDraft.size} bài\\n" +
                        "Bài đang bật: $enabledCount bài\\n" +
                        "Đã chuẩn hóa: $normalizedCount bài\\n" +
                        "Đã có mô tả: $describedCount bài\\n" +
                        "Ước tính khi gửi danh mục AI: khoảng $estimatedTokens token",
                    style = MaterialTheme.typography.bodySmall,
                )'''
    new_stats = '''                Text(
                    "${musicLibraryDraft.size} bài • $enabledCount bật • $normalizedCount chuẩn hóa • $describedCount mô tả",
                    style = MaterialTheme.typography.bodySmall,
                )'''
    text = text.replace(old_stats, new_stats)
    text = text.replace('        val estimatedTokens = musicLibraryDraft.sumOf { it.title.length + it.tagsCsv.length }.div(4).coerceAtLeast(0)\n', '')
    text = text.replace('                    Text("Tên bài gửi cho AI")\n', '')
    text = text.replace('                    Text("Mô tả tham khảo cho AI, không bắt buộc AI làm theo", modifier = Modifier.padding(top = 8.dp))\n', '                    Text("Mô tả cho AI", modifier = Modifier.padding(top = 8.dp))\n')
    text = text.replace('                        placeholder = { Text("Tông/diễn biến: ...; Dùng: ...; Tránh: ...") },', '                        placeholder = { Text("Mô tả ngắn") },')
    text = text.replace('                    Text("Tối đa 300 ký tự. Chỉ ghi thông tin thực sự giúp AI phân biệt và chọn bài.", style = MaterialTheme.typography.bodySmall)\n', '')
    old_bulk = '''                Text(
                    "Mỗi bài một dòng theo định dạng:\\nTên bài || Tông/diễn biến: ...; Dùng: ...; Tránh: ...\\n\\n" +
                        "Tên phải khớp với danh sách. Dòng có mô tả trống được bỏ qua. Ghi [XÓA] để xóa mô tả hiện có.",
                    style = MaterialTheme.typography.bodySmall,
                )'''
    new_bulk = '''                Text("Mỗi dòng: Tên bài || Mô tả. Dùng [XÓA] để xóa mô tả.", style = MaterialTheme.typography.bodySmall)'''
    text = text.replace(old_bulk, new_bulk)
    return text


edit('app/src/main/java/vn/nghetruyen/app/ui/components/GlobalVoiceRoleEditorDialog.kt', voice_editor)
edit('app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt', personal)
edit('app/src/main/java/vn/nghetruyen/app/ui/screens/StoryDetailScreen.kt', story_detail)
edit('app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt', reader)
edit('app/src/main/java/vn/nghetruyen/app/ui/screens/ExploreScreen.kt', explore)
edit('app/src/main/java/vn/nghetruyen/app/ui/screens/LibraryScreen.kt', library)
