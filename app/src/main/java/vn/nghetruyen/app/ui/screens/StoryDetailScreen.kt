package vn.nghetruyen.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import vn.nghetruyen.app.ai.StoryVoiceCastMode
import vn.nghetruyen.app.ai.StoryVoiceCastReferenceCodec
import vn.nghetruyen.app.audio.AudioExportRequest
import vn.nghetruyen.app.core.model.TtsEngineOption
import vn.nghetruyen.app.core.model.VoiceExpression
import vn.nghetruyen.app.core.model.VoiceRoleDraft
import vn.nghetruyen.app.core.model.GLOBAL_VOICE_PROFILE_STORY_ID
import vn.nghetruyen.app.data.local.StoryAiProfileEntity
import vn.nghetruyen.app.data.local.VoiceRoleEntity
import vn.nghetruyen.app.data.settings.AiProvider
import vn.nghetruyen.app.sources.StorySearch
import vn.nghetruyen.app.ui.MainUiState
import vn.nghetruyen.app.ui.components.LoadingRow
import vn.nghetruyen.app.ui.components.ReferenceActionButton
import vn.nghetruyen.app.ui.components.ReferenceDivider
import vn.nghetruyen.app.ui.components.ReferenceGray
import vn.nghetruyen.app.ui.components.ReferenceGreen
import vn.nghetruyen.app.ui.components.ReferencePanelBackground
import vn.nghetruyen.app.ui.components.ReferencePurple
import vn.nghetruyen.app.ui.components.ReferenceScreenBackground
import vn.nghetruyen.app.ui.components.ReferenceSecondaryText
import vn.nghetruyen.app.ui.components.ReferenceTabButton
import vn.nghetruyen.app.ui.components.ReferenceText

@Composable
fun StoryDetailScreen(
    state: MainUiState,
    onBack: () -> Unit,
    onReadFirst: () -> Unit,
    onDownload: () -> Unit,
    onDownloadUnread: () -> Unit,
    onDownloadRange: (Int, Int) -> Unit,
    onDownloadSelected: (List<Int>) -> Unit,
    onToggleFollowing: () -> Unit,
    onToggleStoryBookmark: () -> Unit,
    onGenreSelected: (String) -> Unit,
    onTabSelected: (String) -> Unit,
    onChapterSortDescendingChange: (Boolean) -> Unit,
    onConsumeAdvancedOptionsRequest: () -> Unit,
    onExportAudio: (AudioExportRequest) -> Unit,
    onSaveVoiceProfile: () -> Unit,
    onClearVoiceProfile: () -> Unit,
    onSaveVoiceRole: (VoiceRoleDraft) -> Unit,
    onPreviewVoiceRole: (VoiceRoleDraft) -> Unit,
    onLoadRoleVoices: (String?) -> Unit,
    onVoiceRoleEnabledChange: (String, Boolean) -> Unit,
    onDeleteVoiceRole: (String) -> Unit,
    onSaveAiProfile: (
        String, Boolean, AiProvider, String, String, Float, Boolean, String, String, Boolean,
        Boolean, String, String, Boolean, Boolean, Boolean, String, Int, Int, Int,
    ) -> Unit,
    onClearAiProfile: () -> Unit,
    onChapterClick: (vn.nghetruyen.app.core.model.ChapterSummary) -> Unit,
    onLoadMoreChapters: () -> Unit,
    onLoadAllChapters: () -> Unit,
    onLoadComments: (Boolean) -> Unit,
    onLoadMoreComments: () -> Unit,
    onOpenOriginal: (String) -> Unit,
    onCheckSource: (String) -> Unit,
    onOpenSourceLogin: (String) -> Unit,
    onSourceUiAction: (String, String) -> Unit,
) {
    val detail = state.storyDetail ?: return
    val selectedTab = state.storyDetailTab
    val aiProfile = state.storyAiProfiles[detail.story.id]
    val sourceDescriptor = state.sources.firstOrNull { it.id == detail.story.sourceId }
    val view = LocalView.current

    var chapterQuery by remember(detail.story.id) { mutableStateOf("") }
    var showChapterSearchDialog by remember(detail.story.id) { mutableStateOf(false) }
    var showChapterSortDialog by remember(detail.story.id) { mutableStateOf(false) }
    var showDownloadScopeDialog by remember(detail.story.id) { mutableStateOf(false) }
    var showMultiChapterDialog by remember(detail.story.id) { mutableStateOf(false) }
    var selectedDownloadChapters by remember(detail.story.id) { mutableStateOf(setOf<Int>()) }
    var showStoryMenu by remember(detail.story.id) { mutableStateOf(false) }
    var advancedMode by remember(detail.story.id) { mutableStateOf<String?>(null) }
    var showVoiceProfiles by remember(detail.story.id) { mutableStateOf(false) }
    var showVoiceRoleDialog by remember(detail.story.id) { mutableStateOf(false) }
    var showExpressionPromptDialog by remember(detail.story.id) { mutableStateOf(false) }

    fun defaultRoleDraft() = VoiceRoleDraft(
        roleName = "",
        enginePackage = state.selectedTtsEnginePackage,
        voiceName = state.selectedTtsVoiceName,
        languageTag = state.selectedTtsLanguageTag,
        rate = state.playback.rate,
        pitch = state.playback.pitch,
        volume = state.ttsVolume,
    )
    var roleDraft by remember(detail.story.id) { mutableStateOf(defaultRoleDraft()) }

    val voiceReference = StoryVoiceCastReferenceCodec.decode(aiProfile?.voiceCastNote.orEmpty())
    var voiceMode by remember(detail.story.id, aiProfile?.updatedAt) { mutableStateOf(voiceReference.mode) }
    var voiceAutoRun by remember(detail.story.id, aiProfile?.updatedAt) { mutableStateOf(voiceReference.autoRunOnOpenTts) }
    var voiceNote by remember(detail.story.id, aiProfile?.updatedAt) { mutableStateOf(voiceReference.note) }
    var expressive by remember(detail.story.id, aiProfile?.updatedAt) { mutableStateOf(aiProfile?.expressiveAdjustment ?: false) }
    var expressionPrompt by remember(detail.story.id, aiProfile?.updatedAt) { mutableStateOf(aiProfile?.expressionPrompt.orEmpty()) }
    var speedLimit by remember(detail.story.id, aiProfile?.updatedAt) { mutableStateOf(aiProfile?.expressionSpeedLimitPct ?: 10) }
    var pitchLimit by remember(detail.story.id, aiProfile?.updatedAt) { mutableStateOf(aiProfile?.expressionPitchLimitPct ?: 10) }
    var volumeLimit by remember(detail.story.id, aiProfile?.updatedAt) { mutableStateOf(aiProfile?.expressionVolumeLimitPct ?: 10) }

    var aiMode by remember(detail.story.id, aiProfile?.updatedAt) { mutableStateOf(aiProfile?.mode ?: "INHERIT") }
    var aiAutoRun by remember(detail.story.id, aiProfile?.updatedAt) { mutableStateOf(aiProfile?.autoRunOnOpen ?: false) }
    var aiCustomPrompts by remember(detail.story.id, aiProfile?.updatedAt) { mutableStateOf(aiProfile?.useCustomPrompts ?: false) }
    var aiTranslatePrompt by remember(detail.story.id, aiProfile?.updatedAt) { mutableStateOf(aiProfile?.translationPrompt.orEmpty()) }
    var aiImprovePrompt by remember(detail.story.id, aiProfile?.updatedAt) { mutableStateOf(aiProfile?.improvePrompt.orEmpty()) }

    fun persistCombinedProfile(
        mode: String = aiMode,
        autoRun: Boolean = aiAutoRun,
        customPrompts: Boolean = aiCustomPrompts,
        translatePrompt: String = aiTranslatePrompt,
        improvePrompt: String = aiImprovePrompt,
        storyVoiceMode: StoryVoiceCastMode = voiceMode,
        storyVoiceAuto: Boolean = voiceAutoRun,
        storyVoiceNote: String = voiceNote,
        expressiveEnabled: Boolean = expressive,
        expressivePrompt: String = expressionPrompt,
        speedPct: Int = speedLimit,
        pitchPct: Int = pitchLimit,
        volumePct: Int = volumeLimit,
    ) {
        onSaveAiProfile(
            mode,
            aiProfile?.overrideProvider ?: false,
            runCatching { AiProvider.valueOf(aiProfile?.provider.orEmpty()) }.getOrDefault(state.aiOnline.provider),
            aiProfile?.endpoint.orEmpty().ifBlank { state.aiOnline.endpoint },
            aiProfile?.model.orEmpty().ifBlank { state.aiOnline.model },
            aiProfile?.temperature?.takeIf { it >= 0f } ?: state.aiOnline.temperature.coerceAtMost(1f),
            customPrompts,
            translatePrompt,
            improvePrompt,
            autoRun && mode == "TRANSLATE",
            aiProfile?.useCustomVoiceCastPrompt ?: false,
            aiProfile?.voiceCastPrompt.orEmpty(),
            StoryVoiceCastReferenceCodec.encode(storyVoiceMode, storyVoiceAuto, storyVoiceNote),
            true,
            true,
            expressiveEnabled,
            expressivePrompt,
            speedPct.coerceIn(0, 100),
            pitchPct.coerceIn(0, 100),
            volumePct.coerceIn(0, 100),
        )
    }

    val normalizedQuery = StorySearch.normalize(chapterQuery)
    val filteredChapters = remember(detail.chapters, normalizedQuery) {
        if (normalizedQuery.isBlank()) detail.chapters
        else detail.chapters.filter { chapter ->
            StorySearch.normalize(chapter.title).contains(normalizedQuery) ||
                (chapter.index + 1).toString().contains(normalizedQuery)
        }
    }
    val visibleChapters = remember(filteredChapters, state.chapterSortDescending) {
        if (state.chapterSortDescending) filteredChapters.sortedByDescending { it.index }
        else filteredChapters.sortedBy { it.index }
    }
    val chapterListState = rememberLazyListState()
    val tabs = listOf("intro" to "GIỚI THIỆU", "chapters" to "CHƯƠNG", "comments" to "BÌNH LUẬN", "source" to "NGUỒN")
    val storyMeta = buildList {
        if (detail.story.author.isNotBlank()) add(detail.story.author)
        if (detail.status.isNotBlank()) add(detail.status)
        add("${detail.chapters.size} chương")
    }.joinToString(" • ")
    val storyBookmarkMarker = "Truyện: ${detail.story.title}"
    val storyBookmarked = state.bookmarks.any { it.storyId == detail.story.id && it.label == storyBookmarkMarker }
    val currentChapter = detail.chapters.firstOrNull { it.id == state.playback.chapterId }
    val currentChapterIndex = currentChapter?.index

    LaunchedEffect(state.storyAdvancedOptionsRequested, state.storyAdvancedOptionsMode) {
        if (state.storyAdvancedOptionsRequested) {
            advancedMode = state.storyAdvancedOptionsMode ?: "ai"
            onConsumeAdvancedOptionsRequest()
        }
    }
    LaunchedEffect(selectedTab, visibleChapters, state.playback.chapterId, state.chapterSortDescending) {
        if (selectedTab == "chapters" && state.playback.chapterId.isNotBlank()) {
            val index = visibleChapters.indexOfFirst { it.id == state.playback.chapterId }
            if (index >= 0) {
                delay(100)
                chapterListState.scrollToItem(index)
            }
        }
    }
    LaunchedEffect(selectedTab, state.storyCommentsLoading) {
        delay(120)
        view.announceForAccessibility(
            when (selectedTab) {
                "intro" -> "Giới thiệu truyện ${detail.story.title}"
                "chapters" -> "Danh sách chương, ${visibleChapters.size} mục"
                "comments" -> "Bình luận, ${state.storyComments.size} mục"
                else -> "Thông tin nguồn truyện ${detail.story.title}"
            },
        )
    }

    Column(Modifier.fillMaxSize().background(ReferenceScreenBackground)) {
        ReferenceActionButton("QUAY LẠI", onBack, normalColor = ReferenceGray, modifier = Modifier.fillMaxWidth().padding(4.dp))
        Column(Modifier.fillMaxWidth().background(ReferencePanelBackground).padding(10.dp)) {
            Text(detail.story.title, color = ReferenceText, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
            Text(storyMeta, color = ReferenceSecondaryText)
        }
        Row(Modifier.fillMaxWidth().background(ReferenceDivider).padding(2.dp)) {
            ReferenceActionButton(
                text = if (state.continueAvailable) "ĐỌC TIẾP" + currentChapter?.title?.takeIf(String::isNotBlank)?.let { "\n$it" }.orEmpty() else "ĐỌC NGAY",
                onClick = onReadFirst,
                normalColor = ReferenceGreen,
                minHeight = 64.dp,
                modifier = Modifier.weight(1f).padding(1.dp),
            )
            ReferenceActionButton("TẢI TRUYỆN", { showDownloadScopeDialog = true }, normalColor = ReferencePurple, minHeight = 64.dp, modifier = Modifier.weight(1f).padding(1.dp))
            ReferenceActionButton("TÙY CHỌN", { showStoryMenu = true }, normalColor = ReferenceGray, minHeight = 64.dp, modifier = Modifier.weight(1f).padding(1.dp))
        }

        val customStoryActions = sourceDescriptor?.uiActions.orEmpty()
            .filter { vn.nghetruyen.app.sources.SourceUiSurface.STORY in it.surfaces }
            .sortedWith(compareBy({ it.group }, { it.order }, { it.label }))
        if (sourceDescriptor != null && customStoryActions.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(ReferenceDivider).padding(2.dp)) {
                customStoryActions.forEach { action ->
                    ReferenceActionButton(action.label, { onSourceUiAction(sourceDescriptor.id, action.id) }, normalColor = ReferenceGray, minHeight = 48.dp, modifier = Modifier.padding(1.dp))
                }
            }
        }
        if (sourceDescriptor != null && (sourceDescriptor.loginUrl != null || sourceDescriptor.health != vn.nghetruyen.app.core.model.SourceHealth.READY)) {
            Row(Modifier.fillMaxWidth().background(ReferenceDivider).padding(2.dp)) {
                if (sourceDescriptor.loginUrl != null) {
                    ReferenceActionButton(
                        if (sourceDescriptor.id in state.sourceSessions) "MỞ LẠI PHIÊN" else "ĐĂNG NHẬP NGUỒN",
                        { onOpenSourceLogin(sourceDescriptor.id) }, normalColor = ReferenceGray, minHeight = 48.dp, modifier = Modifier.weight(1f).padding(1.dp),
                    )
                }
                ReferenceActionButton(
                    if (sourceDescriptor.id in state.sourceHealthChecking) "ĐANG KIỂM TRA" else "KIỂM TRA NGUỒN",
                    { onCheckSource(sourceDescriptor.id) },
                    enabled = sourceDescriptor.id !in state.sourceHealthChecking,
                    normalColor = ReferenceGray,
                    minHeight = 48.dp,
                    modifier = Modifier.weight(1f).padding(1.dp),
                )
            }
        }

        Row(Modifier.fillMaxWidth().background(ReferenceDivider).padding(2.dp)) {
            tabs.forEach { (id, label) ->
                ReferenceTabButton(
                    text = label,
                    selected = selectedTab == id,
                    onClick = { onTabSelected(id); if (id == "comments") onLoadComments(false) },
                    minHeight = 60.dp,
                    unselectedColor = ReferenceDivider,
                    unselectedContentColor = ReferenceText,
                    modifier = Modifier.weight(1f).padding(1.dp),
                )
            }
        }
        HorizontalDivider()
        if (state.loading) LoadingRow()

        when (selectedTab) {
            "intro" -> LazyColumn(Modifier.fillMaxSize()) {
                if (detail.genres.isNotEmpty()) {
                    item { Text("THỂ LOẠI", fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().padding(12.dp)) }
                    items(detail.genres.distinct(), key = { "genre:$it" }) { genre ->
                        ReferenceActionButton(genre, { onGenreSelected(genre) }, normalColor = ReferenceDivider, normalContentColor = ReferenceText, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                }
                item { Text("GIỚI THIỆU", fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().padding(12.dp)) }
                item { Text(detail.story.description.ifBlank { "Nguồn chưa cung cấp phần giới thiệu." }, modifier = Modifier.padding(16.dp)) }
            }

            "chapters" -> Column(Modifier.fillMaxSize()) {
                Text("DANH SÁCH CHƯƠNG\n${detail.story.title}\n${visibleChapters.size} chương", fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().padding(12.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                    ReferenceActionButton(
                        if (normalizedQuery.isBlank()) "TÌM CHƯƠNG" else "TÌM KIẾM: $chapterQuery",
                        { showChapterSearchDialog = true }, normalColor = ReferenceGray, minHeight = 50.dp, modifier = Modifier.weight(1f).padding(1.dp),
                    )
                    if (normalizedQuery.isNotBlank()) {
                        ReferenceActionButton("HIỆN TẤT CẢ CHƯƠNG", { chapterQuery = "" }, normalColor = ReferenceGray, minHeight = 50.dp, modifier = Modifier.weight(1f).padding(1.dp))
                    }
                }
                ReferenceActionButton(
                    "SẮP XẾP: ${if (state.chapterSortDescending) "MỚI NHẤT TRƯỚC" else "CŨ NHẤT TRƯỚC"}",
                    { showChapterSortDialog = true }, normalColor = ReferenceGray, minHeight = 50.dp, modifier = Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 1.dp),
                )
                LazyColumn(state = chapterListState, modifier = Modifier.fillMaxSize()) {
                    items(visibleChapters, key = { it.id }) { chapter ->
                        val status = when {
                            chapter.id == state.playback.chapterId -> "\nĐang đọc"
                            currentChapterIndex != null && chapter.index < currentChapterIndex -> "\nĐã đọc"
                            else -> ""
                        }
                        ReferenceActionButton(
                            chapter.title + status,
                            { onChapterClick(chapter) },
                            normalColor = ReferencePanelBackground,
                            normalContentColor = ReferenceText,
                            selected = chapter.id == state.playback.chapterId,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                    if (visibleChapters.isEmpty()) item { Text("Không tìm thấy chương phù hợp.", modifier = Modifier.padding(16.dp)) }
                    if (detail.nextChapterPageUrl != null) {
                        item {
                            Row(Modifier.fillMaxWidth().padding(8.dp)) {
                                Button(onClick = onLoadMoreChapters, enabled = !state.loading, modifier = Modifier.weight(1f).padding(2.dp)) { Text("TẢI THÊM") }
                                Button(onClick = onLoadAllChapters, enabled = !state.loading, modifier = Modifier.weight(1f).padding(2.dp)) { Text("NẠP TOÀN BỘ MỤC LỤC") }
                            }
                        }
                    }
                }
            }

            "comments" -> Column(Modifier.fillMaxSize()) {
                Text("BÌNH LUẬN", fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().padding(12.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    Text("${state.storyComments.size} bình luận", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(top = 12.dp))
                    Button(onClick = { onLoadComments(true) }, enabled = state.storyCommentsRefreshable && !state.storyCommentsLoading) {
                        Text(if (state.storyCommentsRefreshable) "TẢI LẠI" else "BẢN NHÚNG")
                    }
                }
                if (state.storyCommentsLoading) LoadingRow()
                state.storyCommentsMessage?.let { Text(it, modifier = Modifier.padding(16.dp)) }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.storyComments, key = { "${it.user}|${it.time}|${it.text.hashCode()}" }) { comment ->
                        androidx.compose.material3.Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Text(comment.user.ifBlank { "Người đọc" }, fontWeight = FontWeight.SemiBold)
                                if (comment.time.isNotBlank()) Text(comment.time, style = MaterialTheme.typography.bodySmall)
                                Text(comment.text, modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                    }
                    if (state.storyCommentsNextPageUrl != null) item {
                        Button(onClick = onLoadMoreComments, enabled = !state.storyCommentsLoading, modifier = Modifier.fillMaxWidth().padding(8.dp)) { Text("TẢI THÊM BÌNH LUẬN") }
                    }
                }
            }

            "source" -> Column(Modifier.fillMaxSize().padding(16.dp)) {
                Text("NGUỒN", fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp))
                Text("Tên: ${sourceDescriptor?.displayName ?: detail.story.sourceId}")
                Text("ID: ${detail.story.sourceId}", modifier = Modifier.padding(top = 6.dp))
                Text("Website: ${sourceDescriptor?.baseUrl ?: detail.story.url}", modifier = Modifier.padding(top = 6.dp))
                Text("Truyện gốc: ${detail.story.url.ifBlank { "Không có địa chỉ gốc" }}", modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
                ReferenceActionButton("MỞ TRANG GỐC", { if (detail.story.url.startsWith("https://")) onOpenOriginal(detail.story.url) }, enabled = detail.story.url.startsWith("https://"), normalColor = ReferenceGray, modifier = Modifier.fillMaxWidth())
                ReferenceActionButton("KIỂM TRA", { onCheckSource(detail.story.sourceId) }, enabled = detail.story.sourceId !in state.sourceHealthChecking, normalColor = ReferenceGray, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
    }

    if (showStoryMenu) {
        AlertDialog(
            onDismissRequest = { showStoryMenu = false },
            title = { Text("TÙY CHỌN TRUYỆN") },
            text = { Column {
                val following = state.following.any { it.storyId == detail.story.id }
                ReferenceActionButton(if (storyBookmarked) "BỎ ĐÁNH DẤU" else "ĐÁNH DẤU", { showStoryMenu = false; onToggleStoryBookmark() }, selected = storyBookmarked, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
                ReferenceActionButton(if (following) "BỎ THEO DÕI" else "THEO DÕI", { showStoryMenu = false; onToggleFollowing() }, selected = following, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
                ReferenceActionButton("MỞ TRANG GỐC", { showStoryMenu = false; if (detail.story.url.startsWith("https://")) onOpenOriginal(detail.story.url) }, enabled = detail.story.url.startsWith("https://"), normalColor = ReferenceGray, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
                ReferenceActionButton("THÔNG TIN NGUỒN", { showStoryMenu = false; onTabSelected("source") }, normalColor = ReferenceGray, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
            } },
            confirmButton = { TextButton(onClick = { showStoryMenu = false }) { Text("ĐÓNG") } },
        )
    }

    if (advancedMode == "ai") {
        var modeExpanded by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { advancedMode = null },
            title = { Text("THIẾT LẬP AI CHO TRUYỆN NÀY") },
            text = {
                Column(Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState())) {
                    Text("AI RIÊNG CHO TRUYỆN", fontWeight = FontWeight.Bold)
                    Text(detail.story.title, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                    Text("Chế độ của truyện này")
                    Button(onClick = { modeExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(when (aiMode) { "TRANSLATE" -> "Dịch chương gốc"; "IMPROVE" -> "Cải thiện VietPhrase"; else -> "Theo cài đặt chung" })
                    }
                    DropdownMenu(expanded = modeExpanded, onDismissRequest = { modeExpanded = false }) {
                        listOf("INHERIT" to "Theo cài đặt chung", "TRANSLATE" to "Dịch chương gốc", "IMPROVE" to "Cải thiện VietPhrase").forEach { (value, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { aiMode = value; if (value != "TRANSLATE") aiAutoRun = false; modeExpanded = false })
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Tự động dịch khi mở chương", Modifier.weight(1f))
                        Switch(aiAutoRun, { aiAutoRun = it }, enabled = aiMode == "TRANSLATE")
                    }
                    Text(
                        "Chỉ dùng với chế độ Dịch chương gốc. Nếu đang ở chế độ TTS và TTS đã bật, bản dịch sẽ tự đọc. Ở chế độ Văn bản, công cụ chỉ dịch và hiển thị, không tự bật TTS.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Dùng lời nhắc riêng cho truyện này", Modifier.weight(1f))
                        Switch(aiCustomPrompts, { aiCustomPrompts = it })
                    }
                    if (aiCustomPrompts) {
                        Text("Lời nhắc riêng khi dịch", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                        Text("Biến: {{CHAPTER_TITLE}}, {{CHAPTER_TEXT}}", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(aiTranslatePrompt, { aiTranslatePrompt = it.take(16_000) }, minLines = 5, modifier = Modifier.fillMaxWidth())
                        Text("Lời nhắc riêng khi cải thiện VietPhrase", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                        Text("Biến: {{SOURCE_TITLE}}, {{SOURCE_TEXT}}, {{VIETPHRASE_TITLE}}, {{VIETPHRASE_TEXT}}", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(aiImprovePrompt, { aiImprovePrompt = it.take(16_000) }, minLines = 5, modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    persistCombinedProfile()
                    advancedMode = null
                }) { Text("LƯU") }
            },
            dismissButton = {
                TextButton(onClick = {
                    aiMode = "INHERIT"
                    aiAutoRun = false
                    aiCustomPrompts = false
                    aiTranslatePrompt = ""
                    aiImprovePrompt = ""
                    persistCombinedProfile(mode = "INHERIT", autoRun = false, customPrompts = false, translatePrompt = "", improvePrompt = "")
                    advancedMode = null
                }) { Text("DÙNG MẶC ĐỊNH") }
            },
        )
    }

    if (advancedMode == "voice") {
        var modeExpanded by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { advancedMode = null },
            title = { Text("PHÂN VAI TTS CHO TRUYỆN NÀY") },
            text = {
                Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
                    Text("Chế độ")
                    Button(onClick = { modeExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(when (voiceMode) { StoryVoiceCastMode.PRIVATE -> "Dùng cấu hình riêng cho truyện này"; StoryVoiceCastMode.OFF -> "Tắt phân vai cho truyện này"; else -> "Dùng cấu hình chung" })
                    }
                    DropdownMenu(expanded = modeExpanded, onDismissRequest = { modeExpanded = false }) {
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
                            StoryVoiceCastMode.GLOBAL -> "Truyện dùng bộ hồ sơ trong cài đặt chung và phụ thuộc công tắc bật mặc định ở đó."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Tự động phân vai rồi đọc khi mở chương ở chế độ TTS", Modifier.weight(1f))
                        Switch(voiceAutoRun, { voiceAutoRun = it }, enabled = voiceMode != StoryVoiceCastMode.OFF)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("AI tự điều chỉnh tốc độ, cao độ và âm lượng", Modifier.weight(1f))
                        Switch(expressive, { expressive = it }, enabled = voiceMode != StoryVoiceCastMode.OFF)
                    }
                    if (expressive && voiceMode != StoryVoiceCastMode.OFF) {
                        Text(
                            "AI xử lý phân vai và ba thông số phần trăm trong cùng một lượt. Không dùng nhãn buồn, vui hay tức giận. Chỉ lời thoại trực tiếp được đổi giọng và thông số; lời kể cùng nội tâm luôn giữ giọng Người kể chuyện ở thông số gốc. Mức AI trả về được áp trực tiếp trong giới hạn bên dưới. Âm lượng chỉ có thể tăng khi mức gốc còn dưới 100%.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        ReferencePercentSlider("Giới hạn tốc độ", speedLimit) { speedLimit = it }
                        ReferencePercentSlider("Giới hạn cao độ", pitchLimit) { pitchLimit = it }
                        ReferencePercentSlider("Giới hạn âm lượng", volumeLimit) { volumeLimit = it }
                        ReferenceActionButton("XEM / SỬA HƯỚNG DẪN THÔNG SỐ", { showExpressionPromptDialog = true }, normalColor = ReferenceGray, modifier = Modifier.fillMaxWidth())
                    }
                    Text(
                        "Thứ tự luôn là: tải chương → dịch tự động (nếu bật) → phân vai → TTS. Vì vậy hai tác vụ AI không chạy chồng lên nhau.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    if (voiceMode == StoryVoiceCastMode.PRIVATE) {
                        val privateRoles = state.voiceRoles.filter { it.storyId == detail.story.id }
                        Text("Bộ hồ sơ riêng: ${privateRoles.size} vai", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                        ReferenceActionButton("THIẾT LẬP BỘ GIỌNG RIÊNG", { showVoiceProfiles = true }, normalColor = ReferenceGray, modifier = Modifier.fillMaxWidth())
                    }
                    Text("Ghi chú chung bổ sung cho AI", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    OutlinedTextField(
                        voiceNote,
                        { voiceNote = it.take(4_000) },
                        placeholder = { Text("Ví dụ: thông báo trong ngoặc vuông thuộc vai Hệ thống; lời truyền âm dùng giọng của người phát...") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Tên, mô tả và cách tổ chức nhân vật do người dùng quyết định. Ứng dụng chỉ cung cấp cấu trúc hồ sơ, ID ổn định và giọng Người kể chuyện bắt buộc.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    persistCombinedProfile(storyVoiceMode = voiceMode, storyVoiceAuto = voiceAutoRun, storyVoiceNote = voiceNote, expressiveEnabled = expressive, expressivePrompt = expressionPrompt, speedPct = speedLimit, pitchPct = pitchLimit, volumePct = volumeLimit)
                    advancedMode = null
                }) { Text("LƯU") }
            },
        )
    }

    if (showExpressionPromptDialog) {
        var draft by remember(showExpressionPromptDialog) { mutableStateOf(expressionPrompt) }
        AlertDialog(
            onDismissRequest = { showExpressionPromptDialog = false },
            title = { Text("HƯỚNG DẪN ĐIỀU CHỈNH THÔNG SỐ") },
            text = { OutlinedTextField(draft, { draft = it.take(8_000) }, minLines = 8, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { expressionPrompt = draft; showExpressionPromptDialog = false }) { Text("DÙNG LỜI NHẮC NÀY") } },
            dismissButton = { TextButton(onClick = { draft = ""; expressionPrompt = ""; showExpressionPromptDialog = false }) { Text("KHÔI PHỤC LỜI NHẮC MẶC ĐỊNH") } },
        )
    }

    if (showVoiceProfiles) {
        val privateRoles = state.voiceRoles.filter { it.storyId == detail.story.id }
        val globalRoles = state.voiceRoles.filter { it.storyId == GLOBAL_VOICE_PROFILE_STORY_ID }.take(7)
        AlertDialog(
            onDismissRequest = { showVoiceProfiles = false },
            title = { Text("BỘ GIỌNG RIÊNG CỦA TRUYỆN") },
            text = {
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                    privateRoles.forEach { role ->
                        ReferenceActionButton(
                            text = role.roleName + role.description.takeIf(String::isNotBlank)?.let { "\n$it" }.orEmpty(),
                            onClick = {
                                roleDraft = role.toDraft()
                                showVoiceRoleDialog = true
                                onLoadRoleVoices(role.enginePackage)
                            },
                            normalColor = ReferencePanelBackground,
                            normalContentColor = ReferenceText,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        )
                    }
                    ReferenceActionButton("THÊM VAI HOẶC NHÂN VẬT", { roleDraft = defaultRoleDraft(); showVoiceRoleDialog = true; onLoadRoleVoices(roleDraft.enginePackage) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    ReferenceActionButton("SAO CHÉP LẠI TỪ CẤU HÌNH CHUNG", {
                        globalRoles.forEach { onSaveVoiceRole(it.toDraft().copy(originalRoleId = null)) }
                    }, normalColor = ReferenceGray, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    ReferenceActionButton("KHÔI PHỤC 7 HỒ SƠ MẪU", {
                        privateRoles.filterNot(VoiceRoleEntity::isNarrator).forEach { onDeleteVoiceRole(it.id) }
                        globalRoles.forEach { onSaveVoiceRole(it.toDraft().copy(originalRoleId = null)) }
                    }, normalColor = ReferenceGray, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            },
            confirmButton = { TextButton(onClick = { showVoiceProfiles = false }) { Text("ĐÓNG") } },
        )
    }

    if (showVoiceRoleDialog) {
        val engines = listOf<TtsEngineOption?>(null) + state.ttsEngines
        var engineExpanded by remember { mutableStateOf(false) }
        var languageExpanded by remember { mutableStateOf(false) }
        var voiceExpanded by remember { mutableStateOf(false) }
        val roleVoices = state.roleEditorVoices
        val languages = roleVoices.map { it.languageTag }.filter(String::isNotBlank).distinct().sorted()
        val filteredVoices = roleVoices.filter { roleDraft.languageTag.isBlank() || it.languageTag == roleDraft.languageTag }
        AlertDialog(
            onDismissRequest = { showVoiceRoleDialog = false },
            title = { Text(if (roleDraft.originalRoleId == null) "THÊM VAI HOẶC NHÂN VẬT" else "SỬA HỒ SƠ VAI") },
            text = {
                Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(roleDraft.isNarrator, { roleDraft = roleDraft.copy(isNarrator = it, roleName = if (it) "Người kể chuyện" else "") })
                        Text("Đây là Người kể chuyện")
                    }
                    if (!roleDraft.isNarrator) {
                        OutlinedTextField(roleDraft.roleName, { roleDraft = roleDraft.copy(roleName = it.take(80)) }, label = { Text("Tên vai hoặc nhân vật") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(roleDraft.description, { roleDraft = roleDraft.copy(description = it.take(1_000)) }, label = { Text("Mô tả") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(roleDraft.aliases, { roleDraft = roleDraft.copy(aliases = it.take(500)) }, label = { Text("Bí danh, cách nhau bằng dấu phẩy") }, modifier = Modifier.fillMaxWidth())
                    }
                    Text("Bộ đọc TTS", modifier = Modifier.padding(top = 8.dp))
                    Button(onClick = { engineExpanded = true }, modifier = Modifier.fillMaxWidth()) { Text(roleDraft.enginePackage ?: "Mặc định hệ thống") }
                    DropdownMenu(expanded = engineExpanded, onDismissRequest = { engineExpanded = false }) {
                        engines.forEach { engine -> DropdownMenuItem(
                            text = { Text(engine?.label ?: "Mặc định hệ thống") },
                            onClick = { roleDraft = roleDraft.copy(enginePackage = engine?.packageName, voiceName = null); engineExpanded = false; onLoadRoleVoices(engine?.packageName) },
                        ) }
                    }
                    Text("Ngôn ngữ", modifier = Modifier.padding(top = 8.dp))
                    Button(onClick = { languageExpanded = true }, modifier = Modifier.fillMaxWidth()) { Text(roleDraft.languageTag.ifBlank { "vi-VN" }) }
                    DropdownMenu(expanded = languageExpanded, onDismissRequest = { languageExpanded = false }) {
                        (if (languages.isEmpty()) listOf("vi-VN") else languages).forEach { lang -> DropdownMenuItem(text = { Text(lang) }, onClick = { roleDraft = roleDraft.copy(languageTag = lang, voiceName = null); languageExpanded = false }) }
                    }
                    Text("Giọng đọc", modifier = Modifier.padding(top = 8.dp))
                    Button(onClick = { voiceExpanded = true }, modifier = Modifier.fillMaxWidth(), enabled = filteredVoices.isNotEmpty()) { Text(roleDraft.voiceName ?: "Giọng mặc định") }
                    DropdownMenu(expanded = voiceExpanded, onDismissRequest = { voiceExpanded = false }) {
                        DropdownMenuItem(text = { Text("Giọng mặc định") }, onClick = { roleDraft = roleDraft.copy(voiceName = null); voiceExpanded = false })
                        filteredVoices.forEach { voice -> DropdownMenuItem(text = { Text(voice.displayName) }, onClick = { roleDraft = roleDraft.copy(voiceName = voice.name, languageTag = voice.languageTag); voiceExpanded = false }) }
                    }
                    ReferenceFloatSlider("Tốc độ", roleDraft.rate, 0.25f, 3f) { roleDraft = roleDraft.copy(rate = it) }
                    ReferenceFloatSlider("Cao độ", roleDraft.pitch, 0.5f, 2f) { roleDraft = roleDraft.copy(pitch = it) }
                    ReferenceFloatSlider("Âm lượng", roleDraft.volume, 0f, 2f, percent = true) { roleDraft = roleDraft.copy(volume = it) }
                    ReferenceActionButton("NGHE THỬ", { onPreviewVoiceRole(roleDraft) }, normalColor = ReferenceGray, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                }
            },
            confirmButton = { TextButton(enabled = roleDraft.isNarrator || roleDraft.roleName.isNotBlank(), onClick = { onSaveVoiceRole(roleDraft.copy(roleName = if (roleDraft.isNarrator) "Người kể chuyện" else roleDraft.roleName)); showVoiceRoleDialog = false }) { Text("LƯU") } },
            dismissButton = { TextButton(onClick = { showVoiceRoleDialog = false }) { Text("HỦY") } },
        )
    }

    if (showDownloadScopeDialog) {
        AlertDialog(
            onDismissRequest = { showDownloadScopeDialog = false },
            title = { Text("CHỌN PHẠM VI TẢI") },
            text = { Column {
                ReferenceActionButton("TẢI CHƯƠNG ĐẦU", { showDownloadScopeDialog = false; onDownloadRange(1, 1) }, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
                ReferenceActionButton("CHỌN NHIỀU CHƯƠNG", { showDownloadScopeDialog = false; selectedDownloadChapters = emptySet(); showMultiChapterDialog = true }, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
                ReferenceActionButton("TẢI TOÀN BỘ TRUYỆN", { showDownloadScopeDialog = false; onDownload() }, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
            } },
            confirmButton = { TextButton(onClick = { showDownloadScopeDialog = false }) { Text("ĐÓNG") } },
        )
    }

    if (showChapterSearchDialog) {
        var draft by remember(showChapterSearchDialog) { mutableStateOf(chapterQuery) }
        AlertDialog(
            onDismissRequest = { showChapterSearchDialog = false },
            title = { Text("TÌM CHƯƠNG") },
            text = { OutlinedTextField(draft, { draft = it.take(120) }, placeholder = { Text("Nhập tên chương hoặc số chương") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { chapterQuery = draft; showChapterSearchDialog = false }) { Text("TÌM") } },
            dismissButton = { Row {
                TextButton(onClick = { chapterQuery = ""; showChapterSearchDialog = false }) { Text("HIỆN TẤT CẢ") }
                TextButton(onClick = { showChapterSearchDialog = false }) { Text("HỦY") }
            } },
        )
    }

    if (showChapterSortDialog) {
        AlertDialog(
            onDismissRequest = { showChapterSortDialog = false },
            title = { Text("SẮP XẾP DANH SÁCH CHƯƠNG") },
            text = { Column {
                ReferenceActionButton("CŨ NHẤT TRƯỚC", { onChapterSortDescendingChange(false); showChapterSortDialog = false }, modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp))
                ReferenceActionButton("MỚI NHẤT TRƯỚC", { onChapterSortDescendingChange(true); showChapterSortDialog = false }, modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp))
            } },
            confirmButton = { TextButton(onClick = { showChapterSortDialog = false }) { Text("ĐÓNG") } },
        )
    }

    if (showMultiChapterDialog) {
        AlertDialog(
            onDismissRequest = { showMultiChapterDialog = false },
            title = { Text("CHỌN NHIỀU CHƯƠNG") },
            text = { LazyColumn { items(detail.chapters, key = { "download:${it.id}" }) { chapter ->
                val number = chapter.index + 1
                Row(Modifier.fillMaxWidth().clickable { selectedDownloadChapters = if (number in selectedDownloadChapters) selectedDownloadChapters - number else selectedDownloadChapters + number }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(number in selectedDownloadChapters, { checked -> selectedDownloadChapters = if (checked) selectedDownloadChapters + number else selectedDownloadChapters - number })
                    Text(chapter.title, modifier = Modifier.weight(1f))
                }
            } } },
            confirmButton = { TextButton(enabled = selectedDownloadChapters.isNotEmpty(), onClick = { onDownloadSelected(selectedDownloadChapters.sorted()); showMultiChapterDialog = false }) { Text("TẢI ${selectedDownloadChapters.size} CHƯƠNG") } },
            dismissButton = { TextButton(onClick = { showMultiChapterDialog = false }) { Text("HỦY") } },
        )
    }
}

private fun VoiceRoleEntity.toDraft(): VoiceRoleDraft = VoiceRoleDraft(
    roleName = roleName,
    originalRoleId = id,
    aliases = aliasesCsv,
    description = description,
    isNarrator = isNarrator,
    enginePackage = enginePackage,
    voiceName = voiceName,
    languageTag = languageTag,
    rate = rate,
    pitch = pitch,
    volume = volume,
    expression = runCatching { VoiceExpression.valueOf(expression) }.getOrDefault(VoiceExpression.NEUTRAL),
    expressionStrength = expressionStrength,
    sonicSpeed = sonicSpeed,
    sonicPitch = sonicPitch,
    enabled = enabled,
)

@Composable
private fun ReferencePercentSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Text("$label: ±${value.coerceIn(0, 100)}%", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
    Slider(value = value.coerceIn(0, 100).toFloat(), onValueChange = { onChange(it.toInt().coerceIn(0, 100)) }, valueRange = 0f..100f, steps = 19)
}

@Composable
private fun ReferenceFloatSlider(
    label: String,
    value: Float,
    minimum: Float,
    maximum: Float,
    percent: Boolean = false,
    onChange: (Float) -> Unit,
) {
    val shown = value.coerceIn(minimum, maximum)
    Text(
        if (percent) "$label: ${"%.0f".format(shown * 100)}%" else "$label: ${"%.2f".format(shown)}x",
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 6.dp),
    )
    Slider(value = shown, onValueChange = onChange, valueRange = minimum..maximum)
}
