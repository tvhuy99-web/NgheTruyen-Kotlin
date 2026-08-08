from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

def read(path):
    return (ROOT / path).read_text()

def write(path, text):
    (ROOT / path).write_text(text)

def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 occurrence, found {count}")
    return text.replace(old, new, 1)

def regex_once(text, pattern, repl, label):
    result, count = re.subn(pattern, lambda _m: repl, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 regex match, found {count}")
    return result

# ---------------------------------------------------------------------------
# Shared voice draft: processing method and Sonic quality must survive UI flows.
# ---------------------------------------------------------------------------
path = "app/src/main/java/vn/nghetruyen/app/core/model/Models.kt"
text = read(path)
text = replace_once(
    text,
    '''    val sonicSpeed: Float = 1f,\n    val sonicPitch: Float = 1f,\n    val enabled: Boolean = true,\n''',
    '''    val sonicSpeed: Float = 1f,\n    val sonicPitch: Float = 1f,\n    val processingMethod: String = "system",\n    val sonicAccurate: Boolean = false,\n    val enabled: Boolean = true,\n''',
    "VoiceRoleDraft processing fields",
)
write(path, text)

# ---------------------------------------------------------------------------
# Repository ranges must match XPK, not the old UI's narrower limits.
# ---------------------------------------------------------------------------
path = "app/src/main/java/vn/nghetruyen/app/data/repository/LibraryRepository.kt"
text = read(path)
text = replace_once(text, 'rate = rate.coerceIn(0.5f, 2.0f),\n                pitch = pitch.coerceIn(0.5f, 2.0f),\n                volume = volume.coerceIn(0.05f, 1.0f),',
                    'rate = rate.coerceIn(0.25f, 3.0f),\n                pitch = pitch.coerceIn(0.5f, 2.0f),\n                volume = volume.coerceIn(0f, 2.0f),',
                    "story TTS profile ranges")
text = replace_once(text, 'rate = rate.coerceIn(0.5f, 2.0f),\n                pitch = pitch.coerceIn(0.5f, 2.0f),\n                volume = volume.coerceIn(0.05f, 1.0f),\n                expression = expression.trim()',
                    'rate = rate.coerceIn(0.25f, 3.0f),\n                pitch = pitch.coerceIn(0.5f, 2.0f),\n                volume = volume.coerceIn(0f, 2.0f),\n                expression = expression.trim()',
                    "voice role core ranges")
text = replace_once(text, 'sonicSpeed = sonicSpeed.coerceIn(0.5f, 2.0f),\n                sonicPitch = sonicPitch.coerceIn(0.5f, 2.0f),',
                    'sonicSpeed = sonicSpeed.coerceIn(0.25f, 3.0f),\n                sonicPitch = sonicPitch.coerceIn(0.5f, 2.0f),',
                    "voice role Sonic speed range")
write(path, text)

# ---------------------------------------------------------------------------
# AppViewModel: persist extras for both existing and newly created roles.
# ---------------------------------------------------------------------------
path = "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt"
text = read(path)
anchor = 'import vn.nghetruyen.app.ui.reference.ReferenceTtsPersistence\n'
if anchor in text:
    text = replace_once(text, anchor, anchor + 'import vn.nghetruyen.app.ui.reference.ReferenceVoiceRoleExtra\nimport vn.nghetruyen.app.ui.reference.ReferenceVoiceRoleExtras\n', "AppVM voice extras imports")
else:
    # Fall back to a stable nearby import.
    text = replace_once(text, 'import vn.nghetruyen.app.ui.reference.ReferenceVoiceRolePersistence\n',
                        'import vn.nghetruyen.app.ui.reference.ReferenceVoiceRolePersistence\nimport vn.nghetruyen.app.ui.reference.ReferenceVoiceRoleExtra\nimport vn.nghetruyen.app.ui.reference.ReferenceVoiceRoleExtras\n',
                        "AppVM voice extras imports fallback")
text = replace_once(
    text,
    '''            ).onSuccess { savedId ->\n                draft.originalRoleId?.takeIf { it != savedId }?.let { container.libraryRepository.deleteVoiceRole(it) }\n                ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)\n''',
    '''            ).onSuccess { savedId ->\n                ReferenceVoiceRoleExtras.save(\n                    getApplication(), savedId,\n                    ReferenceVoiceRoleExtra(draft.processingMethod, draft.sonicAccurate),\n                )\n                draft.originalRoleId?.takeIf { it != savedId }?.let { oldId ->\n                    container.libraryRepository.deleteVoiceRole(oldId)\n                    ReferenceVoiceRoleExtras.remove(getApplication(), oldId)\n                }\n                ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)\n''',
    "global role extras save",
)
text = replace_once(
    text,
    '''            container.libraryRepository.deleteVoiceRole(id)\n            ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)\n            showMessage("Đã xóa hồ sơ giọng chung ${role.roleName}.")\n''',
    '''            container.libraryRepository.deleteVoiceRole(id)\n            ReferenceVoiceRoleExtras.remove(getApplication(), id)\n            ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)\n            showMessage("Đã xóa hồ sơ giọng chung ${role.roleName}.")\n''',
    "global role extras delete",
)
text = replace_once(
    text,
    '''            ).onSuccess { savedId ->\n                draft.originalRoleId\n                    ?.takeIf { it != savedId }\n                    ?.let { container.libraryRepository.deleteVoiceRole(it) }\n                ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)\n''',
    '''            ).onSuccess { savedId ->\n                ReferenceVoiceRoleExtras.save(\n                    getApplication(), savedId,\n                    ReferenceVoiceRoleExtra(draft.processingMethod, draft.sonicAccurate),\n                )\n                draft.originalRoleId\n                    ?.takeIf { it != savedId }\n                    ?.let { oldId ->\n                        container.libraryRepository.deleteVoiceRole(oldId)\n                        ReferenceVoiceRoleExtras.remove(getApplication(), oldId)\n                    }\n                ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)\n''',
    "private role extras save",
)
text = replace_once(
    text,
    '''    fun deleteVoiceRole(id: String) {\n        viewModelScope.launch {\n            container.libraryRepository.deleteVoiceRole(id)\n            ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)\n''',
    '''    fun deleteVoiceRole(id: String) {\n        viewModelScope.launch {\n            container.libraryRepository.deleteVoiceRole(id)\n            ReferenceVoiceRoleExtras.remove(getApplication(), id)\n            ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)\n''',
    "private role extras delete",
)
write(path, text)

# ---------------------------------------------------------------------------
# Sonic processor: quality is per profile, not only one process-global switch.
# ---------------------------------------------------------------------------
path = "app/src/main/java/vn/nghetruyen/app/audio/SonicPcmProcessor.kt"
text = read(path)
text = replace_once(
    text,
    'fun process(source: File, destination: File, speed: Float, pitch: Float): WaveSegment {',
    'fun process(source: File, destination: File, speed: Float, pitch: Float, accurate: Boolean = ReferenceSonicRuntime.accurateMode): WaveSegment {',
    "Sonic process quality argument",
)
text = replace_once(text, 'accurate = ReferenceSonicRuntime.accurateMode,', 'accurate = accurate,', "Sonic quality forwarding")
write(path, text)

# ---------------------------------------------------------------------------
# Playback runtime: load role extras and honor exact XPK ranges/method.
# ---------------------------------------------------------------------------
path = "app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt"
text = read(path)
text = replace_once(text, 'import vn.nghetruyen.app.data.local.VoiceRoleEntity\n',
                    'import vn.nghetruyen.app.data.local.VoiceRoleEntity\nimport vn.nghetruyen.app.ui.reference.ReferenceVoiceRoleExtras\n',
                    "playback voice extras import")
text = replace_once(
    text,
    '''    val volume: Float,\n    val sonicSpeed: Float = 1f,\n    val sonicPitch: Float = 1f,\n)\n''',
    '''    val volume: Float,\n    val sonicSpeed: Float = 1f,\n    val sonicPitch: Float = 1f,\n    val sonicEnabled: Boolean = false,\n    val sonicAccurate: Boolean = false,\n)\n''',
    "RuntimeVoiceConfig Sonic mode",
)
text = replace_once(
    text,
    '''            volume = profile?.volume ?: settings.ttsVolume,\n        )\n''',
    '''            volume = profile?.volume ?: settings.ttsVolume,\n            sonicEnabled = settings.sonicProcessingEnabled,\n            sonicAccurate = settings.sonicAccurateMode,\n        )\n''',
    "base voice Sonic method",
)
text = replace_once(
    text,
    '''        val config = roleConfig.copy(\n            rate = (roleConfig.rate * expression.rateMultiplier * aiRateMultiplier).coerceIn(0.5f, 2f),\n            pitch = (roleConfig.pitch * expression.pitchMultiplier * aiPitchMultiplier).coerceIn(0.5f, 2f),\n            volume = (roleConfig.volume * expression.volumeMultiplier * aiVolumeMultiplier).coerceIn(0.05f, 1f),\n            sonicSpeed = (roleConfig.sonicSpeed * expression.sonicSpeedMultiplier * sonicDefaultSpeed).coerceIn(0.5f, 2f),\n            sonicPitch = (roleConfig.sonicPitch * expression.sonicPitchMultiplier * sonicDefaultPitch).coerceIn(0.5f, 2f),\n        )\n''',
    '''        val config = roleConfig.copy(\n            rate = (roleConfig.rate * expression.rateMultiplier * aiRateMultiplier).coerceIn(0.25f, 3f),\n            pitch = (roleConfig.pitch * expression.pitchMultiplier * aiPitchMultiplier).coerceIn(0.5f, 2f),\n            volume = (roleConfig.volume * expression.volumeMultiplier * aiVolumeMultiplier)\n                .coerceIn(0f, if (roleConfig.sonicEnabled) 2f else 1f),\n            sonicSpeed = if (roleConfig.sonicEnabled)\n                (roleConfig.sonicSpeed * expression.sonicSpeedMultiplier * sonicDefaultSpeed).coerceIn(0.25f, 3f)\n            else 1f,\n            sonicPitch = if (roleConfig.sonicEnabled)\n                (roleConfig.sonicPitch * expression.sonicPitchMultiplier * sonicDefaultPitch).coerceIn(0.5f, 2f)\n            else 1f,\n        )\n''',
    "playback exact voice ranges",
)
text = replace_once(
    text,
    '''        val hasSonicTransform = sonicProcessingEnabled &&\n            (kotlin.math.abs(config.sonicSpeed - 1f) >= 0.015f || kotlin.math.abs(config.sonicPitch - 1f) >= 0.015f)\n        val useRenderedPipeline = !forceNoSonic &&\n            (hasSonicTransform || ttsCacheEnabled || normalizeTtsVolumeEnabled)\n        val renderConfig = if (hasSonicTransform) config else config.copy(sonicSpeed = 1f, sonicPitch = 1f)\n''',
    '''        val hasSonicTransform = config.sonicEnabled\n        val useRenderedPipeline = !forceNoSonic &&\n            (hasSonicTransform || ttsCacheEnabled || normalizeTtsVolumeEnabled)\n        val renderConfig = if (hasSonicTransform) config else config.copy(sonicSpeed = 1f, sonicPitch = 1f, sonicEnabled = false)\n''',
    "playback role Sonic selection",
)
text = replace_once(text, 'val speed = activeSonicSpeed.coerceIn(0.5f, 2f)\n        val pitch = activeSonicPitch.coerceIn(0.5f, 2f)\n        val volume = activeSonicVolume.coerceIn(0.05f, 1f)',
                    'val speed = activeSonicSpeed.coerceIn(0.25f, 3f)\n        val pitch = activeSonicPitch.coerceIn(0.5f, 2f)\n        val volume = activeSonicVolume.coerceIn(0f, 2f)',
                    "rendered Sonic exact ranges")
text = replace_once(text, 'SonicPcmProcessor.process(pcm, output, speed, pitch)', 'SonicPcmProcessor.process(pcm, output, speed, pitch, activeSpeechAttempt?.config?.sonicAccurate ?: false)', "playback Sonic quality")
text = replace_once(
    text,
    '''            rate = intent.getFloatExtra(EXTRA_PREVIEW_RATE, 1f).coerceIn(0.5f, 2f),\n            pitch = intent.getFloatExtra(EXTRA_PREVIEW_PITCH, 1f).coerceIn(0.5f, 2f),\n            volume = intent.getFloatExtra(EXTRA_PREVIEW_VOLUME, 1f).coerceIn(0.05f, 1f),\n            sonicSpeed = intent.getFloatExtra(EXTRA_PREVIEW_SONIC_SPEED, 1f).coerceIn(0.5f, 2f),\n            sonicPitch = intent.getFloatExtra(EXTRA_PREVIEW_SONIC_PITCH, 1f).coerceIn(0.5f, 2f),\n''',
    '''            rate = intent.getFloatExtra(EXTRA_PREVIEW_RATE, 1f).coerceIn(0.25f, 3f),\n            pitch = intent.getFloatExtra(EXTRA_PREVIEW_PITCH, 1f).coerceIn(0.5f, 2f),\n            volume = intent.getFloatExtra(EXTRA_PREVIEW_VOLUME, 1f).coerceIn(0f, 2f),\n            sonicSpeed = intent.getFloatExtra(EXTRA_PREVIEW_SONIC_SPEED, 1f).coerceIn(0.25f, 3f),\n            sonicPitch = intent.getFloatExtra(EXTRA_PREVIEW_SONIC_PITCH, 1f).coerceIn(0.5f, 2f),\n            sonicEnabled = intent.getBooleanExtra(EXTRA_PREVIEW_SONIC_ENABLED, false),\n            sonicAccurate = intent.getBooleanExtra(EXTRA_PREVIEW_SONIC_ACCURATE, false),\n''',
    "preview exact voice config",
)
text = replace_once(
    text,
    '''                rate = (baseConfig.rate * expressive.rateMultiplier).coerceIn(0.5f, 2f),\n                pitch = (baseConfig.pitch * expressive.pitchMultiplier).coerceIn(0.5f, 2f),\n                volume = (baseConfig.volume * expressive.volumeMultiplier).coerceIn(0.05f, 1f),\n                sonicSpeed = (baseConfig.sonicSpeed * expressive.sonicSpeedMultiplier).coerceIn(0.5f, 2f),\n                sonicPitch = (baseConfig.sonicPitch * expressive.sonicPitchMultiplier).coerceIn(0.5f, 2f),\n''',
    '''                rate = (baseConfig.rate * expressive.rateMultiplier).coerceIn(0.25f, 3f),\n                pitch = (baseConfig.pitch * expressive.pitchMultiplier).coerceIn(0.5f, 2f),\n                volume = (baseConfig.volume * expressive.volumeMultiplier).coerceIn(0f, if (baseConfig.sonicEnabled) 2f else 1f),\n                sonicSpeed = if (baseConfig.sonicEnabled) (baseConfig.sonicSpeed * expressive.sonicSpeedMultiplier).coerceIn(0.25f, 3f) else 1f,\n                sonicPitch = if (baseConfig.sonicEnabled) (baseConfig.sonicPitch * expressive.sonicPitchMultiplier).coerceIn(0.5f, 2f) else 1f,\n''',
    "preview expressive exact ranges",
)
text = replace_once(text, 'tts.setSpeechRate(config.rate.coerceIn(0.5f, 2.0f))', 'tts.setSpeechRate(config.rate.coerceIn(0.25f, 3.0f))', "Android TTS exact rate range")
text = replace_once(
    text,
    '''    private fun VoiceRoleEntity.toRuntimeVoice(): RuntimeVoiceConfig = RuntimeVoiceConfig(\n        enginePackage = enginePackage,\n        voiceName = voiceName,\n        languageTag = languageTag,\n        rate = rate,\n        pitch = pitch,\n        volume = volume,\n        sonicSpeed = sonicSpeed,\n        sonicPitch = sonicPitch,\n    )\n''',
    '''    private fun VoiceRoleEntity.toRuntimeVoice(): RuntimeVoiceConfig {\n        val extra = ReferenceVoiceRoleExtras.load(this@ReaderPlaybackService, id)\n        val sonic = extra.processingMethod == "sonic"\n        return RuntimeVoiceConfig(\n            enginePackage = enginePackage,\n            voiceName = voiceName,\n            languageTag = languageTag,\n            rate = rate,\n            pitch = pitch,\n            volume = volume.coerceIn(0f, if (sonic) 2f else 1f),\n            sonicSpeed = if (sonic) sonicSpeed.coerceIn(0.25f, 3f) else 1f,\n            sonicPitch = if (sonic) sonicPitch.coerceIn(0.5f, 2f) else 1f,\n            sonicEnabled = sonic,\n            sonicAccurate = extra.sonicAccurate,\n        )\n    }\n''',
    "role runtime extras",
)
text = replace_once(
    text,
    '''        private const val EXTRA_PREVIEW_SONIC_PITCH = "preview_sonic_pitch"\n        private const val EXTRA_PREVIEW_EXPRESSION = "preview_expression"\n''',
    '''        private const val EXTRA_PREVIEW_SONIC_PITCH = "preview_sonic_pitch"\n        private const val EXTRA_PREVIEW_SONIC_ENABLED = "preview_sonic_enabled"\n        private const val EXTRA_PREVIEW_SONIC_ACCURATE = "preview_sonic_accurate"\n        private const val EXTRA_PREVIEW_EXPRESSION = "preview_expression"\n''',
    "preview Sonic extras constants",
)
text = replace_once(
    text,
    '''                    .putExtra(EXTRA_PREVIEW_SONIC_PITCH, draft.sonicPitch)\n                    .putExtra(EXTRA_PREVIEW_EXPRESSION, draft.expression.name)\n''',
    '''                    .putExtra(EXTRA_PREVIEW_SONIC_PITCH, draft.sonicPitch)\n                    .putExtra(EXTRA_PREVIEW_SONIC_ENABLED, draft.processingMethod == "sonic")\n                    .putExtra(EXTRA_PREVIEW_SONIC_ACCURATE, draft.sonicAccurate)\n                    .putExtra(EXTRA_PREVIEW_EXPRESSION, draft.expression.name)\n''',
    "preview Sonic extras intent",
)
write(path, text)

# ---------------------------------------------------------------------------
# Active Personal UI: exact headings/wording and functional role editor.
# ---------------------------------------------------------------------------
path = "app/src/main/java/vn/nghetruyen/app/ui/screens/ReferencePersonalScreen.kt"
text = read(path)
text = text.replace('title = { Text("CÀI ĐẶT") }', 'title = { Text("CÀI ĐẶT ỨNG DỤNG") }', 1)
text = text.replace('label = { Text("Từ hoặc cụm từ") }', 'label = { Text("Từ hoặc cụm từ gốc") }', 1)
text = text.replace('label = { Text("Cách đọc") }', 'label = { Text("TTS sẽ đọc thành") }', 1)
# Exact VietPhrase confirmation wording.
text = replace_once(text, 'var deleteAll by remember { mutableStateOf(false) }\n', 'var deleteAll by remember { mutableStateOf(false) }\n    var downloadConfirm by remember { mutableStateOf(false) }\n', "VietPhrase download confirmation state")
text = text.replace('SettingsButton("TẢI TỰ ĐỘNG TỪ MẠNG", onInstallOnline)', 'SettingsButton("TẢI TỰ ĐỘNG TỪ MẠNG") { downloadConfirm = true }', 1)
text = text.replace('title = { Text("XÓA DỮ LIỆU FILE") },\n            text = { Text("Xóa toàn bộ dữ liệu của ${kind.fileName}?") },', 'title = { Text("XÓA ${kind.fileName}") },\n            text = { Text("Xóa dữ liệu của file này?") },', 1)
text = text.replace('title = { Text("XÓA TẤT CẢ VIETPHRASE") },\n            text = { Text("Xóa toàn bộ dữ liệu VietPhrase đã nhập?") },', 'title = { Text("XÓA TOÀN BỘ VIETPHRASE") },\n            text = { Text("Xóa tất cả dữ liệu VietPhrase?") },', 1)
insert = '''    if (downloadConfirm) {\n        AlertDialog(\n            onDismissRequest = { downloadConfirm = false },\n            title = { Text("TẢI TỰ ĐỘNG TỪ MẠNG") },\n            text = { Text("Tải và cài bộ dữ liệu VietPhrase từ mạng?") },\n            confirmButton = { TextButton(onClick = { downloadConfirm = false; onInstallOnline() }) { Text("TẢI") } },\n            dismissButton = { TextButton(onClick = { downloadConfirm = false }) { Text("HỦY") } },\n        )\n    }\n'''
text = replace_once(text, '    state.pendingVietPhraseImport?.let { preview ->\n', insert + '    state.pendingVietPhraseImport?.let { preview ->\n', "VietPhrase download confirmation dialog")
# Global role state and exact editor behavior.
text = replace_once(text, '    var voices by remember { mutableStateOf<List<TtsVoiceOption>>(emptyList()) }\n',
                    '    var voices by remember { mutableStateOf<List<TtsVoiceOption>>(emptyList()) }\n    var deleteRole by remember { mutableStateOf<VoiceRoleEntity?>(null) }\n    var restoreConfirm by remember { mutableStateOf(false) }\n',
                    "global role confirmation states")
text = replace_once(
    text,
    '''                            draft = role.toVoiceDraft()\n                            val extra = ReferenceVoiceRoleExtras.load(context, role.id)\n                            processing = extra.processingMethod\n                            accurate = extra.sonicAccurate\n''',
    '''                            val extra = ReferenceVoiceRoleExtras.load(context, role.id)\n                            processing = extra.processingMethod\n                            accurate = extra.sonicAccurate\n                            draft = role.toVoiceDraft().copy(processingMethod = processing, sonicAccurate = accurate)\n''',
    "global role draft extras",
)
# Remove list-level enabled switch, it belongs in editor in XPK.
text = regex_once(text, r'''                    if \(!role\.isNarrator\) \{\n                        Row\(Modifier\.fillMaxWidth\(\), verticalAlignment = Alignment\.CenterVertically\) \{\n                            Text\("Bật hồ sơ", Modifier\.weight\(1f\)\); Switch\(role\.enabled, \{ onEnabledChange\(role\.id, it\) \}\)\n                        \}\n                    \}\n''', '', "remove list-level role switch")
text = text.replace('SettingsButton("KHÔI PHỤC 7 HỒ SƠ MẪU", onRestore)', 'SettingsButton("KHÔI PHỤC 7 HỒ SƠ MẪU") { restoreConfirm = true }', 1)
text = text.replace('title = { Text(if (current.originalRoleId == null) "THÊM HỒ SƠ GIỌNG" else "SỬA HỒ SƠ GIỌNG") },', 'title = { Text("HỒ SƠ GIỌNG TTS") },', 1)
# Replace role identity block with exact reference fields/switch.
text = regex_once(
    text,
    r'''                Row\(Modifier\.fillMaxWidth\(\), verticalAlignment = Alignment\.CenterVertically\) \{ Checkbox\(current\.isNarrator, .*?\n                \}\n                Text\("Bộ đọc TTS"''',
    '''                if (current.isNarrator) {\n                    Text("Người kể chuyện", fontWeight = FontWeight.SemiBold)\n                    Text("Người kể chuyện luôn được bật", style = MaterialTheme.typography.bodySmall)\n                } else {\n                    OutlinedTextField(current.roleName, { draft = current.copy(roleName = it.take(80)) }, label = { Text("Tên vai hoặc tên nhân vật") }, modifier = Modifier.fillMaxWidth())\n                    OutlinedTextField(current.description, { draft = current.copy(description = it.take(1_000)) }, label = { Text("Mô tả để AI nhận biết") }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))\n                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {\n                        Text("Bật hồ sơ này", Modifier.weight(1f))\n                        Switch(current.enabled, { draft = current.copy(enabled = it) })\n                    }\n                }\n                Text("Bộ đọc TTS"''',
    "global exact identity editor",
)
text = text.replace('Text("Giọng đọc", modifier = Modifier.padding(top = 6.dp))', 'Text("Giọng nói", modifier = Modifier.padding(top = 6.dp))', 1)
text = text.replace('Text((if (processing == "system") "✓ " else "") + "Android")', 'Text((if (processing == "system") "✓ " else "") + "Android, tối đa 100%")', 1)
text = text.replace('Text((if (processing == "sonic") "✓ " else "") + "Sonic")', 'Text((if (processing == "sonic") "✓ " else "") + "Sonic, tối đa 200%")', 1)
text = replace_once(text, 'SettingsButton("NGHE THỬ") { onPreview(current) }', 'SettingsButton("NGHE THỬ") { onPreview(current.copy(processingMethod = processing, sonicAccurate = accurate)) }', "global preview processing")
text = replace_once(
    text,
    '''                onSave(normalized)\n                current.originalRoleId?.let { ReferenceVoiceRoleExtras.save(context, it, ReferenceVoiceRoleExtra(processing, accurate)) }\n                draft = null\n            }) { Text("LƯU") } },\n            dismissButton = { Row {\n                if (!current.isNarrator && current.originalRoleId != null) TextButton(onClick = {\n                    roles.firstOrNull { it.id == current.originalRoleId }?.let(onDelete)\n                    draft = null\n                }) { Text("XÓA") }\n                TextButton(onClick = { draft = null }) { Text("HỦY") }\n            } },\n''',
    '''                onSave(normalized.copy(processingMethod = processing, sonicAccurate = accurate))\n                draft = null\n            }) { Text("LƯU HỒ SƠ") } },\n            dismissButton = { Row {\n                if (!current.isNarrator && current.originalRoleId != null) TextButton(onClick = {\n                    deleteRole = roles.firstOrNull { it.id == current.originalRoleId }\n                    draft = null\n                }) { Text("XÓA HỒ SƠ") }\n                else if (current.isNarrator) TextButton(onClick = {}) { Text("NGƯỜI KỂ CHUYỆN BẮT BUỘC") }\n                TextButton(onClick = { draft = null }) { Text("HỦY") }\n            } },\n''',
    "global exact save delete buttons",
)
# Validation requires name/description/voice for non narrator.
text = text.replace('confirmButton = { TextButton(enabled = current.isNarrator || current.roleName.isNotBlank(), onClick = {',
                    'confirmButton = { TextButton(enabled = current.isNarrator || (current.roleName.isNotBlank() && current.description.isNotBlank() && !current.voiceName.isNullOrBlank()), onClick = {', 1)
# Confirmation dialogs after editor.
marker = '''    }\n}\n\nprivate fun VoiceRoleEntity.toVoiceDraft(): VoiceRoleDraft = VoiceRoleDraft(\n'''
confirm_dialogs = '''    }\n    deleteRole?.let { role ->\n        AlertDialog(\n            onDismissRequest = { deleteRole = null },\n            title = { Text("XÓA HỒ SƠ") },\n            text = { Text("Xóa hồ sơ “${role.roleName}”? Kết quả cũ dùng hồ sơ này sẽ trở về Người kể chuyện.") },\n            confirmButton = { TextButton(onClick = { onDelete(role); deleteRole = null }) { Text("XÓA") } },\n            dismissButton = { TextButton(onClick = { deleteRole = null }) { Text("HỦY") } },\n        )\n    }\n    if (restoreConfirm) {\n        AlertDialog(\n            onDismissRequest = { restoreConfirm = false },\n            title = { Text("KHÔI PHỤC HỒ SƠ MẪU") },\n            text = { Text("Khôi phục tên và mô tả của 7 hồ sơ mẫu? Cấu hình âm thanh và các hồ sơ tùy chỉnh sẽ được giữ lại trong giới hạn 10 hồ sơ.") },\n            confirmButton = { TextButton(onClick = { restoreConfirm = false; onRestore() }) { Text("KHÔI PHỤC") } },\n            dismissButton = { TextButton(onClick = { restoreConfirm = false }) { Text("HỦY") } },\n        )\n    }\n}\n\nprivate fun VoiceRoleEntity.toVoiceDraft(): VoiceRoleDraft = VoiceRoleDraft(\n'''
text = replace_once(text, marker, confirm_dialogs, "global role confirmation dialogs")
write(path, text)

# ---------------------------------------------------------------------------
# Active Story private voice set/editor: same profile semantics as global XPK editor.
# ---------------------------------------------------------------------------
path = "app/src/main/java/vn/nghetruyen/app/ui/screens/StoryDetailScreen.kt"
text = read(path)
text = replace_once(text, 'import androidx.compose.ui.platform.LocalView\n', 'import androidx.compose.ui.platform.LocalContext\nimport androidx.compose.ui.platform.LocalView\n', "Story LocalContext import")
text = replace_once(text, 'import vn.nghetruyen.app.ui.MainUiState\n', 'import vn.nghetruyen.app.ui.MainUiState\nimport vn.nghetruyen.app.ui.reference.ReferenceVoiceRoleExtras\n', "Story voice extras import")
text = replace_once(text, '    val view = LocalView.current\n', '    val view = LocalView.current\n    val context = LocalContext.current\n', "Story context")
text = replace_once(
    text,
    '''    var showVoiceRoleDialog by remember(detail.story.id) { mutableStateOf(false) }\n    var showExpressionPromptDialog by remember(detail.story.id) { mutableStateOf(false) }\n''',
    '''    var showVoiceRoleDialog by remember(detail.story.id) { mutableStateOf(false) }\n    var showExpressionPromptDialog by remember(detail.story.id) { mutableStateOf(false) }\n    var copyGlobalConfirm by remember(detail.story.id) { mutableStateOf(false) }\n    var restorePrivateConfirm by remember(detail.story.id) { mutableStateOf(false) }\n    var deletePrivateRole by remember(detail.story.id) { mutableStateOf<VoiceRoleEntity?>(null) }\n''',
    "Story voice confirmation states",
)
text = replace_once(text, 'roleDraft = role.toDraft()\n', 'roleDraft = role.toDraft(context)\n', "private role load extras")
text = replace_once(
    text,
    '''                    ReferenceActionButton("SAO CHÉP LẠI TỪ CẤU HÌNH CHUNG", {\n                        globalRoles.forEach { onSaveVoiceRole(it.toDraft().copy(originalRoleId = null)) }\n                    }, normalColor = ReferenceGray, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))\n                    ReferenceActionButton("KHÔI PHỤC 7 HỒ SƠ MẪU", {\n                        privateRoles.filterNot(VoiceRoleEntity::isNarrator).forEach { onDeleteVoiceRole(it.id) }\n                        globalRoles.forEach { onSaveVoiceRole(it.toDraft().copy(originalRoleId = null)) }\n                    }, normalColor = ReferenceGray, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))\n''',
    '''                    Text("Đây là bộ hồ sơ độc lập của truyện hiện tại. Bạn có thể tự đặt tên nhân vật, tên gọi khác và mô tả nhận diện. Thay đổi ở đây không ảnh hưởng cấu hình chung hoặc truyện khác.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp))\n                    ReferenceActionButton("SAO CHÉP LẠI TỪ CẤU HÌNH CHUNG", { copyGlobalConfirm = true }, normalColor = ReferenceGray, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))\n                    ReferenceActionButton("KHÔI PHỤC 7 HỒ SƠ MẪU", { restorePrivateConfirm = true }, normalColor = ReferenceGray, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))\n''',
    "private copy restore confirmation triggers",
)
text = text.replace('title = { Text(if (roleDraft.originalRoleId == null) "THÊM VAI HOẶC NHÂN VẬT" else "SỬA HỒ SƠ VAI") },', 'title = { Text("HỒ SƠ GIỌNG RIÊNG") },', 1)
# Replace narrator checkbox and fields.
text = regex_once(
    text,
    r'''                    Row\(Modifier\.fillMaxWidth\(\), verticalAlignment = Alignment\.CenterVertically\) \{\n                        Checkbox\(roleDraft\.isNarrator, .*?\n                    \}\n                    Text\("Bộ đọc TTS"''',
    '''                    if (roleDraft.isNarrator) {\n                        Text("Người kể chuyện", fontWeight = FontWeight.SemiBold)\n                        Text("Người kể chuyện luôn được bật", style = MaterialTheme.typography.bodySmall)\n                    } else {\n                        OutlinedTextField(roleDraft.roleName, { roleDraft = roleDraft.copy(roleName = it.take(80)) }, label = { Text("Tên vai hoặc tên nhân vật") }, modifier = Modifier.fillMaxWidth())\n                        OutlinedTextField(roleDraft.description, { roleDraft = roleDraft.copy(description = it.take(1_000)) }, label = { Text("Mô tả để AI nhận biết") }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))\n                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {\n                            Text("Bật hồ sơ này", Modifier.weight(1f))\n                            Switch(roleDraft.enabled, { roleDraft = roleDraft.copy(enabled = it) })\n                        }\n                    }\n                    Text("Bộ đọc TTS"''',
    "private exact identity editor",
)
# Voice label and processing controls before sliders.
text = text.replace('Text("Giọng đọc", modifier = Modifier.padding(top = 8.dp))', 'Text("Giọng nói", modifier = Modifier.padding(top = 8.dp))', 1)
slider_anchor = '                    ReferenceFloatSlider("Tốc độ", roleDraft.rate, 0.25f, 3f) { roleDraft = roleDraft.copy(rate = it) }\n'
processing_ui = '''                    Text("Phương pháp xử lý", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))\n                    Row(Modifier.fillMaxWidth()) {\n                        TextButton({ roleDraft = roleDraft.copy(processingMethod = "system", volume = roleDraft.volume.coerceAtMost(1f)) }, Modifier.weight(1f)) { Text((if (roleDraft.processingMethod != "sonic") "✓ " else "") + "Android, tối đa 100%") }\n                        TextButton({ roleDraft = roleDraft.copy(processingMethod = "sonic") }, Modifier.weight(1f)) { Text((if (roleDraft.processingMethod == "sonic") "✓ " else "") + "Sonic, tối đa 200%") }\n                    }\n                    if (roleDraft.processingMethod == "sonic") {\n                        Text("Chế độ Sonic", fontWeight = FontWeight.SemiBold)\n                        Row(Modifier.fillMaxWidth()) {\n                            TextButton({ roleDraft = roleDraft.copy(sonicAccurate = false) }, Modifier.weight(1f)) { Text((if (!roleDraft.sonicAccurate) "✓ " else "") + "Nhanh") }\n                            TextButton({ roleDraft = roleDraft.copy(sonicAccurate = true) }, Modifier.weight(1f)) { Text((if (roleDraft.sonicAccurate) "✓ " else "") + "Chính xác") }\n                        }\n                    }\n'''
text = replace_once(text, slider_anchor, processing_ui + slider_anchor, "private processing UI")
text = replace_once(text, 'ReferenceFloatSlider("Âm lượng", roleDraft.volume, 0f, 2f, percent = true) { roleDraft = roleDraft.copy(volume = it) }',
                    'ReferenceFloatSlider("Âm lượng", roleDraft.volume, 0f, if (roleDraft.processingMethod == "sonic") 2f else 1f, percent = true) { roleDraft = roleDraft.copy(volume = it) }',
                    "private volume max by method")
text = replace_once(
    text,
    '''            confirmButton = { TextButton(enabled = roleDraft.isNarrator || roleDraft.roleName.isNotBlank(), onClick = { onSaveVoiceRole(roleDraft.copy(roleName = if (roleDraft.isNarrator) "Người kể chuyện" else roleDraft.roleName)); showVoiceRoleDialog = false }) { Text("LƯU") } },\n            dismissButton = { TextButton(onClick = { showVoiceRoleDialog = false }) { Text("HỦY") } },\n''',
    '''            confirmButton = { TextButton(enabled = roleDraft.isNarrator || (roleDraft.roleName.isNotBlank() && roleDraft.description.isNotBlank() && !roleDraft.voiceName.isNullOrBlank()), onClick = { onSaveVoiceRole(roleDraft.copy(roleName = if (roleDraft.isNarrator) "Người kể chuyện" else roleDraft.roleName)); showVoiceRoleDialog = false }) { Text("LƯU HỒ SƠ") } },\n            dismissButton = { Row {\n                if (!roleDraft.isNarrator && roleDraft.originalRoleId != null) TextButton(onClick = { deletePrivateRole = privateRoles.firstOrNull { it.id == roleDraft.originalRoleId }; showVoiceRoleDialog = false }) { Text("XÓA HỒ SƠ") }\n                else if (roleDraft.isNarrator) TextButton(onClick = {}) { Text("NGƯỜI KỂ CHUYỆN BẮT BUỘC") }\n                TextButton(onClick = { showVoiceRoleDialog = false }) { Text("HỦY") }\n            } },\n''',
    "private save delete buttons",
)
# Insert confirmations before download dialog.
confirm_block = '''    if (copyGlobalConfirm) {\n        AlertDialog(\n            onDismissRequest = { copyGlobalConfirm = false },\n            title = { Text("SAO CHÉP CẤU HÌNH CHUNG") },\n            text = { Text("Thay toàn bộ bộ giọng riêng hiện tại bằng một bản sao mới của cấu hình chung? Sau khi sao chép, hai bộ vẫn độc lập.") },\n            confirmButton = { TextButton(onClick = {\n                privateRoles.forEach { onDeleteVoiceRole(it.id) }\n                globalRoles.forEach { onSaveVoiceRole(it.toDraft(context).copy(originalRoleId = null)) }\n                copyGlobalConfirm = false\n            }) { Text("SAO CHÉP") } },\n            dismissButton = { TextButton(onClick = { copyGlobalConfirm = false }) { Text("HỦY") } },\n        )\n    }\n    if (restorePrivateConfirm) {\n        AlertDialog(\n            onDismissRequest = { restorePrivateConfirm = false },\n            title = { Text("KHÔI PHỤC HỒ SƠ MẪU") },\n            text = { Text("Khôi phục tên và mô tả của 7 hồ sơ mẫu? Cấu hình âm thanh và các hồ sơ tùy chỉnh sẽ được giữ lại trong giới hạn 10 hồ sơ.") },\n            confirmButton = { TextButton(onClick = {\n                val currentByName = privateRoles.associateBy { it.roleName.trim().lowercase() }\n                globalRoles.forEach { global ->\n                    val old = currentByName[global.roleName.trim().lowercase()]\n                    val draft = global.toDraft(context).copy(\n                        originalRoleId = old?.id,\n                        enginePackage = old?.enginePackage ?: global.enginePackage,\n                        voiceName = old?.voiceName ?: global.voiceName,\n                        languageTag = old?.languageTag ?: global.languageTag,\n                        rate = old?.rate ?: global.rate,\n                        pitch = old?.pitch ?: global.pitch,\n                        volume = old?.volume ?: global.volume,\n                    )\n                    onSaveVoiceRole(draft)\n                }\n                restorePrivateConfirm = false\n            }) { Text("KHÔI PHỤC") } },\n            dismissButton = { TextButton(onClick = { restorePrivateConfirm = false }) { Text("HỦY") } },\n        )\n    }\n    deletePrivateRole?.let { role ->\n        AlertDialog(\n            onDismissRequest = { deletePrivateRole = null },\n            title = { Text("XÓA HỒ SƠ") },\n            text = { Text("Xóa hồ sơ “${role.roleName}”? Kết quả cũ dùng hồ sơ này sẽ trở về Người kể chuyện.") },\n            confirmButton = { TextButton(onClick = { onDeleteVoiceRole(role.id); deletePrivateRole = null }) { Text("XÓA") } },\n            dismissButton = { TextButton(onClick = { deletePrivateRole = null }) { Text("HỦY") } },\n        )\n    }\n\n'''
text = replace_once(text, '    if (showDownloadScopeDialog) {\n', confirm_block + '    if (showDownloadScopeDialog) {\n', "private voice confirmations")
# Update toDraft helper to load per-profile processing extras.
text = regex_once(
    text,
    r'''private fun VoiceRoleEntity\.toDraft\(\): VoiceRoleDraft = VoiceRoleDraft\(.*?\n\)\n''',
    '''private fun VoiceRoleEntity.toDraft(context: android.content.Context): VoiceRoleDraft {\n    val extra = ReferenceVoiceRoleExtras.load(context, id)\n    return VoiceRoleDraft(\n        roleName = roleName,\n        originalRoleId = id,\n        aliases = aliasesCsv,\n        description = description,\n        isNarrator = isNarrator,\n        enginePackage = enginePackage,\n        voiceName = voiceName,\n        languageTag = languageTag,\n        rate = rate,\n        pitch = pitch,\n        volume = volume,\n        expression = runCatching { VoiceExpression.valueOf(expression) }.getOrDefault(VoiceExpression.NEUTRAL),\n        expressionStrength = expressionStrength,\n        sonicSpeed = sonicSpeed,\n        sonicPitch = sonicPitch,\n        processingMethod = extra.processingMethod,\n        sonicAccurate = extra.sonicAccurate,\n        enabled = enabled,\n    )\n}\n''',
    "private toDraft extras",
)
write(path, text)

# ---------------------------------------------------------------------------
# Audio export: per-role method/quality and exact voice ranges.
# ---------------------------------------------------------------------------
path = "app/src/main/java/vn/nghetruyen/app/audio/AudioExportWorker.kt"
text = read(path)
text = replace_once(text, 'import vn.nghetruyen.app.playback.VoiceExpressionProcessor\n',
                    'import vn.nghetruyen.app.playback.VoiceExpressionProcessor\nimport vn.nghetruyen.app.ui.reference.ReferenceVoiceRoleExtras\n',
                    "export voice extras import")
text = replace_once(
    text,
    '''                    val voice = roleVoice.copy(\n                        rate = (roleVoice.rate * expression.rateMultiplier * aiRateMultiplier).coerceIn(0.5f, 2f),\n                        pitch = (roleVoice.pitch * expression.pitchMultiplier * aiPitchMultiplier).coerceIn(0.5f, 2f),\n                    )\n''',
    '''                    val voice = roleVoice.copy(\n                        rate = (roleVoice.rate * expression.rateMultiplier * aiRateMultiplier).coerceIn(0.25f, 3f),\n                        pitch = (roleVoice.pitch * expression.pitchMultiplier * aiPitchMultiplier).coerceIn(0.5f, 2f),\n                    )\n''',
    "export exact rate range",
)
text = replace_once(
    text,
    '''                    val volume = ((role?.volume ?: profile?.volume ?: settings.ttsVolume) * expression.volumeMultiplier * aiVolumeMultiplier)\n                        .coerceIn(0.05f, 1f)\n                    Pcm16WaveConverter.convert(rawOutput, pcmOutput, volume)\n                    val sonicSpeed = ((role?.sonicSpeed ?: settings.sonicDefaultSpeed) * expression.sonicSpeedMultiplier)\n                        .coerceIn(0.5f, 2f)\n                    val sonicPitch = ((role?.sonicPitch ?: settings.sonicDefaultPitch) * expression.sonicPitchMultiplier)\n                        .coerceIn(0.5f, 2f)\n                    if (settings.sonicProcessingEnabled &&\n                        (kotlin.math.abs(sonicSpeed - 1f) >= 0.015f || kotlin.math.abs(sonicPitch - 1f) >= 0.015f)\n                    ) {\n                        SonicPcmProcessor.process(pcmOutput, normalizedOutput, sonicSpeed, sonicPitch)\n                    } else {\n''',
    '''                    val roleExtra = role?.let { ReferenceVoiceRoleExtras.load(applicationContext, it.id) }\n                    val useSonic = roleExtra?.processingMethod?.equals("sonic") ?: settings.sonicProcessingEnabled\n                    val accurateSonic = roleExtra?.sonicAccurate ?: settings.sonicAccurateMode\n                    val volume = ((role?.volume ?: profile?.volume ?: settings.ttsVolume) * expression.volumeMultiplier * aiVolumeMultiplier)\n                        .coerceIn(0f, if (useSonic) 2f else 1f)\n                    Pcm16WaveConverter.convert(rawOutput, pcmOutput, volume)\n                    val sonicSpeed = if (useSonic) ((role?.sonicSpeed ?: settings.sonicDefaultSpeed) * expression.sonicSpeedMultiplier).coerceIn(0.25f, 3f) else 1f\n                    val sonicPitch = if (useSonic) ((role?.sonicPitch ?: settings.sonicDefaultPitch) * expression.sonicPitchMultiplier).coerceIn(0.5f, 2f) else 1f\n                    if (useSonic) {\n                        SonicPcmProcessor.process(pcmOutput, normalizedOutput, sonicSpeed, sonicPitch, accurateSonic)\n                    } else {\n''',
    "export role Sonic method and volume",
)
# Fingerprint must include extras so resumable export invalidates when profile method changes.
text = replace_once(
    text,
    '''        roles.sortedBy { it.id }.forEach {\n            add(listOf(\n                it.id, it.enginePackage, it.voiceName, it.languageTag, it.rate, it.pitch, it.volume,\n                it.expression, it.expressionStrength, it.sonicSpeed, it.sonicPitch, it.enabled,\n            ).joinToString("|"))\n        }\n''',
    '''        roles.sortedBy { it.id }.forEach {\n            val extra = ReferenceVoiceRoleExtras.load(applicationContext, it.id)\n            add(listOf(\n                it.id, it.enginePackage, it.voiceName, it.languageTag, it.rate, it.pitch, it.volume,\n                it.expression, it.expressionStrength, it.sonicSpeed, it.sonicPitch, it.enabled,\n                extra.processingMethod, extra.sonicAccurate,\n            ).joinToString("|"))\n        }\n''',
    "export fingerprint role extras",
)
write(path, text)

print("REFERENCE_PARITY_PHASE3_PATCH_OK")
