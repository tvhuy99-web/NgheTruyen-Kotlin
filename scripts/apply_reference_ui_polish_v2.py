#!/usr/bin/env python3
from pathlib import Path


def read(path: str) -> str:
    return Path(path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    Path(path).write_text(text, encoding="utf-8")


def replace_once(path: str, old: str, new: str) -> None:
    text = read(path)
    if new and new in text:
        return
    count = text.count(old)
    if count == 0 and not new:
        return
    if count != 1:
        raise SystemExit(f"{path}: expected one occurrence, found {count}: {old[:120]!r}")
    write(path, text.replace(old, new, 1))


def remove_range(path: str, start: str, end: str) -> None:
    text = read(path)
    start_index = text.find(start)
    if start_index < 0:
        return
    end_index = text.find(end, start_index)
    if end_index < 0:
        raise SystemExit(f"{path}: end marker not found: {end[:120]!r}")
    write(path, text[:start_index] + text[end_index:])


def insert_before_final_brace(path: str, addition: str, sentinel: str) -> None:
    text = read(path)
    if sentinel in text:
        return
    index = text.rfind("\n}\n")
    if index < 0:
        raise SystemExit(f"{path}: final brace not found")
    write(path, text[:index] + addition + text[index:])



replace_once(
    "app/src/main/java/vn/nghetruyen/app/ui/components/Common.kt",
    """            .semantics {
                role = roleValue
                this.selected = selected
                contentDescription = accessibilityLabel + if (selected) ", đang chọn" else ""
            },
""",
    """            .semantics {
                role = roleValue
                if (roleValue == Role.Tab || selected) {
                    this.selected = selected
                }
                contentDescription = accessibilityLabel + if (selected) ", đang chọn" else ""
            },
""",
)



explore = "app/src/main/java/vn/nghetruyen/app/ui/screens/ExploreScreen.kt"
replace_once(
    explore,
    "import androidx.compose.material3.DropdownMenu\n",
    "import androidx.compose.material3.AlertDialog\nimport androidx.compose.material3.DropdownMenu\n",
)
replace_once(
    explore,
    "import androidx.compose.material3.Text\n",
    "import androidx.compose.material3.Text\nimport androidx.compose.material3.TextButton\n",
)
replace_once(explore, "import androidx.compose.ui.graphics.Color\n", "")
replace_once(
    explore,
    "    var sourceMenuOpen by remember { mutableStateOf(false) }\n",
    "    var sourceMenuOpen by remember { mutableStateOf(false) }\n    var searchDialogOpen by remember { mutableStateOf(false) }\n",
)
replace_once(
    explore,
    """    ) {
        Column(
            modifier = Modifier
""",
    """    ) {
        ReferenceActionButton(
            text = "TÌM KIẾM",
            onClick = { searchDialogOpen = true },
            normalColor = ReferencePurple,
            accessibilityLabel = "Mở tìm kiếm truyện",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 2.dp),
        )
        Column(
            modifier = Modifier
""",
)
remove_range(
    explore,
    """        OutlinedTextField(
            value = state.query,
""",
    """        Text(
            text = listTitle,
""",
)
insert_before_final_brace(
    explore,
    """

    if (searchDialogOpen) {
        AlertDialog(
            onDismissRequest = { searchDialogOpen = false },
            title = { Text("TÌM KIẾM") },
            text = {
                Column {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = onQueryChange,
                        label = { Text("Tên truyện, tác giả hoặc URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        ReferenceTabButton(
                            text = "MỘT NGUỒN",
                            selected = !state.searchAllSources,
                            onClick = { onSearchAllSourcesChange(false) },
                            accessibilityLabel = "Tìm trên một nguồn",
                            minHeight = 50.dp,
                            unselectedColor = ReferenceDivider,
                            unselectedContentColor = ReferenceText,
                            modifier = Modifier.weight(1f).padding(1.dp),
                        )
                        ReferenceTabButton(
                            text = "TẤT CẢ NGUỒN",
                            selected = state.searchAllSources,
                            onClick = { onSearchAllSourcesChange(true) },
                            accessibilityLabel = "Tìm trên tất cả nguồn",
                            minHeight = 50.dp,
                            unselectedColor = ReferenceDivider,
                            unselectedContentColor = ReferenceText,
                            modifier = Modifier.weight(1f).padding(1.dp),
                        )
                    }
                    Text("Sắp xếp kết quả", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        listOf(
                            SearchSortMode.RELEVANCE to "LIÊN QUAN",
                            SearchSortMode.TITLE to "TÊN",
                            SearchSortMode.AUTHOR to "TÁC GIẢ",
                            SearchSortMode.SOURCE to "NGUỒN",
                        ).forEach { (mode, label) ->
                            ReferenceTabButton(
                                text = label,
                                selected = state.searchSortMode == mode,
                                onClick = { onSortModeChange(mode) },
                                accessibilityLabel = "Sắp xếp theo ${label.lowercase()}",
                                minHeight = 48.dp,
                                unselectedColor = ReferenceDivider,
                                unselectedContentColor = ReferenceText,
                            )
                        }
                    }
                    if (state.sourceSuggestions.isNotEmpty() && !state.searchAllSources) {
                        Text("Gợi ý", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            state.sourceSuggestions.forEach { suggestion ->
                                TextButton(onClick = { onSuggestionSelected(suggestion) }) {
                                    Text(suggestion)
                                }
                            }
                        }
                    }
                    if (state.searchAllSources && state.totalSearchSourceCount > 0) {
                        Text(
                            "Đã nhận phản hồi ${state.searchedSourceCount}/${state.totalSearchSourceCount} nguồn",
                            color = ReferenceSecondaryText,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !state.loading,
                    onClick = {
                        searchDialogOpen = false
                        onSearch()
                    },
                ) { Text("TÌM") }
            },
            dismissButton = {
                if (state.loading && state.searchAllSources) {
                    TextButton(onClick = {
                        onCancelSearch()
                        searchDialogOpen = false
                    }) { Text("HỦY TÌM") }
                } else {
                    TextButton(onClick = { searchDialogOpen = false }) { Text("ĐÓNG") }
                }
            },
        )
    }
""",
    "title = { Text(\"TÌM KIẾM\") }",
)



personal = "app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt"
replace_once(
    personal,
    "import vn.nghetruyen.app.ui.components.ReferenceScreenBackground\n",
    """import vn.nghetruyen.app.ui.components.ReferenceActionButton
import vn.nghetruyen.app.ui.components.ReferenceGray
import vn.nghetruyen.app.ui.components.ReferencePanelBackground
import vn.nghetruyen.app.ui.components.ReferenceScreenBackground
import vn.nghetruyen.app.ui.components.ReferenceText
""",
)
replace_once(
    personal,
    ") {\n    Column(Modifier.fillMaxSize().background(ReferenceScreenBackground).verticalScroll(rememberScrollState())) {\n        VoiceSettingsCard(\n",
    """) {
    var personalPage by remember { mutableStateOf("home") }

    if (personalPage == "home") {
        Column(Modifier.fillMaxSize().background(ReferenceScreenBackground)) {
            Text(
                "CÁ NHÂN",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            )
            ReferenceActionButton(
                text = "Cài đặt",
                onClick = { personalPage = "settings" },
                accessibilityLabel = "Cài đặt ứng dụng, giọng đọc và AI",
                normalColor = ReferencePanelBackground,
                normalContentColor = ReferenceText,
                minHeight = 64.dp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
            )
            ReferenceActionButton(
                text = "Tiện ích mở rộng",
                onClick = { personalPage = "extensions" },
                accessibilityLabel = "Quản lý nguồn truyện và tiện ích mở rộng",
                normalColor = ReferencePanelBackground,
                normalContentColor = ReferenceText,
                minHeight = 64.dp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        return
    }

    Column(Modifier.fillMaxSize().background(ReferenceScreenBackground).verticalScroll(rememberScrollState())) {
        ReferenceActionButton(
            text = "QUAY LẠI CÁ NHÂN",
            onClick = { personalPage = "home" },
            normalColor = ReferenceGray,
            accessibilityLabel = "Quay lại danh sách Cá nhân",
            modifier = Modifier.fillMaxWidth().padding(4.dp),
        )
        if (personalPage == "settings") {
        VoiceSettingsCard(
""",
)
replace_once(
    personal,
    "        SourceManagementCard(\n",
    """        SettingsCard("Kiến trúc ứng dụng", "Kotlin, Compose, Room, DataStore, WorkManager và foreground TTS service. Lua Native Source API 2 chạy trong LuaJ sandbox; không AndroLua, không luajava và không nạp DEX động.")
        }
        if (personalPage == "extensions") {
        SourceManagementCard(
""",
)
replace_once(
    personal,
    """        SettingsCard("Kiến trúc ứng dụng", "Kotlin, Compose, Room, DataStore, WorkManager và foreground TTS service. Lua Native Source API 2 chạy trong LuaJ sandbox; không AndroLua, không luajava và không nạp DEX động.")
    }
}
""",
    """        }
    }
}
""",
)



story = "app/src/main/java/vn/nghetruyen/app/ui/screens/StoryDetailScreen.kt"
replace_once(
    story,
    "    var showAdvancedOptions by remember(detail.story.id) { mutableStateOf(false) }\n",
    "    var showAdvancedOptions by remember(detail.story.id) { mutableStateOf(false) }\n    var showStoryMenu by remember(detail.story.id) { mutableStateOf(false) }\n",
)
replace_once(
    story,
    "    }.joinToString(\" • \")\n    LaunchedEffect(selectedTab, visibleChapters.size, state.storyComments.size) {\n",
    "    }.joinToString(\" • \")\n    val hasVoiceProfile = state.storyTtsProfiles.containsKey(detail.story.id)\n    LaunchedEffect(selectedTab, state.storyCommentsLoading) {\n",
)
replace_once(
    story,
    """                onClick = { showAdvancedOptions = !showAdvancedOptions },
                selected = showAdvancedOptions,
                selectedColor = ReferenceGray,
                normalColor = ReferenceGray,
                accessibilityLabel = if (showAdvancedOptions) "Đóng tùy chọn truyện" else "Mở tùy chọn truyện",
""",
    """                onClick = { showStoryMenu = true },
                selected = false,
                selectedColor = ReferenceGray,
                normalColor = ReferenceGray,
                accessibilityLabel = "Tùy chọn truyện",
""",
)
replace_once(
    story,
    """        }
        if (showAdvancedOptions) {
""",
    """        }
        if (showStoryMenu) {
            val following = state.following.any { it.storyId == detail.story.id }
            AlertDialog(
                onDismissRequest = { showStoryMenu = false },
                title = { Text("TÙY CHỌN TRUYỆN") },
                text = {
                    Column {
                        ReferenceActionButton(
                            text = if (following) "BỎ THEO DÕI" else "THEO DÕI",
                            onClick = {
                                showStoryMenu = false
                                onToggleFollowing()
                            },
                            selected = following,
                            accessibilityLabel = if (following) "Bỏ theo dõi truyện" else "Theo dõi truyện",
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        )
                        ReferenceActionButton(
                            text = "TẢI CHƯA ĐỌC",
                            onClick = {
                                showStoryMenu = false
                                onDownloadUnread()
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        )
                        ReferenceActionButton(
                            text = "TẢI KHOẢNG",
                            onClick = {
                                showStoryMenu = false
                                showRangeDialog = true
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        )
                        if (detail.story.url.startsWith("https://")) {
                            ReferenceActionButton(
                                text = "MỞ TRANG GỐC",
                                onClick = {
                                    showStoryMenu = false
                                    onOpenOriginal(detail.story.url)
                                },
                                normalColor = ReferenceGray,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            )
                        }
                        ReferenceActionButton(
                            text = "XUẤT SÁCH NÓI",
                            onClick = {
                                showStoryMenu = false
                                showExportDialog = true
                            },
                            normalColor = ReferencePurple,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        )
                        ReferenceActionButton(
                            text = if (hasVoiceProfile) "CẬP NHẬT GIỌNG RIÊNG" else "LƯU GIỌNG RIÊNG",
                            onClick = {
                                showStoryMenu = false
                                onSaveVoiceProfile()
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        )
                        if (hasVoiceProfile) {
                            ReferenceActionButton(
                                text = "BỎ GIỌNG RIÊNG",
                                onClick = {
                                    showStoryMenu = false
                                    onClearVoiceProfile()
                                },
                                normalColor = ReferenceGray,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            )
                        }
                        ReferenceActionButton(
                            text = "CẤU HÌNH GIỌNG & AI",
                            onClick = {
                                showStoryMenu = false
                                showAdvancedOptions = true
                            },
                            normalColor = ReferenceGray,
                            accessibilityLabel = "Mở cấu hình giọng đọc và AI nâng cao",
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showStoryMenu = false }) { Text("ĐÓNG") }
                },
            )
        }
        if (showAdvancedOptions) {
            ReferenceActionButton(
                text = "ĐÓNG CẤU HÌNH NÂNG CAO",
                onClick = { showAdvancedOptions = false },
                normalColor = ReferenceGray,
                accessibilityLabel = "Đóng cấu hình giọng đọc và AI nâng cao",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            )
""",
)



reader = "app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt"
replace_once(
    reader,
    """                ReaderButton("TÌM", { showSearchDialog = true }, Modifier.weight(0.8f), accessibilityLabel = "Tìm trong chương")
                ReaderButton(
                    "TÙY CHỌN",
                    { showReaderActions = !showReaderActions },
                    Modifier.weight(1f),
""",
    """                ReaderButton(
                    "TÙY CHỌN",
                    { showReaderActions = !showReaderActions },
                    Modifier.weight(1.8f),
""",
)
replace_once(
    reader,
    """                Row(modifier = Modifier.fillMaxWidth()) {
                    ReaderButton("HIỂN THỊ", { showDisplayDialog = true }, Modifier.weight(1f))
                    ReaderButton("BẢN GỐC", onShowOriginal, Modifier.weight(1f))
                    ReaderButton("VIETPHRASE", onApplyVietPhrase, Modifier.weight(1f))
                }
""",
    """                Row(modifier = Modifier.fillMaxWidth()) {
                    ReaderButton("TÌM", { showSearchDialog = true }, Modifier.weight(1f), accessibilityLabel = "Tìm trong chương")
                    ReaderButton("HIỂN THỊ", { showDisplayDialog = true }, Modifier.weight(1f))
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    ReaderButton("BẢN GỐC", onShowOriginal, Modifier.weight(1f))
                    ReaderButton("VIETPHRASE", onApplyVietPhrase, Modifier.weight(1f))
                }
""",
)
replace_once(reader, '                        state.playback.isPlaying -> "DỪNG"\n', '                        state.playback.isPlaying -> "TẠM DỪNG"\n')
replace_once(reader, "                                    this.selected = active\n", "                                    if (active) this.selected = true\n")


remove_range(
    reader,
    """            Row(modifier = Modifier.fillMaxWidth()) {
                ReaderButton(
                    when {
                        state.aiBusy -> "AI ĐANG CHẠY…"
""",
    """            Row(modifier = Modifier.fillMaxWidth()) {
                ReaderButton("TRƯỚC", onPreviousChapter, Modifier.weight(1.2f),
""",
)

replace_once(
    reader,
    """                ReaderButton("SAU", onNextChapter, Modifier.weight(1.2f), minHeight = 64.dp, accessibilityLabel = "Chương sau")
            }
            if (showReaderActions) {
""",
    """                ReaderButton("SAU", onNextChapter, Modifier.weight(1.2f), minHeight = 64.dp, accessibilityLabel = "Chương sau")
            }
            if (state.playback.preparationState == PlaybackPreparationState.PREPARING) {
                Text(
                    "Đang chuẩn bị giọng đọc và nội dung tiếp theo…",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    color = palette.text,
                )
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                ReaderButton(
                    when {
                        state.aiBusy -> "AI ĐANG CHẠY…"
                        state.chapterTextMode == ChapterTextMode.AI_TRANSLATION -> "DỊCH LẠI"
                        else -> "DỊCH AI"
                    },
                    onAiTranslate,
                    Modifier.weight(1f),
                    enabled = !state.aiBusy,
                    normalColor = ReferencePurple,
                    accessibilityLabel = "Dịch chương bằng AI",
                )
                ReaderButton(
                    "PHÂN VAI AI",
                    onVoiceCast,
                    Modifier.weight(1f),
                    enabled = !state.aiBusy,
                    normalColor = Color(0xFFAF52DE),
                    accessibilityLabel = "Phân vai giọng đọc bằng AI",
                )
            }
            if (showReaderActions) {
""",
)

print("REFERENCE_UI_POLISH_V2_APPLIED")
