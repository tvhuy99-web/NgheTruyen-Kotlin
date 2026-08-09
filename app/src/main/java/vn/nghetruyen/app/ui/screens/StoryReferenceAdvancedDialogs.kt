package vn.nghetruyen.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vn.nghetruyen.app.ai.StoryVoiceCastMode
import vn.nghetruyen.app.ai.StoryVoiceCastReferenceCodec
import vn.nghetruyen.app.core.model.GLOBAL_VOICE_PROFILE_STORY_ID
import vn.nghetruyen.app.core.model.VoiceExpression
import vn.nghetruyen.app.core.model.VoiceRoleDraft
import vn.nghetruyen.app.data.local.VoiceRoleEntity
import vn.nghetruyen.app.data.settings.AiProvider
import vn.nghetruyen.app.ui.MainUiState
import vn.nghetruyen.app.ui.components.GlobalVoiceRoleEditorDialog
import vn.nghetruyen.app.ui.components.ReferenceActionButton
import vn.nghetruyen.app.ui.components.ReferenceGray
import vn.nghetruyen.app.ui.components.ReferencePanelBackground
import vn.nghetruyen.app.ui.components.ReferenceText
import vn.nghetruyen.app.ui.reference.ReferenceVoiceRoleExtras

/**
 * Story-scoped reference dialogs that can be hosted by either StoryDetail or Reader.
 * Keeping them inside the current destination is important: the XPK opens these
 * dialogs over the reader instead of navigating away and then navigating back.
 */
@Composable
fun StoryReferenceAdvancedDialogs(
    state: MainUiState,
    mode: String?,
    onDismiss: () -> Unit,
    onSaveVoiceRole: (VoiceRoleDraft) -> Unit,
    onPreviewVoiceRole: (VoiceRoleDraft) -> Unit,
    onDeleteVoiceRole: (String) -> Unit,
    onSaveAiProfile: (
        String, Boolean, AiProvider, String, String, Float, Boolean, String, String, Boolean,
        Boolean, String, String, Boolean, Boolean, Boolean, String, Int, Int, Int,
    ) -> Unit,
) {
    if (mode == null) return
    val detail = state.storyDetail ?: return
    val aiProfile = state.storyAiProfiles[detail.story.id]
    val context = LocalContext.current
    val privateRoles = state.voiceRoles.filter { it.storyId == detail.story.id }
    val globalRoles = state.voiceRoles.filter { it.storyId == GLOBAL_VOICE_PROFILE_STORY_ID }.take(7)

    val voiceReference = StoryVoiceCastReferenceCodec.decode(aiProfile?.voiceCastNote.orEmpty())
    var voiceMode by remember(detail.story.id, aiProfile?.updatedAt, mode) { mutableStateOf(voiceReference.mode) }
    var voiceAutoRun by remember(detail.story.id, aiProfile?.updatedAt, mode) { mutableStateOf(voiceReference.autoRunOnOpenTts) }
    var voiceNote by remember(detail.story.id, aiProfile?.updatedAt, mode) { mutableStateOf(voiceReference.note) }
    var expressive by remember(detail.story.id, aiProfile?.updatedAt, mode) { mutableStateOf(aiProfile?.expressiveAdjustment ?: false) }
    var expressionPrompt by remember(detail.story.id, aiProfile?.updatedAt, mode) { mutableStateOf(aiProfile?.expressionPrompt.orEmpty()) }
    var speedLimit by remember(detail.story.id, aiProfile?.updatedAt, mode) { mutableStateOf(aiProfile?.expressionSpeedLimitPct ?: 10) }
    var pitchLimit by remember(detail.story.id, aiProfile?.updatedAt, mode) { mutableStateOf(aiProfile?.expressionPitchLimitPct ?: 10) }
    var volumeLimit by remember(detail.story.id, aiProfile?.updatedAt, mode) { mutableStateOf(aiProfile?.expressionVolumeLimitPct ?: 10) }

    var aiMode by remember(detail.story.id, aiProfile?.updatedAt, mode) { mutableStateOf(aiProfile?.mode ?: "INHERIT") }
    var aiAutoRun by remember(detail.story.id, aiProfile?.updatedAt, mode) { mutableStateOf(aiProfile?.autoRunOnOpen ?: false) }
    var aiCustomPrompts by remember(detail.story.id, aiProfile?.updatedAt, mode) { mutableStateOf(aiProfile?.useCustomPrompts ?: false) }
    var aiTranslatePrompt by remember(detail.story.id, aiProfile?.updatedAt, mode) { mutableStateOf(aiProfile?.translationPrompt.orEmpty()) }
    var aiImprovePrompt by remember(detail.story.id, aiProfile?.updatedAt, mode) { mutableStateOf(aiProfile?.improvePrompt.orEmpty()) }

    var showVoiceProfiles by remember(detail.story.id, mode) { mutableStateOf(false) }
    var showVoiceRoleDialog by remember(detail.story.id, mode) { mutableStateOf(false) }
    var showExpressionPromptDialog by remember(detail.story.id, mode) { mutableStateOf(false) }
    var copyGlobalConfirm by remember(detail.story.id, mode) { mutableStateOf(false) }
    var restorePrivateConfirm by remember(detail.story.id, mode) { mutableStateOf(false) }
    var deletePrivateRole by remember(detail.story.id, mode) { mutableStateOf<VoiceRoleEntity?>(null) }

    fun defaultRoleDraft() = VoiceRoleDraft(
        roleName = "",
        enginePackage = state.selectedTtsEnginePackage,
        voiceName = state.selectedTtsVoiceName,
        languageTag = state.selectedTtsLanguageTag,
        rate = state.playback.rate,
        pitch = state.playback.pitch,
        volume = state.ttsVolume,
    )
    var roleDraft by remember(detail.story.id, mode) { mutableStateOf(defaultRoleDraft()) }

    fun persistCombinedProfile(
        savedMode: String = aiMode,
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
            savedMode,
            aiProfile?.overrideProvider ?: false,
            runCatching { AiProvider.valueOf(aiProfile?.provider.orEmpty()) }.getOrDefault(state.aiOnline.provider),
            aiProfile?.endpoint.orEmpty().ifBlank { state.aiOnline.endpoint },
            aiProfile?.model.orEmpty().ifBlank { state.aiOnline.model },
            aiProfile?.temperature?.takeIf { it >= 0f } ?: state.aiOnline.temperature.coerceAtMost(1f),
            customPrompts,
            translatePrompt,
            improvePrompt,
            autoRun && savedMode == "TRANSLATE",
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

    if (mode == "ai") {
        var modeExpanded by remember { mutableStateOf(false) }
        val effectiveMode = when (aiMode) {
            "TRANSLATE" -> "TRANSLATE"
            "IMPROVE" -> "IMPROVE"
            else -> if (state.aiOnline.mode.equals("improve", ignoreCase = true)) "IMPROVE" else "TRANSLATE"
        }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("AI RIÊNG CHO TRUYỆN") },
            text = {
                Column(Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState())) {
                    Text(detail.story.title, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                    Text("Chế độ của truyện này")
                    Button(onClick = { modeExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(when (aiMode) {
                            "TRANSLATE" -> "Dịch chương gốc"
                            "IMPROVE" -> "Cải thiện VietPhrase"
                            else -> "Theo cài đặt chung"
                        })
                    }
                    DropdownMenu(expanded = modeExpanded, onDismissRequest = { modeExpanded = false }) {
                        listOf(
                            "INHERIT" to "Theo cài đặt chung",
                            "TRANSLATE" to "Dịch chương gốc",
                            "IMPROVE" to "Cải thiện VietPhrase",
                        ).forEach { (value, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                aiMode = value
                                if (value != "TRANSLATE") aiAutoRun = false
                                modeExpanded = false
                            })
                        }
                    }
                    if (effectiveMode == "TRANSLATE") {
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
                }
            },
            confirmButton = {
                TextButton(onClick = { persistCombinedProfile(); onDismiss() }) { Text("LƯU") }
            },
            dismissButton = {
                TextButton(onClick = {
                    persistCombinedProfile(
                        savedMode = "INHERIT",
                        autoRun = false,
                        customPrompts = false,
                        translatePrompt = "",
                        improvePrompt = "",
                    )
                    onDismiss()
                }) { Text("DÙNG MẶC ĐỊNH") }
            },
        )
    }

    if (mode == "voice") {
        var modeExpanded by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("PHÂN VAI TTS CHO TRUYỆN NÀY") },
            text = {
                Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
                    Text("Chế độ")
                    Button(onClick = { modeExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(when (voiceMode) {
                            StoryVoiceCastMode.PRIVATE -> "Dùng cấu hình riêng cho truyện này"
                            StoryVoiceCastMode.OFF -> "Tắt phân vai cho truyện này"
                            else -> "Dùng cấu hình chung"
                        })
                    }
                    DropdownMenu(expanded = modeExpanded, onDismissRequest = { modeExpanded = false }) {
                        listOf(
                            StoryVoiceCastMode.GLOBAL to "Dùng cấu hình chung",
                            StoryVoiceCastMode.PRIVATE to "Dùng cấu hình riêng cho truyện này",
                            StoryVoiceCastMode.OFF to "Tắt phân vai cho truyện này",
                        ).forEach { (value, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { voiceMode = value; modeExpanded = false })
                        }
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
                            modifier = Modifier.padding(top = 5.dp, bottom = 6.dp),
                        )
                        ReferenceStoryPercentSlider("Giới hạn tốc độ", speedLimit) { speedLimit = it }
                        ReferenceStoryPercentSlider("Giới hạn cao độ", pitchLimit) { pitchLimit = it }
                        ReferenceStoryPercentSlider("Giới hạn âm lượng", volumeLimit) { volumeLimit = it }
                        ReferenceActionButton(
                            "XEM / SỬA HƯỚNG DẪN THÔNG SỐ",
                            { showExpressionPromptDialog = true },
                            normalColor = ReferenceGray,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        "Thứ tự luôn là: tải chương → dịch tự động (nếu bật) → phân vai → TTS. Vì vậy hai tác vụ AI không chạy chồng lên nhau.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    if (voiceMode == StoryVoiceCastMode.PRIVATE) {
                        Text("Bộ hồ sơ riêng: ${privateRoles.size} vai", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                        ReferenceActionButton(
                            "THIẾT LẬP BỘ GIỌNG RIÊNG",
                            { showVoiceProfiles = true },
                            normalColor = ReferenceGray,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text("Ghi chú chung bổ sung cho AI", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    OutlinedTextField(
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
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    persistCombinedProfile(
                        storyVoiceMode = voiceMode,
                        storyVoiceAuto = voiceAutoRun,
                        storyVoiceNote = voiceNote,
                        expressiveEnabled = expressive,
                        expressivePrompt = expressionPrompt,
                        speedPct = speedLimit,
                        pitchPct = pitchLimit,
                        volumePct = volumeLimit,
                    )
                    onDismiss()
                }) { Text("LƯU") }
            },
        )
    }

    if (showExpressionPromptDialog) {
        var draft by remember(showExpressionPromptDialog) { mutableStateOf(expressionPrompt) }
        AlertDialog(
            onDismissRequest = { showExpressionPromptDialog = false },
            title = { Text("HƯỚNG DẪN ĐIỀU CHỈNH THÔNG SỐ") },
            text = {
                Column {
                    Text(
                        "Đây là phần hướng dẫn ngữ cảnh được gửi cho AI. Giới hạn ba thanh và cấu trúc dữ liệu vẫn được ứng dụng bảo vệ riêng.",
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
            confirmButton = { TextButton(onClick = { expressionPrompt = draft; showExpressionPromptDialog = false }) { Text("DÙNG LỜI NHẮC NÀY") } },
            dismissButton = { TextButton(onClick = { expressionPrompt = ""; showExpressionPromptDialog = false }) { Text("KHÔI PHỤC LỜI NHẮC MẶC ĐỊNH") } },
        )
    }

    if (showVoiceProfiles) {
        AlertDialog(
            onDismissRequest = { showVoiceProfiles = false },
            title = { Text("BỘ GIỌNG RIÊNG CỦA TRUYỆN") },
            text = {
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                    privateRoles.forEach { role ->
                        ReferenceActionButton(
                            text = (if (role.enabled) "" else "TẮT • ") + role.roleName + "\n" +
                                (role.enginePackage ?: "Hệ thống") + " • " + (role.voiceName ?: "Mặc định"),
                            onClick = { roleDraft = role.toReferenceDraft(context); showVoiceRoleDialog = true },
                            normalColor = ReferencePanelBackground,
                            normalContentColor = ReferenceText,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        )
                    }
                    ReferenceActionButton(
                        "THÊM VAI HOẶC NHÂN VẬT",
                        { roleDraft = defaultRoleDraft(); showVoiceRoleDialog = true },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    ReferenceActionButton(
                        "SAO CHÉP LẠI TỪ CẤU HÌNH CHUNG",
                        { copyGlobalConfirm = true },
                        normalColor = ReferenceGray,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                    ReferenceActionButton(
                        "KHÔI PHỤC 7 HỒ SƠ MẪU",
                        { restorePrivateConfirm = true },
                        normalColor = ReferenceGray,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showVoiceProfiles = false }) { Text("ĐÓNG") } },
        )
    }

    if (showVoiceRoleDialog) {
        GlobalVoiceRoleEditorDialog(
            draft = roleDraft,
            engines = state.ttsEngines,
            title = "HỒ SƠ GIỌNG RIÊNG",
            onDraftChange = { roleDraft = it },
            onPreview = onPreviewVoiceRole,
            onSave = { saved ->
                onSaveVoiceRole(if (saved.isNarrator) saved.copy(roleName = "Người kể chuyện", enabled = true) else saved)
                showVoiceRoleDialog = false
            },
            onDelete = if (!roleDraft.isNarrator && roleDraft.originalRoleId != null) {
                {
                    deletePrivateRole = privateRoles.firstOrNull { it.id == roleDraft.originalRoleId }
                    showVoiceRoleDialog = false
                }
            } else null,
            onDismiss = { showVoiceRoleDialog = false },
        )
    }

    if (copyGlobalConfirm) {
        AlertDialog(
            onDismissRequest = { copyGlobalConfirm = false },
            title = { Text("SAO CHÉP CẤU HÌNH CHUNG") },
            text = { Text("Thay bộ giọng riêng bằng cấu hình chung?") },
            confirmButton = { TextButton(onClick = {
                privateRoles.forEach { onDeleteVoiceRole(it.id) }
                globalRoles.forEach { onSaveVoiceRole(it.toReferenceDraft(context).copy(originalRoleId = null)) }
                copyGlobalConfirm = false
            }) { Text("SAO CHÉP") } },
            dismissButton = { TextButton(onClick = { copyGlobalConfirm = false }) { Text("HỦY") } },
        )
    }

    if (restorePrivateConfirm) {
        AlertDialog(
            onDismissRequest = { restorePrivateConfirm = false },
            title = { Text("KHÔI PHỤC HỒ SƠ MẪU") },
            text = { Text("Khôi phục 7 hồ sơ mẫu?") },
            confirmButton = { TextButton(onClick = {
                val currentByName = privateRoles.associateBy { it.roleName.trim().lowercase() }
                globalRoles.forEach { global ->
                    val old = currentByName[global.roleName.trim().lowercase()]
                    onSaveVoiceRole(
                        global.toReferenceDraft(context).copy(
                            originalRoleId = old?.id,
                            enginePackage = old?.enginePackage ?: global.enginePackage,
                            voiceName = old?.voiceName ?: global.voiceName,
                            languageTag = old?.languageTag ?: global.languageTag,
                            rate = old?.rate ?: global.rate,
                            pitch = old?.pitch ?: global.pitch,
                            volume = old?.volume ?: global.volume,
                        ),
                    )
                }
                restorePrivateConfirm = false
            }) { Text("KHÔI PHỤC") } },
            dismissButton = { TextButton(onClick = { restorePrivateConfirm = false }) { Text("HỦY") } },
        )
    }

    deletePrivateRole?.let { role ->
        AlertDialog(
            onDismissRequest = { deletePrivateRole = null },
            title = { Text("XÓA HỒ SƠ") },
            text = { Text("Xóa hồ sơ “${role.roleName}”?") },
            confirmButton = { TextButton(onClick = { onDeleteVoiceRole(role.id); deletePrivateRole = null }) { Text("XÓA") } },
            dismissButton = { TextButton(onClick = { deletePrivateRole = null }) { Text("HỦY") } },
        )
    }
}

private fun VoiceRoleEntity.toReferenceDraft(context: android.content.Context): VoiceRoleDraft {
    val extra = ReferenceVoiceRoleExtras.load(context, id)
    return VoiceRoleDraft(
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
        processingMethod = extra.processingMethod,
        sonicAccurate = extra.sonicAccurate,
        enabled = enabled,
    )
}

@Composable
private fun ReferenceStoryPercentSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    val safe = value.coerceIn(0, 100)
    Text("$label: ±$safe%", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
    Slider(
        value = safe.toFloat(),
        onValueChange = { onChange(it.toInt().coerceIn(0, 100)) },
        valueRange = 0f..100f,
        steps = 99,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$label: ±$safe%" },
    )
}
