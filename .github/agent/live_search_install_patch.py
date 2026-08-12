from pathlib import Path
import re


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"missing pattern: {label}")
    return text.replace(old, new, 1)


# AppViewModel: preserve verification, remove manual approval from the user flow,
# and expose an explicit install outcome for a required result dialog.
path = Path("app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt")
text = path.read_text()
text = replace_once(
    text,
    "\ndata class MainUiState(\n",
    """
data class SourceInstallOutcomeUi(
    val success: Boolean,
    val name: String,
    val version: String,
    val reason: String = "",
)

data class MainUiState(
""",
    "SourceInstallOutcomeUi",
)
text = replace_once(
    text,
    """    val sourceRepositoryRefreshing: Boolean = false,
    val pendingSourceInstall: SourceInstallPreview? = null,
""",
    """    val sourceRepositoryRefreshing: Boolean = false,
    val sourceInstallBusy: Boolean = false,
    val sourceInstallOutcome: SourceInstallOutcomeUi? = null,
    val pendingSourceInstall: SourceInstallPreview? = null,
""",
    "install state fields",
)

start = text.index("    fun prepareRepositorySourceInstall(repositoryId: String, sourceId: String) {")
end = text.index("    fun cancelSourcePackInstall() {", start)
replacement = r'''    private fun sourceInstallFailureReason(error: Throwable): String {
        val messages = generateSequence<Throwable>(error) { it.cause }
            .mapNotNull { it.message?.trim()?.takeIf(String::isNotBlank) }
            .distinct()
            .toList()
        val detail = messages.joinToString(" | ").ifBlank {
            error.javaClass.simpleName.ifBlank { "Lỗi cài đặt không xác định." }
        }
        return when {
            detail.contains("VBOOK_REPOSITORY_PACKAGE_HTTPS_REQUIRED", ignoreCase = true) ->
                "Gói tiện ích không dùng HTTPS an toàn. Chi tiết: $detail"
            detail.contains("VBOOK_IMPORT_NOT_ACTIVATABLE", ignoreCase = true) ->
                "Tiện ích không tương thích với runtime hiện tại. Chi tiết: $detail"
            detail.contains("VBOOK_INSTALL_QUARANTINED", ignoreCase = true) ->
                "Tiện ích bị cách ly vì không vượt qua kiểm tra kích hoạt. Chi tiết: $detail"
            detail.contains("signature", ignoreCase = true) || detail.contains("chữ ký", ignoreCase = true) ->
                "Kiểm tra chữ ký thất bại. Chi tiết: $detail"
            detail.contains("trust", ignoreCase = true) || detail.contains("fingerprint", ignoreCase = true) ->
                "Khóa tin cậy hoặc fingerprint không hợp lệ. Chi tiết: $detail"
            detail.contains("checksum", ignoreCase = true) || detail.contains("sha256", ignoreCase = true) ->
                "Mã kiểm tra của gói không khớp. Chi tiết: $detail"
            detail.contains("manifest", ignoreCase = true) ->
                "Manifest của tiện ích không hợp lệ. Chi tiết: $detail"
            detail.contains("fixture", ignoreCase = true) || detail.contains("self-test", ignoreCase = true) ->
                "Tự kiểm tra của tiện ích thất bại. Chi tiết: $detail"
            else -> detail
        }
    }

    private fun setSourceInstallOutcome(
        success: Boolean,
        name: String,
        version: String,
        reason: String = "",
    ) {
        mutableState.update {
            it.copy(
                sourceInstallBusy = false,
                sourceRepositoryRefreshing = false,
                pendingSourceInstall = null,
                pendingSourceInstallWarnings = emptyList(),
                sourceInstallOutcome = SourceInstallOutcomeUi(success, name, version, reason),
            )
        }
    }

    /** Runs the same prepare/verify path as installation, but never commits the staged package. */
    fun prepareRepositorySourceInstall(repositoryId: String, sourceId: String) {
        if (state.value.sourceInstallBusy || state.value.sourceRepositoryRefreshing) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    sourceRepositoryRefreshing = true,
                    pendingSourceInstall = null,
                    pendingSourceInstallWarnings = emptyList(),
                    sourceInstallOutcome = null,
                )
            }
            val result = withContext(Dispatchers.IO) {
                container.sourcePlatformManager.prepareRepositoryInstall(repositoryId, sourceId)
            }
            val warnings = if (result.isSuccess) container.sourcePlatformManager.pendingInstallWarnings() else emptyList()
            container.sourcePlatformManager.cancelPendingInstall()
            result.onSuccess { preview ->
                mutableState.update {
                    it.copy(
                        pendingSourceInstall = preview,
                        pendingSourceInstallWarnings = warnings,
                    )
                }
            }.onFailure { error ->
                showMessage("Chẩn đoán thất bại: ${sourceInstallFailureReason(error)}")
            }
            refreshSourcePlatformState()
            mutableState.update { it.copy(sourceRepositoryRefreshing = false) }
        }
    }

    /** Prepare + verify + commit in one user action. Security checks remain mandatory. */
    fun installRepositorySource(repositoryId: String, sourceId: String) {
        if (state.value.sourceInstallBusy || state.value.sourceRepositoryRefreshing) return
        val requested = state.value.sourceRepositoryPackages.firstOrNull {
            it.repositoryId == repositoryId && it.sourceId == sourceId
        }
        val requestedName = requested?.name ?: sourceId
        val requestedVersion = requested?.version.orEmpty()
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    sourceInstallBusy = true,
                    sourceRepositoryRefreshing = true,
                    sourceInstallOutcome = null,
                    pendingSourceInstall = null,
                    pendingSourceInstallWarnings = emptyList(),
                )
            }
            val prepared = withContext(Dispatchers.IO) {
                container.sourcePlatformManager.prepareRepositoryInstall(repositoryId, sourceId)
            }
            if (prepared.isFailure) {
                container.sourcePlatformManager.cancelPendingInstall()
                val error = prepared.exceptionOrNull()
                    ?: IllegalStateException("Không chuẩn bị được gói tiện ích.")
                setSourceInstallOutcome(
                    false,
                    requestedName,
                    requestedVersion,
                    sourceInstallFailureReason(error),
                )
                return@launch
            }
            val preview = prepared.getOrThrow()
            val installed = withContext(Dispatchers.IO) {
                container.sourcePlatformManager.confirmPendingInstall()
            }
            installed.onSuccess { pack ->
                refreshSourcePlatformState()
                setSourceInstallOutcome(true, pack.name, pack.version)
            }.onFailure { error ->
                container.sourcePlatformManager.cancelPendingInstall()
                setSourceInstallOutcome(
                    false,
                    preview.name,
                    preview.version,
                    sourceInstallFailureReason(error),
                )
            }
        }
    }

    fun prepareSourcePack(uri: Uri) {
        if (state.value.sourceInstallBusy) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    sourceInstallBusy = true,
                    sourceInstallOutcome = null,
                    pendingSourceInstall = null,
                    pendingSourceInstallWarnings = emptyList(),
                )
            }
            val result = withContext(Dispatchers.IO) {
                val resolver = getApplication<Application>().contentResolver
                val signedAttempt = runCatching {
                    resolver.openInputStream(uri)?.use { input ->
                        container.sourcePlatformManager.prepareInstall(input).getOrThrow()
                    } ?: error("Không mở được gói nguồn.")
                }
                if (signedAttempt.isSuccess) signedAttempt else {
                    val vBookAttempt = runCatching {
                        resolver.openInputStream(uri)?.use { input ->
                            container.sourcePlatformManager.prepareVBookImport(input).getOrThrow()
                        } ?: error("Không mở được gói vBook.")
                    }
                    if (vBookAttempt.isSuccess) vBookAttempt else runCatching {
                        resolver.openInputStream(uri)?.use { input ->
                            container.sourcePlatformManager.prepareNativeLuaImport(input).getOrThrow()
                        } ?: error("Không mở được extension Lua Native Source API 2.")
                    }.recoverCatching { luaError ->
                        error(
                            "Không nhận diện được .ntsource, vBook ZIP hoặc Lua Native Source API 2. " +
                                "SourcePack: ${signedAttempt.exceptionOrNull()?.message.orEmpty()}; " +
                                "vBook: ${vBookAttempt.exceptionOrNull()?.message.orEmpty()}; Lua: ${luaError.message}",
                        )
                    }
                }
            }
            if (result.isFailure) {
                container.sourcePlatformManager.cancelPendingInstall()
                val error = result.exceptionOrNull()
                    ?: IllegalStateException("Gói nguồn không hợp lệ.")
                setSourceInstallOutcome(false, "Gói tiện ích", "", sourceInstallFailureReason(error))
                return@launch
            }
            val preview = result.getOrThrow()
            val installed = withContext(Dispatchers.IO) {
                container.sourcePlatformManager.confirmPendingInstall()
            }
            installed.onSuccess { pack ->
                refreshSourcePlatformState()
                setSourceInstallOutcome(true, pack.name, pack.version)
            }.onFailure { error ->
                container.sourcePlatformManager.cancelPendingInstall()
                setSourceInstallOutcome(
                    false,
                    preview.name,
                    preview.version,
                    sourceInstallFailureReason(error),
                )
            }
        }
    }

    fun confirmSourcePackInstall() {
        if (state.value.sourceInstallBusy) return
        val preview = state.value.pendingSourceInstall ?: return
        viewModelScope.launch {
            mutableState.update { it.copy(sourceInstallBusy = true, sourceInstallOutcome = null) }
            withContext(Dispatchers.IO) { container.sourcePlatformManager.confirmPendingInstall() }
                .onSuccess { pack ->
                    refreshSourcePlatformState()
                    setSourceInstallOutcome(true, pack.name, pack.version)
                }
                .onFailure { error ->
                    container.sourcePlatformManager.cancelPendingInstall()
                    setSourceInstallOutcome(
                        false,
                        preview.name,
                        preview.version,
                        sourceInstallFailureReason(error),
                    )
                }
        }
    }

'''
text = text[:start] + replacement + text[end:]
text = replace_once(
    text,
    """    fun cancelSourcePackInstall() {
        container.sourcePlatformManager.cancelPendingInstall()
        mutableState.update { it.copy(pendingSourceInstall = null, pendingSourceInstallWarnings = emptyList()) }
    }
""",
    """    fun cancelSourcePackInstall() {
        container.sourcePlatformManager.cancelPendingInstall()
        mutableState.update {
            it.copy(
                pendingSourceInstall = null,
                pendingSourceInstallWarnings = emptyList(),
                sourceInstallOutcome = null,
                sourceInstallBusy = false,
            )
        }
    }
""",
    "cancel install",
)
text = replace_once(
    text,
    "        prepareRepositorySourceInstall(update.repositoryId, update.sourceId)\n",
    "        installRepositorySource(update.repositoryId, update.sourceId)\n",
    "direct update install",
)
path.write_text(text)


# PersonalScreen: search-as-you-type plus compact install/diagnostic/result dialogs.
path = Path("app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt")
text = path.read_text()
text = replace_once(
    text,
    """    onPrepareRepositorySourceInstall: (String, String) -> Unit,
    onConfirmSourcePackInstall: () -> Unit,
    onCancelSourcePackInstall: () -> Unit,
""",
    """    onPrepareRepositorySourceInstall: (String, String) -> Unit,
    onInstallRepositorySource: (String, String) -> Unit,
    onCancelSourcePackInstall: () -> Unit,
""",
    "PersonalScreen install callbacks",
)
text = replace_once(
    text,
    """            extraContent = {
                if (state.pendingSourceInstall != null) {
                    PendingSourceInstallSection(state, onConfirmSourcePackInstall, onCancelSourcePackInstall)
                }
            },
""",
    "",
    "extensions home approval card",
)
text = text.replace(
    "            PendingSourceInstallSection(state, onConfirmSourcePackInstall, onCancelSourcePackInstall)\n",
    "",
    2,
)
text = replace_once(
    text,
    """                onPrepareInstall = onPrepareRepositorySourceInstall,
                onAddRepository = { showAddRepositoryDialog = true },
""",
    """                onPrepareInstall = onPrepareRepositorySourceInstall,
                onInstall = onInstallRepositorySource,
                onAddRepository = { showAddRepositoryDialog = true },
""",
    "repository callbacks",
)

# Result/diagnostic dialogs are global to the Personal screen so they remain visible
# after the package dialog closes.
marker = "\n}\n\n@Composable\nprivate fun ReferenceSettingsHomePage("
insert = """
    if (state.pendingSourceInstall != null) {
        SourceInstallDiagnosticDialog(state, onCancelSourcePackInstall)
    }
    if (state.sourceInstallOutcome != null) {
        SourceInstallOutcomeDialog(state, onCancelSourcePackInstall)
    }
}

@Composable
private fun ReferenceSettingsHomePage("""
text = replace_once(text, marker, insert, "global install dialogs")

pattern = re.compile(
    r"@Composable\nprivate fun PendingSourceInstallSection\(.*?\n}\n\nprivate fun extensionSearchNormalize",
    re.S,
)
replacement = r'''@Composable
private fun SourceInstallDiagnosticDialog(
    state: MainUiState,
    onDismiss: () -> Unit,
) {
    val preview = state.pendingSourceInstall ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("CHẨN ĐOÁN TIỆN ÍCH") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text("${preview.name} ${preview.version}", fontWeight = FontWeight.SemiBold)
                Text("Nguồn: ${preview.sourceId}", style = MaterialTheme.typography.bodySmall)
                Text("Chữ ký: ${preview.signerKeyId}", style = MaterialTheme.typography.bodySmall)
                Text("Self-test: ${preview.fixtureCount} kiểm tra đạt", style = MaterialTheme.typography.bodySmall)
                preview.permissionSummary.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                state.pendingSourceInstallWarnings.forEach { warning ->
                    Text("Cảnh báo: $warning", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    if (state.pendingSourceInstallWarnings.isEmpty()) "Kết luận: Có thể cài đặt."
                    else "Kết luận: Có thể cài đặt, nhưng có cảnh báo ở trên.",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("ĐÓNG") } },
    )
}

@Composable
private fun SourceInstallOutcomeDialog(
    state: MainUiState,
    onDismiss: () -> Unit,
) {
    val outcome = state.sourceInstallOutcome ?: return
    val versionSuffix = outcome.version.takeIf(String::isNotBlank)?.let { " $it" }.orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (outcome.success) "CÀI ĐẶT THÀNH CÔNG" else "CÀI ĐẶT THẤT BẠI") },
        text = {
            Text(
                if (outcome.success) {
                    "Đã cài ${outcome.name}$versionSuffix và kích hoạt tiện ích."
                } else {
                    "Không thể cài ${outcome.name}$versionSuffix.\n\nNguyên nhân: ${outcome.reason.ifBlank { "Không xác định được nguyên nhân." }}"
                },
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("ĐÓNG") } },
    )
}

private fun extensionSearchNormalize'''
text, count = pattern.subn(replacement, text, count=1)
if count != 1:
    raise SystemExit("failed replacing approval section")

# Installed extensions live search.
text = replace_once(
    text,
    """    var installedQuery by remember { mutableStateOf("") }
    var installedSearchDraft by remember { mutableStateOf("") }
    var showInstalledSearch by remember { mutableStateOf(false) }
""",
    """    var installedQuery by remember { mutableStateOf("") }
""",
    "installed search state",
)
pattern = re.compile(
    r"\n    ReferenceActionButton\(\n        text = if \(installedQuery\.isBlank\(\)\).*?\n    \)\n",
    re.S,
)
replacement = r'''
    OutlinedTextField(
        value = installedQuery,
        onValueChange = { installedQuery = it.take(240) },
        label = { Text("Tìm tiện ích") },
        placeholder = { Text("Nhập tên hoặc vài ký tự liên quan") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
    )
'''
text, count = pattern.subn(replacement, text, count=1)
if count != 1:
    raise SystemExit("failed installed live search field")
pattern = re.compile(
    r"\n    if \(showInstalledSearch\) \{.*?\n    \}\n\n    selectedPackId\?\.let",
    re.S,
)
text, count = pattern.subn("\n\n    selectedPackId?.let", text, count=1)
if count != 1:
    raise SystemExit("failed removing installed search dialog")

# Repository list and repository package list live search.
text = replace_once(
    text,
    """    onRemove: (String) -> Unit,
    onPrepareInstall: (String, String) -> Unit,
    onAddRepository: () -> Unit,
""",
    """    onRemove: (String) -> Unit,
    onPrepareInstall: (String, String) -> Unit,
    onInstall: (String, String) -> Unit,
    onAddRepository: () -> Unit,
""",
    "SourceRepositorySection signature",
)
text = replace_once(
    text,
    """    var repositoryQuery by remember { mutableStateOf("") }
    var repositorySearchDraft by remember { mutableStateOf("") }
    var showRepositorySearch by remember { mutableStateOf(false) }
    var repositoryDetailQuery by remember { mutableStateOf("") }
    var repositoryDetailSearchDraft by remember { mutableStateOf("") }
    var showRepositoryDetailSearch by remember { mutableStateOf(false) }
""",
    """    var repositoryQuery by remember { mutableStateOf("") }
    var repositoryDetailQuery by remember { mutableStateOf("") }
""",
    "repository search state",
)
pattern = re.compile(
    r"\n        ReferenceActionButton\(\n            text = if \(repositoryQuery\.isBlank\(\)\).*?\n        \)\n        if \(repositoryQuery\.isNotBlank\(\)\) \{.*?\n        \}\n",
    re.S,
)
replacement = r'''
        OutlinedTextField(
            value = repositoryQuery,
            onValueChange = { repositoryQuery = it.take(240) },
            label = { Text("Tìm kho") },
            placeholder = { Text("Nhập tên kho hoặc địa chỉ") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        )
'''
text, count = pattern.subn(replacement, text, count=1)
if count != 1:
    raise SystemExit("failed repository live search field")
pattern = re.compile(
    r"\n        ReferenceActionButton\(\n            text = if \(repositoryDetailQuery\.isBlank\(\)\).*?\n        \)\n",
    re.S,
)
replacement = r'''
        OutlinedTextField(
            value = repositoryDetailQuery,
            onValueChange = { repositoryDetailQuery = it.take(240) },
            label = { Text("Tìm trong kho") },
            placeholder = { Text("Nhập tên hoặc vài ký tự liên quan") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        )
'''
text, count = pattern.subn(replacement, text, count=1)
if count != 1:
    raise SystemExit("failed repository detail live search field")
pattern = re.compile(
    r"\n    if \(showRepositorySearch\) \{.*?\n    if \(selectedRepository != null && selectedPackageId != null\)",
    re.S,
)
text, count = pattern.subn(
    "\n\n    if (selectedRepository != null && selectedPackageId != null)",
    text,
    count=1,
)
if count != 1:
    raise SystemExit("failed removing repository search dialogs")

# Compact package install dialog: basic information + CHẨN ĐOÁN / HỦY / CÀI ĐẶT.
text = text.replace(
    "enabled = !state.sourceRepositoryRefreshing && (item.canInstall || installed),",
    "enabled = !state.sourceRepositoryRefreshing && !state.sourceInstallBusy && (item.canInstall || installed),",
)
text = replace_once(
    text,
    '                            append("Phiên bản kho: ${item.version}\\n")\n',
    '                            append("Phiên bản: ${item.version}\\n")\n',
    "package version label",
)
text = text.replace(
    '                            if (item.changelog.isNotBlank()) append("\\n\\nThay đổi: ${item.changelog.trim()}")\n',
    "",
    1,
)
text = replace_once(
    text,
    """                            selectedPackageId = null
                            onPrepareInstall(item.repositoryId, item.sourceId)
                        },
                    ) { Text(if (installed) "CÀI / CẬP NHẬT" else "CÀI") }
""",
    """                            selectedPackageId = null
                            onInstall(item.repositoryId, item.sourceId)
                        },
                    ) { Text("CÀI ĐẶT") }
""",
    "one-step install action",
)
text = replace_once(
    text,
    ') { Text("KIỂM TRA NGUỒN") }\n',
    ') { Text("CHẨN ĐOÁN") }\n',
    "diagnose label",
)
path.write_text(text)


# Wire the new one-step repository install callback in both app shells.
for app_path in (
    Path("app/src/main/java/vn/nghetruyen/app/ui/NgheTruyenApp.kt"),
    Path("app/src/main/java/vn/nghetruyen/app/ui/ReferenceNgheTruyenApp.kt"),
):
    app = app_path.read_text()
    app = replace_once(
        app,
        """                        onPrepareRepositorySourceInstall = viewModel::prepareRepositorySourceInstall,
                        onConfirmSourcePackInstall = viewModel::confirmSourcePackInstall,
                        onCancelSourcePackInstall = viewModel::cancelSourcePackInstall,
""",
        """                        onPrepareRepositorySourceInstall = viewModel::prepareRepositorySourceInstall,
                        onInstallRepositorySource = viewModel::installRepositorySource,
                        onCancelSourcePackInstall = viewModel::cancelSourcePackInstall,
""",
        f"callback wiring {app_path.name}",
    )
    app_path.write_text(app)

print("Live search and one-step install patch applied.")
