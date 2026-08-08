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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    onDraftChange: (VoiceRoleDraft) -> Unit,
    onPreview: (VoiceRoleDraft) -> Unit,
    onSave: (VoiceRoleDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var engineExpanded by remember { mutableStateOf(false) }
    var languageExpanded by remember { mutableStateOf(false) }
    var voiceExpanded by remember { mutableStateOf(false) }
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
            val activeTts = tts
            val loaded = if (status == TextToSpeech.SUCCESS && activeTts != null) {
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
            mainHandler.post {
                if (!disposed) {
                    voices = loaded
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.originalRoleId == null) "THÊM GIỌNG" else "SỬA HỒ SƠ GIỌNG") },
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
                    label = { Text("Tên vai") },
                    enabled = !draft.isNarrator,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.description,
                    onValueChange = { onDraftChange(draft.copy(description = it.take(1_000))) },
                    label = { Text("Mô tả") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                )
                OutlinedTextField(
                    value = draft.aliases,
                    onValueChange = { onDraftChange(draft.copy(aliases = it.take(500))) },
                    label = { Text("Bí danh") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                )

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

                Text("Giọng", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 7.dp))
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

                Text("Xử lý", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            onDraftChange(
                                draft.copy(
                                    processingMethod = "system",
                                    volume = draft.volume.coerceAtMost(1f),
                                ),
                            )
                        },
                        modifier = Modifier.weight(1f).padding(end = 2.dp),
                    ) {
                        Text((if (draft.processingMethod != "sonic") "✓ " else "") + "HỆ THỐNG")
                    }
                    Button(
                        onClick = { onDraftChange(draft.copy(processingMethod = "sonic")) },
                        modifier = Modifier.weight(1f).padding(start = 2.dp),
                    ) {
                        Text((if (draft.processingMethod == "sonic") "✓ " else "") + "SONIC")
                    }
                }

                if (draft.processingMethod == "sonic") {
                    Row(Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { onDraftChange(draft.copy(sonicAccurate = false)) },
                            modifier = Modifier.weight(1f).padding(end = 2.dp),
                        ) {
                            Text((if (!draft.sonicAccurate) "✓ " else "") + "NHANH")
                        }
                        Button(
                            onClick = { onDraftChange(draft.copy(sonicAccurate = true)) },
                            modifier = Modifier.weight(1f).padding(start = 2.dp),
                        ) {
                            Text((if (draft.sonicAccurate) "✓ " else "") + "CHÍNH XÁC")
                        }
                    }
                }

                CompactVoiceValueRow("Tốc độ TTS", draft.rate, 0.25f, 3f) {
                    onDraftChange(draft.copy(rate = it))
                }
                CompactVoiceValueRow("Cao độ TTS", draft.pitch, 0.5f, 2f) {
                    onDraftChange(draft.copy(pitch = it))
                }
                CompactVoiceValueRow(
                    label = "Âm lượng",
                    value = draft.volume,
                    minimum = 0f,
                    maximum = if (draft.processingMethod == "sonic") 2f else 1f,
                    percent = true,
                ) {
                    onDraftChange(draft.copy(volume = it))
                }

                if (draft.processingMethod == "sonic") {
                    CompactVoiceValueRow("Tốc độ Sonic", draft.sonicSpeed, 0.25f, 3f) {
                        onDraftChange(draft.copy(sonicSpeed = it))
                    }
                    CompactVoiceValueRow("Cao độ Sonic", draft.sonicPitch, 0.5f, 2f) {
                        onDraftChange(draft.copy(sonicPitch = it))
                    }
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
                enabled = draft.roleName.isNotBlank(),
                onClick = { onSave(draft) },
            ) {
                Text("LƯU")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("HỦY") } },
    )
}

@Composable
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
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(
            if (percent) "$label ${"%.0f".format(safeValue * 100)}%" else "$label ${"%.2f".format(safeValue)}×",
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onChange((safeValue - step).coerceAtLeast(minimum)) }) { Text("−") }
        TextButton(onClick = { onChange((safeValue + step).coerceAtMost(maximum)) }) { Text("+") }
    }
}
