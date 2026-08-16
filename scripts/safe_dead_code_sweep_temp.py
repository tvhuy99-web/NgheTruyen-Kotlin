from pathlib import Path
import re
import subprocess

TEMP_PATHS = {
    Path('scripts/safe_dead_code_sweep_temp.py'),
    Path('.github/workflows/safe-dead-code-sweep-temp.yml'),
}
TEXT_EXT = {'.kt', '.kts', '.java', '.xml', '.gradle', '.properties', '.toml', '.py', '.sh', '.ps1', '.yml', '.yaml', '.md', '.txt', '.json', '.lua', '.patch', '.b64'}


def tracked_texts():
    paths = [Path(x) for x in subprocess.check_output(['git', 'ls-files'], text=True).splitlines()]
    result = {}
    for p in paths:
        if p in TEMP_PATHS or (p.as_posix().startswith('.github/workflows/') and '-temp.' in p.name):
            continue
        if p.suffix.lower() not in TEXT_EXT:
            continue
        try:
            result[p] = p.read_text(encoding='utf-8', errors='replace')
        except OSError:
            pass
    return result


def token_count(texts, symbol):
    rx = re.compile(rf'\b{re.escape(symbol)}\b')
    return sum(len(rx.findall(text)) for text in texts.values())


def replace_exact(path, old, new, expected=1):
    p = Path(path)
    text = p.read_text(encoding='utf-8')
    count = text.count(old)
    if count != expected:
        raise SystemExit(f'{path}: expected {expected} exact matches, found {count}: {old!r}')
    p.write_text(text.replace(old, new), encoding='utf-8')
    print('patched', path, 'matches=', count)


def remove_block_if_declaration_only(texts, symbol, path, block):
    count = token_count(texts, symbol)
    print('token_count', symbol, count)
    if count != 1:
        print('SKIP', symbol, 'because it is not declaration-only')
        return False
    replace_exact(path, block, '')
    return True


def delete_file_if_symbols_declaration_only(texts, path, symbols):
    counts = {s: token_count(texts, s) for s in symbols}
    print('file_candidate', path, counts)
    if any(c != 1 for c in counts.values()):
        print('SKIP file', path, 'because at least one symbol has another reference')
        return False
    p = Path(path)
    if not p.is_file():
        raise SystemExit(f'missing candidate file: {path}')
    p.unlink()
    print('deleted', path)
    return True


texts = tracked_texts()

# Private declaration-only helpers.
remove_block_if_declaration_only(
    texts,
    'assignmentId',
    'app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt',
    '    private fun assignmentId(chapterId: String, paragraphIndex: Int) = UUID.nameUUIDFromBytes("$chapterId\\u0000voice\\u0000$paragraphIndex".toByteArray()).toString()\n',
)
remove_block_if_declaration_only(
    texts,
    'sceneCueId',
    'app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt',
    '    private fun sceneCueId(chapterId: String, paragraphIndex: Int) = UUID.nameUUIDFromBytes("$chapterId\\u0000scene\\u0000$paragraphIndex".toByteArray()).toString()\n',
)
remove_block_if_declaration_only(
    texts,
    'openStoryAiOptions',
    'app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt',
    '    fun openStoryAiOptions() = openStoryAdvancedOptions("ai")\n\n',
)
remove_block_if_declaration_only(
    texts,
    'openStoryVoiceCastOptions',
    'app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt',
    '    fun openStoryVoiceCastOptions() = openStoryAdvancedOptions("voice")\n\n',
)

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
remove_block_if_declaration_only(
    texts,
    'extensionDiagnosticLabel',
    'app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt',
    personal_helper,
)

slider_block = '''@Composable
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
remove_block_if_declaration_only(
    texts,
    'ReferenceFloatSlider',
    'app/src/main/java/vn/nghetruyen/app/ui/screens/StoryDetailScreen.kt',
    slider_block,
)

# InstalledSourcesSection had two callbacks retained only through a fake list to suppress unused warnings.
personal_path = 'app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt'
replace_exact(personal_path, '                onRollback = onRollbackSourcePack,\n', '')
replace_exact(personal_path, '                onLogin = onOpenSourceLogin,\n', '')
replace_exact(personal_path, '    onRollback: (String) -> Unit,\n', '')
replace_exact(personal_path, '    onLogin: (String) -> Unit,\n', '')
replace_exact(
    personal_path,
    '    // Kept in the signature because the runtime still supports them, but the primary action\n'
    '    // surface intentionally follows the Lua menu and therefore does not expose these here.\n'
    '    @Suppress("UNUSED_VARIABLE")\n'
    '    val retainedRuntimeActions = listOf(onRollback, onLogin)\n\n',
    '',
)

# Whole files whose public names occur only at their declarations in the whole tracked tree.
delete_file_if_symbols_declaration_only(
    texts,
    'app/src/main/java/vn/nghetruyen/app/ui/components/AccessibilityControls.kt',
    ['LabeledSwitchRow', 'LabeledCheckboxRow', 'ExplicitButton', 'ExplicitTextButton'],
)
delete_file_if_symbols_declaration_only(
    texts,
    'app/src/main/java/vn/nghetruyen/app/sourceplatform/SourceSessionCookiePartition.kt',
    ['SourceSessionCookiePartition'],
)

# Small app-only declaration-only APIs.
remove_block_if_declaration_only(
    texts,
    'AudioExportProgress',
    'app/src/main/java/vn/nghetruyen/app/audio/AudioExportEngine.kt',
    'data class AudioExportProgress(\n    val completedSegments: Int,\n    val totalSegments: Int,\n    val stage: String,\n)\n',
)
remove_block_if_declaration_only(
    texts,
    'LargeActionButton',
    'app/src/main/java/vn/nghetruyen/app/ui/components/Common.kt',
    '''@Composable
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

''',
)
remove_block_if_declaration_only(
    texts,
    'enqueueStory',
    'app/src/main/java/vn/nghetruyen/app/downloads/DownloadScheduler.kt',
    '''    fun enqueueStory(
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

''',
)

install_vbook_block = '''    fun installOrUpdateVBook(
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
remove_block_if_declaration_only(texts, 'installOrUpdateVBook', 'app/src/main/java/vn/nghetruyen/app/AppContainer.kt', install_vbook_block)
rollback_vbook_block = '''    fun rollbackVBook(repositoryId: String, remoteIdentity: String): SourceArtifactDescriptor {
        val restored = vBookSourcePlatform.rollback(repositoryId, remoteIdentity)
        refreshSourceRegistry()
        return restored
    }

'''
remove_block_if_declaration_only(texts, 'rollbackVBook', 'app/src/main/java/vn/nghetruyen/app/AppContainer.kt', rollback_vbook_block)

# Room observers superseded by the joined progress query and one chapter-storage snapshot.
repo_path = 'app/src/main/java/vn/nghetruyen/app/data/repository/LibraryRepository.kt'
room_wrapper_guards = {
    'fun observeReadingProgress(): Flow<List<ReadingProgressEntity>> = db.progressDao().observeAll()\n': 'db.progressDao().observeAll()',
    '    fun observeOfflineStorage(): Flow<List<OfflineStoryStorage>> = db.chapterDao().observeOfflineStorage()\n': 'db.chapterDao().observeOfflineStorage()',
    '    fun observeDownloadedChapterIds(): Flow<List<String>> = db.chapterDao().observeDownloadedIds()\n': 'db.chapterDao().observeDownloadedIds()',
    '    fun observeStorageUsage(): Flow<StorageUsage> = db.chapterDao().observeStorageUsage()\n': 'db.chapterDao().observeStorageUsage()',
}
repo_text = Path(repo_path).read_text(encoding='utf-8')
for wrapper, call in room_wrapper_guards.items():
    call_count = sum(text.count(call) for text in texts.values())
    print('room_wrapper_call_count', call, call_count)
    if call_count != 1:
        raise SystemExit(f'{call}: expected wrapper-only occurrence, found {call_count}')
    if repo_text.count(wrapper) != 1:
        raise SystemExit(f'wrapper exact block mismatch for {call}')
    repo_text = repo_text.replace(wrapper, '', 1)
Path(repo_path).write_text(repo_text, encoding='utf-8')

# Now remove the matching DAO methods/queries. Exact blocks prevent accidental removal of similarly named APIs.
db_path = 'app/src/main/java/vn/nghetruyen/app/data/local/AppDatabase.kt'
replace_exact(
    db_path,
    '    @Query("SELECT id FROM chapters WHERE downloadedAt IS NOT NULL AND content IS NOT NULL AND TRIM(content) != \'\'")\n'
    '    fun observeDownloadedIds(): Flow<List<String>>\n\n',
    '',
)
replace_exact(
    db_path,
    '''    @Query("""
        SELECT c.storyId AS storyId, COUNT(*) AS chapterCount,
               COALESCE(SUM(LENGTH(CAST(c.content AS BLOB))), 0) AS bytes
        FROM chapters c
        INNER JOIN stories s ON s.id = c.storyId
        WHERE c.downloadedAt IS NOT NULL AND c.content IS NOT NULL AND TRIM(c.content) != ''
        GROUP BY c.storyId
    """)
    fun observeOfflineStorage(): Flow<List<OfflineStoryStorage>>

''',
    '',
)
replace_exact(
    db_path,
    '''    @Query("""
        SELECT
          COALESCE(SUM(CASE WHEN c.downloadedAt IS NOT NULL AND c.content IS NOT NULL AND TRIM(c.content) != '' THEN 1 ELSE 0 END), 0) AS downloadedChapters,
          COALESCE(SUM(CASE WHEN c.downloadedAt IS NOT NULL THEN LENGTH(CAST(c.content AS BLOB)) ELSE 0 END), 0) AS downloadedBytes,
          COALESCE(SUM(CASE WHEN c.downloadedAt IS NULL AND c.content IS NOT NULL AND TRIM(c.content) != '' THEN 1 ELSE 0 END), 0) AS cachedChapters,
          COALESCE(SUM(CASE WHEN c.downloadedAt IS NULL THEN LENGTH(CAST(c.content AS BLOB)) ELSE 0 END), 0) AS cachedBytes
        FROM chapters c
        INNER JOIN stories s ON s.id = c.storyId
    """)
    fun observeStorageUsage(): Flow<StorageUsage>

''',
    '',
)
replace_exact(
    db_path,
    '    @Query("SELECT * FROM reading_progress ORDER BY updatedAt DESC")\n'
    '    fun observeAll(): Flow<List<ReadingProgressEntity>>\n\n',
    '',
)

# Root report copies must be byte-identical to docs/ copies and have no root-specific references.
for root_name, docs_name in [
    ('MILESTONE_3_VIETPHRASE_COMPLETION_REPORT.md', 'docs/MILESTONE_3_VIETPHRASE_COMPLETION_REPORT.md'),
    ('MILESTONE_5_PLAYBACK_COMPLETION_REPORT.md', 'docs/MILESTONE_5_PLAYBACK_COMPLETION_REPORT.md'),
]:
    root = Path(root_name)
    docs = Path(docs_name)
    if not root.is_file() or not docs.is_file():
        raise SystemExit(f'missing report duplicate pair: {root_name}, {docs_name}')
    if root.read_bytes() != docs.read_bytes():
        raise SystemExit(f'report pair diverged: {root_name}')
    refs = []
    for p, text in texts.items():
        if p in {root, docs}:
            continue
        if root_name in text:
            refs.append(str(p))
            if not p.as_posix().startswith('docs/') or f'../{root_name}' in text:
                raise SystemExit(f'root-specific reference prevents deletion of {root_name}: {p}')
    print('report_refs', root_name, refs)
    root.unlink()
    print('deleted root duplicate', root_name)
