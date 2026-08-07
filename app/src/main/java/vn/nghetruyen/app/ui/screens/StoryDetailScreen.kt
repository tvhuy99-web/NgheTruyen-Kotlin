package vn.nghetruyen.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

@Composable
fun StoryDetailScreen(
    state: MainUiState,
    onBack: () -> Unit,
    onReadFirst: () -> Unit,
    onDownload: () -> Unit,
    onDownloadUnread: () -> Unit,
    onDownloadRange: (Int, Int) -> Unit,
    onToggleFollowing: () -> Unit,
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
) {
    val detail = state.storyDetail ?: return
    var selectedTab by remember(detail.story.id) { mutableStateOf("chapters") }
    var chapterQuery by remember(detail.story.id) { mutableStateOf("") }
    var showRangeDialog by remember(detail.story.id) { mutableStateOf(false) }
    var showExportDialog by remember(detail.story.id) { mutableStateOf(false) }
    var showVoiceRoleDialog by remember(detail.story.id) { mutableStateOf(false) }
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
    var rangeStart by remember(detail.story.id) { mutableStateOf("1") }
    var rangeEnd by remember(detail.story.id, detail.chapters.size) {
        mutableStateOf(detail.chapters.size.coerceAtLeast(1).toString())
    }
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
    val visibleChapters = remember(chapterCatalog, normalizedQuery) { chapterCatalog.search(normalizedQuery) }
    val tabs = buildList {
        add("intro" to "GIỚI THIỆU")
        add("chapters" to "CHƯƠNG")
        if (state.storyCommentsAvailable) add("comments" to "BÌNH LUẬN")
        add("source" to "NGUỒN")
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth().padding(4.dp)) { Text("QUAY LẠI") }
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    detail.story.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
                if (detail.story.author.isNotBlank()) Text("Tác giả: ${detail.story.author}")
                if (detail.status.isNotBlank()) Text("Trạng thái: ${detail.status}")
                if (detail.genres.isNotEmpty()) Text("Thể loại: ${detail.genres.joinToString()}")
                Text("Đã nạp ${detail.chapters.size} chương")
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
            LargeActionButton(if (state.continueAvailable) "ĐỌC TIẾP" else "ĐỌC TỪ ĐẦU", onReadFirst, Modifier.weight(1f).padding(1.dp))
            LargeActionButton("TẢI TRUYỆN", onDownload, Modifier.weight(1f).padding(1.dp))
            LargeActionButton("TẢI CHƯA ĐỌC", onDownloadUnread, Modifier.weight(1f).padding(1.dp))
            LargeActionButton("TẢI KHOẢNG", { showRangeDialog = true }, Modifier.weight(1f).padding(1.dp))
            val following = state.following.any { it.storyId == detail.story.id }
            LargeActionButton(if (following) "BỎ THEO DÕI" else "THEO DÕI", onToggleFollowing, Modifier.weight(1f).padding(1.dp))
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            LargeActionButton(
                "XUẤT SÁCH NÓI",
                { showExportDialog = true },
                Modifier.weight(1.2f).padding(1.dp),
            )
            val hasVoiceProfile = state.storyTtsProfiles.containsKey(detail.story.id)
            LargeActionButton(
                if (hasVoiceProfile) "CẬP NHẬT GIỌNG RIÊNG" else "LƯU GIỌNG RIÊNG",
                onSaveVoiceProfile,
                Modifier.weight(1.4f).padding(1.dp),
            )
            if (hasVoiceProfile) LargeActionButton("BỎ GIỌNG RIÊNG", onClearVoiceProfile, Modifier.weight(1.2f).padding(1.dp))
        }
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text("Vai giọng thủ công", fontWeight = FontWeight.SemiBold)
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
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text("AI riêng cho truyện", fontWeight = FontWeight.SemiBold)
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
        Row(modifier = Modifier.fillMaxWidth()) {
            tabs.forEach { (tabId, label) ->
                Button(
                    onClick = {
                        selectedTab = tabId
                        if (tabId == "comments") onLoadComments(false)
                    },
                    modifier = Modifier.weight(1f).padding(1.dp),
                ) {
                    Text((if (selectedTab == tabId) "✓ " else "") + label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        HorizontalDivider()
        if (state.loading) LoadingRow()
        when (selectedTab) {
            "intro" -> Text(detail.story.description.ifBlank { "Nguồn chưa cung cấp phần giới thiệu." }, modifier = Modifier.padding(16.dp))
            "chapters" -> Column(modifier = Modifier.fillMaxSize()) {
                OutlinedTextField(
                    value = chapterQuery,
                    onValueChange = { chapterQuery = it.take(120) },
                    label = { Text("Tìm chương theo tên hoặc số") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                )
                if (normalizedQuery.isNotBlank()) {
                    Text("${visibleChapters.size} kết quả trong ${detail.chapters.size} chương đã nạp", modifier = Modifier.padding(horizontal = 12.dp))
                }
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(visibleChapters, key = { it.id }) { chapter ->
                        Button(
                            onClick = { onChapterClick(chapter) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
                        ) { Text(chapter.title, modifier = Modifier.fillMaxWidth()) }
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
                                ) { Text("TẢI THÊM") }
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
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Column(modifier = Modifier.weight(1f).padding(top = 6.dp)) {
                        Text("${state.storyComments.size} bình luận", fontWeight = FontWeight.SemiBold)
                        if (state.storyCommentsFromCache) {
                            Text("Đang hiển thị bản lưu tạm", style = MaterialTheme.typography.bodySmall)
                        }
                    }
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
                        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(comment.user.ifBlank { "Người đọc" }, fontWeight = FontWeight.SemiBold)
                                if (comment.time.isNotBlank()) {
                                    Text(comment.time, style = MaterialTheme.typography.bodySmall)
                                }
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
                        item(key = "empty-comments") {
                            Text("Chưa tìm thấy bình luận trong trang này.", modifier = Modifier.padding(16.dp))
                        }
                    }
                }
            }
            "source" -> Column(modifier = Modifier.padding(16.dp)) {
                Text("Nguồn: ${detail.story.sourceId}")
                Text("Địa chỉ: ${detail.story.url}")
                if (detail.story.url.startsWith("https://")) {
                    Button(onClick = { onOpenOriginal(detail.story.url) }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                        Text("MỞ TRANG GỐC")
                    }
                }
                if (!detail.commentsUrl.isNullOrBlank()) {
                    Button(onClick = { onOpenOriginal(detail.commentsUrl) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Text("MỞ TRANG BÌNH LUẬN GỐC")
                    }
                }
                if (!state.storyCommentsAvailable) {
                    Text(
                        "Nguồn này chưa khai báo khả năng lấy bình luận trong ứng dụng.",
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
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

    if (showRangeDialog) {
        AlertDialog(
            onDismissRequest = { showRangeDialog = false },
            title = { Text("Tải theo khoảng chương") },
            text = {
                Column {
                    OutlinedTextField(
                        value = rangeStart,
                        onValueChange = { rangeStart = it.filter(Char::isDigit).take(6) },
                        label = { Text("Từ chương") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = rangeEnd,
                        onValueChange = { rangeEnd = it.filter(Char::isDigit).take(6) },
                        label = { Text("Đến chương") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Text("Số chương được tính theo thứ tự trong mục lục đầy đủ.", modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val start = rangeStart.toIntOrNull() ?: 1
                    val end = rangeEnd.toIntOrNull() ?: start
                    onDownloadRange(start, end)
                    showRangeDialog = false
                }) { Text("TẢI") }
            },
            dismissButton = { TextButton(onClick = { showRangeDialog = false }) { Text("HỦY") } },
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
