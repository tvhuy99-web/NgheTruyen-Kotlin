#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one occurrence, found {count}: {old[:100]!r}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


# Personal settings: every continuous/ranged numeric setting uses a slider.
personal = "app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt"
replace_once(
    personal,
    "import androidx.compose.material3.OutlinedTextField\nimport androidx.compose.material3.Switch\n",
    "import androidx.compose.material3.OutlinedTextField\nimport androidx.compose.material3.Slider\nimport androidx.compose.material3.Switch\n",
)
replace_once(
    personal,
    "import androidx.compose.ui.platform.LocalView\nimport androidx.compose.ui.text.font.FontWeight\n",
    "import androidx.compose.ui.platform.LocalView\nimport androidx.compose.ui.semantics.contentDescription\nimport androidx.compose.ui.semantics.semantics\nimport androidx.compose.ui.text.font.FontWeight\n",
)

replace_once(
    personal,
    '''            Text("Chuẩn bị trước: ${state.narrationPrefetchWindowChapters} chương", modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.fillMaxWidth()) {
                Button({ onNarrationPrefetchWindowChange(state.narrationPrefetchWindowChapters - 1) }, Modifier.weight(1f).padding(2.dp)) { Text("ÍT HƠN") }
                Button({ onNarrationPrefetchWindowChange(state.narrationPrefetchWindowChapters + 1) }, Modifier.weight(1f).padding(2.dp)) { Text("NHIỀU HƠN") }
            }
            SettingSwitch("Dùng Sonic", state.sonicProcessingEnabled, onSonicProcessingEnabledChange)
            Text("Sonic mặc định: tốc độ ${"%.2f".format(state.sonicDefaultSpeed)}× • cao độ ${"%.2f".format(state.sonicDefaultPitch)}×")
            Row(Modifier.fillMaxWidth()) {
                Button({ onSonicDefaultSpeedChange(state.sonicDefaultSpeed - 0.05f) }, Modifier.weight(1f).padding(2.dp)) { Text("TỐC ĐỘ -") }
                Button({ onSonicDefaultSpeedChange(state.sonicDefaultSpeed + 0.05f) }, Modifier.weight(1f).padding(2.dp)) { Text("TỐC ĐỘ +") }
            }
            Row(Modifier.fillMaxWidth()) {
                Button({ onSonicDefaultPitchChange(state.sonicDefaultPitch - 0.05f) }, Modifier.weight(1f).padding(2.dp)) { Text("CAO ĐỘ -") }
                Button({ onSonicDefaultPitchChange(state.sonicDefaultPitch + 0.05f) }, Modifier.weight(1f).padding(2.dp)) { Text("CAO ĐỘ +") }
            }
''',
    '''            ReferenceIntSettingsSlider(
                label = "Chuẩn bị trước",
                value = state.narrationPrefetchWindowChapters,
                minimum = 1,
                maximum = 5,
                suffix = " chương",
                onChange = onNarrationPrefetchWindowChange,
            )
            SettingSwitch("Dùng Sonic", state.sonicProcessingEnabled, onSonicProcessingEnabledChange)
            ReferenceFloatSettingsSlider(
                label = "Tốc độ Sonic mặc định",
                value = state.sonicDefaultSpeed,
                minimum = 0.25f,
                maximum = 3f,
                shown = { "%.2f×".format(it) },
                onChange = onSonicDefaultSpeedChange,
            )
            ReferenceFloatSettingsSlider(
                label = "Cao độ Sonic mặc định",
                value = state.sonicDefaultPitch,
                minimum = 0.5f,
                maximum = 2f,
                shown = { "%.2f×".format(it) },
                onChange = onSonicDefaultPitchChange,
            )
''',
)
replace_once(
    personal,
    '''            SettingSwitch("Chuẩn hóa âm lượng", state.normalizeTtsVolumeEnabled, onNormalizeTtsVolumeChange)
            Text("Mức giọng mục tiêu: ${"%.1f".format(state.ttsTargetLufs)} LUFS")
            Row(Modifier.fillMaxWidth()) {
                Button({ onTtsTargetLufsChange(state.ttsTargetLufs - 1f) }, Modifier.weight(1f).padding(2.dp)) { Text("GIỌNG NHỎ") }
                Button({ onTtsTargetLufsChange(state.ttsTargetLufs + 1f) }, Modifier.weight(1f).padding(2.dp)) { Text("GIỌNG LỚN") }
            }
''',
    '''            SettingSwitch("Chuẩn hóa âm lượng", state.normalizeTtsVolumeEnabled, onNormalizeTtsVolumeChange)
            ReferenceFloatSettingsSlider(
                label = "Mức giọng mục tiêu",
                value = state.ttsTargetLufs,
                minimum = -36f,
                maximum = -12f,
                steps = 23,
                shown = { "%.1f LUFS".format(it) },
                onChange = onTtsTargetLufsChange,
            )
''',
)
replace_once(
    personal,
    '''            Text("Nhạc: ${"%.1f".format(state.sceneMusicTargetLufs)} LUFS")
            Row(Modifier.fillMaxWidth()) {
                Button({ onSceneMusicTargetLufsChange(state.sceneMusicTargetLufs - 1f) }, Modifier.weight(1f).padding(2.dp)) { Text("NHỎ HƠN") }
                Button({ onSceneMusicTargetLufsChange(state.sceneMusicTargetLufs + 1f) }, Modifier.weight(1f).padding(2.dp)) { Text("LỚN HƠN") }
            }
            Text("Tránh lặp: ${state.sceneMusicAvoidRepeatWindow} bài")
            Row(Modifier.fillMaxWidth()) {
                Button({ onSceneMusicAvoidRepeatWindowChange(state.sceneMusicAvoidRepeatWindow - 1) }, Modifier.weight(1f).padding(2.dp)) { Text("GIẢM") }
                Button({ onSceneMusicAvoidRepeatWindowChange(state.sceneMusicAvoidRepeatWindow + 1) }, Modifier.weight(1f).padding(2.dp)) { Text("TĂNG") }
            }
            Text("Crossfade: ${state.sceneMusicCrossfadeMillis} ms", modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth()) {
                Button(
                    onClick = { onSceneMusicCrossfadeChange((state.sceneMusicCrossfadeMillis - 400).coerceAtLeast(0)) },
                    modifier = Modifier.weight(1f).padding(end = 2.dp),
                ) { Text("-400 ms") }
                Button(
                    onClick = { onSceneMusicCrossfadeChange((state.sceneMusicCrossfadeMillis + 400).coerceAtMost(8_000)) },
                    modifier = Modifier.weight(1f).padding(start = 2.dp),
                ) { Text("+400 ms") }
            }
''',
    '''            ReferenceFloatSettingsSlider(
                label = "Mức chuẩn hóa nhạc",
                value = state.sceneMusicTargetLufs,
                minimum = -36f,
                maximum = -18f,
                steps = 17,
                shown = { "%.1f LUFS".format(it) },
                onChange = onSceneMusicTargetLufsChange,
            )
            ReferenceIntSettingsSlider(
                label = "Tránh lặp",
                value = state.sceneMusicAvoidRepeatWindow,
                minimum = 0,
                maximum = 20,
                suffix = " bài",
                onChange = onSceneMusicAvoidRepeatWindowChange,
            )
            ReferenceIntSettingsSlider(
                label = "Crossfade",
                value = state.sceneMusicCrossfadeMillis,
                minimum = 0,
                maximum = 8_000,
                step = 400,
                suffix = " ms",
                onChange = onSceneMusicCrossfadeChange,
            )
''',
)

replace_once(
    personal,
    '''            Text("Tốc độ ${"%.1f".format(rate)}×", modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth()) {
                Button({ onRateChange(rate - 0.1f) }, Modifier.weight(1f).padding(2.dp)) { Text("CHẬM") }
                Button({ onRateChange(rate + 0.1f) }, Modifier.weight(1f).padding(2.dp)) { Text("NHANH") }
            }
            Text("Cao độ ${"%.1f".format(pitch)}×", modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth()) {
                Button({ onPitchChange(pitch - 0.1f) }, Modifier.weight(1f).padding(2.dp)) { Text("TRẦM") }
                Button({ onPitchChange(pitch + 0.1f) }, Modifier.weight(1f).padding(2.dp)) { Text("CAO") }
            }
            Text("Âm lượng giọng ${"%.0f".format(volume * 100)}%", modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth()) {
                Button({ onVolumeChange(volume - 0.1f) }, Modifier.weight(1f).padding(2.dp)) { Text("GIẢM") }
                Button({ onVolumeChange(volume + 0.1f) }, Modifier.weight(1f).padding(2.dp)) { Text("TĂNG") }
            }
''',
    '''            ReferenceFloatSettingsSlider(
                label = "Tốc độ đọc",
                value = rate,
                minimum = 0.25f,
                maximum = 3f,
                shown = { "%.2f×".format(it) },
                onChange = onRateChange,
            )
            ReferenceFloatSettingsSlider(
                label = "Cao độ",
                value = pitch,
                minimum = 0.5f,
                maximum = 2f,
                shown = { "%.2f×".format(it) },
                onChange = onPitchChange,
            )
            ReferenceFloatSettingsSlider(
                label = "Âm lượng",
                value = volume,
                minimum = 0f,
                maximum = 1f,
                steps = 99,
                shown = { "%.0f%%".format(it * 100) },
                onChange = onVolumeChange,
            )
''',
)
replace_once(
    personal,
    '''            Text("Âm lượng nền ${"%.0f".format(volume * 100)}%")
            Row(Modifier.fillMaxWidth()) {
                Button({ onVolumeChange(volume - 0.05f) }, Modifier.weight(1f).padding(2.dp)) { Text("NHỎ") }
                Button({ onVolumeChange(volume + 0.05f) }, Modifier.weight(1f).padding(2.dp)) { Text("LỚN") }
            }
            Text("Mức còn lại khi TTS đọc ${"%.0f".format(duckFactor * 100)}%", modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.fillMaxWidth()) {
                Button({ onDuckChange(duckFactor - 0.05f) }, Modifier.weight(1f).padding(2.dp)) { Text("HẠ THÊM") }
                Button({ onDuckChange(duckFactor + 0.05f) }, Modifier.weight(1f).padding(2.dp)) { Text("HẠ ÍT") }
            }
''',
    '''            ReferenceFloatSettingsSlider(
                label = "Âm lượng nền",
                value = volume,
                minimum = 0f,
                maximum = 1f,
                steps = 99,
                shown = { "%.0f%%".format(it * 100) },
                onChange = onVolumeChange,
            )
            ReferenceFloatSettingsSlider(
                label = "Mức còn lại khi TTS đọc",
                value = duckFactor,
                minimum = 0f,
                maximum = 1f,
                steps = 99,
                shown = { "%.0f%%".format(it * 100) },
                onChange = onDuckChange,
            )
''',
)

replace_once(
    personal,
    '''@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
''',
    '''@Composable
private fun ReferenceFloatSettingsSlider(
    label: String,
    value: Float,
    minimum: Float,
    maximum: Float,
    steps: Int = 0,
    shown: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    val safe = value.coerceIn(minimum, maximum)
    val description = "$label: ${shown(safe)}"
    Text(description, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 7.dp))
    Slider(
        value = safe,
        onValueChange = { onChange(it.coerceIn(minimum, maximum)) },
        valueRange = minimum..maximum,
        steps = steps,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
    )
}

@Composable
private fun ReferenceIntSettingsSlider(
    label: String,
    value: Int,
    minimum: Int,
    maximum: Int,
    step: Int = 1,
    suffix: String = "",
    onChange: (Int) -> Unit,
) {
    val safeStep = step.coerceAtLeast(1)
    val safe = value.coerceIn(minimum, maximum)
    val intervals = ((maximum - minimum) / safeStep).coerceAtLeast(1)
    val description = "$label: $safe$suffix"
    Text(description, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 7.dp))
    Slider(
        value = safe.toFloat(),
        onValueChange = { raw ->
            val snapped = minimum + (((raw - minimum) / safeStep).toInt() * safeStep)
            onChange(snapped.coerceIn(minimum, maximum))
        },
        valueRange = minimum.toFloat()..maximum.toFloat(),
        steps = (intervals - 1).coerceAtLeast(0),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
    )
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
''',
)

# Reader dialogs: replace steppers with sliders and match XPK SeekBar granularity.
reader = "app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt"
replace_once(
    reader,
    '''                ValueStepper("Cỡ chữ", "${display.fontSizeSp} sp", { onFontSizeChange(display.fontSizeSp - 1) }, { onFontSizeChange(display.fontSizeSp + 1) })
                ValueStepper("Khoảng cách dòng", "${display.lineHeightPercent}%", { onLineHeightChange(display.lineHeightPercent - 10) }, { onLineHeightChange(display.lineHeightPercent + 10) })
                ValueStepper("Lề ngang", "${display.horizontalPaddingDp} dp", { onHorizontalPaddingChange(display.horizontalPaddingDp - 2) }, { onHorizontalPaddingChange(display.horizontalPaddingDp + 2) })
                ValueStepper("Khoảng đoạn", "${display.paragraphSpacingDp} dp", { onParagraphSpacingChange(display.paragraphSpacingDp - 2) }, { onParagraphSpacingChange(display.paragraphSpacingDp + 2) })
''',
    '''                ReaderIntSlider("Cỡ chữ", display.fontSizeSp, 12, 40, suffix = " sp", onChange = onFontSizeChange)
                ReaderIntSlider("Khoảng cách dòng", display.lineHeightPercent, 100, 200, suffix = "%", onChange = onLineHeightChange)
                ReaderIntSlider("Lề ngang", display.horizontalPaddingDp, 0, 64, step = 2, suffix = " dp", onChange = onHorizontalPaddingChange)
                ReaderIntSlider("Khoảng đoạn", display.paragraphSpacingDp, 0, 48, step = 2, suffix = " dp", onChange = onParagraphSpacingChange)
''',
)
replace_once(
    reader,
    '''                            ValueStepper(
                                "Tránh lặp",
                                "$musicAvoidRepeatWindow bài",
                                { musicAvoidRepeatWindow = (musicAvoidRepeatWindow - 1).coerceAtLeast(0) },
                                { musicAvoidRepeatWindow = (musicAvoidRepeatWindow + 1).coerceAtMost(20) },
                            )
''',
    '''                            ReaderIntSlider("Tránh lặp", musicAvoidRepeatWindow, 0, 20, suffix = " bài") { musicAvoidRepeatWindow = it }
''',
)
replace_once(
    reader,
    '''                        ValueStepper(
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
''',
    '''                        ReaderIntSlider("Crossfade", musicCrossfadeMs, 0, 8_000, step = 400, suffix = " ms") { musicCrossfadeMs = it }
                        ReaderFloatSlider("Mức chuẩn hóa", musicTargetLufs, -36f, -18f, steps = 17, shown = { "%.0f LUFS".format(it) }) { musicTargetLufs = it }
                        ReaderFloatSlider("Giảm khi giọng đọc phát", musicDuckDb, 0f, 24f, steps = 23, shown = { "%.0f dB".format(it) }) { musicDuckDb = it }
                        ReaderIntSlider("Attack", musicAttackMs, 0, 2_000, step = 10, suffix = " ms") { musicAttackMs = it }
                        ReaderIntSlider("Release", musicReleaseMs, 0, 5_000, step = 10, suffix = " ms") { musicReleaseMs = it }
''',
)
replace_once(
    reader,
    '''@Composable
private fun ValueStepper(label: String, value: String, less: () -> Unit, more: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("$label: $value", Modifier.weight(1f))
        TextButton(less) { Text("−") }
        TextButton(more) { Text("+") }
    }
}

@Composable
private fun TtsSlider(label: String, value: Float, min: Float, max: Float, percent: Boolean = false, onChange: (Float) -> Unit) {
    val shown = value.coerceIn(min, max)
    Text(if (percent) "$label: ${"%.0f".format(shown * 100)}%" else "$label: ${"%.2f".format(shown)}x", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
    Slider(shown, onChange, valueRange = min..max)
}
''',
    '''@Composable
private fun ReaderIntSlider(
    label: String,
    value: Int,
    minimum: Int,
    maximum: Int,
    step: Int = 1,
    suffix: String = "",
    onChange: (Int) -> Unit,
) {
    val safeStep = step.coerceAtLeast(1)
    val safe = value.coerceIn(minimum, maximum)
    val intervals = ((maximum - minimum) / safeStep).coerceAtLeast(1)
    val description = "$label: $safe$suffix"
    Text(description, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
    Slider(
        value = safe.toFloat(),
        onValueChange = { raw ->
            val snapped = minimum + (((raw - minimum) / safeStep).toInt() * safeStep)
            onChange(snapped.coerceIn(minimum, maximum))
        },
        valueRange = minimum.toFloat()..maximum.toFloat(),
        steps = (intervals - 1).coerceAtLeast(0),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
    )
}

@Composable
private fun ReaderFloatSlider(
    label: String,
    value: Float,
    minimum: Float,
    maximum: Float,
    steps: Int = 0,
    shown: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    val safe = value.coerceIn(minimum, maximum)
    val description = "$label: ${shown(safe)}"
    Text(description, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
    Slider(
        value = safe,
        onValueChange = { onChange(it.coerceIn(minimum, maximum)) },
        valueRange = minimum..maximum,
        steps = steps,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
    )
}

@Composable
private fun TtsSlider(label: String, value: Float, min: Float, max: Float, percent: Boolean = false, onChange: (Float) -> Unit) {
    val shown = value.coerceIn(min, max)
    val description = if (percent) "$label: ${"%.0f".format(shown * 100)}%" else "$label: ${"%.2f".format(shown)}x"
    Text(description, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
    val intervals = when {
        percent && max <= 1f -> 100
        percent -> 200
        label.contains("Tốc độ", ignoreCase = true) -> 275
        else -> 150
    }
    Slider(
        value = shown,
        onValueChange = { onChange(it.coerceIn(min, max)) },
        valueRange = min..max,
        steps = (intervals - 1).coerceAtLeast(0),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
    )
}
''',
)

# Story voice-cast dialog: exact 1% SeekBar granularity and reference wording.
story = "app/src/main/java/vn/nghetruyen/app/ui/screens/StoryDetailScreen.kt"
replace_once(story, 'Text("Tự động phân vai", Modifier.weight(1f))', 'Text("Tự động phân vai rồi đọc khi mở chương ở chế độ TTS", Modifier.weight(1f))')
replace_once(story, 'Text("AI điều chỉnh giọng", Modifier.weight(1f))', 'Text("AI tự điều chỉnh tốc độ, cao độ và âm lượng", Modifier.weight(1f))')
replace_once(story, 'ReferenceActionButton("HƯỚNG DẪN AI", { showExpressionPromptDialog = true }, normalColor = ReferenceGray, modifier = Modifier.fillMaxWidth())', 'ReferenceActionButton("XEM / SỬA HƯỚNG DẪN THÔNG SỐ", { showExpressionPromptDialog = true }, normalColor = ReferenceGray, modifier = Modifier.fillMaxWidth())')
replace_once(story, 'ReferenceActionButton("BỘ GIỌNG RIÊNG", { showVoiceProfiles = true }, normalColor = ReferenceGray, modifier = Modifier.fillMaxWidth())', 'ReferenceActionButton("THIẾT LẬP BỘ GIỌNG RIÊNG", { showVoiceProfiles = true }, normalColor = ReferenceGray, modifier = Modifier.fillMaxWidth())')
replace_once(story, 'Text("Ghi chú AI", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))', 'Text("Ghi chú chung bổ sung cho AI", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))')
replace_once(
    story,
    'Slider(value = value.coerceIn(0, 100).toFloat(), onValueChange = { onChange(it.toInt().coerceIn(0, 100)) }, valueRange = 0f..100f, steps = 19)',
    'Slider(value = value.coerceIn(0, 100).toFloat(), onValueChange = { onChange(it.toInt().coerceIn(0, 100)) }, valueRange = 0f..100f, steps = 99, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$label: ±${value.coerceIn(0, 100)}%" })',
)

# Voice role editor wording follows the XPK dialog while preserving Compose dropdowns.
role = "app/src/main/java/vn/nghetruyen/app/ui/components/GlobalVoiceRoleEditorDialog.kt"
for old, new in [
    ('label = { Text("Tên vai") }', 'label = { Text("Tên vai hoặc tên nhân vật") }'),
    ('label = { Text("Mô tả") }', 'label = { Text("Mô tả để AI nhận biết") }'),
    ('Text("TTS", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))', 'Text("Bộ đọc TTS", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))'),
    ('Text("Giọng", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 7.dp))', 'Text("Giọng nói", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 7.dp))'),
    ('Text("Xử lý", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))', 'Text("Phương pháp xử lý", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))'),
    ('CompactVoiceValueRow("Tốc độ TTS", draft.rate, 0.25f, 3f)', 'CompactVoiceValueRow("Tốc độ đọc", draft.rate, 0.25f, 3f)'),
]:
    replace_once(role, old, new)

# Static assertions: reject the exact increment/decrement controls that caused parity regressions.
checks = {
    personal: ["Text(\"CHẬM\")", "Text(\"NHANH\")", "Text(\"TỐC ĐỘ -\")", "Text(\"TỐC ĐỘ +\")", "Text(\"CAO ĐỘ -\")", "Text(\"CAO ĐỘ +\")", "Text(\"GIỌNG NHỎ\")", "Text(\"GIỌNG LỚN\")", "Text(\"NHỎ HƠN\")", "Text(\"LỚN HƠN\")", "Text(\"-400 ms\")", "Text(\"+400 ms\")"],
    reader: ["ValueStepper("],
}
for path, forbidden in checks.items():
    text = Path(path).read_text(encoding="utf-8")
    for token in forbidden:
        if token in text:
            raise SystemExit(f"{path}: forbidden legacy numeric control remains: {token}")

print("UI_CONTROL_PARITY_APPLIED")
