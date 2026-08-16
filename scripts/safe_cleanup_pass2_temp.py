from pathlib import Path
import re
import subprocess

TEMP_PATHS = {
    Path('scripts/safe_cleanup_pass2_temp.py'),
    Path('.github/workflows/safe-cleanup-pass2b-temp.yml'),
}


def replace_exact(path: str, old: str, new: str, expected: int = 1) -> None:
    p = Path(path)
    text = p.read_text(encoding='utf-8')
    count = text.count(old)
    if count != expected:
        raise SystemExit(f'{path}: expected {expected} matches, found {count}: {old!r}')
    p.write_text(text.replace(old, new), encoding='utf-8')
    print('patched', path, 'matches=', count)


# Compiler-proven redundant null handling / casts.
replace_exact(
    'source-api/src/main/kotlin/vn/nghetruyen/source/api/SourceManifest.kt',
    'val extensionCleartext = extensionPublicInternet && network?.allowCleartext == true',
    'val extensionCleartext = extensionPublicInternet && network.allowCleartext',
)
replace_exact(
    'source-network/src/main/kotlin/vn/nghetruyen/source/network/SourceNetworkPolicy.kt',
    'val cleartext = publicInternet && network?.allowCleartext == true',
    'val cleartext = publicInternet && network.allowCleartext',
)
replace_exact(
    'source-repository/src/main/kotlin/com/nghetruyen/source/repository/VBookRepositoryUpdatePlanner.kt',
    'item.item.version?.toString() == update.version',
    'item.item.version == update.version',
)
replace_exact(
    'source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookJsRuntime.kt',
    '}.mapNotNull(::normalizeSuggestion).distinctBy { (it as JsonValue.Str).value.lowercase() }.take(20)',
    '}.mapNotNull(::normalizeSuggestion).distinctBy { it.value.lowercase() }.take(20)',
)
replace_exact(
    'source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookJsRuntime.kt',
    '"body" -> fn { document.body()?.let { JsoupElementObject(it, ownerScope) } ?: Context.getUndefinedValue() }',
    '"body" -> fn { JsoupElementObject(document.body(), ownerScope) }',
)
replace_exact(
    'source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookJsRuntime.kt',
    'metadata.method.equals(options?.propertyString("method"), true)',
    'metadata.method.equals(options.propertyString("method"), true)',
)
replace_exact(
    'source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookStoryNormalizer.kt',
    'val body = document.body()?.text().orEmpty().ifBlank { document.text() }',
    'val body = document.body().text().ifBlank { document.text() }',
)
replace_exact(
    'app/src/main/java/vn/nghetruyen/app/ai/OnlineAiServices.kt',
    'response.body?.charStream()?.use { reader ->',
    'response.body.charStream().use { reader ->',
    expected=2,
)
replace_exact(
    'app/src/main/java/vn/nghetruyen/app/ai/XpkNarrationAiServices.kt',
    'response.body?.charStream()?.use { reader ->',
    'response.body.charStream().use { reader ->',
)
replace_exact(
    'app/src/main/java/vn/nghetruyen/app/ai/vietphrase/VietPhraseOnlineUpdater.kt',
    'val declared = it.body?.contentLength() ?: -1L',
    'val declared = it.body.contentLength()',
)
replace_exact(
    'app/src/main/java/vn/nghetruyen/app/ai/vietphrase/VietPhraseOnlineUpdater.kt',
    'val input = it.body?.byteStream() ?: throw IOException("Máy chủ không trả nội dung.")',
    'val input = it.body.byteStream()',
)
replace_exact(
    'app/src/main/java/vn/nghetruyen/app/audio/AudioExportWorker.kt',
    'synthesizer!!.',
    'synthesizer.',
    expected=2,
)
replace_exact(
    'app/src/main/java/vn/nghetruyen/app/importers/BookImporter.kt',
    'document.body()?.wholeText().orEmpty().ifBlank { document.wholeText() }',
    'document.body().wholeText().ifBlank { document.wholeText() }',
)
replace_exact(
    'app/src/main/java/vn/nghetruyen/app/importers/BookImporter.kt',
    'doc.body()?.wholeText().orEmpty().trim()',
    'doc.body().wholeText().trim()',
)

# Repo-wide text corpus excluding temporary cleanup machinery.
tracked = [Path(x) for x in subprocess.check_output(['git', 'ls-files'], text=True).splitlines()]
text_ext = {'.kt', '.kts', '.java', '.xml', '.gradle', '.properties', '.toml', '.py', '.sh', '.ps1', '.yml', '.yaml', '.md', '.txt', '.json', '.lua', '.patch', '.b64'}
texts = {}
for p in tracked:
    if p in TEMP_PATHS or (p.as_posix().startswith('.github/workflows/') and '-temp.' in p.name):
        continue
    if p.suffix.lower() not in text_ext:
        continue
    try:
        texts[p] = p.read_text(encoding='utf-8', errors='replace')
    except OSError:
        pass
joined = '\n'.join(texts.values())

for symbol in ['assignmentId', 'sceneCueId', 'extensionDiagnosticLabel', 'ReferenceFloatSlider']:
    count = len(re.findall(rf'\b{re.escape(symbol)}\b', joined))
    print('symbol_count', symbol, count)
    if count != 1:
        raise SystemExit(f'{symbol}: expected declaration-only count 1, found {count}; refusing removal')

replace_exact(
    'app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt',
    '    private fun assignmentId(chapterId: String, paragraphIndex: Int) = UUID.nameUUIDFromBytes("$chapterId\\u0000voice\\u0000$paragraphIndex".toByteArray()).toString()\n'
    '    private fun sceneCueId(chapterId: String, paragraphIndex: Int) = UUID.nameUUIDFromBytes("$chapterId\\u0000scene\\u0000$paragraphIndex".toByteArray()).toString()\n',
    '',
)

personal_block = '''private fun extensionDiagnosticLabel(name: String, severity: String): String {
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
replace_exact(
    'app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt',
    personal_block,
    '',
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
}'''
replace_exact(
    'app/src/main/java/vn/nghetruyen/app/ui/screens/StoryDetailScreen.kt',
    slider_block,
    '',
)

# Root duplicates: retain canonical docs copies only after exact byte and reference proof.
duplicate_pairs = [
    ('MILESTONE_3_VIETPHRASE_COMPLETION_REPORT.md', 'docs/MILESTONE_3_VIETPHRASE_COMPLETION_REPORT.md'),
    ('MILESTONE_5_PLAYBACK_COMPLETION_REPORT.md', 'docs/MILESTONE_5_PLAYBACK_COMPLETION_REPORT.md'),
]
for root_name, docs_name in duplicate_pairs:
    root = Path(root_name)
    docs = Path(docs_name)
    if not root.is_file() or not docs.is_file():
        raise SystemExit(f'missing duplicate pair: {root_name}, {docs_name}')
    if root.read_bytes() != docs.read_bytes():
        raise SystemExit(f'content diverged; refusing root duplicate deletion: {root_name}')
    refs = []
    for p, text in texts.items():
        if p in {root, docs}:
            continue
        if root_name in text:
            refs.append(p)
            if not p.as_posix().startswith('docs/'):
                raise SystemExit(f'{root_name}: non-doc reference exists in {p}')
            if f'../{root_name}' in text:
                raise SystemExit(f'{root_name}: explicit root reference exists in {p}')
    print('duplicate_refs', root_name, [str(p) for p in refs])
    root.unlink()
    print('deleted root duplicate', root_name)
