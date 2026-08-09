#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one occurrence, found {count}: {old[:140]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


personal = "app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt"
replace_once(
    personal,
    '''    var showAllVoices by remember { mutableStateOf(false) }
    var showAllEngines by remember { mutableStateOf(false) }
    val visibleVoices = if (showAllVoices) voices else voices.take(MAX_VISIBLE_VOICES)
    val visibleEngines = if (showAllEngines) engines else engines.take(6)
''',
    '''    var engineExpanded by remember { mutableStateOf(false) }
    var voiceExpanded by remember { mutableStateOf(false) }
    val selectedEngineLabel = engines.firstOrNull { it.packageName == selectedEnginePackage }?.label ?: "Mặc định hệ thống"
    val selectedVoiceLabel = voices.firstOrNull { it.name == selectedVoiceName }?.displayName ?: "Giọng mặc định"
''',
)
replace_once(
    personal,
    '''            Text("Bộ máy TTS", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
            Button({ onEngineSelected(null) }, Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(if (selectedEnginePackage == null) "✓ MẶC ĐỊNH HỆ THỐNG" else "MẶC ĐỊNH HỆ THỐNG")
            }
            visibleEngines.forEach { engine ->
                Button({ onEngineSelected(engine) }, Modifier.fillMaxWidth().padding(top = 3.dp)) {
                    Text((if (engine.packageName == selectedEnginePackage) "✓ " else "") + engine.label)
                }
            }
            if (engines.size > 6) Button({ showAllEngines = !showAllEngines }, Modifier.fillMaxWidth().padding(top = 3.dp)) {
                Text(if (showAllEngines) "THU GỌN BỘ MÁY" else "HIỂN THỊ TẤT CẢ ${engines.size} BỘ MÁY")
            }
            Text("Giọng TTS", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
            Button({ onVoiceSelected(null) }, Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(if (selectedVoiceName == null) "✓ GIỌNG MẶC ĐỊNH" else "GIỌNG MẶC ĐỊNH")
            }
            visibleVoices.forEach { voice ->
                Button({ onVoiceSelected(voice) }, Modifier.fillMaxWidth().padding(top = 3.dp)) {
                    Text((if (voice.name == selectedVoiceName) "✓ " else "") + voice.displayName)
                }
            }
            if (voices.size > MAX_VISIBLE_VOICES) {
                Button({ showAllVoices = !showAllVoices }, Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(if (showAllVoices) "THU GỌN GIỌNG" else "HIỂN THỊ TẤT CẢ ${voices.size} GIỌNG")
                }
            }
''',
    '''            Text("Bộ đọc TTS", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
            Box(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Button(onClick = { engineExpanded = true }, modifier = Modifier.fillMaxWidth()) { Text(selectedEngineLabel) }
                DropdownMenu(expanded = engineExpanded, onDismissRequest = { engineExpanded = false }) {
                    DropdownMenuItem(text = { Text("Mặc định hệ thống") }, onClick = { engineExpanded = false; onEngineSelected(null) })
                    engines.forEach { engine ->
                        DropdownMenuItem(text = { Text(engine.label) }, onClick = { engineExpanded = false; onEngineSelected(engine) })
                    }
                }
            }
            Text("Giọng đọc", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
            Box(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Button(onClick = { voiceExpanded = true }, enabled = !loadingVoices, modifier = Modifier.fillMaxWidth()) {
                    Text(if (loadingVoices) "ĐANG QUÉT…" else selectedVoiceLabel)
                }
                DropdownMenu(expanded = voiceExpanded, onDismissRequest = { voiceExpanded = false }) {
                    DropdownMenuItem(text = { Text("Giọng mặc định") }, onClick = { voiceExpanded = false; onVoiceSelected(null) })
                    voices.forEach { voice ->
                        DropdownMenuItem(text = { Text(voice.displayName) }, onClick = { voiceExpanded = false; onVoiceSelected(voice) })
                    }
                }
            }
''',
)
# Use reference SeekBar step sizes for speed/pitch where these settings remain available.
replace_once(personal, 'maximum = 3f,\n                shown = { "%.2f×".format(it) },\n                onChange = onRateChange,', 'maximum = 3f,\n                steps = 274,\n                shown = { "%.2f×".format(it) },\n                onChange = onRateChange,')
replace_once(personal, 'maximum = 2f,\n                shown = { "%.2f×".format(it) },\n                onChange = onPitchChange,', 'maximum = 2f,\n                steps = 149,\n                shown = { "%.2f×".format(it) },\n                onChange = onPitchChange,')
# Sonic default uses the same numeric domains as the reference TTS controls.
text = Path(personal).read_text(encoding="utf-8")
text = text.replace('label = "Tốc độ Sonic mặc định",\n                value = state.sonicDefaultSpeed,\n                minimum = 0.25f,\n                maximum = 3f,\n                shown =', 'label = "Tốc độ Sonic mặc định",\n                value = state.sonicDefaultSpeed,\n                minimum = 0.25f,\n                maximum = 3f,\n                steps = 274,\n                shown =', 1)
text = text.replace('label = "Cao độ Sonic mặc định",\n                value = state.sonicDefaultPitch,\n                minimum = 0.5f,\n                maximum = 2f,\n                shown =', 'label = "Cao độ Sonic mặc định",\n                value = state.sonicDefaultPitch,\n                minimum = 0.5f,\n                maximum = 2f,\n                steps = 149,\n                shown =', 1)
Path(personal).write_text(text, encoding="utf-8")

# Scene music mode is a Spinner in the reference; use a single dropdown selector rather than stacked mode buttons.
replace_once(
    personal,
    ') {\n    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) {\n        Column(Modifier.padding(16.dp)) {\n            Text("Tai nghe & tự động",',
    ') {\n    var sceneModeExpanded by remember { mutableStateOf(false) }\n    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) {\n        Column(Modifier.padding(16.dp)) {\n            Text("Tai nghe & tự động",',
)
replace_once(
    personal,
    '''            Text("Chế độ nhạc cảnh", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            SceneMusicPlaybackMode.entries.forEach { mode ->
                Button({ onSceneMusicPlaybackModeChange(mode) }, Modifier.fillMaxWidth().padding(top = 2.dp)) {
                    val label = when (mode) {
                        SceneMusicPlaybackMode.SEQUENTIAL -> "TUẦN TỰ"
                        SceneMusicPlaybackMode.SHUFFLE -> "NGẪU NHIÊN"
                        SceneMusicPlaybackMode.SMART_AVOID_REPEAT -> "TRÁNH LẶP"
                    }
                    Text((if (state.sceneMusicPlaybackMode == mode) "✓ " else "") + label)
                }
            }
''',
    '''            Text("Chế độ phát", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            Box(Modifier.fillMaxWidth()) {
                val currentModeLabel = when (state.sceneMusicPlaybackMode) {
                    SceneMusicPlaybackMode.SEQUENTIAL -> "Lần lượt"
                    SceneMusicPlaybackMode.SHUFFLE -> "Ngẫu nhiên"
                    SceneMusicPlaybackMode.SMART_AVOID_REPEAT -> "Tránh lặp"
                }
                Button(onClick = { sceneModeExpanded = true }, modifier = Modifier.fillMaxWidth()) { Text(currentModeLabel) }
                DropdownMenu(expanded = sceneModeExpanded, onDismissRequest = { sceneModeExpanded = false }) {
                    SceneMusicPlaybackMode.entries.forEach { mode ->
                        val label = when (mode) {
                            SceneMusicPlaybackMode.SEQUENTIAL -> "Lần lượt"
                            SceneMusicPlaybackMode.SHUFFLE -> "Ngẫu nhiên"
                            SceneMusicPlaybackMode.SMART_AVOID_REPEAT -> "Tránh lặp"
                        }
                        DropdownMenuItem(text = { Text(label) }, onClick = { sceneModeExpanded = false; onSceneMusicPlaybackModeChange(mode) })
                    }
                }
            }
''',
)

reader = "app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt"
replace_once(
    reader,
    '''        var engineExpanded by remember { mutableStateOf(false) }
        var languageExpanded by remember { mutableStateOf(false) }
        var voiceExpanded by remember { mutableStateOf(false) }
''',
    '''        var engineExpanded by remember { mutableStateOf(false) }
        var languageExpanded by remember { mutableStateOf(false) }
        var voiceExpanded by remember { mutableStateOf(false) }
        var processingExpanded by remember { mutableStateOf(false) }
        var sonicQualityExpanded by remember { mutableStateOf(false) }
''',
)
replace_once(
    reader,
    '''                Text("Phương pháp xử lý", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.fillMaxWidth()) {
                    TextButton({ ttsDraft = ttsDraft.copy(processingMethod = "system", volume = ttsDraft.volume.coerceAtMost(1f)) }, Modifier.weight(1f)) { Text((if (ttsDraft.processingMethod == "system") "✓ " else "") + "Android, tối đa 100%") }
                    TextButton({ ttsDraft = ttsDraft.copy(processingMethod = "sonic") }, Modifier.weight(1f)) { Text((if (ttsDraft.processingMethod == "sonic") "✓ " else "") + "Sonic, tối đa 200%") }
                }
                if (ttsDraft.processingMethod == "sonic") {
                    Text("Chế độ Sonic", fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth()) {
                        TextButton({ ttsDraft = ttsDraft.copy(sonicAccurate = false) }, Modifier.weight(1f)) { Text((if (!ttsDraft.sonicAccurate) "✓ " else "") + "Nhanh") }
                        TextButton({ ttsDraft = ttsDraft.copy(sonicAccurate = true) }, Modifier.weight(1f)) { Text((if (ttsDraft.sonicAccurate) "✓ " else "") + "Chính xác") }
                    }
                }
''',
    '''                Text("Phương pháp xử lý", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                Box(Modifier.fillMaxWidth()) {
                    Button(onClick = { processingExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (ttsDraft.processingMethod == "sonic") "Sonic, tối đa 200%" else "Android, tối đa 100%")
                    }
                    DropdownMenu(expanded = processingExpanded, onDismissRequest = { processingExpanded = false }) {
                        DropdownMenuItem(text = { Text("Android, tối đa 100%") }, onClick = { processingExpanded = false; ttsDraft = ttsDraft.copy(processingMethod = "system", volume = ttsDraft.volume.coerceAtMost(1f)) })
                        DropdownMenuItem(text = { Text("Sonic, tối đa 200%") }, onClick = { processingExpanded = false; ttsDraft = ttsDraft.copy(processingMethod = "sonic") })
                    }
                }
                if (ttsDraft.processingMethod == "sonic") {
                    Text("Chế độ Sonic", fontWeight = FontWeight.SemiBold)
                    Box(Modifier.fillMaxWidth()) {
                        Button(onClick = { sonicQualityExpanded = true }, modifier = Modifier.fillMaxWidth()) { Text(if (ttsDraft.sonicAccurate) "Chính xác" else "Nhanh") }
                        DropdownMenu(expanded = sonicQualityExpanded, onDismissRequest = { sonicQualityExpanded = false }) {
                            DropdownMenuItem(text = { Text("Nhanh") }, onClick = { sonicQualityExpanded = false; ttsDraft = ttsDraft.copy(sonicAccurate = false) })
                            DropdownMenuItem(text = { Text("Chính xác") }, onClick = { sonicQualityExpanded = false; ttsDraft = ttsDraft.copy(sonicAccurate = true) })
                        }
                    }
                }
''',
)
# Music mode uses a Spinner in the XPK; preserve Kotlin's third mode but use the same selector shape.
replace_once(
    reader,
    '''    if (showMusicDialog) {
        AlertDialog(
''',
    '''    if (showMusicDialog) {
        var musicModeExpanded by remember { mutableStateOf(false) }
        AlertDialog(
''',
)
replace_once(
    reader,
    '''                        Text("Chế độ phát", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                        Row(Modifier.fillMaxWidth()) {
                            TextButton({ musicMode = SceneMusicPlaybackMode.SEQUENTIAL }, Modifier.weight(1f)) { Text((if (musicMode == SceneMusicPlaybackMode.SEQUENTIAL) "✓ " else "") + "LẦN LƯỢT") }
                            TextButton({ musicMode = SceneMusicPlaybackMode.SHUFFLE }, Modifier.weight(1f)) { Text((if (musicMode == SceneMusicPlaybackMode.SHUFFLE) "✓ " else "") + "NGẪU NHIÊN") }
                            TextButton({ musicMode = SceneMusicPlaybackMode.SMART_AVOID_REPEAT }, Modifier.weight(1f)) { Text((if (musicMode == SceneMusicPlaybackMode.SMART_AVOID_REPEAT) "✓ " else "") + "TRÁNH LẶP") }
                        }
''',
    '''                        Text("Chế độ phát khi không dùng nhạc theo cảnh", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                        Box(Modifier.fillMaxWidth()) {
                            val currentModeLabel = when (musicMode) {
                                SceneMusicPlaybackMode.SEQUENTIAL -> "Lần lượt"
                                SceneMusicPlaybackMode.SHUFFLE -> "Ngẫu nhiên"
                                SceneMusicPlaybackMode.SMART_AVOID_REPEAT -> "Tránh lặp"
                            }
                            Button(onClick = { musicModeExpanded = true }, modifier = Modifier.fillMaxWidth()) { Text(currentModeLabel) }
                            DropdownMenu(expanded = musicModeExpanded, onDismissRequest = { musicModeExpanded = false }) {
                                SceneMusicPlaybackMode.entries.forEach { mode ->
                                    val label = when (mode) {
                                        SceneMusicPlaybackMode.SEQUENTIAL -> "Lần lượt"
                                        SceneMusicPlaybackMode.SHUFFLE -> "Ngẫu nhiên"
                                        SceneMusicPlaybackMode.SMART_AVOID_REPEAT -> "Tránh lặp"
                                    }
                                    DropdownMenuItem(text = { Text(label) }, onClick = { musicMode = mode; musicModeExpanded = false })
                                }
                            }
                        }
''',
)

global_role = "app/src/main/java/vn/nghetruyen/app/ui/components/GlobalVoiceRoleEditorDialog.kt"
replace_once(
    global_role,
    '''    var engineExpanded by remember { mutableStateOf(false) }
    var languageExpanded by remember { mutableStateOf(false) }
    var voiceExpanded by remember { mutableStateOf(false) }
''',
    '''    var engineExpanded by remember { mutableStateOf(false) }
    var languageExpanded by remember { mutableStateOf(false) }
    var voiceExpanded by remember { mutableStateOf(false) }
    var processingExpanded by remember { mutableStateOf(false) }
    var sonicQualityExpanded by remember { mutableStateOf(false) }
''',
)
replace_once(
    global_role,
    '''                Text("Phương pháp xử lý", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
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
''',
    '''                Text("Phương pháp xử lý", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                Box(Modifier.fillMaxWidth()) {
                    Button(onClick = { processingExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (draft.processingMethod == "sonic") "Sonic, tối đa 200%" else "Android, tối đa 100%")
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

                if (draft.processingMethod == "sonic") {
                    Text("Chế độ Sonic", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 7.dp))
                    Box(Modifier.fillMaxWidth()) {
                        Button(onClick = { sonicQualityExpanded = true }, modifier = Modifier.fillMaxWidth()) { Text(if (draft.sonicAccurate) "Chính xác" else "Nhanh") }
                        DropdownMenu(expanded = sonicQualityExpanded, onDismissRequest = { sonicQualityExpanded = false }) {
                            DropdownMenuItem(text = { Text("Nhanh") }, onClick = { sonicQualityExpanded = false; onDraftChange(draft.copy(sonicAccurate = false)) })
                            DropdownMenuItem(text = { Text("Chính xác") }, onClick = { sonicQualityExpanded = false; onDraftChange(draft.copy(sonicAccurate = true)) })
                        }
                    }
                }
''',
)
# Exact SeekBar increments and an explicit spoken label for role sliders.
replace_once(
    global_role,
    'import androidx.compose.ui.platform.LocalContext\nimport androidx.compose.ui.text.font.FontWeight\n',
    'import androidx.compose.ui.platform.LocalContext\nimport androidx.compose.ui.semantics.contentDescription\nimport androidx.compose.ui.semantics.semantics\nimport androidx.compose.ui.text.font.FontWeight\n',
)
replace_once(
    global_role,
    '''    Slider(
        value = safeValue,
        onValueChange = { onChange(it.coerceIn(minimum, maximum)) },
        valueRange = minimum..maximum,
        modifier = Modifier.fillMaxWidth(),
    )
''',
    '''    val intervals = when {
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
''',
)

print("SELECTOR_PARITY_APPLIED")
