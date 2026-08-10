from pathlib import Path
import re

path = Path("app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt")
text = path.read_text()


def replace_once(old: str, new: str, label: str):
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, found {count}")
    text = text.replace(old, new, 1)


replace_once(
    '    var repositoryName by remember { mutableStateOf("") }\n',
    "",
    "remove repositoryName state",
)

replace_once(
    '        "extensions_installed" -> "Tiện ích đã cài"\n',
    '        "extensions_installed" -> "Đã cài"\n',
    "installed page title",
)

replace_once(
    '        "extensions_installed" -> PersonalSubPage("TIỆN ÍCH ĐÃ CÀI") {\n',
    '        "extensions_installed" -> PersonalSubPage("ĐÃ CÀI") {\n',
    "installed heading",
)

old_add_call = '''            SourceAddLinkSection(
                state = state,
                repositoryUrl = repositoryUrl,
                onRepositoryUrlChange = { repositoryUrl = it },
                onRefresh = onRefreshSourceRepository,
                trustKeyId = trustKeyId,
                onTrustKeyIdChange = { trustKeyId = it },
                trustAlgorithm = trustAlgorithm,
                onTrustAlgorithmChange = { trustAlgorithm = it },
                trustPublicKey = trustPublicKey,
                onTrustPublicKeyChange = { trustPublicKey = it },
                trustFingerprint = trustFingerprint,
                onTrustFingerprintChange = { trustFingerprint = it },
                onEnrollKey = onEnrollSourceTrustKey,
                onRevokeKey = onRevokeSourceTrustKey,
                onImportRotation = onImportSourceTrustRotation,
            )'''
new_add_call = '''            SourceAddLinkSection(
                state = state,
                repositoryUrl = repositoryUrl,
                onRepositoryUrlChange = { repositoryUrl = it },
                onRefresh = onRefreshSourceRepository,
            )'''
replace_once(old_add_call, new_add_call, "simplify add-link call")

old_dialog_fields = '''                Column {
                    OutlinedTextField(
                        value = repositoryUrl,
                        onValueChange = { repositoryUrl = it.take(4096) },
                        label = { Text("Liên kết HTTPS") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = repositoryName,
                        onValueChange = { repositoryName = it.take(120) },
                        label = { Text("Tên kho (tùy chọn)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }'''
new_dialog_fields = '''                OutlinedTextField(
                    value = repositoryUrl,
                    onValueChange = { repositoryUrl = it.take(4096) },
                    label = { Text("Liên kết") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )'''
replace_once(old_dialog_fields, new_dialog_fields, "single URL add dialog")

installed = r'''@Composable
private fun InstalledSourcesSection(
    state: MainUiState,
    onEnabledChange: (String, Boolean) -> Unit,
    onRollback: (String) -> Unit,
    onUpdate: (String) -> Unit,
    onExport: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    onCheck: (String) -> Unit,
    onSaveConfig: (String, Map<String, String>) -> Unit,
    onResetConfig: (String) -> Unit,
    onLogin: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var selectedPackId by remember { mutableStateOf<String?>(null) }
    var configurePackId by remember { mutableStateOf<String?>(null) }
    var diagnosticPackId by remember { mutableStateOf<String?>(null) }
    var removePackId by remember { mutableStateOf<String?>(null) }
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
                minHeight = 52.dp,
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
                    Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                        Text("${pack.version} • ${pack.ecosystem} • ${pack.contentType}", style = MaterialTheme.typography.bodySmall)
                        Text(pack.id, style = MaterialTheme.typography.bodySmall)
                        ReferenceActionButton(
                            text = if (pack.enabled) "TẮT" else "BẬT",
                            onClick = {
                                onEnabledChange(pack.id, !pack.enabled)
                                selectedPackId = null
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                        ReferenceActionButton(
                            text = "KIỂM TRA",
                            onClick = {
                                onCheck(pack.id)
                                selectedPackId = null
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                        if (pack.configFields.isNotEmpty()) {
                            ReferenceActionButton(
                                text = "CẤU HÌNH",
                                onClick = {
                                    configurePackId = pack.id
                                    selectedPackId = null
                                },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            )
                        }
                        if (pack.loginAvailable) {
                            ReferenceActionButton(
                                text = "ĐĂNG NHẬP",
                                onClick = {
                                    onLogin(pack.id)
                                    selectedPackId = null
                                },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            )
                        }
                        if (pack.canRollback) {
                            ReferenceActionButton(
                                text = "KHÔI PHỤC PHIÊN BẢN TRƯỚC",
                                onClick = {
                                    onRollback(pack.id)
                                    selectedPackId = null
                                },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            )
                        }
                        ReferenceActionButton(
                            text = "CẬP NHẬT",
                            onClick = {
                                onUpdate(pack.id)
                                selectedPackId = null
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                        ReferenceActionButton(
                            text = "NHẬT KÝ",
                            onClick = {
                                diagnosticPackId = pack.id
                                selectedPackId = null
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                        ReferenceActionButton(
                            text = "XUẤT GÓI",
                            onClick = {
                                onExport(pack.id, pack.name)
                                selectedPackId = null
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                        if (pack.removable) {
                            ReferenceActionButton(
                                text = "GỠ TIỆN ÍCH",
                                onClick = {
                                    removePackId = pack.id
                                    selectedPackId = null
                                },
                                normalColor = MaterialTheme.colorScheme.error,
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            )
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { selectedPackId = null }) { Text("ĐÓNG") } },
            )
        }
    }

    configurePackId?.let { packId ->
        state.sourcePacks.firstOrNull { it.id == packId }?.let { pack ->
            SourcePackConfigDialog(
                pack = pack,
                onSave = { changes -> onSaveConfig(pack.id, changes) },
                onReset = { onResetConfig(pack.id) },
                onDismiss = { configurePackId = null },
            )
        }
    }

    diagnosticPackId?.let { sourceId ->
        val pack = state.sourcePacks.firstOrNull { it.id == sourceId }
        val events = state.sourceDiagnostics.filter { it.sourceId == sourceId }.take(50)
        AlertDialog(
            onDismissRequest = { diagnosticPackId = null },
            title = { Text("NHẬT KÝ · ${pack?.name ?: sourceId}") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    if (events.isEmpty()) {
                        Text("Chưa có sự kiện chẩn đoán cho tiện ích này.")
                    } else {
                        events.forEach { event ->
                            val duration = event.durationMs?.let { " • ${it} ms" }.orEmpty()
                            Text(
                                "${event.severity} ${event.category}/${event.name}$duration",
                                fontWeight = if (event.severity == "ERROR") FontWeight.SemiBold else FontWeight.Normal,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "trace ${event.traceId.take(12)}${event.detail.takeIf(String::isNotBlank)?.let { " • $it" }.orEmpty()}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { diagnosticPackId = null }) { Text("ĐÓNG") } },
        )
    }

    removePackId?.let { packId ->
        val pack = state.sourcePacks.firstOrNull { it.id == packId }
        AlertDialog(
            onDismissRequest = { removePackId = null },
            title = { Text("GỠ TIỆN ÍCH") },
            text = { Text("Gỡ ${pack?.name ?: packId} khỏi thiết bị?") },
            confirmButton = {
                TextButton(onClick = {
                    onRemove(packId)
                    removePackId = null
                }) { Text("GỠ") }
            },
            dismissButton = { TextButton(onClick = { removePackId = null }) { Text("HỦY") } },
        )
    }
}'''

repository = r'''@Composable
private fun SourceRepositorySection(
    state: MainUiState,
    onRefresh: (String) -> Unit,
    onRemove: (String) -> Unit,
    onPrepareInstall: (String, String) -> Unit,
    onAddRepository: () -> Unit,
) {
    var repositoryQuery by remember { mutableStateOf("") }
    var showRepositorySearch by remember { mutableStateOf(false) }
    var selectedRepositoryId by remember { mutableStateOf<String?>(null) }
    var packageQuery by remember { mutableStateOf("") }
    var removeRepositoryId by remember { mutableStateOf<String?>(null) }

    ReferenceActionButton(
        text = "THÊM KHO / LIÊN KẾT",
        onClick = onAddRepository,
        normalColor = ReferenceGray,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    )
    ReferenceActionButton(
        text = if (repositoryQuery.isBlank()) "TÌM KHO" else "TÌM: $repositoryQuery ✓",
        onClick = { showRepositorySearch = true },
        normalColor = ReferenceGray,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
    )
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

    if (state.sourceRepositories.isEmpty()) {
        Text("Chưa có kho tiện ích.", modifier = Modifier.padding(16.dp))
    } else if (repositories.isEmpty()) {
        Text("Không tìm thấy kho phù hợp.", modifier = Modifier.padding(16.dp))
    } else {
        repositories.forEach { repository ->
            ReferenceActionButton(
                text = buildString {
                    append(repository.name)
                    append("\n").append(repository.packageCount).append(" tiện ích")
                },
                onClick = {
                    packageQuery = ""
                    selectedRepositoryId = repository.id
                },
                normalColor = ReferencePanelBackground,
                normalContentColor = ReferenceText,
                minHeight = 52.dp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }

    if (showRepositorySearch) {
        AlertDialog(
            onDismissRequest = { showRepositorySearch = false },
            title = { Text("TÌM KHO") },
            text = {
                OutlinedTextField(
                    value = repositoryQuery,
                    onValueChange = { repositoryQuery = it.take(120) },
                    label = { Text("Tên hoặc liên kết") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = { TextButton(onClick = { showRepositorySearch = false }) { Text("TÌM") } },
            dismissButton = {
                TextButton(onClick = {
                    repositoryQuery = ""
                    showRepositorySearch = false
                }) { Text("HIỆN TẤT CẢ") }
            },
        )
    }

    selectedRepositoryId?.let { repositoryId ->
        state.sourceRepositories.firstOrNull { it.id == repositoryId }?.let { repository ->
            val packages = state.sourceRepositoryPackages
                .filter { it.repositoryId == repository.id }
                .filter { item -> packageQuery.isBlank() || item.name.contains(packageQuery, true) || item.sourceId.contains(packageQuery, true) }

            AlertDialog(
                onDismissRequest = { selectedRepositoryId = null },
                title = { Text(repository.name) },
                text = {
                    Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState())) {
                        Text(repository.url, style = MaterialTheme.typography.bodySmall)
                        Text("${repository.packageCount} tiện ích", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                            Button(
                                onClick = { onRefresh(repository.url) },
                                enabled = !state.sourceRepositoryRefreshing,
                                modifier = Modifier.weight(1f).padding(2.dp),
                            ) { Text(if (state.sourceRepositoryRefreshing) "ĐANG LÀM MỚI" else "LÀM MỚI") }
                            Button(
                                onClick = { removeRepositoryId = repository.id },
                                modifier = Modifier.weight(1f).padding(2.dp),
                            ) { Text("XÓA KHO") }
                        }
                        if (repository.packageCount > 0) {
                            OutlinedTextField(
                                value = packageQuery,
                                onValueChange = { packageQuery = it.take(120) },
                                label = { Text("Tìm tiện ích trong kho") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            )
                        }
                        if (packages.isEmpty()) {
                            Text(
                                if (repository.packageCount == 0) "Kho chưa có tiện ích." else "Không tìm thấy tiện ích phù hợp.",
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        } else {
                            packages.forEach { item ->
                                HorizontalDivider(Modifier.padding(vertical = 7.dp))
                                Text("${item.name} ${item.version}", fontWeight = FontWeight.SemiBold)
                                val statusLabel = when (item.status) {
                                    "UPDATE_AVAILABLE" -> "Có bản cập nhật"
                                    "INSTALLED" -> "Đã cài"
                                    "UP_TO_DATE" -> "Đã cài"
                                    else -> item.status.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
                                }
                                Text(statusLabel, style = MaterialTheme.typography.bodySmall)
                                item.description.takeIf(String::isNotBlank)?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall)
                                }
                                Button(
                                    onClick = { onPrepareInstall(item.repositoryId, item.sourceId) },
                                    enabled = item.canInstall && !state.sourceRepositoryRefreshing,
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                ) {
                                    Text(if (item.status == "UPDATE_AVAILABLE") "CẬP NHẬT" else "CÀI")
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { selectedRepositoryId = null }) { Text("ĐÓNG") } },
            )
        }
    }

    removeRepositoryId?.let { repositoryId ->
        val repository = state.sourceRepositories.firstOrNull { it.id == repositoryId }
        AlertDialog(
            onDismissRequest = { removeRepositoryId = null },
            title = { Text("XÓA KHO") },
            text = { Text("Xóa ${repository?.name ?: repositoryId} khỏi danh sách kho?") },
            confirmButton = {
                TextButton(onClick = {
                    onRemove(repositoryId)
                    removeRepositoryId = null
                    selectedRepositoryId = null
                }) { Text("XÓA") }
            },
            dismissButton = { TextButton(onClick = { removeRepositoryId = null }) { Text("HỦY") } },
        )
    }
}'''

add_link = r'''@Composable
private fun SourceAddLinkSection(
    state: MainUiState,
    repositoryUrl: String,
    onRepositoryUrlChange: (String) -> Unit,
    onRefresh: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Column(Modifier.padding(14.dp)) {
            OutlinedTextField(
                value = repositoryUrl,
                onValueChange = { onRepositoryUrlChange(it.take(4096)) },
                label = { Text("Liên kết") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onRefresh(repositoryUrl.trim()) },
                enabled = repositoryUrl.trim().startsWith("https://") && !state.sourceRepositoryRefreshing,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) { Text(if (state.sourceRepositoryRefreshing) "ĐANG KIỂM TRA…" else "THÊM") }
        }
    }
}'''

patterns = [
    (
        r"@Composable\nprivate fun InstalledSourcesSection\(.*?\n}\n\n@Composable\nprivate fun SourceRepositorySection",
        installed + "\n\n@Composable\nprivate fun SourceRepositorySection",
        "InstalledSourcesSection",
    ),
    (
        r"@Composable\nprivate fun SourceRepositorySection\(.*?\n}\n\n@Composable\nprivate fun SourceAddLinkSection",
        repository + "\n\n@Composable\nprivate fun SourceAddLinkSection",
        "SourceRepositorySection",
    ),
    (
        r"@Composable\nprivate fun SourceAddLinkSection\(.*?\n}\n\n@Composable\nprivate fun SourceDiagnosticsSection",
        add_link + "\n\n@Composable\nprivate fun SourceDiagnosticsSection",
        "SourceAddLinkSection",
    ),
]

for pattern, replacement, label in patterns:
    text, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 regex match, found {count}")

path.write_text(text)
print("Applied Lua-style extensions UI patch")
