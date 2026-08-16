from pathlib import Path
import re
import subprocess

TEMP = {Path('scripts/safe_app_dead_sweep_temp.py'), Path('.github/workflows/safe-app-dead-sweep-temp.yml')}
EXT = {'.kt','.kts','.java','.xml','.gradle','.properties','.toml','.py','.sh','.ps1','.yml','.yaml','.md','.txt','.json','.lua','.patch','.b64'}


def corpus():
    out = {}
    for raw in subprocess.check_output(['git','ls-files'], text=True).splitlines():
        p = Path(raw)
        if p in TEMP or (p.as_posix().startswith('.github/workflows/') and '-temp.' in p.name):
            continue
        if p.suffix.lower() not in EXT:
            continue
        try: out[p] = p.read_text(encoding='utf-8', errors='replace')
        except OSError: pass
    return out


def count_token(texts, name):
    rx = re.compile(rf'\b{re.escape(name)}\b')
    return sum(len(rx.findall(t)) for t in texts.values())


def replace_once(path, old, new=''):
    p = Path(path); text = p.read_text(encoding='utf-8'); count = text.count(old)
    if count != 1: raise SystemExit(f'{path}: expected 1 exact match, found {count}: {old!r}')
    p.write_text(text.replace(old, new, 1), encoding='utf-8'); print('patched', path)


def remove_decl_only(texts, name, path, block):
    c = count_token(texts, name); print('token_count', name, c)
    if c != 1:
        print('SKIP', name, 'not declaration-only'); return
    replace_once(path, block)


texts = corpus()

# AppViewModel helpers with no call site anywhere in the tracked tree.
remove_decl_only(texts, 'assignmentId', 'app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt', '    private fun assignmentId(chapterId: String, paragraphIndex: Int) = UUID.nameUUIDFromBytes("$chapterId\\u0000voice\\u0000$paragraphIndex".toByteArray()).toString()\n')
remove_decl_only(texts, 'sceneCueId', 'app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt', '    private fun sceneCueId(chapterId: String, paragraphIndex: Int) = UUID.nameUUIDFromBytes("$chapterId\\u0000scene\\u0000$paragraphIndex".toByteArray()).toString()\n')
remove_decl_only(texts, 'openStoryAiOptions', 'app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt', '    fun openStoryAiOptions() = openStoryAdvancedOptions("ai")\n\n')
remove_decl_only(texts, 'openStoryVoiceCastOptions', 'app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt', '    fun openStoryVoiceCastOptions() = openStoryAdvancedOptions("voice")\n\n')

# Private UI helpers proven declaration-only.
personal_helper = '''private fun extensionDiagnosticLabel(name: String, severity: String): String {
    val action = when {
        name.contains("INSTALL", ignoreCase = true) -> "Cài đặt tiện ích"
        name.contains("PACKAGE", ignoreCase = true) || name.contains("FETCH", ignoreCase = true) -> "Tải dữ liệu"
        name.contains("NETWORK", ignoreCase = true) || name.contains("HTTP", ignoreCase = true) -> "Kết nối mạng"
        name.contains("PARSE", ignoreCase = true) || name.contains("PARSER", ignoreCase = true) -> "Phân tích dữ liệu"
        name.contains("BROWSER", ignoreCase = true) || name.contains("WEBVIEW", ignoreCase = true) -> "Trình duyệt"
        name.contains("LOGIN", ignoreCase = true) || name.contains("SESSION", ignoreCase = true) -> "Phiên đăng nhập"
        name.contains("ACTION", ignoreCase = true) || name.contains("RUNTIME", ignoreCase = true) -> "Chạy tiện ích"
        else -> name.replace('_', ' ').lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
    }
    return when {
        severity.equals("ERROR", ignoreCase = true) || name.contains("FAILED", ignoreCase = true) -> "$action thất bại"
        name.contains("STARTED", ignoreCase = true) -> "Bắt đầu $action"
        name.contains("COMPLETED", ignoreCase = true) || name.contains("SUCCEEDED", ignoreCase = true) -> "$action hoàn tất"
        else -> action
    }
}

'''
remove_decl_only(texts, 'extensionDiagnosticLabel', 'app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt', personal_helper)

slider = '''@Composable
private fun ReferenceFloatSlider(
    label: String,
    value: Float,
    minimum: Float,
    maximum: Float,
    percent: Boolean = false,
    onChange: (Float) -> Unit,
) {
    val shown = value.coerceIn(minimum, maximum)
    Text(
        if (percent) "$label: ${"%.0f".format(shown * 100)}%" else "$label: ${"%.2f".format(shown)}x",
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 6.dp),
    )
    Slider(value = shown, onValueChange = onChange, valueRange = minimum..maximum)
}
'''
remove_decl_only(texts, 'ReferenceFloatSlider', 'app/src/main/java/vn/nghetruyen/app/ui/screens/StoryDetailScreen.kt', slider)

# Remove fake callback retention only from InstalledSourcesSection.
pp = 'app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt'
old_call = '''            InstalledSourcesSection(
                state = state,
                onEnabledChange = onSourcePackEnabledChange,
                onRollback = onRollbackSourcePack,
                onUpdate = onUpdateSourcePack,
                onExport = onExportSourcePack,
                onRemove = onRemoveSourcePack,
                onCheck = onCheckSourcePack,
                onSaveConfig = onSaveSourceConfig,
                onResetConfig = onResetSourceConfig,
                onLogin = onOpenSourceLogin,
                onExportDiagnostics = onExportSourceDiagnostics,
                onClearDiagnostics = onClearSourceDiagnostics,
            )'''
new_call = '''            InstalledSourcesSection(
                state = state,
                onEnabledChange = onSourcePackEnabledChange,
                onUpdate = onUpdateSourcePack,
                onExport = onExportSourcePack,
                onRemove = onRemoveSourcePack,
                onCheck = onCheckSourcePack,
                onSaveConfig = onSaveSourceConfig,
                onResetConfig = onResetSourceConfig,
                onExportDiagnostics = onExportSourceDiagnostics,
                onClearDiagnostics = onClearSourceDiagnostics,
            )'''
replace_once(pp, old_call, new_call)
old_sig = '''private fun InstalledSourcesSection(
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
    onExportDiagnostics: () -> Unit,
    onClearDiagnostics: () -> Unit,
)'''
new_sig = '''private fun InstalledSourcesSection(
    state: MainUiState,
    onEnabledChange: (String, Boolean) -> Unit,
    onUpdate: (String) -> Unit,
    onExport: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    onCheck: (String) -> Unit,
    onSaveConfig: (String, Map<String, String>) -> Unit,
    onResetConfig: (String) -> Unit,
    onExportDiagnostics: () -> Unit,
    onClearDiagnostics: () -> Unit,
)'''
replace_once(pp, old_sig, new_sig)
replace_once(pp, '    // Kept in the signature because the runtime still supports them, but the primary action\n    // surface intentionally follows the Lua menu and therefore does not expose these here.\n    @Suppress("UNUSED_VARIABLE")\n    val retainedRuntimeActions = listOf(onRollback, onLogin)\n\n')

# Entire app-only files with every public symbol declaration-only.
for path, names in [
    ('app/src/main/java/vn/nghetruyen/app/ui/components/AccessibilityControls.kt', ['LabeledSwitchRow','LabeledCheckboxRow','ExplicitButton','ExplicitTextButton']),
    ('app/src/main/java/vn/nghetruyen/app/sourceplatform/SourceSessionCookiePartition.kt', ['SourceSessionCookiePartition']),
]:
    counts = {n: count_token(texts,n) for n in names}; print('file_counts', path, counts)
    if all(v == 1 for v in counts.values()):
        Path(path).unlink(); print('deleted', path)
    else: print('SKIP file', path)

remove_decl_only(texts, 'AudioExportProgress', 'app/src/main/java/vn/nghetruyen/app/audio/AudioExportEngine.kt', 'data class AudioExportProgress(\n    val completedSegments: Int,\n    val totalSegments: Int,\n    val stage: String,\n)\n\n')
remove_decl_only(texts, 'LargeActionButton', 'app/src/main/java/vn/nghetruyen/app/ui/components/Common.kt', '''@Composable
fun LargeActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    ReferenceActionButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        minHeight = 56.dp,
    )
}

''')
remove_decl_only(texts, 'enqueueStory', 'app/src/main/java/vn/nghetruyen/app/downloads/DownloadScheduler.kt', '''    fun enqueueStory(
        sourceId: String,
        storyId: String,
        wifiOnly: Boolean = false,
        chargingOnly: Boolean = false,
    ): DownloadRequest = enqueue(
        DownloadRequest.create(
            sourceId = sourceId,
            storyId = storyId,
            selectionMode = DownloadSelectionMode.ALL,
            wifiOnly = wifiOnly,
            chargingOnly = chargingOnly,
        ),
    )

''')

install = '''    fun installOrUpdateVBook(
        repositoryId: String,
        remoteIdentity: String,
        version: String?,
        packageBytes: ByteArray,
        trust: SourceTrustState = SourceTrustState.REPOSITORY_TRUSTED,
    ): VBookUpdateResult {
        val result = vBookSourcePlatform.installOrUpdate(
            repositoryId = repositoryId,
            remoteIdentity = remoteIdentity,
            version = version,
            packageBytes = packageBytes,
            trust = trust,
        )
        refreshSourceRegistry()
        return result
    }

'''
remove_decl_only(texts, 'installOrUpdateVBook', 'app/src/main/java/vn/nghetruyen/app/AppContainer.kt', install)
rollback = '''    fun rollbackVBook(repositoryId: String, remoteIdentity: String): SourceArtifactDescriptor {
        val restored = vBookSourcePlatform.rollback(repositoryId, remoteIdentity)
        refreshSourceRegistry()
        return restored
    }

'''
remove_decl_only(texts, 'rollbackVBook', 'app/src/main/java/vn/nghetruyen/app/AppContainer.kt', rollback)

# Root report duplicates: keep docs/ copy only if bytes are identical and all refs are docs-local.
for root_name, docs_name in [
    ('MILESTONE_3_VIETPHRASE_COMPLETION_REPORT.md','docs/MILESTONE_3_VIETPHRASE_COMPLETION_REPORT.md'),
    ('MILESTONE_5_PLAYBACK_COMPLETION_REPORT.md','docs/MILESTONE_5_PLAYBACK_COMPLETION_REPORT.md'),
]:
    root=Path(root_name); docs=Path(docs_name)
    if not root.is_file() or not docs.is_file() or root.read_bytes()!=docs.read_bytes():
        raise SystemExit(f'duplicate proof failed for {root_name}')
    refs=[]
    for p,t in texts.items():
        if p in {root,docs}: continue
        if root_name in t:
            refs.append(str(p))
            if not p.as_posix().startswith('docs/') or f'../{root_name}' in t:
                raise SystemExit(f'root-specific ref blocks deletion: {root_name} from {p}')
    print('report_refs', root_name, refs); root.unlink(); print('deleted', root_name)
