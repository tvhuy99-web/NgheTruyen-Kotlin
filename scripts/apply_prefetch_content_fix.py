from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"Expected block not found in {path}: {old[:180]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_between(path: Path, start: str, end: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    i = text.find(start)
    if i < 0:
        raise SystemExit(f"Start marker not found in {path}: {start!r}")
    j = text.find(end, i)
    if j < 0:
        raise SystemExit(f"End marker not found in {path}: {end!r}")
    path.write_text(text[:i] + new + text[j:], encoding="utf-8")


formatter = Path("app/src/main/java/vn/nghetruyen/app/playback/FreesoundPlaybackStatusFormatter.kt")
replace_once(
    formatter,
    '''            if (downloaded > 0) add("$downloaded tải mới")
            if (reused > 0) add("$reused bộ nhớ tạm")
''',
    '''            add("$downloaded tải mới")
            add("$reused bộ nhớ tạm")
''',
)

queue = Path("app/src/main/java/vn/nghetruyen/app/playback/PlaybackQueueStore.kt")
replace_between(
    queue,
    '''        val parentId = parentChapterId.trim()
        val targetId = targetChapterId.trim()
''',
    '''        val current = mutable.value
''',
    '''        val parentId = parentChapterId.trim()
        val targetId = targetChapterId.trim()
        rememberFreesoundTransferSummary(targetId, downloadedAssets, reusedAssets)

''',
)
replace_between(
    queue,
    '''    fun consumeFreesoundTransferSummary(
''',
    '''    fun setPlaying(value: Boolean) {
''',
    '''    fun rememberFreesoundTransferSummary(
        chapterId: String,
        downloadedAssets: Int,
        reusedAssets: Int,
    ) {
        val id = chapterId.trim()
        if (id.isEmpty()) return
        val incoming = FreesoundTransferSummary(
            downloadedAssets = downloadedAssets.coerceAtLeast(0),
            reusedAssets = reusedAssets.coerceAtLeast(0),
        )
        if (incoming.downloadedAssets == 0 && incoming.reusedAssets == 0) return
        synchronized(narrationTransferLock) {
            prefetchedFreesoundTransfers[id] = mergeFreesoundTransferSummaries(
                previous = prefetchedFreesoundTransfers[id],
                current = incoming,
            )
            while (prefetchedFreesoundTransfers.size > MAX_PREFETCH_TRANSFER_ENTRIES) {
                val oldest = prefetchedFreesoundTransfers.keys.firstOrNull() ?: break
                prefetchedFreesoundTransfers.remove(oldest)
            }
        }
    }

    fun consumeFreesoundTransferSummary(
        chapterId: String,
        currentDownloadedAssets: Int,
        currentReusedAssets: Int,
    ): FreesoundTransferSummary {
        val current = FreesoundTransferSummary(
            downloadedAssets = currentDownloadedAssets.coerceAtLeast(0),
            reusedAssets = currentReusedAssets.coerceAtLeast(0),
        )
        val id = chapterId.trim()
        if (id.isEmpty()) return current
        val prefetched = synchronized(narrationTransferLock) {
            prefetchedFreesoundTransfers.remove(id)
        } ?: return current
        return mergeFreesoundTransferSummaries(prefetched, current)
    }

    private fun mergeFreesoundTransferSummaries(
        previous: FreesoundTransferSummary?,
        current: FreesoundTransferSummary,
    ): FreesoundTransferSummary {
        if (previous == null) return current
        val previousDownloads = previous.downloadedAssets.coerceAtLeast(0)
        return FreesoundTransferSummary(
            downloadedAssets = previousDownloads + current.downloadedAssets.coerceAtLeast(0),
            reusedAssets = maxOf(
                previous.reusedAssets.coerceAtLeast(0),
                current.reusedAssets.coerceAtLeast(0) - previousDownloads,
            ).coerceAtLeast(0),
        )
    }

''',
)

service = Path("app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt")
replace_once(
    service,
    '''                val result = attempt.getOrNull()
                if (offset == 0) {
                    val assignmentCount = if (!planVoice || result == null) {
''',
    '''                val result = attempt.getOrNull()
                if (offset == 0) {
                    val transferSummary = if (result == null) {
                        FreesoundTransferSummary()
                    } else {
                        PlaybackQueueStore.consumeFreesoundTransferSummary(
                            chapterId = chapter.chapter.id,
                            currentDownloadedAssets = result.freesoundDownloadedAssets,
                            currentReusedAssets = result.freesoundReusedAssets,
                        )
                    }
                    val assignmentCount = if (!planVoice || result == null) {
''',
)
replace_once(
    service,
    '''                            downloadedAssets = result.freesoundDownloadedAssets,
                            reusedAssets = result.freesoundReusedAssets,
''',
    '''                            downloadedAssets = transferSummary.downloadedAssets,
                            reusedAssets = transferSummary.reusedAssets,
''',
)
replace_once(
    service,
    '''                    val downloadedAssets = result?.freesoundDownloadedAssets ?: 0
                    val reusedAssets = result?.freesoundReusedAssets ?: 0
''',
    '''                    val downloadedAssets = transferSummary.downloadedAssets
                    val reusedAssets = transferSummary.reusedAssets
''',
)
replace_once(
    service,
    '''                } else if (result == null) {
                    return@launch
                }
                current = loadNextChapter(
''',
    '''                } else {
                    if (result == null) return@launch
                    PlaybackQueueStore.rememberFreesoundTransferSummary(
                        chapterId = chapter.chapter.id,
                        downloadedAssets = result.freesoundDownloadedAssets,
                        reusedAssets = result.freesoundReusedAssets,
                    )
                }
                current = loadNextChapter(
''',
)
replace_once(
    service,
    '''                "resolvedAssets" to (result?.freesoundResolvedAssets ?: 0).toString(),
                "musicPlanCreated" to (result?.musicPlanCreated ?: false).toString(),
''',
    '''                "resolvedAssets" to (result?.freesoundResolvedAssets ?: 0).toString(),
                "downloadedAssets" to (result?.freesoundDownloadedAssets ?: 0).toString(),
                "reusedAssets" to (result?.freesoundReusedAssets ?: 0).toString(),
                "musicPlanCreated" to (result?.musicPlanCreated ?: false).toString(),
''',
)

normalizer = Path("source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookStoryNormalizer.kt")
replace_between(
    normalizer,
    '''    private fun htmlToParagraphs(html: String): List<String> {
''',
    '''    fun resolveUrl(host: String?, raw: String?): String? {
''',
    r'''    private fun htmlToParagraphs(html: String): List<String> {
        if (html.isBlank()) return emptyList()
        val doc = Jsoup.parseBodyFragment(html)
        doc.select("script,style,noscript,iframe,template,[hidden]").remove()
        doc.select("[style]").forEach { element ->
            val style = element.attr("style").lowercase().replace(Regex("\s+"), "")
            if ("display:none" in style || "visibility:hidden" in style) element.remove()
        }
        doc.select("br").forEach { it.after("\n") }
        doc.select("p,blockquote,li,div,section,article").forEach { element ->
            element.before("\n")
            element.after("\n")
        }
        val lines = doc.body().wholeText()
            .split(Regex("[\r\n]+"))
            .map { it.replace(Regex("\s+"), " ").trim() }
            .filter(String::isNotBlank)
        val withoutDuplicateHeader = if (lines.firstOrNull()?.matches(CHAPTER_HEADER_PATTERN) == true) {
            lines.drop(1)
        } else {
            lines
        }
        val cleaned = withoutDuplicateHeader.filterNot(::isChapterBoilerplate)
        if (cleaned.isNotEmpty()) return cleaned
        val fallback = doc.text().replace(Regex("\s+"), " ").trim()
        return listOfNotNull(
            fallback.takeIf(String::isNotBlank)
                ?.takeUnless { CHAPTER_HEADER_PATTERN.matches(it) || isChapterBoilerplate(it) },
        )
    }

    private fun isChapterBoilerplate(text: String): Boolean {
        val tokens = text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter(String::isNotBlank)
        if (tokens.isEmpty()) return true
        return tokens.all { token -> token in TRUYENFULL_BOILERPLATE_TOKENS }
    }

''',
)
replace_once(
    normalizer,
    '''    private const val MAX_EXPLORE_ITEMS = 20_000
''',
    r'''    private val CHAPTER_HEADER_PATTERN = Regex("(?i)^chương\s+\d+\s*[:：].*")
    private val TRUYENFULL_BOILERPLATE_TOKENS = setOf(
        "truyen", "full", "truyenfull", "truyenfullvn", "truyenfulllive",
    )
    private const val MAX_EXPLORE_ITEMS = 20_000
''',
)

print("Applied Freesound prefetch accounting + chapter HTML normalization fixes")
