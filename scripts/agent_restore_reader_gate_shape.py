from pathlib import Path

p = Path('app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt')
s = p.read_text()

old = '''            val content = container.sourceDiagnostics.navigateWithCausalHandoff(
                traceKind = "chapter-open",'''
new = '''            val navigationContent = container.sourceDiagnostics.navigateWithCausalHandoff(
                traceKind = "chapter-open",'''
assert s.count(old) == 1, s.count(old)
s = s.replace(old, new, 1)

replacements = [
    ('                val rawContent: AppResult<ChapterContent> = when {', '                val content: AppResult<ChapterContent> = when {'),
    ('                when (rawContent) {', '                when (content) {'),
    ('ReaderDocumentNormalizer.normalize(rawContent.value)', 'ReaderDocumentNormalizer.normalize(content.value)'),
    ('                    is AppResult.Failure -> rawContent', '                    is AppResult.Failure -> content'),
    ('            when (content) {\n                is AppResult.Success -> {\n                    val enriched = content.value', '            when (navigationContent) {\n                is AppResult.Success -> {\n                    val enriched = navigationContent.value'),
    ('                is AppResult.Failure -> mutableState.update { it.copy(loading = false, message = content.message) }', '                is AppResult.Failure -> mutableState.update { it.copy(loading = false, message = navigationContent.message) }'),
]
for a, b in replacements:
    assert s.count(a) == 1, (a, s.count(a))
    s = s.replace(a, b, 1)

assert 'ReaderDocumentNormalizer.normalize(content.value)' in s
assert 'val navigationContent = container.sourceDiagnostics.navigateWithCausalHandoff(' in s
p.write_text(s)
print('reader gate shape restored without changing navigation contract')
