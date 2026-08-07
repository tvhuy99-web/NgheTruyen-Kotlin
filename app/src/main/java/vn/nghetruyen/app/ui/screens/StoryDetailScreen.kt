package vn.nghetruyen.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import vn.nghetruyen.app.audio.AudioExportPackaging
import vn.nghetruyen.app.audio.AudioExportRequest
import vn.nghetruyen.app.audio.AudioExportScope
import vn.nghetruyen.app.data.settings.AiProvider
import vn.nghetruyen.app.core.model.AudioExportFormat
import vn.nghetruyen.app.core.model.ChapterSummary
import vn.nghetruyen.app.core.model.VoiceExpression
import vn.nghetruyen.app.core.model.VoiceRoleDraft
import vn.nghetruyen.app.sources.ChapterCatalogIndex
import vn.nghetruyen.app.sources.StorySearch
import vn.nghetruyen.app.ui.MainUiState
import vn.nghetruyen.app.ui.components.LargeActionButton
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
    onChapterClick: (ChapterSummary) -> Unit,
    onLoadMoreChapters: () -> Unit,
    onLoadAllChapters: () -> Unit,
    onLoadComments: (Boolean) -> Unit,
    onLoadMoreComments: () -> Unit,
    onOpenOriginal: (String) -> Unit,
    onCheckSource: (String) -> Unit,
    onOpenSourceLogin: (String) -> Unit,
) {
    val detail = state.storyDetail ?: return
    val selectedTab = state.storyDetailTab
    var chapterQuery by remember(detail.story.id) { mutableStateOf("") }
    var showDownloadScopeDialog by remember(detail.story.id) { mutableStateOf(false) }
    var showMultiChapterDialog by remember(detail.story.id) { mutableStateOf(false) }
    var showChapterSearchDialog by remember(detail.story.id) { mutableStateOf(false) }
    val chapterSortDescending = state.chapterSortDescending
    var showExportDialog by remember(detail.story.id) { mutableStateOf(false) }
    var showVoiceRoleDialog by remember(detail.story.id) { mutableStateOf(false) }
    var advancedMode by remember(detail.story.id) { mutableStateOf<String?>(null) }
    var showStoryMenu by remember(detail.story.id) { mutableStateOf(false) }
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
    var selectedDownloadChapters by remember(detail.story.id) { mutableStateOf(setOf<Int>()) }
    var exportRangeEnabled by remember(detail.story.id) { mutableStateOf(false) }
    var exportStart by remember(detail.story.id) { mutableStateOf("1") }
    var exportEnd by remember(detail.story.id, detail.chapters.size) {
        mutableStateOf(detail.chapters.size.coerceAtLeast(1).toString())
    }
    var exportFormat by remember(detail.story.id) { mutableStateOf(AudioExportFormat.MP3) }
    var exportSceneMusic by remember(detail.story.id) { mutableStateOf(false) }
    var exportSplitChapters by remember(detail.story.id) { mutableStateOf(false) }
    var exportChapterMarkers by remember(detail.story.id) { mutableStateOf(true) }
    val aiProfile = state.storyAiProfiles[detail.story.id]
    var aiMode by remember(detail.story.id, aiProfile?.updatedAt) { mutableStateOf(aiProfile?.mode ?: "INHERIT") }
    var aiOverrideProvider by remember(detail.story.id, aiProfile?.updatedAt) { mutableStateOf(aiProfile?.overrideProvider ?: false) }
    var aiProvider by remember(detail.story.id, aiProfile?.updatedAt) {
        mutableStateOf(runCatching { AiProvider.valueOf(aiProfile?.provider.orEmpty()) }.getOrDefault(state.aiOnline.provider))
    }
    var aiEndpoint by remember(detail.story.id, aiProfile?.updatedAt) { mutableStateOf(aiProfile?.endpoint?.ifBlank { state.aiOnline.endpoint } ?: state.aiOnline.endpoint) }
    var aiModel by remember(detail.story.id, aiProfile?.updatedAt) { mutableStateOf(aiProfile?.model?.ifBlank { state.aiOnline.model } ?: state.aiOnline.model) }
    var aiTemperature by remember(detail.story.id, aiProfile?.updatedAt) { mutableStateOf(aiProfile?.temperature?.takeIf { it >= 0f } ?: state.aiOnline.temperature) }
    var aiCustomPrompts by remember(detail.story.id, aiProfile?.updatedAt) { mutableStateOf(aiProfile?.useCustomPrompts ?: false) }
    var aiTranslationPrompt by remember(detail.story.id, aiProfile?.updatedAt) { mutableStateOf(aiProfile?.translationPrompt.orEmpty()) }
    var aiImprovePrompt by remember(detail.story.id, aiProfile?.updatedAt) { mutableStateOf(aiProfile?.improvePrompt.orEmpty()) }
    var aiAutoRun by remember(detail.story.id, aiProfile?.updatedAt) { mutableStateOf(aiProfile?.autoRunOnOpen ?: false) }
    var aiCustomVoiceCastPrompt by remember(detail.story.id, aiProfile?.updatedAt) { mutableStateOf(aiProfile?.useCustomVoiceCastPrompt ?: false) }
    var aiVoiceCastPrompt by remember(detail.story.id, aiProfile?.updatedAt) { mutableStateOf(aiProfile?.voiceCastPrompt.orEmpty()) }
    var aiVoiceCastNote by remember(detail.story.id, aiProfile?.updatedAt) { mutableStateOf(aiProfile?.voiceCastNote.orEmpty()) }
    var aiDialogueOnly by remember(detail.story.id, aiProfile?.updatedAt) { mutableStateOf(aiProfile?.voiceCastDialogueOnly ?: true) }
    var aiStableNarrator by remember(detail.story.id, aiProfile?.updatedAt) { mutableStateOf(aiProfile?.voiceCastStableNarrator ?: true) }
    var aiExpressiveAdjustment by remember(detail.story.id, aiProfile?.updatedAt) { mutableStateOf(aiProfile?.expressiveAdjustment ?: true) }
    var aiExpressionPrompt by remember(detail.story.id, aiProfile?.updatedAt) { mutableStateOf(aiProfile?.expressionPrompt.orEmpty()) }
    var aiExpressionSpeedLimit by remember(detail.story.id, aiProfile?.updatedAt) { mutableStateOf(aiProfile?.expressionSpeedLimitPct ?: 10) }
    var aiExpressionPitchLimit by remember(detail.story.id, aiProfile?.updatedAt) { mutableStateOf(aiProfile?.expressionPitchLimitPct ?: 10) }
    var aiExpressionVolumeLimit by remember(detail.story.id, aiProfile?.updatedAt) { mutableStateOf(aiProfile?.expressionVolumeLimitPct ?: 10) }
    val normalizedQuery = StorySearch.normalize(chapterQuery)
    val chapterCatalog = remember(detail.story.id, detail.chapters) { ChapterCatalogIndex(detail.chapters) }
    val filteredChapters = remember(chapterCatalog, normalizedQuery) { chapterCatalog.search(normalizedQuery) }
    val visibleChapters = remember(filteredChapters, chapterSortDescending) {
        if (chapterSortDescending) filteredChapters.sortedByDescending { it.index } else filteredChapters.sortedBy { it.index }
    }
    val chapterListState = rememberLazyListState()
    val tabs = listOf(
        "intro" to "GIỚI THIỆU",
        "chapters" to "CHƯƠNG",
        "comments" to "BÌNH LUẬN",
        "source" to "NGUỒN",
    )

    val view = LocalView.current
    val storyMeta = buildList {
        if (detail.story.author.isNotBlank()) add(detail.story.author)
        if (detail.status.isNotBlank()) add(detail.status)
        add("${detail.chapters.size} chương")
    }.joinToString(" • ")
    val hasVoiceProfile = state.storyTtsProfiles.containsKey(detail.story.id)
    val storyBookmarkMarker = "Truyện: ${detail.story.title}"
    val storyBookmarked = state.bookmarks.any { it.storyId == detail.story.id && it.label == storyBookmarkMarker }
    val currentChapter = detail.chapters.firstOrNull { it.id == state.playback.chapterId }
    val currentChapterIndex = currentChapter?.index
    val sourceDescriptor = state.sources.firstOrNull { it.id == detail.story.sourceId }
    LaunchedEffect(selectedTab, visibleChapters, state.playback.chapterId, chapterSortDescending) {
        if (selectedTab == "chapters" && state.playback.chapterId.isNotBlank()) {
            val targetIndex = visibleChapters.indexOfFirst { it.id == state.playback.chapterId }
            if (targetIndex >= 0) {
                delay(100)
                chapterListState.scrollToItem(targetIndex)
            }
        }
    }
    LaunchedEffect(state.storyAdvancedOptionsRequested, state.storyAdvancedOptionsMode) {
        if (state.storyAdvancedOptionsRequested) {
            advancedMode = state.storyAdvancedOptionsMode ?: "ai"
            onConsumeAdvancedOptionsRequest()
        }
    }
    LaunchedEffect(selectedTab, state.storyCommentsLoading) {
        delay(120)
        val announcement = when (selectedTab) {
            "intro" -> "Giới thiệu truyện ${detail.story.title}"
            "chapters" -> "Danh sách chương, ${visibleChapters.size} mục"
            "comments" -> "Bình luận, ${state.storyComments.size} mục"
            else -> "Thông tin nguồn truyện ${detail.story.title}"
        }
        view.announceForAccessibility(announcement)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ReferenceScreenBackground),
    ) {
        ReferenceActionButton(
            text = "QUAY LẠI",
            onClick = onBack,
            normalColor = ReferenceGray,
            accessibilityLabel = "Quay lại màn hình trước",
            modifier = Modifier.fillMaxWidth().padding(4.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(ReferencePanelBackground)
                .padding(10.dp),
        ) {
            Text(
                detail.story.title,
                color = ReferenceText,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics {
                    heading()
                    contentDescription = detail.story.title + if (storyMeta.isNotBlank()) ". " + storyMeta.replace(" • ", ". ") else ""
                },
            )
            Text(
                storyMeta,
                color = ReferenceSecondaryText,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ReferenceDivider)
                .padding(2.dp),
        ) {
            ReferenceActionButton(
                text = if (state.continueAvailable) {
                    "ĐỌC TIẾP" + currentChapter?.title?.takeIf(String::isNotBlank)?.let { "\n$it" }.orEmpty()
                } else "ĐỌC NGAY",
                onClick = onReadFirst,
                normalColor = ReferenceGreen,
                accessibilityLabel = if (state.continueAvailable) "Đọc tiếp truyện" else "Đọc ngay truyện",
                minHeight = 64.dp,
                modifier = Modifier.weight(1f).padding(1.dp),
            )
            ReferenceActionButton(
                text = "TẢI TRUYỆN",
                onClick = { showDownloadScopeDialog = true },
                normalColor = ReferencePurple,
                accessibilityLabel = "Tải truyện để đọc ngoại tuyến",
                minHeight = 64.dp,
                modifier = Modifier.weight(1f).padding(1.dp),
            )
            ReferenceActionButton(
                text = "TÙY CHỌN",
                onClick = { showStoryMenu = true },
                selected = false,
                selectedColor = ReferenceGray,
                normalColor = ReferenceGray,
                accessibilityLabel = "Tùy chọn truyện",
                minHeight = 64.dp,
                modifier = Modifier.weight(1f).padding(1.dp),
            )
        }
        if (sourceDescriptor != null && (sourceDescriptor.loginUrl != null || sourceDescriptor.health != vn.nghetruyen.app.core.model.SourceHealth.READY)) {
            Row(modifier = Modifier.fillMaxWidth().background(ReferenceDivider).padding(2.dp)) {
                if (sourceDescriptor.loginUrl != null) {
                    ReferenceActionButton(
                        text = if (sourceDescriptor.id in state.sourceSessions) "MỞ LẠI PHIÊN" else "ĐĂNG NHẬP NGUỒN",
                        onClick = { onOpenSourceLogin(sourceDescriptor.id) },
                        normalColor = ReferenceGray,
                        minHeight = 48.dp,
                        modifier = Modifier.weight(1f).padding(1.dp),
                    )
                }
                ReferenceActionButton(
                    text = if (sourceDescriptor.id in state.sourceHealthChecking) "ĐANG KIỂM TRA" else "KIỂM TRA NGUỒN",
                    onClick = { onCheckSource(sourceDescriptor.id) },
                    enabled = sourceDescriptor.id !in state.sourceHealthChecking,
                    normalColor = ReferenceGray,
                    minHeight = 48.dp,
                    modifier = Modifier.weight(1f).padding(1.dp),
                )
            }
        }
        if (showStoryMenu) {
            AlertDialog(
                onDismissRequest = { showStoryMenu = false },
                title = { Text("TÙY CHỌN TRUYỆN") },
                text = {
                    Column {
                        ReferenceActionButton(
                            text = if (storyBookmarked) "BỎ ĐÁNH DẤU" else "ĐÁNH DẤU",
                            onClick = {
                                showStoryMenu = false
                                onToggleStoryBookmark()
                            },
                            selected = storyBookmarked,
                            accessibilityLabel = if (storyBookmarked) "Bỏ đánh dấu truyện" else "Đánh dấu truyện",
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        )
                        val following = state.following.any { it.storyId == detail.story.id }
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
                            text = "MỞ TRANG GỐC",
                            onClick = {
                                showStoryMenu = false
                                if (detail.story.url.startsWith("https://")) onOpenOriginal(detail.story.url)
                            },
                            enabled = detail.story.url.startsWith("https://"),
                            normalColor = ReferenceGray,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        )
                        ReferenceActionButton(
                            text = "THÔNG TIN NGUỒN",
                            onClick = {
                                showStoryMenu = false
                                onTabSelected("source")
                            },
                            normalColor = ReferenceGray,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        )
                    }
                },
                confirmButton = { TextButton(onClick = { showStoryMenu = false }) { Text("ĐÓNG") } },
            )
        }
        if (advancedMode != null) {
            ReferenceActionButton(
                text = if (advancedMode == "voice") "ĐÓNG PHÂN VAI TTS" else "ĐÓNG THIẾT LẬP AI",
                onClick = { advancedMode = null },
                normalColor = ReferenceGray,
                accessibilityLabel = "Đóng cấu hình giọng đọc và AI nâng cao",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            )
        if (advancedMode == "voice") {
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Column(modifier = Modifier.padding(10.dp)) {
                // Legacy wiring validator token: Vai giọng thủ công
                Text("PHÂN VAI TTS CHO TRUYỆN NÀY", fontWeight = FontWeight.SemiBold)
                Text("Đoạn có tiền tố Tên:, Tên — hoặc [Tên] sẽ dùng giọng của vai tương ứng; đoạn khác dùng Người kể chuyện.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    roleDraft = defaultRoleDraft()
                    showVoiceRoleDialog = true
                    onLoadRoleVoices(roleDraft.enginePackage)
                }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text("THÊM HỒ SƠ VAI") }
                state.voiceRoles.filter { it.storyId == detail.story.id }.forEach { role ->
                    val draft = VoiceRoleDraft(
                        roleName = role.roleName,
                        originalRoleId = role.id,
                        aliases = role.aliasesCsv,
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
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text((if (role.isNarrator) "Người kể chuyện" else role.roleName), fontWeight = FontWeight.SemiBold)
                            if (role.aliasesCsv.isNotBlank()) Text("Bí danh: ${role.aliasesCsv}", style = MaterialTheme.typography.bodySmall)
                            Text("${role.enginePackage ?: "TTS mặc định"} • ${role.voiceName ?: "giọng mặc định"} • ${"%.2f".format(role.rate)}×/${"%.2f".format(role.pitch)}×/${"%.2f".format(role.volume)}", style = MaterialTheme.typography.bodySmall)
                            Text("${role.expression} ${"%.0f".format(role.expressionStrength * 100)}% • Sonic ${"%.2f".format(role.sonicSpeed)}×/${"%.2f".format(role.sonicPitch)}×", style = MaterialTheme.typography.bodySmall)
                        }
                        Column {
                            Row {
                                Button(onClick = { onPreviewVoiceRole(draft) }, modifier = Modifier.padding(1.dp)) { Text("NGHE") }
                                Button(onClick = {
                                    roleDraft = draft
                                    showVoiceRoleDialog = true
                                    onLoadRoleVoices(draft.enginePackage)
                                }, modifier = Modifier.padding(1.dp)) { Text("SỬA") }
                            }
                            Row {
                                Switch(checked = role.enabled, onCheckedChange = { onVoiceRoleEnabledChange(role.id, it) })
                                Button(onClick = { onDeleteVoiceRole(role.id) }, modifier = Modifier.padding(start = 4.dp)) { Text("XÓA") }
                            }
                        }
                    }
                }
            }
        }
        }
        if (advancedMode == "ai") {
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text("THIẾT LẬP AI CHO TRUYỆN NÀY", fontWeight = FontWeight.SemiBold)
                Text("Chọn hành vi mặc định và có thể ghi đè provider, model, temperature cùng prompt cho riêng truyện này.", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth()) {
                    listOf("INHERIT" to "KẾ THỪA", "TRANSLATE" to "DỊCH", "IMPROVE" to "CẢI THIỆN VP").forEach { (value, label) ->
                        Button({ aiMode = value }, Modifier.weight(1f).padding(2.dp)) { Text((if (aiMode == value) "✓ " else "") + label) }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Ghi đè nhà cung cấp và model", Modifier.weight(1f))
                    Switch(aiOverrideProvider, { aiOverrideProvider = it })
                }
                if (aiOverrideProvider) {
                    Row(Modifier.fillMaxWidth()) {
                        Button({
                            aiProvider = AiProvider.GEMINI
                            if (!aiModel.startsWith("gemini-", ignoreCase = true)) aiModel = "gemini-3.6-flash"
                        }, Modifier.weight(1f).padding(2.dp)) { Text((if (aiProvider == AiProvider.GEMINI) "✓ " else "") + "GEMINI") }
                        Button({ aiProvider = AiProvider.OPENAI_COMPATIBLE }, Modifier.weight(1f).padding(2.dp)) { Text((if (aiProvider == AiProvider.OPENAI_COMPATIBLE) "✓ " else "") + "OPENAI") }
                    }
                    if (aiProvider == AiProvider.OPENAI_COMPATIBLE) {
                        OutlinedTextField(aiEndpoint, { aiEndpoint = it.take(500) }, label = { Text("Endpoint riêng") }, modifier = Modifier.fillMaxWidth())
                    }
                    OutlinedTextField(aiModel, { aiModel = it.take(200) }, label = { Text("Model riêng") }, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth()) {
                        Button({ aiTemperature = (aiTemperature - 0.1f).coerceAtLeast(0f) }, Modifier.weight(1f).padding(2.dp)) { Text("TEMP -") }
                        Text("${"%.1f".format(aiTemperature)}", Modifier.padding(12.dp))
                        Button({ aiTemperature = (aiTemperature + 0.1f).coerceAtMost(1f) }, Modifier.weight(1f).padding(2.dp)) { Text("TEMP +") }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Dùng prompt riêng", Modifier.weight(1f))
                    Switch(aiCustomPrompts, { aiCustomPrompts = it })
                }
                if (aiCustomPrompts) {
                    OutlinedTextField(
                        aiTranslationPrompt,
                        { aiTranslationPrompt = it.take(8_000) },
                        label = { Text("Prompt dịch riêng, dùng {{CHAPTER_TEXT}}") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                    )
                    OutlinedTextField(
                        aiImprovePrompt,
                        { aiImprovePrompt = it.take(12_000) },
                        label = { Text("Prompt cải thiện, dùng {{SOURCE_TEXT}} và {{VIETPHRASE_TEXT}}") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Tự chạy chế độ đã chọn khi mở chương", Modifier.weight(1f))
                    Switch(aiAutoRun, { aiAutoRun = it }, enabled = aiMode != "INHERIT")
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Phân vai AI nâng cao", fontWeight = FontWeight.SemiBold)
                Text("Thiết lập này được dùng khi tự phân vai, phát TTS và xuất sách nói.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    aiVoiceCastNote,
                    { aiVoiceCastNote = it.take(4_000) },
                    label = { Text("Ghi chú bối cảnh và nhân vật cho AI") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Chỉ phân vai lời thoại", Modifier.weight(1f))
                    Switch(aiDialogueOnly, { aiDialogueOnly = it })
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Giữ ổn định giọng người kể chuyện", Modifier.weight(1f))
                    Switch(aiStableNarrator, { aiStableNarrator = it })
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Dùng prompt phân vai riêng", Modifier.weight(1f))
                    Switch(aiCustomVoiceCastPrompt, { aiCustomVoiceCastPrompt = it })
                }
                if (aiCustomVoiceCastPrompt) {
                    OutlinedTextField(
                        aiVoiceCastPrompt,
                        { aiVoiceCastPrompt = it.take(12_000) },
                        label = { Text("Prompt phân vai, bắt buộc có {{CHAPTER_TEXT}}") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                    )
                    Text("Có thể dùng {{STORY_NOTE}}, {{EXISTING_ROLES}}, {{EXPRESSION_RULES}}", style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("AI điều chỉnh diễn cảm từng đoạn", Modifier.weight(1f))
                    Switch(aiExpressiveAdjustment, { aiExpressiveAdjustment = it })
                }
                if (aiExpressiveAdjustment) {
                    OutlinedTextField(
                        aiExpressionPrompt,
                        { aiExpressionPrompt = it.take(8_000) },
                        label = { Text("Quy tắc diễn cảm bổ sung") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                    PercentageLimitRow("Giới hạn tốc độ", aiExpressionSpeedLimit, { aiExpressionSpeedLimit = it })
                    PercentageLimitRow("Giới hạn cao độ", aiExpressionPitchLimit, { aiExpressionPitchLimit = it })
                    PercentageLimitRow("Giới hạn âm lượng", aiExpressionVolumeLimit, { aiExpressionVolumeLimit = it })
                }
                Row(Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            onSaveAiProfile(
                                aiMode,
                                aiOverrideProvider,
                                aiProvider,
                                aiEndpoint,
                                aiModel,
                                aiTemperature,
                                aiCustomPrompts,
                                aiTranslationPrompt,
                                aiImprovePrompt,
                                aiAutoRun && aiMode != "INHERIT",
                                aiCustomVoiceCastPrompt,
                                aiVoiceCastPrompt,
                                aiVoiceCastNote,
                                aiDialogueOnly,
                                aiStableNarrator,
                                aiExpressiveAdjustment,
                                aiExpressionPrompt,
                                aiExpressionSpeedLimit,
                                aiExpressionPitchLimit,
                                aiExpressionVolumeLimit,
                            )
                        },
                        modifier = Modifier.weight(1f).padding(2.dp),
                    ) { Text("LƯU AI RIÊNG") }
                    Button(onClearAiProfile, Modifier.weight(1f).padding(2.dp), enabled = aiProfile != null) { Text("DÙNG CẤU HÌNH CHUNG") }
                }
            }
        }
        }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ReferenceDivider)
                .padding(2.dp),
        ) {
            tabs.forEach { (tabId, label) ->
                ReferenceTabButton(
                    text = label,
                    selected = selectedTab == tabId,
                    onClick = {
                        onTabSelected(tabId)
                        if (tabId == "comments") onLoadComments(false)
                    },
                    accessibilityLabel = "Tab ${label.lowercase()}",
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
            "intro" -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (detail.genres.isNotEmpty()) {
                    item(key = "genre-heading") {
                        Text(
                            "THỂ LOẠI",
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                    items(detail.genres.distinct(), key = { "genre:$it" }) { genre ->
                        ReferenceActionButton(
                            text = genre,
                            onClick = { onGenreSelected(genre) },
                            normalColor = ReferenceDivider,
                            normalContentColor = ReferenceText,
                            accessibilityLabel = "Thể loại $genre",
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                item(key = "intro-heading") {
                    Text(
                        "GIỚI THIỆU",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
                item(key = "intro-body") {
                    Text(
                        detail.story.description.ifBlank { "Nguồn chưa cung cấp phần giới thiệu." },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
            "chapters" -> Column(modifier = Modifier.fillMaxSize()) {
                Text(
                    "DANH SÁCH CHƯƠNG\n${detail.story.title}\n${visibleChapters.size} chương",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                )
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
                    ReferenceActionButton(
                        text = if (normalizedQuery.isBlank()) "TÌM CHƯƠNG" else "TÌM KIẾM: $chapterQuery",
                        onClick = { showChapterSearchDialog = true },
                        normalColor = ReferenceGray,
                        minHeight = 50.dp,
                        modifier = Modifier.weight(1f).padding(1.dp),
                    )
                    if (normalizedQuery.isNotBlank()) {
                        ReferenceActionButton(
                            text = "HIỆN TẤT CẢ CHƯƠNG",
                            onClick = { chapterQuery = "" },
                            normalColor = ReferenceGray,
                            minHeight = 50.dp,
                            modifier = Modifier.weight(1f).padding(1.dp),
                        )
                    }
                }
                ReferenceActionButton(
                    text = "SẮP XẾP: " + if (chapterSortDescending) "MỚI NHẤT TRƯỚC" else "CŨ NHẤT TRƯỚC",
                    onClick = { onChapterSortDescendingChange(!chapterSortDescending) },
                    normalColor = ReferenceGray,
                    minHeight = 50.dp,
                    accessibilityLabel = "Đổi cách sắp xếp danh sách chương",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 1.dp),
                )
                LazyColumn(state = chapterListState, modifier = Modifier.fillMaxSize()) {
                    items(visibleChapters, key = { it.id }) { chapter ->
                        val status = when {
                            chapter.id == state.playback.chapterId -> "\nĐang đọc"
                            currentChapterIndex != null && chapter.index < currentChapterIndex -> "\nĐã đọc"
                            else -> ""
                        }
                        ReferenceActionButton(
                            text = chapter.title + status,
                            onClick = { onChapterClick(chapter) },
                            normalColor = ReferencePanelBackground,
                            normalContentColor = ReferenceText,
                            selected = chapter.id == state.playback.chapterId,
                            accessibilityLabel = chapter.title + status.replace("\n", ". "),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                    if (visibleChapters.isEmpty()) {
                        item(key = "empty-chapter-search") {
                            Text("Không tìm thấy chương phù hợp.", modifier = Modifier.padding(16.dp))
                        }
                    }
                    if (detail.nextChapterPageUrl != null) {
                        item(key = "chapter-loading-actions") {
                            Row(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                                Button(
                                    onClick = onLoadMoreChapters,
                                    enabled = !state.loading,
                                    modifier = Modifier.weight(1f).padding(2.dp),
                                ) { Text(if (normalizedQuery.isBlank()) "TẢI THÊM" else "TẢI THÊM ĐỂ TÌM") }
                                Button(
                                    onClick = onLoadAllChapters,
                                    enabled = !state.loading,
                                    modifier = Modifier.weight(1f).padding(2.dp),
                                ) { Text("NẠP TOÀN BỘ MỤC LỤC") }
                            }
                        }
                    }
                }
            }
            "comments" -> Column(modifier = Modifier.fillMaxSize()) {
                Text(
                    "BÌNH LUẬN",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                )
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
                    Text("${state.storyComments.size} bình luận", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(top = 12.dp))
                    Button(
                        onClick = { onLoadComments(true) },
                        enabled = state.storyCommentsRefreshable && !state.storyCommentsLoading,
                    ) { Text(if (state.storyCommentsRefreshable) "TẢI LẠI" else "BẢN NHÚNG") }
                }
                if (state.storyCommentsLoading) LoadingRow()
                state.storyCommentsMessage?.let { message ->
                    Text(message, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(
                        items = state.storyComments,
                        key = { comment -> "${comment.user}|${comment.time}|${comment.text.hashCode()}" },
                    ) { comment ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .semantics(mergeDescendants = true) {
                                    contentDescription = buildString {
                                        append(comment.user.ifBlank { "Người đọc" })
                                        if (comment.time.isNotBlank()) append(". ${comment.time}")
                                        append(". ${comment.text}")
                                    }
                                },
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(comment.user.ifBlank { "Người đọc" }, fontWeight = FontWeight.SemiBold)
                                if (comment.time.isNotBlank()) Text(comment.time, style = MaterialTheme.typography.bodySmall)
                                Text(comment.text, modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                    }
                    if (state.storyCommentsNextPageUrl != null) {
                        item(key = "load-more-comments") {
                            Button(
                                onClick = onLoadMoreComments,
                                enabled = !state.storyCommentsLoading,
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                            ) { Text("TẢI THÊM BÌNH LUẬN") }
                        }
                    }
                    if (state.storyCommentsLoaded && state.storyComments.isEmpty() && state.storyCommentsMessage == null) {
                        item(key = "empty-comments") { Text("Chưa tìm thấy bình luận trong trang này.", modifier = Modifier.padding(16.dp)) }
                    }
                }
            }
            "source" -> Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text(
                    "NGUỒN",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                )
                Text("Tên: ${sourceDescriptor?.displayName ?: detail.story.sourceId}")
                Text("ID: ${detail.story.sourceId}", modifier = Modifier.padding(top = 6.dp))
                Text(
                    "Website: ${sourceDescriptor?.baseUrl ?: detail.story.url}",
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    "Truyện gốc: ${detail.story.url.ifBlank { "Không có địa chỉ gốc" }}",
                    modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
                )
                ReferenceActionButton(
                    text = "MỞ TRANG GỐC",
                    onClick = { if (detail.story.url.startsWith("https://")) onOpenOriginal(detail.story.url) },
                    enabled = detail.story.url.startsWith("https://"),
                    normalColor = ReferenceGray,
                    accessibilityLabel = "Mở trang gốc của truyện",
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
                ReferenceActionButton(
                    text = if (detail.story.sourceId in state.sourceHealthChecking) "ĐANG KIỂM TRA" else "KIỂM TRA",
                    onClick = { onCheckSource(detail.story.sourceId) },
                    enabled = detail.story.sourceId !in state.sourceHealthChecking,
                    normalColor = ReferenceGray,
                    accessibilityLabel = "Kiểm tra nguồn ${sourceDescriptor?.displayName ?: detail.story.sourceId}",
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("Xuất sách nói") },
            text = {
                Column {
                    Text("Định dạng", fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth()) {
                        AudioExportFormat.entries.forEach { format ->
                            Button(
                                onClick = { exportFormat = format },
                                modifier = Modifier.weight(1f).padding(2.dp),
                            ) { Text((if (format == exportFormat) "✓ " else "") + format.name) }
                        }
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Checkbox(checked = exportRangeEnabled, onCheckedChange = { exportRangeEnabled = it })
                        Text("Chỉ xuất một khoảng chương", modifier = Modifier.padding(top = 12.dp))
                    }
                    if (exportRangeEnabled) {
                        OutlinedTextField(
                            value = exportStart,
                            onValueChange = { exportStart = it.filter(Char::isDigit).take(6) },
                            label = { Text("Từ chương") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = exportEnd,
                            onValueChange = { exportEnd = it.filter(Char::isDigit).take(6) },
                            label = { Text("Đến chương") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Checkbox(checked = exportSplitChapters, onCheckedChange = { exportSplitChapters = it })
                        Text("Mỗi chương một tệp", modifier = Modifier.padding(top = 12.dp))
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Checkbox(checked = exportSceneMusic, onCheckedChange = { exportSceneMusic = it })
                        Text("Trộn nhạc cảnh vào audiobook", modifier = Modifier.padding(top = 12.dp))
                    }
                    if (!exportSplitChapters && exportFormat == AudioExportFormat.MP3) {
                        Row(Modifier.fillMaxWidth()) {
                            Checkbox(checked = exportChapterMarkers, onCheckedChange = { exportChapterMarkers = it })
                            Text("Ghi chapter marker vào MP3", modifier = Modifier.padding(top = 12.dp))
                        }
                    }
                    Text(
                        if (exportSplitChapters) "Android sẽ yêu cầu chọn một thư mục đích."
                        else "Android sẽ yêu cầu đặt tên một tệp đích.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val start = exportStart.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    val end = exportEnd.toIntOrNull()?.coerceAtLeast(start) ?: start
                    onExportAudio(
                        AudioExportRequest(
                            scope = if (exportRangeEnabled) AudioExportScope.CHAPTER_RANGE else AudioExportScope.CACHED_STORY,
                            format = exportFormat,
                            startChapterNumber = start,
                            endChapterNumber = end,
                            includeSceneMusic = exportSceneMusic,
                            packaging = if (exportSplitChapters) AudioExportPackaging.ONE_FILE_PER_CHAPTER else AudioExportPackaging.SINGLE_FILE,
                            chapterMarkers = exportChapterMarkers,
                        ),
                    )
                    showExportDialog = false
                }) { Text("CHỌN ĐÍCH XUẤT") }
            },
            dismissButton = { TextButton(onClick = { showExportDialog = false }) { Text("HỦY") } },
        )
    }

    if (showVoiceRoleDialog) {
        val engineOptions = listOf<String?>(null) + state.ttsEngines.map { it.packageName }
        val engineIndex = engineOptions.indexOf(roleDraft.enginePackage).takeIf { it >= 0 } ?: 0
        val availableVoices = state.roleEditorVoices
        val voiceIndex = availableVoices.indexOfFirst { it.name == roleDraft.voiceName }
        AlertDialog(
            onDismissRequest = { showVoiceRoleDialog = false },
            title = { Text(if (roleDraft.roleName.isBlank()) "Thêm hồ sơ vai" else "Chỉnh hồ sơ vai") },
            text = {
                LazyColumn {
                    item {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Checkbox(
                                checked = roleDraft.isNarrator,
                                onCheckedChange = { roleDraft = roleDraft.copy(isNarrator = it, roleName = if (it) "Người kể chuyện" else roleDraft.roleName) },
                            )
                            Text("Đây là Người kể chuyện", modifier = Modifier.padding(top = 12.dp))
                        }
                        if (!roleDraft.isNarrator) {
                            OutlinedTextField(
                                value = roleDraft.roleName,
                                onValueChange = { roleDraft = roleDraft.copy(roleName = it.take(80)) },
                                label = { Text("Tên vai") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = roleDraft.aliases,
                                onValueChange = { roleDraft = roleDraft.copy(aliases = it.take(500)) },
                                label = { Text("Bí danh, cách nhau bằng dấu phẩy") },
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            )
                        }
                        Text("Bộ máy TTS: ${roleDraft.enginePackage ?: "mặc định hệ thống"}", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                        Row(Modifier.fillMaxWidth()) {
                            Button(onClick = {
                                val selected = engineOptions[(engineIndex - 1 + engineOptions.size) % engineOptions.size]
                                roleDraft = roleDraft.copy(enginePackage = selected, voiceName = null)
                                onLoadRoleVoices(selected)
                            }, modifier = Modifier.weight(1f).padding(2.dp)) { Text("ENGINE TRƯỚC") }
                            Button(onClick = {
                                val selected = engineOptions[(engineIndex + 1) % engineOptions.size]
                                roleDraft = roleDraft.copy(enginePackage = selected, voiceName = null)
                                onLoadRoleVoices(selected)
                            }, modifier = Modifier.weight(1f).padding(2.dp)) { Text("ENGINE SAU") }
                        }
                        Text(
                            if (state.roleEditorVoiceLoading) "Đang nạp giọng…" else "Giọng: ${roleDraft.voiceName ?: "mặc định"} (${roleDraft.languageTag})",
                            fontWeight = FontWeight.SemiBold,
                        )
                        Row(Modifier.fillMaxWidth()) {
                            Button(onClick = {
                                if (availableVoices.isNotEmpty()) {
                                    val index = if (voiceIndex < 0) availableVoices.lastIndex else (voiceIndex - 1 + availableVoices.size) % availableVoices.size
                                    val voice = availableVoices[index]
                                    roleDraft = roleDraft.copy(voiceName = voice.name, languageTag = voice.languageTag)
                                }
                            }, modifier = Modifier.weight(1f).padding(2.dp), enabled = availableVoices.isNotEmpty()) { Text("GIỌNG TRƯỚC") }
                            Button(onClick = {
                                if (availableVoices.isNotEmpty()) {
                                    val index = if (voiceIndex < 0) 0 else (voiceIndex + 1) % availableVoices.size
                                    val voice = availableVoices[index]
                                    roleDraft = roleDraft.copy(voiceName = voice.name, languageTag = voice.languageTag)
                                }
                            }, modifier = Modifier.weight(1f).padding(2.dp), enabled = availableVoices.isNotEmpty()) { Text("GIỌNG SAU") }
                        }
                        OutlinedTextField(
                            value = roleDraft.languageTag,
                            onValueChange = { roleDraft = roleDraft.copy(languageTag = it.take(32)) },
                            label = { Text("Ngôn ngữ, ví dụ vi-VN") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        VoiceValueEditor("Tốc độ TTS", roleDraft.rate, 0.5f, 2f) { roleDraft = roleDraft.copy(rate = it) }
                        VoiceValueEditor("Cao độ TTS", roleDraft.pitch, 0.5f, 2f) { roleDraft = roleDraft.copy(pitch = it) }
                        VoiceValueEditor("Âm lượng", roleDraft.volume, 0.05f, 1f) { roleDraft = roleDraft.copy(volume = it) }
                        Text("Biểu cảm: ${roleDraft.expression.name}", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                        Row(Modifier.fillMaxWidth()) {
                            Button(onClick = {
                                val values = VoiceExpression.entries
                                roleDraft = roleDraft.copy(expression = values[(values.indexOf(roleDraft.expression) - 1 + values.size) % values.size])
                            }, modifier = Modifier.weight(1f).padding(2.dp)) { Text("CẢM XÚC TRƯỚC") }
                            Button(onClick = {
                                val values = VoiceExpression.entries
                                roleDraft = roleDraft.copy(expression = values[(values.indexOf(roleDraft.expression) + 1) % values.size])
                            }, modifier = Modifier.weight(1f).padding(2.dp)) { Text("CẢM XÚC SAU") }
                        }
                        VoiceValueEditor("Cường độ biểu cảm", roleDraft.expressionStrength, 0f, 1f) { roleDraft = roleDraft.copy(expressionStrength = it) }
                        VoiceValueEditor("Sonic tốc độ", roleDraft.sonicSpeed, 0.5f, 2f) { roleDraft = roleDraft.copy(sonicSpeed = it) }
                        VoiceValueEditor("Sonic cao độ", roleDraft.sonicPitch, 0.5f, 2f) { roleDraft = roleDraft.copy(sonicPitch = it) }
                        Row(Modifier.fillMaxWidth()) {
                            Checkbox(checked = roleDraft.enabled, onCheckedChange = { roleDraft = roleDraft.copy(enabled = it) })
                            Text("Bật vai này", modifier = Modifier.padding(top = 12.dp))
                        }
                        Button(
                            onClick = { onPreviewVoiceRole(roleDraft) },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        ) { Text("NGHE THỬ HỒ SƠ NÀY") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = {
                onSaveVoiceRole(roleDraft.copy(roleName = if (roleDraft.isNarrator) "Người kể chuyện" else roleDraft.roleName))
                showVoiceRoleDialog = false
                roleDraft = defaultRoleDraft()
            }, enabled = roleDraft.isNarrator || roleDraft.roleName.isNotBlank()) { Text("LƯU") } },
            dismissButton = { TextButton(onClick = { showVoiceRoleDialog = false }) { Text("HỦY") } },
        )
    }

    if (showDownloadScopeDialog) {
        AlertDialog(
            onDismissRequest = { showDownloadScopeDialog = false },
            title = { Text("CHỌN PHẠM VI TẢI") },
            text = {
                Column {
                    ReferenceActionButton(
                        text = "TẢI CHƯƠNG ĐẦU",
                        onClick = {
                            showDownloadScopeDialog = false
                            onDownloadRange(1, 1)
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    )
                    ReferenceActionButton(
                        text = "CHỌN NHIỀU CHƯƠNG",
                        onClick = {
                            showDownloadScopeDialog = false
                            selectedDownloadChapters = emptySet()
                            showMultiChapterDialog = true
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    )
                    ReferenceActionButton(
                        text = "TẢI TOÀN BỘ TRUYỆN",
                        onClick = {
                            showDownloadScopeDialog = false
                            onDownload()
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showDownloadScopeDialog = false }) { Text("ĐÓNG") } },
        )
    }

    if (showChapterSearchDialog) {
        AlertDialog(
            onDismissRequest = { showChapterSearchDialog = false },
            title = { Text("TÌM CHƯƠNG") },
            text = {
                OutlinedTextField(
                    value = chapterQuery,
                    onValueChange = { chapterQuery = it.take(120) },
                    label = { Text("Tìm chương theo tên hoặc số") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = { TextButton(onClick = { showChapterSearchDialog = false }) { Text("TÌM") } },
            dismissButton = {
                TextButton(onClick = {
                    chapterQuery = ""
                    showChapterSearchDialog = false
                }) { Text("HIỆN TẤT CẢ") }
            },
        )
    }

    if (showMultiChapterDialog) {
        AlertDialog(
            onDismissRequest = { showMultiChapterDialog = false },
            title = { Text("CHỌN NHIỀU CHƯƠNG") },
            text = {
                LazyColumn {
                    items(detail.chapters, key = { "download:${it.id}" }) { chapter ->
                        val number = chapter.index + 1
                        Row(Modifier.fillMaxWidth()) {
                            Checkbox(
                                checked = number in selectedDownloadChapters,
                                onCheckedChange = { checked ->
                                    selectedDownloadChapters = if (checked) {
                                        selectedDownloadChapters + number
                                    } else {
                                        selectedDownloadChapters - number
                                    }
                                },
                            )
                            Text(
                                chapter.title,
                                modifier = Modifier.weight(1f).padding(top = 12.dp, end = 8.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = selectedDownloadChapters.isNotEmpty(),
                    onClick = {
                        onDownloadSelected(selectedDownloadChapters.sorted())
                        showMultiChapterDialog = false
                    },
                ) { Text("TẢI ${selectedDownloadChapters.size} CHƯƠNG") }
            },
            dismissButton = { TextButton(onClick = { showMultiChapterDialog = false }) { Text("HỦY") } },
        )
    }
}

@Composable
private fun PercentageLimitRow(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("$label: $value%", Modifier.weight(1f))
        Button({ onValueChange((value - 1).coerceAtLeast(0)) }, Modifier.padding(2.dp)) { Text("−") }
        Button({ onValueChange((value + 1).coerceAtMost(50)) }, Modifier.padding(2.dp)) { Text("+") }
    }
}



@Composable
private fun VoiceValueEditor(
    label: String,
    value: Float,
    minimum: Float,
    maximum: Float,
    onChange: (Float) -> Unit,
) {
    Text("$label: ${"%.2f".format(value)}", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
    Row(Modifier.fillMaxWidth()) {
        Button(onClick = { onChange((value - 0.05f).coerceAtLeast(minimum)) }, modifier = Modifier.weight(1f).padding(2.dp)) { Text("−") }
        Button(onClick = { onChange((value + 0.05f).coerceAtMost(maximum)) }, modifier = Modifier.weight(1f).padding(2.dp)) { Text("+") }
    }
}
