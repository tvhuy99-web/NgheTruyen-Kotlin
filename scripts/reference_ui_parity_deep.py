#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def read(path):
    return (ROOT / path).read_text(encoding="utf-8")

def write(path, text):
    (ROOT / path).write_text(text, encoding="utf-8")

def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit(f"missing marker: {label}")
    return text.replace(old, new, 1)

def balanced_block(text, start_marker):
    start = text.find(start_marker)
    if start < 0:
        raise SystemExit(f"missing block start: {start_marker}")
    brace = text.find("{", start + len(start_marker))
    if brace < 0:
        raise SystemExit("missing block brace")
    depth = 0
    for i in range(brace, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return start, i + 1
    raise SystemExit("unterminated block")

# ---------------------------------------------------------------------------
# Story-specific AI and voice-cast settings: use real dialogs instead of inline
# panels that push the story tabs/content out of position.
# ---------------------------------------------------------------------------
path = "app/src/main/java/vn/nghetruyen/app/ui/screens/StoryDetailScreen.kt"
t = read(path)
t = replace_once(t, 'import androidx.compose.foundation.background\n', 'import androidx.compose.foundation.background\nimport androidx.compose.foundation.rememberScrollState\nimport androidx.compose.foundation.verticalScroll\n', "story scroll imports")
t = replace_once(t, 'import androidx.compose.foundation.layout.fillMaxWidth\n', 'import androidx.compose.foundation.layout.fillMaxWidth\nimport androidx.compose.foundation.layout.heightIn\n', "story height import")
start_marker = '        if (advancedMode != null) {'
start, end = balanced_block(t, start_marker)
block = t[start:end]
voice_start = block.find('        if (advancedMode == "voice") {')
if voice_start < 0:
    raise SystemExit("missing voice advanced block")
inner = block[voice_start:block.rfind('}')]
# Prevent nested voice editor from stacking on top of the parent settings dialog.
inner = inner.replace(
    '                    roleDraft = defaultRoleDraft()\n                    showVoiceRoleDialog = true\n',
    '                    roleDraft = defaultRoleDraft()\n                    advancedMode = null\n                    showVoiceRoleDialog = true\n',
    1,
)
inner = inner.replace(
    '                                    roleDraft = draft\n                                    showVoiceRoleDialog = true\n',
    '                                    roleDraft = draft\n                                    advancedMode = null\n                                    showVoiceRoleDialog = true\n',
    1,
)
replacement = '''        if (advancedMode != null) {
            AlertDialog(
                onDismissRequest = { advancedMode = null },
                title = {
                    Text(if (advancedMode == "voice") "PHÂN VAI TTS CHO TRUYỆN NÀY" else "THIẾT LẬP AI CHO TRUYỆN NÀY")
                },
                text = {
                    Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
''' + inner + '''
                    }
                },
                confirmButton = { TextButton(onClick = { advancedMode = null }) { Text("ĐÓNG") } },
            )
        }'''
t = t[:start] + replacement + t[end:]
write(path, t)

# ---------------------------------------------------------------------------
# Extension management: search and repository controls are restored at the same
# levels used by the reference tool. Add-link is a dialog instead of a deep page.
# ---------------------------------------------------------------------------
path = "app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt"
t = read(path)
t = replace_once(
    t,
    '    var showFactoryResetFinal by remember { mutableStateOf(false) }\n',
    '    var showFactoryResetFinal by remember { mutableStateOf(false) }\n'
    '    var showAddRepositoryDialog by remember { mutableStateOf(false) }\n'
    '    var repositoryName by remember { mutableStateOf("") }\n',
    "extension add dialog state",
)
t = replace_once(
    t,
    '''            onSelect = { target ->
                if (target == "extensions_install") onInstallSourcePack() else personalPage = target
            },
''',
    '''            onSelect = { target ->
                when (target) {
                    "extensions_install" -> onInstallSourcePack()
                    "extensions_add" -> showAddRepositoryDialog = true
                    else -> personalPage = target
                }
            },
''',
    "extension home add dialog routing",
)
t = replace_once(
    t,
    '''                onRemove = onRemoveSourceRepository,
                onPrepareInstall = onPrepareRepositorySourceInstall,
            )
''',
    '''                onRemove = onRemoveSourceRepository,
                onPrepareInstall = onPrepareRepositorySourceInstall,
                onAddRepository = { showAddRepositoryDialog = true },
            )
''',
    "repository add callback wiring",
)
# Insert repository dialog before PersonalScreen closes, after factory reset dialogs.
marker = '''    if (showFactoryResetFinal) {
        AlertDialog(
            onDismissRequest = { showFactoryResetFinal = false; showSettingsDialog = true },
            title = { Text("XÁC NHẬN LẦN CUỐI") },
            text = { Text("Dữ liệu sau khi xóa không thể khôi phục nếu bạn chưa sao lưu. Đặt lại ứng dụng ngay?") },
            confirmButton = {
                TextButton(onClick = {
                    showFactoryResetFinal = false
                    onFactoryResetApplication()
                }) { Text("ĐẶT LẠI NGAY") }
            },
            dismissButton = {
                TextButton(onClick = { showFactoryResetFinal = false; showSettingsDialog = true }) { Text("HỦY") }
            },
        )
    }
'''
addition = marker + '''
    if (showAddRepositoryDialog) {
        AlertDialog(
            onDismissRequest = { showAddRepositoryDialog = false },
            title = { Text("THÊM KHO / CÀI TỪ LIÊN KẾT") },
            text = {
                Column {
                    OutlinedTextField(
                        value = repositoryUrl,
                        onValueChange = { repositoryUrl = it.take(4096) },
                        label = { Text("Liên kết HTTPS của kho hoặc plugin.zip") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = repositoryName,
                        onValueChange = { repositoryName = it.take(120) },
                        label = { Text("Tên kho, không bắt buộc") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = repositoryUrl.startsWith("https://"),
                    onClick = {
                        onRefreshSourceRepository(repositoryUrl)
                        showAddRepositoryDialog = false
                    },
                ) { Text("KIỂM TRA") }
            },
            dismissButton = { TextButton(onClick = { showAddRepositoryDialog = false }) { Text("HỦY") } },
        )
    }
'''
t = replace_once(t, marker, addition, "repository add dialog")

# Replace installed extension list with reference-style search + action dialog.
start, end = balanced_block(t, '@Composable\nprivate fun InstalledSourcesSection(')
old = t[start:end]
new = '''@Composable
private fun InstalledSourcesSection(
    state: MainUiState,
    onEnabledChange: (String, Boolean) -> Unit,
    onRollback: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var selectedPackId by remember { mutableStateOf<String?>(null) }
    val filtered = state.sourcePacks.filter { pack ->
        query.isBlank() || pack.name.contains(query, ignoreCase = true) || pack.id.contains(query, ignoreCase = true)
    }

    ReferenceActionButton(
        text = if (query.isBlank()) "TÌM TIỆN ÍCH" else "TÌM: $query ✓",
        onClick = { showSearch = true },
        normalColor = ReferenceGray,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    )
    if (query.isNotBlank()) {
        ReferenceActionButton(
            text = "HIỆN TẤT CẢ",
            onClick = { query = "" },
            normalColor = ReferenceGray,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }

    if (state.sourcePacks.isEmpty()) {
        Text("Chưa cài tiện ích nguồn nào.", modifier = Modifier.padding(16.dp))
    } else if (filtered.isEmpty()) {
        Text("Không tìm thấy tiện ích phù hợp.", modifier = Modifier.padding(16.dp))
    } else {
        filtered.forEach { pack ->
            ReferenceActionButton(
                text = buildString {
                    append(pack.name)
                    append("\n").append(pack.version)
                    if (!pack.enabled) append(" • Đã tắt")
                },
                onClick = { selectedPackId = pack.id },
                normalColor = ReferencePanelBackground,
                normalContentColor = ReferenceText,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }

    if (showSearch) {
        AlertDialog(
            onDismissRequest = { showSearch = false },
            title = { Text("TÌM TIỆN ÍCH") },
            text = {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(120) },
                    label = { Text("Tên hoặc ID tiện ích") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = { TextButton(onClick = { showSearch = false }) { Text("TÌM") } },
            dismissButton = { TextButton(onClick = { query = ""; showSearch = false }) { Text("HIỆN TẤT CẢ") } },
        )
    }

    selectedPackId?.let { selectedId ->
        state.sourcePacks.firstOrNull { it.id == selectedId }?.let { pack ->
            AlertDialog(
                onDismissRequest = { selectedPackId = null },
                title = { Text(pack.name) },
                text = {
                    Column {
                        Text("${pack.id} • ${pack.version}", style = MaterialTheme.typography.bodySmall)
                        ReferenceActionButton(
                            text = if (pack.enabled) "TẮT" else "BẬT",
                            onClick = {
                                onEnabledChange(pack.id, !pack.enabled)
                                selectedPackId = null
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                        if (pack.canRollback) {
                            ReferenceActionButton(
                                text = "ROLLBACK PHIÊN BẢN NGUỒN",
                                onClick = {
                                    onRollback(pack.id)
                                    selectedPackId = null
                                },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            )
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { selectedPackId = null }) { Text("ĐÓNG") } },
            )
        }
    }
}'''
t = t[:start] + new + t[end:]

# Replace repository section with top search/add and per-repository package filters.
start, end = balanced_block(t, '@Composable\nprivate fun SourceRepositorySection(')
new = '''@Composable
private fun SourceRepositorySection(
    state: MainUiState,
    onRefresh: (String) -> Unit,
    onRemove: (String) -> Unit,
    onPrepareInstall: (String, String) -> Unit,
    onAddRepository: () -> Unit,
) {
    var repositoryQuery by remember { mutableStateOf("") }
    var showRepositorySearch by remember { mutableStateOf(false) }
    var packageQuery by remember { mutableStateOf("") }
    var packageFilter by remember { mutableStateOf("ALL") }

    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp)) {
        ReferenceActionButton(
            text = if (repositoryQuery.isBlank()) "TÌM KHO" else "TÌM: $repositoryQuery ✓",
            onClick = { showRepositorySearch = true },
            normalColor = ReferenceGray,
            modifier = Modifier.weight(1f).padding(1.dp),
        )
        ReferenceActionButton(
            text = "THÊM KHO MỚI",
            onClick = onAddRepository,
            normalColor = ReferenceGray,
            modifier = Modifier.weight(1f).padding(1.dp),
        )
    }
    if (repositoryQuery.isNotBlank()) {
        ReferenceActionButton(
            text = "HIỆN TẤT CẢ",
            onClick = { repositoryQuery = "" },
            normalColor = ReferenceGray,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }

    val repositories = state.sourceRepositories.filter { repository ->
        repositoryQuery.isBlank() || repository.name.contains(repositoryQuery, ignoreCase = true) ||
            repository.url.contains(repositoryQuery, ignoreCase = true)
    }
    if (repositories.isEmpty()) {
        Text("Chưa có kho tiện ích phù hợp.", modifier = Modifier.padding(16.dp))
    }
    repositories.forEach { repository ->
        Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text(repository.name, fontWeight = FontWeight.SemiBold)
                Text("${repository.packageCount} gói • ký bởi ${repository.signerKeyId}", style = MaterialTheme.typography.bodySmall)
                Text(repository.url, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Button({ showRepositorySearch = true }, Modifier.weight(1f).padding(1.dp)) { Text("TÌM KIẾM") }
                    Button({ packageFilter = "ALL" }, Modifier.weight(1f).padding(1.dp)) { Text((if (packageFilter == "ALL") "✓ " else "") + "TẤT CẢ") }
                    Button({ packageFilter = "INSTALLED" }, Modifier.weight(1f).padding(1.dp)) { Text((if (packageFilter == "INSTALLED") "✓ " else "") + "ĐÃ CÀI") }
                    Button({ packageFilter = "UPDATE" }, Modifier.weight(1f).padding(1.dp)) { Text((if (packageFilter == "UPDATE") "✓ " else "") + "CẬP NHẬT") }
                }
                Row(Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { onRefresh(repository.url) },
                        enabled = !state.sourceRepositoryRefreshing,
                        modifier = Modifier.weight(1f).padding(2.dp),
                    ) { Text("LÀM MỚI") }
                    Button(onClick = { onRemove(repository.id) }, modifier = Modifier.weight(1f).padding(2.dp)) { Text("XÓA KHO") }
                }
                state.sourceRepositoryPackages
                    .filter { it.repositoryId == repository.id }
                    .filter { item -> packageQuery.isBlank() || item.name.contains(packageQuery, true) || item.sourceId.contains(packageQuery, true) }
                    .filter { item ->
                        when (packageFilter) {
                            "INSTALLED" -> item.installedVersion != null
                            "UPDATE" -> item.status == "UPDATE_AVAILABLE"
                            else -> true
                        }
                    }
                    .forEach { item ->
                        HorizontalDivider(Modifier.padding(vertical = 7.dp))
                        Text("${item.name} ${item.version}", fontWeight = FontWeight.SemiBold)
                        Text("${item.sourceId} • ${item.status}${item.installedVersion?.let { " • đang cài $it" }.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                        item.description.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Button(
                            onClick = { onPrepareInstall(item.repositoryId, item.sourceId) },
                            enabled = item.canInstall && !state.sourceRepositoryRefreshing,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        ) { Text(if (item.status == "UPDATE_AVAILABLE") "TẢI BẢN CẬP NHẬT" else "TẢI & KIỂM TRA GÓI") }
                    }
            }
        }
    }

    if (showRepositorySearch) {
        AlertDialog(
            onDismissRequest = { showRepositorySearch = false },
            title = { Text("TÌM KIẾM") },
            text = {
                Column {
                    OutlinedTextField(
                        value = repositoryQuery,
                        onValueChange = { repositoryQuery = it.take(120) },
                        label = { Text("Tên hoặc URL kho") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = packageQuery,
                        onValueChange = { packageQuery = it.take(120) },
                        label = { Text("Tên hoặc ID tiện ích trong kho") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showRepositorySearch = false }) { Text("TÌM") } },
            dismissButton = {
                TextButton(onClick = {
                    repositoryQuery = ""
                    packageQuery = ""
                    showRepositorySearch = false
                }) { Text("HIỆN TẤT CẢ") }
            },
        )
    }
}'''
t = t[:start] + new + t[end:]
write(path, t)

print("deep hierarchy parity patch applied")
