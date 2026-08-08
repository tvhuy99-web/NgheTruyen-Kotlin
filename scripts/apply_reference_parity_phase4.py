from pathlib import Path

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


# 1) UI model: XÓA is legal only for non-builtin packages.
path = "app/src/main/java/vn/nghetruyen/app/sourceplatform/SourcePlatformModels.kt"
text = read(path)
text = replace_once(
    text,
    "    val commentCapability: String,\n    val commentFixtureCount: Int,\n)\n",
    "    val commentCapability: String,\n    val commentFixtureCount: Int,\n    val removable: Boolean = true,\n)\n",
    "SourcePackUiInfo removable",
)
write(path, text)


# 2) Source platform backend: protect builtins, export active package, remove user package.
path = "app/src/main/java/vn/nghetruyen/app/sourceplatform/SourcePlatformManager.kt"
text = read(path)
text = replace_once(
    text,
    "import vn.nghetruyen.source.api.SourceRuntimeMode\n",
    "import vn.nghetruyen.source.api.SourceRuntimeMode\nimport vn.nghetruyen.source.api.SourceManifest\n",
    "SourceManifest import",
)
text = replace_once(
    text,
    "import java.io.InputStream\n",
    "import java.io.InputStream\nimport java.io.OutputStream\n",
    "OutputStream import",
)
text = replace_once(
    text,
    "import java.util.UUID\n",
    "import java.util.UUID\nimport java.util.zip.ZipEntry\nimport java.util.zip.ZipOutputStream\n",
    "zip imports",
)
text = replace_once(
    text,
    "    private val repositories = linkedMapOf<String, CachedRepository>()\n    private var pendingPack: VerifiedSourcePack? = null\n",
    "    private val repositories = linkedMapOf<String, CachedRepository>()\n    private val builtinSourceIds = linkedSetOf<String>()\n    private var pendingPack: VerifiedSourcePack? = null\n",
    "builtin source id set",
)
text = replace_once(
    text,
    "            commentFixtureCount = active?.manifest?.fixtures?.count { it.action == vn.nghetruyen.source.api.SourceActionName.COMMENTS } ?: 0,\n        )\n",
    "            commentFixtureCount = active?.manifest?.fixtures?.count { it.action == vn.nghetruyen.source.api.SourceActionName.COMMENTS } ?: 0,\n            removable = installed.sourceId !in builtinSourceIds,\n        )\n",
    "installed pack removable flag",
)
text = replace_once(
    text,
    "    fun rollback(sourceId: String): Result<Unit> = runCatching {\n        when (val result = store.rollback(sourceId)) {\n            is SourcePlatformResult.Success -> Unit\n            is SourcePlatformResult.Failure -> error(result.error.message)\n        }\n    }\n\n    fun diagnosticsSnapshot",
    "    fun rollback(sourceId: String): Result<Unit> = runCatching {\n        when (val result = store.rollback(sourceId)) {\n            is SourcePlatformResult.Success -> Unit\n            is SourcePlatformResult.Failure -> error(result.error.message)\n        }\n    }\n\n    fun removeInstalledPack(sourceId: String): Result<Unit> = runCatching {\n        require(sourceId !in builtinSourceIds) { \"Không thể xóa tiện ích nguồn tích hợp.\" }\n        require(store.remove(sourceId)) { \"Không tìm thấy tiện ích để xóa.\" }\n    }\n\n    fun exportInstalledPack(sourceId: String, output: OutputStream): Result<Unit> = runCatching {\n        val pack = store.readActivePack(sourceId) ?: error(\"Không tìm thấy gói tiện ích đang hoạt động.\")\n        ZipOutputStream(output.buffered()).use { zip ->\n            pack.entries.toSortedMap().forEach { (entryPath, bytes) ->\n                SourceManifest.requireSafeRelativePath(entryPath)\n                zip.putNextEntry(ZipEntry(entryPath).apply { time = 0L })\n                zip.write(bytes)\n                zip.closeEntry()\n            }\n        }\n    }\n\n    fun diagnosticsSnapshot",
    "installed pack export/remove backend",
)
text = replace_once(
    text,
    "                    validateCompatibility(pack)\n                    selfTest(pack)\n                    val existing = store.load(pack.manifest.id)\n",
    "                    builtinSourceIds += pack.manifest.id\n                    validateCompatibility(pack)\n                    selfTest(pack)\n                    val existing = store.load(pack.manifest.id)\n",
    "mark builtin pack id",
)
write(path, text)


# 3) ViewModel: real update/export/remove actions.
path = "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt"
text = read(path)
anchor = "    fun rollbackSourcePack(sourceId: String) {\n        viewModelScope.launch {\n            container.sourcePlatformManager.rollback(sourceId)\n                .onSuccess {\n                    refreshSourcePlatformState()\n                    showMessage(\"Đã rollback nguồn về phiên bản trước.\")\n                }\n                .onFailure { showMessage(it.message ?: \"Không có phiên bản để rollback.\") }\n        }\n    }\n\n    private fun refreshSourcePlatformState()"
replacement = "    fun rollbackSourcePack(sourceId: String) {\n        viewModelScope.launch {\n            container.sourcePlatformManager.rollback(sourceId)\n                .onSuccess {\n                    refreshSourcePlatformState()\n                    showMessage(\"Đã rollback nguồn về phiên bản trước.\")\n                }\n                .onFailure { showMessage(it.message ?: \"Không có phiên bản để rollback.\") }\n        }\n    }\n\n    fun updateSourcePack(sourceId: String) {\n        val update = state.value.sourceRepositoryPackages.firstOrNull {\n            it.sourceId == sourceId && it.status == \"UPDATE_AVAILABLE\" && it.canInstall\n        }\n        if (update == null) {\n            showMessage(\"Không có bản cập nhật.\")\n            return\n        }\n        prepareRepositorySourceInstall(update.repositoryId, update.sourceId)\n    }\n\n    fun exportSourcePack(sourceId: String, destination: Uri) {\n        viewModelScope.launch {\n            withContext(Dispatchers.IO) {\n                runCatching {\n                    val resolver = getApplication<Application>().contentResolver\n                    resolver.openOutputStream(destination, \"w\")?.use { output ->\n                        container.sourcePlatformManager.exportInstalledPack(sourceId, output).getOrThrow()\n                    } ?: error(\"Không mở được tệp xuất tiện ích.\")\n                }\n            }.onSuccess {\n                showMessage(\"Đã xuất tiện ích.\")\n            }.onFailure { error ->\n                showMessage(error.message ?: \"Không xuất được tiện ích.\")\n            }\n        }\n    }\n\n    fun removeSourcePack(sourceId: String) {\n        viewModelScope.launch {\n            withContext(Dispatchers.IO) { container.sourcePlatformManager.removeInstalledPack(sourceId) }\n                .onSuccess {\n                    refreshSourcePlatformState()\n                    showMessage(\"Đã xóa tiện ích. Dữ liệu truyện đã tải được giữ lại.\")\n                }\n                .onFailure { error -> showMessage(error.message ?: \"Không xóa được tiện ích.\") }\n        }\n    }\n\n    private fun refreshSourcePlatformState()"
text = replace_once(text, anchor, replacement, "AppViewModel source pack actions")
write(path, text)


# 4) Active Personal UI: exact visible action order and delete confirmation.
path = "app/src/main/java/vn/nghetruyen/app/ui/screens/ReferencePersonalScreen.kt"
text = read(path)
text = replace_once(
    text,
    "    onSourcePackEnabledChange: (String, Boolean) -> Unit,\n    onRollbackSourcePack: (String) -> Unit,\n",
    "    onSourcePackEnabledChange: (String, Boolean) -> Unit,\n    onRollbackSourcePack: (String) -> Unit,\n    onUpdateSourcePack: (String) -> Unit,\n    onExportSourcePack: (String, String) -> Unit,\n    onRemoveSourcePack: (String) -> Unit,\n",
    "ReferencePersonal extension callbacks",
)
text = replace_once(
    text,
    "            onCheckSource = onCheckSource,\n            onPrepareUpdate = onPrepareRepositorySourceInstall,\n            onDismiss = { showExtensionsInstalled = false },\n",
    "            onCheckSource = onCheckSource,\n            onUpdate = onUpdateSourcePack,\n            onExport = onExportSourcePack,\n            onRemove = onRemoveSourcePack,\n            onDismiss = { showExtensionsInstalled = false },\n",
    "Installed extensions route callbacks",
)
text = replace_once(
    text,
    "private fun InstalledExtensionsReferenceDialog(\n    state: MainUiState,\n    onEnabledChange: (String, Boolean) -> Unit,\n    onCheckSource: (String) -> Unit,\n    onPrepareUpdate: (String, String) -> Unit,\n    onDismiss: () -> Unit,\n) {",
    "private fun InstalledExtensionsReferenceDialog(\n    state: MainUiState,\n    onEnabledChange: (String, Boolean) -> Unit,\n    onCheckSource: (String) -> Unit,\n    onUpdate: (String) -> Unit,\n    onExport: (String, String) -> Unit,\n    onRemove: (String) -> Unit,\n    onDismiss: () -> Unit,\n) {",
    "Installed extensions dialog signature",
)
text = replace_once(
    text,
    "    var query by remember { mutableStateOf(\"\") }\n    var selected by remember { mutableStateOf<String?>(null) }\n    val visible = state.sourcePacks.filter { query.isBlank() || it.name.contains(query, true) || it.id.contains(query, true) }\n",
    "    var query by remember { mutableStateOf(\"\") }\n    var selected by remember { mutableStateOf<String?>(null) }\n    var deleteTarget by remember { mutableStateOf<vn.nghetruyen.app.sourceplatform.SourcePackUiInfo?>(null) }\n    val visible = state.sourcePacks.filter { query.isBlank() || it.name.contains(query, true) || it.id.contains(query, true) }\n",
    "Installed extensions delete target",
)
old_actions = "                    SettingsButton(if (pack.enabled) \"TẮT\" else \"BẬT\") { onEnabledChange(pack.id, !pack.enabled); selected = null }\n                    if (pack.runtimeMode == \"VBOOK_JS_COMPAT\") SettingsButton(\"TƯƠNG THÍCH\") { selected = null }\n                    if (pack.runtimeMode == \"NATIVE_LUA_COMPAT\" && source != null) SettingsButton(\"KIỂM TRA NATIVE\") { onCheckSource(source.id); selected = null }\n                    if (source != null) SettingsButton(\"KIỂM TRA NGUỒN\") { onCheckSource(source.id); selected = null }\n                    if (update != null) SettingsButton(\"CẬP NHẬT\") { onPrepareUpdate(update.repositoryId, update.sourceId); selected = null }\n"
new_actions = "                    SettingsButton(if (pack.enabled) \"TẮT\" else \"BẬT\") { onEnabledChange(pack.id, !pack.enabled); selected = null }\n                    if (pack.runtimeMode == \"VBOOK_JS_COMPAT\") SettingsButton(\"TƯƠNG THÍCH\") { selected = null }\n                    if (pack.runtimeMode == \"NATIVE_LUA_COMPAT\" && source != null) SettingsButton(\"KIỂM TRA NATIVE\") { onCheckSource(source.id); selected = null }\n                    if (source != null) SettingsButton(\"KIỂM TRA NGUỒN\") { onCheckSource(source.id); selected = null }\n                    SettingsButton(\"CẬP NHẬT\") { onUpdate(pack.id); selected = null }\n                    SettingsButton(\"XUẤT\") { onExport(pack.id, pack.name); selected = null }\n                    if (pack.removable) SettingsButton(\"XÓA\") { selected = null; deleteTarget = pack }\n"
text = replace_once(text, old_actions, new_actions, "exact installed extension actions")
needle = "    }\n}\n\n@Composable\nprivate fun RepositoriesReferenceDialog"
insert = "    }\n    deleteTarget?.let { pack ->\n        AlertDialog(\n            onDismissRequest = { deleteTarget = null },\n            title = { Text(\"XÓA TIỆN ÍCH\") },\n            text = { Text(\"Xóa ${pack.name}? Dữ liệu truyện đã tải sẽ được giữ lại.\") },\n            confirmButton = { TextButton(onClick = { onRemove(pack.id); deleteTarget = null }) { Text(\"XÓA\") } },\n            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(\"HỦY\") } },\n        )\n    }\n}\n\n@Composable\nprivate fun RepositoriesReferenceDialog"
text = replace_once(text, needle, insert, "installed extension delete confirmation")
write(path, text)


# 5) Active app wiring.
path = "app/src/main/java/vn/nghetruyen/app/ui/ReferenceNgheTruyenApp.kt"
text = read(path)
text = replace_once(
    text,
    "    onInstallSourcePack: () -> Unit,\n    onImportSourceTrustRotation: () -> Unit,\n",
    "    onInstallSourcePack: () -> Unit,\n    onExportSourcePack: (String, String) -> Unit,\n    onImportSourceTrustRotation: () -> Unit,\n",
    "Reference app source export callback",
)
text = replace_once(
    text,
    "                        onSourcePackEnabledChange = viewModel::setSourcePackEnabled,\n                        onRollbackSourcePack = viewModel::rollbackSourcePack,\n",
    "                        onSourcePackEnabledChange = viewModel::setSourcePackEnabled,\n                        onRollbackSourcePack = viewModel::rollbackSourcePack,\n                        onUpdateSourcePack = viewModel::updateSourcePack,\n                        onExportSourcePack = onExportSourcePack,\n                        onRemoveSourcePack = viewModel::removeSourcePack,\n",
    "Reference app installed source action wiring",
)
write(path, text)


# 6) Android CreateDocument bridge for XUẤT.
path = "app/src/main/java/vn/nghetruyen/app/MainActivity.kt"
text = read(path)
text = replace_once(
    text,
    "            val sourcePackInstallLauncher = rememberLauncherForActivityResult(\n                contract = ActivityResultContracts.OpenDocument(),\n            ) { uri ->\n                if (uri != null) viewModel.prepareSourcePack(uri)\n            }\n",
    "            val sourcePackInstallLauncher = rememberLauncherForActivityResult(\n                contract = ActivityResultContracts.OpenDocument(),\n            ) { uri ->\n                if (uri != null) viewModel.prepareSourcePack(uri)\n            }\n            var pendingSourcePackExportId by remember { mutableStateOf<String?>(null) }\n            val sourcePackExportLauncher = rememberLauncherForActivityResult(\n                contract = ActivityResultContracts.CreateDocument(\"application/zip\"),\n            ) { uri ->\n                val sourceId = pendingSourcePackExportId\n                pendingSourcePackExportId = null\n                if (uri != null && sourceId != null) viewModel.exportSourcePack(sourceId, uri)\n            }\n",
    "MainActivity source export launcher",
)
text = replace_once(
    text,
    "            val launchSourcePackInstall = remember(sourcePackInstallLauncher) {\n                { sourcePackInstallLauncher.launch(arrayOf(\"application/zip\", \"application/octet-stream\", \"text/plain\", \"text/x-lua\")) }\n            }\n",
    "            val launchSourcePackInstall = remember(sourcePackInstallLauncher) {\n                { sourcePackInstallLauncher.launch(arrayOf(\"application/zip\", \"application/octet-stream\", \"text/plain\", \"text/x-lua\")) }\n            }\n            val launchSourcePackExport: (String, String) -> Unit = remember(sourcePackExportLauncher) {\n                { sourceId, displayName ->\n                    pendingSourcePackExportId = sourceId\n                    val safeName = displayName.replace(Regex(\"[\\\\/:*?\\\"<>|]\"), \"_\").trim().ifBlank { \"extension\" }\n                    sourcePackExportLauncher.launch(\"$safeName.ntsource\")\n                }\n            }\n",
    "MainActivity source export callback",
)
text = replace_once(
    text,
    "                    onInstallSourcePack = launchSourcePackInstall,\n                    onImportSourceTrustRotation = launchSourceTrustRotation,\n",
    "                    onInstallSourcePack = launchSourcePackInstall,\n                    onExportSourcePack = launchSourcePackExport,\n                    onImportSourceTrustRotation = launchSourceTrustRotation,\n",
    "MainActivity pass source export callback",
)
write(path, text)

print("REFERENCE_PARITY_PHASE4_PATCH_OK")
