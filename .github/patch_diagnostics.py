from pathlib import Path

def read(path):
    return Path(path).read_text(encoding="utf-8")

def write(path, value):
    Path(path).write_text(value, encoding="utf-8")

def replace_once(s, old, new, label):
    count = s.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 occurrence, got {count}")
    return s.replace(old, new, 1)

# 1) Patch actual PersonalScreen used by ReferenceNgheTruyenApp.
path = "app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt"
s = read(path)
s = replace_once(
    s,
    "import androidx.compose.ui.platform.LocalView\n",
    "import androidx.compose.ui.platform.LocalClipboardManager\nimport androidx.compose.ui.platform.LocalView\nimport androidx.compose.ui.text.AnnotatedString\n",
    "personal clipboard imports",
)
s = replace_once(
    s,
    "import vn.nghetruyen.app.sources.SourceCheckStatus\n",
    "import vn.nghetruyen.app.sources.SourceCheckStatus\nimport vn.nghetruyen.app.sourceplatform.DiagnosticHumanFormatter\n",
    "personal formatter import",
)
s = replace_once(
    s,
    "    onDiagnosticsModeChange: (String) -> Unit,\n    onHeadsetMultiClickChange: (Boolean) -> Unit,",
    "    onDiagnosticsModeChange: (String) -> Unit,\n    onDiagnosticScreenChanged: (String) -> Unit = {},\n    onHeadsetMultiClickChange: (Boolean) -> Unit,",
    "personal callback signature",
)
s = replace_once(
    s,
    '''    LaunchedEffect(personalPage) {
        view.announceForAccessibility(pageTitle(personalPage))
    }''',
    '''    LaunchedEffect(personalPage) {
        onDiagnosticScreenChanged(personalPage)
        view.announceForAccessibility(pageTitle(personalPage))
    }''',
    "personal page lifecycle",
)
s = replace_once(
    s,
    '''                onSaveConfig = onSaveSourceConfig,
                onResetConfig = onResetSourceConfig,
                onLogin = onOpenSourceLogin,
            )''',
    '''                onSaveConfig = onSaveSourceConfig,
                onResetConfig = onResetSourceConfig,
                onLogin = onOpenSourceLogin,
                onExportDiagnostics = onExportSourceDiagnostics,
                onClearDiagnostics = onClearSourceDiagnostics,
            )''',
    "installed source callbacks",
)

old_modes = '''        val diagnosticsLabel = when (diagnosticsMode) {
            "basic" -> "Gỡ lỗi cơ bản"
            "advanced_ram" -> "Gỡ lỗi nâng cao • RAM-only"
            "advanced_crash", "advanced" -> "Gỡ lỗi nâng cao • crash-safe"
            else -> "Tắt"
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
            Button(onClick = { diagnosticsExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text("$diagnosticsLabel ▼")
            }
            DropdownMenu(expanded = diagnosticsExpanded, onDismissRequest = { diagnosticsExpanded = false }) {
                listOf(
                    "off" to "Tắt",
                    "basic" to "Gỡ lỗi cơ bản",
                    "advanced_ram" to "Gỡ lỗi nâng cao • RAM-only",
                    "advanced_crash" to "Gỡ lỗi nâng cao • crash-safe",
                ).forEach { (value, label) ->
                    DropdownMenuItem(
                        text = { Text((if (diagnosticsMode == value) "✓ " else "") + label) },
                        onClick = {
                            diagnosticsExpanded = false
                            onDiagnosticsModeChange(value)
                        },
                    )
                }
            }
        }'''
new_modes = '''        val diagnosticsLabel = when (diagnosticsMode) {
            "basic" -> "Gỡ lỗi theo màn hình"
            "advanced", "advanced_crash" -> "Gỡ lỗi nối liền"
            else -> "Tắt"
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
            Button(onClick = { diagnosticsExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text("$diagnosticsLabel ▼")
            }
            DropdownMenu(expanded = diagnosticsExpanded, onDismissRequest = { diagnosticsExpanded = false }) {
                listOf(
                    "off" to "Tắt",
                    "basic" to "Gỡ lỗi theo màn hình",
                    "advanced" to "Gỡ lỗi nối liền",
                ).forEach { (value, label) ->
                    DropdownMenuItem(
                        text = { Text((if (diagnosticsMode == value) "✓ " else "") + label) },
                        onClick = {
                            diagnosticsExpanded = false
                            onDiagnosticsModeChange(value)
                        },
                    )
                }
            }
        }
        Text(
            when (diagnosticsMode) {
                "basic" -> "Chỉ giữ nhật ký của màn hình/ngữ cảnh hiện tại. Chuyển màn hình sẽ bắt đầu một nhật ký mới."
                "advanced", "advanced_crash" -> "Nối liền qua màn hình và lần mở ứng dụng. Chỉ nút XÓA mới xóa lịch sử đã lưu."
                else -> "Tắt hoàn toàn: không ghi event, trace hoặc evidence ngầm."
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 10.dp, bottom = 8.dp),
        )'''
s = replace_once(s, old_modes, new_modes, "three mode selector")

s = replace_once(
    s,
    "    onResetConfig: (String) -> Unit,\n    onLogin: (String) -> Unit,\n) {",
    "    onResetConfig: (String) -> Unit,\n    onLogin: (String) -> Unit,\n    onExportDiagnostics: () -> Unit,\n    onClearDiagnostics: () -> Unit,\n) {",
    "installed source signature",
)
s = replace_once(
    s,
    "    var logTechnicalDetailsVisible by remember { mutableStateOf(false) }\n",
    "",
    "remove technical toggle state",
)
s = replace_once(
    s,
    '''                                logPackId = pack.id
                                logTechnicalDetailsVisible = false
                                selectedPackId = null''',
    '''                                logPackId = pack.id
                                selectedPackId = null''',
    "log open action",
)

start = s.index("    logPackId?.let { packId ->")
end = s.index("\n\n    removePackId?.let { packId ->", start)
new_log_dialog = r'''    logPackId?.let { packId ->
        val pack = state.sourcePacks.firstOrNull { it.id == packId }
        if (pack != null) {
            val clipboard = LocalClipboardManager.current
            val events = state.sourceDiagnostics.filter { it.sourceId == packId }
            val logText = DiagnosticHumanFormatter.formatUi(
                events = events,
                mode = state.diagnosticsMode,
                title = "NHẬT KÝ TIỆN ÍCH • ${pack.name}",
            )
            AlertDialog(
                onDismissRequest = { logPackId = null },
                title = { Text("NHẬT KÝ TIỆN ÍCH") },
                text = {
                    Text(
                        logText,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()),
                    )
                },
                confirmButton = {
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(
                            onClick = { clipboard.setText(AnnotatedString(logText)) },
                            modifier = Modifier.weight(1f),
                        ) { Text("SAO CHÉP") }
                        TextButton(
                            onClick = onClearDiagnostics,
                            modifier = Modifier.weight(1f),
                        ) { Text("XÓA") }
                        TextButton(
                            onClick = onExportDiagnostics,
                            modifier = Modifier.weight(1f),
                        ) { Text("XUẤT TỆP") }
                    }
                },
            )
        }
    }'''
s = s[:start] + new_log_dialog + s[end:]

source_fn = s.index("private fun SourceDiagnosticsSection(")
log_start = s.index('    if (state.diagnosticsMode != "off") {', source_fn)
selector_marker = '''    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text("SELECTOR INSPECTOR"'''
selector_start = s.index(selector_marker, log_start)
new_global_log = r'''    if (state.diagnosticsMode != "off") {
        val clipboard = LocalClipboardManager.current
        val logText = DiagnosticHumanFormatter.formatUi(
            events = state.sourceDiagnostics,
            mode = state.diagnosticsMode,
        )
        Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text("NHẬT KÝ", fontWeight = FontWeight.Bold)
                Text(
                    logText,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 6.dp),
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Button(
                        onClick = { clipboard.setText(AnnotatedString(logText)) },
                        modifier = Modifier.weight(1f).padding(2.dp),
                    ) { Text("SAO CHÉP") }
                    Button(
                        onClick = onClearDiagnostics,
                        modifier = Modifier.weight(1f).padding(2.dp),
                    ) { Text("XÓA") }
                    Button(
                        onClick = onExportDiagnostics,
                        modifier = Modifier.weight(1f).padding(2.dp),
                    ) { Text("XUẤT TỆP") }
                }
            }
        }
    }
'''
s = s[:log_start] + new_global_log + s[selector_start:]
write(path, s)

# 2) Patch actual ReferenceNgheTruyenApp navigation to reset screen-scoped diagnostics.
path = "app/src/main/java/vn/nghetruyen/app/ui/ReferenceNgheTruyenApp.kt"
s = read(path)
s = replace_once(
    s,
    "import androidx.compose.ui.Modifier\n",
    "import androidx.compose.ui.Modifier\nimport androidx.compose.ui.platform.LocalContext\n",
    "reference app LocalContext import",
)
s = replace_once(
    s,
    "import vn.nghetruyen.app.audio.AudioExportRequest\n",
    "import vn.nghetruyen.app.NgheTruyenApplication\nimport vn.nghetruyen.app.audio.AudioExportRequest\n",
    "reference app application import",
)
s = replace_once(
    s,
    '''    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    BackHandler''',
    '''    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val app = LocalContext.current.applicationContext as NgheTruyenApplication
    val diagnosticScreenKey = referenceDiagnosticScreenKey(state)

    LaunchedEffect(state.diagnosticsMode, diagnosticScreenKey) {
        app.container.sourceDiagnostics.onScreenChanged(diagnosticScreenKey)
    }

    BackHandler''',
    "reference app navigation diagnostic effect",
)
s = replace_once(
    s,
    '''                        onInterruptionModeChange = viewModel::setAudioInterruptionMode,
                        onDiagnosticsModeChange = viewModel::setDiagnosticsMode,
                        onHeadsetMultiClickChange''',
    '''                        onInterruptionModeChange = viewModel::setAudioInterruptionMode,
                        onDiagnosticsModeChange = viewModel::setDiagnosticsMode,
                        onDiagnosticScreenChanged = { key ->
                            app.container.sourceDiagnostics.onScreenChanged("personal:$key")
                        },
                        onHeadsetMultiClickChange''',
    "reference app personal screen diagnostic callback",
)
insert_marker = "\n@Composable\nprivate fun ReferencePrimaryBottomBar("
helper = r'''
private fun referenceDiagnosticScreenKey(state: MainUiState): String = when (state.destination) {
    Destination.Root -> when (state.rootTab) {
        RootTab.EXPLORE -> listOf(
            "explore",
            state.selectedSourceId.orEmpty(),
            state.exploreMode.name,
            state.activeCategory.orEmpty(),
        ).joinToString(":")
        RootTab.LIBRARY -> "library:${state.librarySection.name}"
        RootTab.PERSONAL -> "personal:home"
    }
    Destination.Story -> "story:${state.storyDetail?.story?.id.orEmpty()}"
    Destination.Reader -> "reader:${state.chapterContent?.chapter?.id.orEmpty()}"
}

'''
if insert_marker not in s:
    raise SystemExit("reference app bottom bar marker missing")
s = s.replace(insert_marker, "\n" + helper + "@Composable\nprivate fun ReferencePrimaryBottomBar(", 1)
write(path, s)

# 3) Update AppViewModel text and enlarge the live plain-text log window.
path = "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt"
s = read(path)
s = s.replace("diagnosticSummaries(200)", "diagnosticSummaries(2_000)")
s = replace_once(
    s,
    '''            "advanced" -> "Đã bật gỡ lỗi nâng cao: ghi trace, HTML/DOM, runtime, network và hộp đen chống mất log khi crash."
            "basic" -> "Đã bật gỡ lỗi cơ bản."
            else -> "Đã tắt ghi nhật ký chẩn đoán."''',
    '''            "advanced" -> "Đã bật gỡ lỗi nối liền. Nhật ký tiếp tục qua các màn hình và lần mở ứng dụng cho đến khi bạn chủ động xóa."
            "basic" -> "Đã bật gỡ lỗi theo màn hình. Chuyển sang màn hình hoặc ngữ cảnh khác sẽ bắt đầu nhật ký mới."
            else -> "Đã tắt hoàn toàn nhật ký chẩn đoán."''',
    "viewmodel mode messages",
)
s = replace_once(
    s,
    'showMessage("Đã xóa nhật ký, evidence RAM, critical breadcrumbs và hộp đen crash-safe.")',
    'showMessage("Đã xóa toàn bộ nhật ký và dữ liệu chẩn đoán đã lưu.")',
    "viewmodel clear message",
)
write(path, s)

# 4) In screen mode export only the current screen; old continuous history remains stored but inactive.
path = "app/src/main/java/vn/nghetruyen/app/sourceplatform/SourceDiagnosticRuntime.kt"
s = read(path)
old = '''            continuousStore.filesForExport().forEach { (name, file) ->
                zip.addFile("continuous/$name", file)
            }
            zip.addText("continuous/status.json", continuousStore.statusJson().toString(2))'''
new = '''            if (mode == MODE_CONTINUOUS) {
                continuousStore.filesForExport().forEach { (name, file) ->
                    zip.addFile("continuous/$name", file)
                }
                zip.addText("continuous/status.json", continuousStore.statusJson().toString(2))
            }'''
s = replace_once(s, old, new, "continuous export scope")
write(path, s)

# 5) Update the parity gate to the intentionally redesigned behavior.
path = "scripts/check_lua_diagnostics_ui_parity.py"
s = read(path)
s = replace_once(
    s,
    '    "large live event window": "diagnosticSummaries(200)" in vm,',
    '    "large live event window": "diagnosticSummaries(2_000)" in vm,',
    "gate live event window",
)
s = replace_once(
    s,
    '    "dual Advanced profiles": all(token in runtime for token in ("advanced_ram", "advanced_crash", "crashSafe")),',
    '''    "three diagnostic modes": all(token in runtime for token in (
        'MODE_OFF = "off"',
        'MODE_SCREEN = "basic"',
        'MODE_CONTINUOUS = "advanced"',
        "fun onScreenChanged",
        "ContinuousDiagnosticStore",
    )),''',
    "gate three modes",
)
s = replace_once(
    s,
    '    "active operation tracker": "DiagnosticActivityTracker" in runtime and "diagnosticActiveOperations" in vm and "ĐANG HOẠT ĐỘNG" in chrome,',
    '    "active operation tracker": "DiagnosticActivityTracker" in runtime and "diagnosticActiveOperations" in vm and "diagnosticActiveOperations.isNotEmpty()" in chrome,',
    "gate active operation UI",
)
s = replace_once(
    s,
    '    "critical breadcrumbs while OFF": "shouldRetainWhenDiagnosticsOff" in runtime and "MAX_CRITICAL_EVENTS = 100" in runtime,',
    '''    "OFF means no hidden recording": (
        "if (mode == MODE_OFF) return" in runtime
        and "restoreCriticalEvents" not in runtime
        and "MAX_CRITICAL_EVENTS" not in runtime
    ),''',
    "gate true off",
)
anchor = '    "Lua empty label": "CHƯA CÓ NHẬT KÝ" in chrome,\n'
extra = '''    "single readable log viewer": (
        "DiagnosticHumanFormatter.formatUi" in chrome
        and 'Text("SAO CHÉP")' in chrome
        and 'Text("XÓA")' in chrome
        and 'Text("XUẤT TỆP")' in chrome
        and 'Text("TRACE (' not in chrome
    ),
    "screen scoped navigation reset": "referenceDiagnosticScreenKey" in app and "onScreenChanged" in app,
    "continuous append-only store": "append-only" in runtime.lower() and "events.jsonl" in runtime,
'''
if anchor not in s:
    raise SystemExit("gate anchor missing")
s = s.replace(anchor, anchor + extra, 1)
write(path, s)
