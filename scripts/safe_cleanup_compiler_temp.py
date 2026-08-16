from pathlib import Path


def replace_exact(path: str, old: str, new: str, expected: int = 1) -> None:
    p = Path(path)
    text = p.read_text(encoding='utf-8')
    count = text.count(old)
    if count != expected:
        raise SystemExit(f'{path}: expected {expected} matches, found {count}: {old!r}')
    p.write_text(text.replace(old, new), encoding='utf-8')
    print('patched', path, 'matches=', count)


replace_exact('source-api/src/main/kotlin/vn/nghetruyen/source/api/SourceManifest.kt', 'val extensionCleartext = extensionPublicInternet && network?.allowCleartext == true', 'val extensionCleartext = extensionPublicInternet && network.allowCleartext')
replace_exact('source-network/src/main/kotlin/vn/nghetruyen/source/network/SourceNetworkPolicy.kt', 'val cleartext = publicInternet && network?.allowCleartext == true', 'val cleartext = publicInternet && network.allowCleartext')
replace_exact('source-repository/src/main/kotlin/com/nghetruyen/source/repository/VBookRepositoryUpdatePlanner.kt', 'state(local?.version, item.item.version?.toString())', 'state(local?.version, item.item.version)')
replace_exact('source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookJsRuntime.kt', '}.mapNotNull(::normalizeSuggestion).distinctBy { (it as JsonValue.Str).value.lowercase() }.take(20)', '}.mapNotNull(::normalizeSuggestion).distinctBy { it.value.lowercase() }.take(20)')
replace_exact('source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookJsRuntime.kt', '"body" -> fn { document.body()?.let { JsoupElementObject(it, ownerScope) } ?: Context.getUndefinedValue() }', '"body" -> fn { JsoupElementObject(document.body(), ownerScope) }')
replace_exact('source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookJsRuntime.kt', 'metadata.method.equals(options?.propertyString("method"), true)', 'metadata.method.equals(options.propertyString("method"), true)')
replace_exact('source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookStoryNormalizer.kt', 'val whole = doc.body()?.wholeText().orEmpty()', 'val whole = doc.body().wholeText()')
replace_exact('app/src/main/java/vn/nghetruyen/app/ai/OnlineAiServices.kt', 'response.body?.charStream()?.use { reader ->', 'response.body.charStream().use { reader ->', expected=2)
replace_exact('app/src/main/java/vn/nghetruyen/app/ai/XpkNarrationAiServices.kt', 'response.body?.charStream()?.use { reader ->', 'response.body.charStream().use { reader ->')
replace_exact('app/src/main/java/vn/nghetruyen/app/ai/vietphrase/VietPhraseOnlineUpdater.kt', 'val declared = it.body?.contentLength() ?: -1L', 'val declared = it.body.contentLength()')
replace_exact('app/src/main/java/vn/nghetruyen/app/ai/vietphrase/VietPhraseOnlineUpdater.kt', 'val input = it.body?.byteStream() ?: throw IOException("Máy chủ không trả nội dung.")', 'val input = it.body.byteStream()')
replace_exact('app/src/main/java/vn/nghetruyen/app/audio/AudioExportWorker.kt', 'synthesizer!!.', 'synthesizer.', expected=2)
replace_exact('app/src/main/java/vn/nghetruyen/app/importers/BookImporter.kt', 'document.body()?.wholeText().orEmpty().ifBlank { document.wholeText() }', 'document.body().wholeText().ifBlank { document.wholeText() }')
replace_exact('app/src/main/java/vn/nghetruyen/app/importers/BookImporter.kt', 'doc.body()?.wholeText().orEmpty().trim()', 'doc.body().wholeText().trim()')
