from pathlib import Path
import re, subprocess

TEMP={Path('scripts/safe_proven_dead_temp.py'),Path('.github/workflows/safe-proven-dead-temp.yml')}
EXT={'.kt','.kts','.java','.xml','.gradle','.properties','.toml','.py','.sh','.ps1','.yml','.yaml','.md','.txt','.json','.lua','.patch','.b64'}
texts={}
for raw in subprocess.check_output(['git','ls-files'],text=True).splitlines():
    p=Path(raw)
    if p in TEMP or (p.as_posix().startswith('.github/workflows/') and '-temp.' in p.name) or p.suffix.lower() not in EXT: continue
    try: texts[p]=p.read_text(encoding='utf-8',errors='replace')
    except OSError: pass

def count(name):
    rx=re.compile(rf'\b{re.escape(name)}\b')
    return sum(len(rx.findall(t)) for t in texts.values())

def replace1(path,old,new=''):
    p=Path(path); t=p.read_text(encoding='utf-8'); n=t.count(old)
    if n!=1: raise SystemExit(f'{path}: expected exact match once, found {n}')
    p.write_text(t.replace(old,new,1),encoding='utf-8'); print('patched',path)

def dead(name,path,block):
    n=count(name); print(name,n)
    if n!=1: raise SystemExit(f'{name}: expected declaration-only token count 1, found {n}')
    replace1(path,block)

vm='app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt'
dead('assignmentId',vm,'    private fun assignmentId(chapterId: String, paragraphIndex: Int) = UUID.nameUUIDFromBytes("$chapterId\\u0000voice\\u0000$paragraphIndex".toByteArray()).toString()\n')
dead('sceneCueId',vm,'    private fun sceneCueId(chapterId: String, paragraphIndex: Int) = UUID.nameUUIDFromBytes("$chapterId\\u0000scene\\u0000$paragraphIndex".toByteArray()).toString()\n')
dead('openStoryAiOptions',vm,'    fun openStoryAiOptions() = openStoryAdvancedOptions("ai")\n\n')
dead('openStoryVoiceCastOptions',vm,'    fun openStoryVoiceCastOptions() = openStoryAdvancedOptions("voice")\n\n')
ph='''private fun extensionDiagnosticLabel(name: String, severity: String): String {
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
dead('extensionDiagnosticLabel','app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt',ph)
slider='''@Composable
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
story='app/src/main/java/vn/nghetruyen/app/ui/screens/StoryDetailScreen.kt'
dead('ReferenceFloatSlider',story,slider)
p=Path(story); p.write_text(p.read_text(encoding='utf-8').rstrip()+"\n",encoding='utf-8')

pp='app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt'
old='''            InstalledSourcesSection(
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
new='''            InstalledSourcesSection(
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
replace1(pp,old,new)
old='''private fun InstalledSourcesSection(
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
new='''private fun InstalledSourcesSection(
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
replace1(pp,old,new)
replace1(pp,'    // Kept in the signature because the runtime still supports them, but the primary action\n    // surface intentionally follows the Lua menu and therefore does not expose these here.\n    @Suppress("UNUSED_VARIABLE")\n    val retainedRuntimeActions = listOf(onRollback, onLogin)\n\n')
for path,names in [
 ('app/src/main/java/vn/nghetruyen/app/ui/components/AccessibilityControls.kt',['LabeledSwitchRow','LabeledCheckboxRow','ExplicitButton','ExplicitTextButton']),
 ('app/src/main/java/vn/nghetruyen/app/sourceplatform/SourceSessionCookiePartition.kt',['SourceSessionCookiePartition']),
]:
    c={n:count(n) for n in names}; print(path,c)
    if not all(v==1 for v in c.values()): raise SystemExit(f'{path}: no longer declaration-only: {c}')
    Path(path).unlink(); print('deleted',path)
