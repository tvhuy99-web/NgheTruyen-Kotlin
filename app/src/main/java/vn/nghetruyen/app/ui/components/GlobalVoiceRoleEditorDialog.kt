package vn.nghetruyen.app.ui.components

import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import vn.nghetruyen.app.core.model.TtsEngineOption
import vn.nghetruyen.app.core.model.TtsVoiceOption
import vn.nghetruyen.app.core.model.VoiceRoleDraft
import vn.nghetruyen.app.ui.reference.ReferenceVoiceRoleExtras

@Composable
fun GlobalVoiceRoleEditorDialog(
    draft: VoiceRoleDraft,
    engines: List<TtsEngineOption>,
    title: String? = null,
    onDraftChange: (VoiceRoleDraft) -> Unit,
    onPreview: (VoiceRoleDraft) -> Unit,
    onSave: (VoiceRoleDraft) -> Unit,
    onDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var engineExpanded by remember { mutableStateOf(false) }
    var languageExpanded by remember { mutableStateOf(false) }
    var voiceExpanded by remember { mutableStateOf(false) }
    var processingExpanded by remember { mutableStateOf(false) }
    var sonicQualityExpanded by remember { mutableStateOf(false) }
    var voices by remember(draft.enginePackage) { mutableStateOf<List<TtsVoiceOption>>(emptyList()) }
    var loadingVoices by remember(draft.enginePackage) { mutableStateOf(true) }

    LaunchedEffect(draft.originalRoleId) {
        draft.originalRoleId?.let { roleId ->
            val extra = ReferenceVoiceRoleExtras.load(context, roleId)
            if (draft.processingMethod != extra.processingMethod || draft.sonicAccurate != extra.sonicAccurate) {
                onDraftChange(
                    draft.copy(
                        processingMethod = extra.processingMethod,
                        sonicAccurate = extra.sonicAccurate,
                    ),
                )
            }
        }
    }

    DisposableEffect(draft.enginePackage) {
        val mainHandler = Handler(Looper.getMainLooper())
        var disposed = false
        var tts: TextToSpeech? = null
        loadingVoices = true
        voices = emptyList()
        val listener = TextToSpeech.OnInitListener { status ->
            mainHandler.post {
                if (!disposed) {
                    val activeTts = tts
                    voices = if (status == TextToSpeech.SUCCESS && activeTts != null) {
                        activeTts.voices.orEmpty()
                            .map { voice ->
                                TtsVoiceOption(
                                    name = voice.name,
                                    displayName = voice.name,
                                    languageTag = voice.locale?.toLanguageTag().orEmpty(),
                                    networkRequired = voice.isNetworkConnectionRequired,
                                    quality = voice.quality,
                                    enginePackage = draft.enginePackage,
                                )
                            }
                            .sortedWith(
                                compareBy<TtsVoiceOption> { it.networkRequired }
                                    .thenByDescending { it.quality }
                                    .thenBy { it.displayName.lowercase() },
                            )
                    } else {
                        emptyList()
                    }
                    loadingVoices = false
                }
            }
        }
        tts = draft.enginePackage?.takeIf(String::isNotBlank)?.let { packageName ->
            TextToSpeech(context.applicationContext, listener, packageName)
        } ?: TextToSpeech(context.applicationContext, listener)

        onDispose {
            disposed = true
            mainHandler.removeCallbacksAndMessages(null)
            tts?.runCatching { stop() }
            tts?.runCatching { shutdown() }
            tts = null
        }
    }

    val engineLabel = engines.firstOrNull { it.packageName == draft.enginePackage }?.label
        ?: draft.enginePackage
        ?: "Mặc định hệ thống"
    val languages = (voices.map(TtsVoiceOption::languageTag) + draft.languageTag)
        .filter(String::isNotBlank)
        .distinct()
        .sorted()
    val filteredVoices = voices.filter { voice ->
        draft.languageTag.isBlank() || voice.languageTag == draft.languageTag
    }
    val voiceLabel = filteredVoices.firstOrNull { it.name == draft.voiceName }?.displayName
        ?: draft.voiceName
        ?: "Giọng mặc định"
    val dialogTitle = title ?: "HỒ SƠ GIỌNG TTS"
    val sonicSelected = draft.processingMethod == "sonic"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dialogTitle) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = draft.roleName,
                    onValueChange = { value ->
                        if (!draft.isNarrator) onDraftChange(draft.copy(roleName = value.take(80)))
                    },
                    label = { Text("Tên vai hoặc tên nhân vật") },
                    enabled = !draft.isNarrator,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.description,
                    onValueChange = { onDraftChange(draft.copy(description = it.take(1_000))) },
                    label = { Text("Mô tả để AI nhận biết") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (draft.isNarrator) "Người kể chuyện luôn được bật" else "Bật hồ sơ này", Modifier.weight(1f))
                    Switch(
                        checked = if (draft.isNarrator) true else draft.enabled,
                        onCheckedChange = { if (!draft.isNarrator) onDraftChange(draft.copy(enabled = it)) },
                        enabled = !draft.isNarrator,
                    )
                }

                Text("Bộ đọc TTS", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                Box(Modifier.fillMaxWidth()) {
                    Button(onClick = { engineExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(engineLabel)
                    }
                    DropdownMenu(expanded = engineExpanded, onDismissRequest = { engineExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Mặc định hệ thống") },
                            onClick = {
                                engineExpanded = false
                                onDraftChange(draft.copy(enginePackage = null, voiceName = null))
                            },
                        )
                        engines.forEach { engine ->
                            DropdownMenuItem(
                                text = { Text(engine.label) },
                                onClick = {
                                    engineExpanded = false
                                    onDraftChange(draft.copy(enginePackage = engine.packageName, voiceName = null))
                                },
                            )
                        }
                    }
                }

                Text("Ngôn ngữ", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 7.dp))
                Box(Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { languageExpanded = true },
                        enabled = languages.isNotEmpty() && !loadingVoices,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (loadingVoices) "ĐANG QUÉT…" else draft.languageTag.ifBlank { "vi-VN" })
                    }
                    DropdownMenu(expanded = languageExpanded, onDismissRequest = { languageExpanded = false }) {
                        languages.forEach { language ->
                            DropdownMenuItem(
                                text = { Text(language) },
                                onClick = {
                                    languageExpanded = false
                                    onDraftChange(draft.copy(languageTag = language, voiceName = null))
                                },
                            )
                        }
                    }
                }

                Text("Giọng nói", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 7.dp))
                Box(Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { voiceExpanded = true },
                        enabled = !loadingVoices,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (loadingVoices) "ĐANG QUÉT…" else voiceLabel)
                    }
                    DropdownMenu(expanded = voiceExpanded, onDismissRequest = { voiceExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Giọng mặc định") },
                            onClick = {
                                voiceExpanded = false
                                onDraftChange(draft.copy(voiceName = null))
                            },
                        )
                        filteredVoices.forEach { voice ->
                            DropdownMenuItem(
                                text = { Text(voice.displayName) },
                                onClick = {
                                    voiceExpanded = false
                                    onDraftChange(
                                        draft.copy(
                                            voiceName = voice.name,
                                            languageTag = voice.languageTag,
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }

                Text("Phương pháp xử lý", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                Box(Modifier.fillMaxWidth()) {
                    Button(onClick = { processingExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (sonicSelected) "Sonic, tối đa 200%" else "Android, tối đa 100%")
                    }
                    DropdownMenu(expanded = processingExpanded, onDismissRequest = { processingExpanded = false }) {
                        DropdownMenuItem(text = { Text("Android, tối đa 100%") }, onClick = {
                            processingExpanded = false
                            onDraftChange(draft.copy(processingMethod = "system", volume = draft.volume.coerceAtMost(1f)))
                        })
                        DropdownMenuItem(text = { Text("Sonic, tối đa 200%") }, onClick = {
                            processingExpanded = false
                            onDraftChange(draft.copy(processingMethod = "sonic"))
                        })
                    }
                }

                if (sonicSelected) {
                    Text("Chế độ Sonic", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 7.dp))
                    Box(Modifier.fillMaxWidth()) {
                        Button(onClick = { sonicQualityExpanded = true }, modifier = Modifier.fillMaxWidth()) { Text(if (draft.sonicAccurate) "Chính xác" else "Nhanh") }
                        DropdownMenu(expanded = sonicQualityExpanded, onDismissRequest = { sonicQualityExpanded = false }) {
                            DropdownMenuItem(text = { Text("Nhanh") }, onClick = { sonicQualityExpanded = false; onDraftChange(draft.copy(sonicAccurate = false)) })
                            DropdownMenuItem(text = { Text("Chính xác") }, onClick = { sonicQualityExpanded = false; onDraftChange(draft.copy(sonicAccurate = true)) })
                        }
                    }
                }

                CompactVoiceValueRow(
                    "Tốc độ đọc",
                    if (sonicSelected) draft.sonicSpeed else draft.rate,
                    0.25f,
                    3f,
                ) { value ->
                    onDraftChange(if (sonicSelected) draft.copy(sonicSpeed = value) else draft.copy(rate = value))
                }
                CompactVoiceValueRow(
                    "Cao độ",
                    if (sonicSelected) draft.sonicPitch else draft.pitch,
                    0.5f,
                    2f,
                ) { value ->
                    onDraftChange(if (sonicSelected) draft.copy(sonicPitch = value) else draft.copy(pitch = value))
                }
                CompactVoiceValueRow(
                    label = "Âm lượng",
                    value = draft.volume,
                    minimum = 0f,
                    maximum = if (sonicSelected) 2f else 1f,
                    percent = true,
                ) {
                    onDraftChange(draft.copy(volume = it))
                }

                Button(
                    onClick = { onPreview(draft) },
                    modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
                ) {
                    Text("NGHE THỬ")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = draft.roleName.isNotBlank() && draft.description.isNotBlank(),
                onClick = { onSave(draft.copy(enabled = if (draft.isNarrator) true else draft.enabled)) },
            ) {
                Text("LƯU HỒ SƠ")
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null && !draft.isNarrator && draft.originalRoleId != null) {
                    TextButton(onClick = onDelete) { Text("XÓA HỒ SƠ") }
                }
                TextButton(onClick = onDismiss) { Text("HỦY") }
            }
        },
    )
}

@Composable
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
    val intervals = when {
        percent && maximum <= 1f -> 100
        percent -> 200
        minimum == 0.25f && maximum == 3f -> 275
        minimum == 0.5f && maximum == 2f -> 150
        else -> 100
    }
    val spoken = if (percent) "$label ${"%.0f".format(safeValue * 100)}%" else "$label ${"%.2f".format(safeValue)}×"
    Slider(
        value = safeValue,
        onValueChange = { onChange(it.coerceIn(minimum, maximum)) },
        valueRange = minimum..maximum,
        steps = (intervals - 1).coerceAtLeast(0),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = spoken },
    )
}
