package vn.nghetruyen.app.ai.vietphrase

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.data.repository.LibraryRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Discovers and installs the public recommended VietPhrase dictionaries without executing remote code.
 * Only HTTPS resources from an explicit host allow-list are accepted. Downloads are bounded, redirects
 * are revalidated, HTML error pages are rejected, and the repository commit remains transactional.
 */
class VietPhraseOnlineUpdater(
    private val repository: LibraryRepository,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(18, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) {
    data class CheckResult(
        val sourceRoot: String,
        val candidateKinds: Int,
        val totalCandidates: Int,
        val updateAvailable: Boolean,
        val missingKinds: List<VietPhraseDictionaryKind>,
        val remoteSummary: String,
    )

    data class InstallResult(
        val sourceRoot: String,
        val importedKinds: Int,
        val importedRules: Int,
        val snapshotId: String,
        val sourceUrls: List<String>,
    )

    suspend fun checkForUpdates(): AppResult<CheckResult> = withContext(Dispatchers.IO) {
        runCatching {
            val discovery = discover()
            val current = repository.listVietPhraseDictionaryStates()
                .associateBy { it.kind }
            val missing = REQUIRED_KINDS.filterNot(current::containsKey)
            val remoteMarkers = mutableListOf<String>()
            var changed = missing.isNotEmpty()
            REQUIRED_KINDS.forEach { kind ->
                val resolved = firstReachableCandidateSet(kind, discovery.candidates[kind].orEmpty())
                    ?: return@forEach
                val marker = resolved.metadata.joinToString(";") { it.marker }
                val display = resolved.metadata.joinToString(" + ") { it.display }
                remoteMarkers += "${kind.name}:$display"
                val installed = current[kind]
                if (installed == null || marker.isNotBlank() && marker !in installed.sourceFormat) changed = true
            }
            CheckResult(
                sourceRoot = discovery.sourceRoot,
                candidateKinds = discovery.candidates.count { it.value.isNotEmpty() },
                totalCandidates = discovery.candidates.values.sumOf(List<RemoteCandidate>::size),
                updateAvailable = changed,
                missingKinds = missing,
                remoteSummary = remoteMarkers.joinToString(" • ").take(2_000),
            )
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = { AppResult.Failure("VIETPHRASE_ONLINE_CHECK_FAILED", it.message ?: "Không kiểm tra được bộ VietPhrase trực tuyến.", it) },
        )
    }

    suspend fun installRecommended(): AppResult<InstallResult> = withContext(Dispatchers.IO) {
        runCatching {
            val discovery = discover()
            val allRules = mutableListOf<VietPhraseRule>()
            val states = mutableListOf<VietPhrasePersistenceArchiveCodec.DictionaryState>()
            val sourceUrls = mutableListOf<String>()
            val now = System.currentTimeMillis()

            REQUIRED_KINDS.forEach { kind ->
                val installedSet = installFirstValidCandidateSet(kind, discovery.candidates[kind].orEmpty(), now)
                val kindRules = installedSet.rules
                val markers = installedSet.markers
                val urls = installedSet.urls
                val rules = kindRules
                allRules += rules
                sourceUrls += urls
                val archive = VietPhraseArchiveCodec.encode(rules)
                states += VietPhrasePersistenceArchiveCodec.DictionaryState(
                    id = "${kind.name}:GLOBAL:",
                    kind = kind,
                    scope = VietPhraseScope.GLOBAL,
                    storyId = null,
                    enabled = true,
                    sourceName = urls.joinToString(" | ").take(500),
                    sourceFormat = ("HTTPS_RECOMMENDED;" + markers.joinToString(";")).take(500),
                    checksum = VietPhraseArchiveCodec.checksumBytes(archive),
                    entryCount = rules.size,
                    revision = now,
                    importedAt = now,
                )
            }

            val replacedKinds = allRules.mapTo(linkedSetOf()) { it.kind }
            val plan = repository.previewVietPhraseImport(allRules, replacedKinds)
            require(plan.canCommit) { "Bộ dữ liệu tải về có xung đột nghiêm trọng; chưa thay đổi dữ liệu hiện tại." }
            val snapshot = repository.commitVietPhraseImport(
                plan = plan,
                sourceName = discovery.sourceRoot,
                sourceFormat = "HTTPS_RECOMMENDED",
                importedStates = states,
                label = "Trước khi cập nhật VietPhrase từ mạng",
            )
            InstallResult(
                sourceRoot = discovery.sourceRoot,
                importedKinds = replacedKinds.size,
                importedRules = allRules.size,
                snapshotId = snapshot.id,
                sourceUrls = sourceUrls.distinct(),
            )
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = { AppResult.Failure("VIETPHRASE_ONLINE_INSTALL_FAILED", it.message ?: "Không cài được bộ VietPhrase trực tuyến.", it) },
        )
    }

    private fun discover(): Discovery {
        val candidates = REQUIRED_KINDS.associateWith { mutableListOf<RemoteCandidate>() }
        var reachableRoot: String? = null
        val scriptUrls = linkedSetOf<String>()
        ROOTS.forEach { root ->
            val text = runCatching { readText(root, MAX_DISCOVERY_BYTES) }.getOrNull() ?: return@forEach
            if (reachableRoot == null) reachableRoot = root
            scanResourceText(text, root, candidates, scriptUrls)
        }
        scriptUrls.take(MAX_DISCOVERY_SCRIPTS).forEach { script ->
            val text = runCatching { readText(script, MAX_SCRIPT_BYTES) }.getOrNull() ?: return@forEach
            scanResourceText(text, script, candidates, linkedSetOf())
        }
        addFallbackCandidates(candidates)
        val source = reachableRoot ?: error("Không kết nối được nguồn VietPhrase được tin cậy.")
        REQUIRED_KINDS.forEach { kind ->
            require(candidates[kind].orEmpty().isNotEmpty()) { "Không tìm thấy tài nguyên ${kind.fileName}." }
        }
        return Discovery(source, candidates.mapValues { it.value.distinctBy(RemoteCandidate::url) })
    }

    private fun scanResourceText(
        text: String,
        baseUrl: String,
        candidates: Map<VietPhraseDictionaryKind, MutableList<RemoteCandidate>>,
        scripts: MutableSet<String>,
    ) {
        RESOURCE_REFERENCE.findAll(text).forEach { match ->
            val reference = match.groupValues.drop(1).firstOrNull(String::isNotBlank).orEmpty()
            val resolved = resolveTrusted(baseUrl, reference) ?: return@forEach
            val lower = resolved.substringBefore('#').substringBefore('?').lowercase(Locale.ROOT)
            if (lower.endsWith(".js") || lower.endsWith(".json")) scripts += resolved
            val kind = remoteKind(lower) ?: return@forEach
            candidates.getValue(kind) += RemoteCandidate(resolved, resolved.substringAfterLast('/').ifBlank { kind.fileName })
        }
    }

    private fun addFallbackCandidates(candidates: Map<VietPhraseDictionaryKind, MutableList<RemoteCandidate>>) {
        val directories = listOf("", "data/", "dict/", "dicts/", "dictionary/", "dictionaries/", "assets/", "assets/data/", "assets/dicts/")
        ROOTS.forEach { root ->
            REQUIRED_KINDS.forEach { kind ->
                val names = when (kind) {
                    VietPhraseDictionaryKind.VIET_PHRASE -> listOf(
                        "VietPhrase.txt", "VietPhrase.txt.gz", "VietPhrase1.txt", "VietPhrase2.txt",
                        "VietPhrase_1.txt", "VietPhrase_2.txt", "VietPhrase_part1.txt", "VietPhrase_part2.txt",
                        "VietPhrase1.txt.gz", "VietPhrase2.txt.gz",
                    )
                    else -> listOf(kind.fileName, "${kind.fileName}.gz")
                }
                directories.forEach { directory ->
                    names.forEach { name -> candidates.getValue(kind) += RemoteCandidate(root + directory + name, name) }
                }
            }
        }
    }

    private fun candidateSets(kind: VietPhraseDictionaryKind, values: List<RemoteCandidate>): List<List<RemoteCandidate>> {
        val distinct = values.distinctBy(RemoteCandidate::url)
        if (kind != VietPhraseDictionaryKind.VIET_PHRASE) {
            return distinct.take(MAX_CANDIDATE_SETS).map(::listOf)
        }
        val wholeSets = distinct.filterNot { looksLikePart(it.fileName) }.map(::listOf)
        val firstParts = distinct.filter { looksLikePart(it.fileName) && partNumber(it.fileName) == 1 }
        val secondParts = distinct.filter { looksLikePart(it.fileName) && partNumber(it.fileName) == 2 }
        val pairedSets = firstParts.flatMap { first ->
            secondParts
                .sortedByDescending { sameDirectory(first.url, it.url) }
                .map { second -> listOf(first, second) }
        }
        return (wholeSets + pairedSets + distinct.map(::listOf))
            .distinctBy { set -> set.joinToString("|") { it.url } }
            .take(MAX_CANDIDATE_SETS)
    }

    private fun firstReachableCandidateSet(
        kind: VietPhraseDictionaryKind,
        values: List<RemoteCandidate>,
    ): ReachableCandidateSet? {
        candidateSets(kind, values).forEach { candidates ->
            val metadata = runCatching { candidates.map { probe(it.url) } }.getOrNull() ?: return@forEach
            return ReachableCandidateSet(candidates, metadata)
        }
        return null
    }

    private fun installFirstValidCandidateSet(
        kind: VietPhraseDictionaryKind,
        values: List<RemoteCandidate>,
        now: Long,
    ): InstalledCandidateSet {
        val failures = mutableListOf<String>()
        candidateSets(kind, values).forEach { candidates ->
            val attempt = runCatching {
                val rules = LinkedHashMap<String, VietPhraseRule>()
                val markers = mutableListOf<String>()
                val urls = mutableListOf<String>()
                candidates.forEach { candidate ->
                    val downloaded = download(candidate.url)
                    decodeRemote(downloaded.bytes, candidate.fileName, kind)
                        .filter { it.kind == kind }
                        .forEach { rule -> rules[rule.source.lowercase(Locale.ROOT)] = rule.copy(updatedAt = now) }
                    urls += downloaded.finalUrl
                    markers += downloaded.marker
                }
                val minimum = MINIMUM_ENTRIES.getValue(kind)
                require(rules.size >= minimum) {
                    "${kind.fileName} chỉ có ${rules.size} mục, thấp hơn ngưỡng an toàn $minimum."
                }
                InstalledCandidateSet(rules.values.toList(), markers, urls)
            }
            attempt.getOrNull()?.let { return it }
            failures += attempt.exceptionOrNull()?.message.orEmpty().take(240)
        }
        error("Không tìm thấy bản ${kind.fileName} hợp lệ. ${failures.filter(String::isNotBlank).take(3).joinToString(" | ")}")
    }

    private fun partNumber(fileName: String): Int? {
        val name = fileName.lowercase(Locale.ROOT).substringBefore('?')
        return when {
            Regex("(?:part|split|vietphrase[_-]?)[_-]?1(?:\\.|_|-|$)").containsMatchIn(name) -> 1
            Regex("(?:part|split|vietphrase[_-]?)[_-]?2(?:\\.|_|-|$)").containsMatchIn(name) -> 2
            else -> null
        }
    }

    private fun sameDirectory(first: String, second: String): Boolean =
        first.substringBeforeLast('/', "") == second.substringBeforeLast('/', "")

    private fun probe(url: String): RemoteMetadata {
        val response = executeFollowingTrustedRedirects(Request.Builder().url(url).head().build())
        response.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code} khi kiểm tra ${url.take(160)}")
            val etag = it.header("ETag").orEmpty().trim()
            val lastModified = it.header("Last-Modified").orEmpty().trim()
            val length = it.header("Content-Length").orEmpty().trim()
            val marker = buildString {
                if (etag.isNotBlank()) append("etag=").append(etag)
                if (lastModified.isNotBlank()) { if (isNotEmpty()) append(';'); append("lastModified=").append(lastModified) }
                if (length.isNotBlank()) { if (isNotEmpty()) append(';'); append("length=").append(length) }
            }
            return RemoteMetadata(marker, listOf(etag, lastModified, length).filter(String::isNotBlank).joinToString(" / ").ifBlank { "không có ETag" })
        }
    }

    private fun readText(url: String, maxBytes: Int): String {
        val downloaded = download(url, maxBytes)
        return downloaded.bytes.toString(Charsets.UTF_8)
    }

    private fun download(url: String, maxBytes: Int = MAX_DOWNLOAD_BYTES): Downloaded {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/plain, application/octet-stream, application/gzip, application/zip;q=0.9, */*;q=0.1")
            .header("Accept-Encoding", "identity")
            .header("User-Agent", "NgheTruyen-VietPhrase/2.3")
            .get()
            .build()
        val response = executeFollowingTrustedRedirects(request)
        response.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code} khi tải ${url.take(160)}")
            val declared = it.body.contentLength()
            require(declared <= 0 || declared <= maxBytes) { "Tệp tải về vượt giới hạn an toàn." }
            val contentType = it.header("Content-Type").orEmpty().lowercase(Locale.ROOT)
            val output = ByteArrayOutputStream(minOf(maxBytes, 1 shl 20))
            val input = it.body.byteStream()
            input.use { stream ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= maxBytes) { "Tệp tải về vượt giới hạn an toàn." }
                    output.write(buffer, 0, count)
                }
            }
            val bytes = output.toByteArray()
            require(bytes.size >= MIN_DOWNLOAD_BYTES) { "Tệp tải về quá nhỏ." }
            val head = bytes.take(256).toByteArray().toString(Charsets.UTF_8).lowercase(Locale.ROOT)
            require("<!doctype html" !in head && "<html" !in head && "text/html" !in contentType) {
                "Máy chủ trả HTML thay vì từ điển."
            }
            val finalUrl = it.request.url.toString()
            val metadata = RemoteMetadata(
                marker = listOf(
                    it.header("ETag")?.let { value -> "etag=$value" },
                    it.header("Last-Modified")?.let { value -> "lastModified=$value" },
                    "sha256=${sha256(bytes)}",
                ).filterNotNull().joinToString(";"),
                display = "${bytes.size} byte",
            )
            return Downloaded(bytes, finalUrl, metadata.marker)
        }
    }

    private fun executeFollowingTrustedRedirects(initial: Request): okhttp3.Response {
        var request = initial
        repeat(MAX_REDIRECTS + 1) { attempt ->
            require(isTrusted(request.url)) { "URL VietPhrase không thuộc danh sách host tin cậy." }
            val response = client.newCall(request).execute()
            if (response.code !in REDIRECT_CODES) return response
            if (attempt >= MAX_REDIRECTS) {
                response.close()
                throw IOException("Quá nhiều redirect khi tải VietPhrase.")
            }
            val location = response.header("Location")
            val target = location?.let { request.url.resolve(it) }
            response.close()
            require(target != null && isTrusted(target)) { "Redirect sang host không được tin cậy." }
            request = request.newBuilder().url(target).build()
        }
        error("Không thể hoàn tất yêu cầu VietPhrase.")
    }

    private fun decodeRemote(bytes: ByteArray, fileName: String, kind: VietPhraseDictionaryKind): List<VietPhraseRule> {
        val uncompressed = if (isGzip(bytes)) inflateGzip(bytes) else bytes
        if (isZip(uncompressed)) {
            return VietPhraseBundleCodec.decodeZip(uncompressed).rules
        }
        val lower = fileName.lowercase(Locale.ROOT).removeSuffix(".gz")
        return if (lower.endsWith(".dic") || lower.endsWith(".dat")) {
            VietPhraseBinaryDictionaryCodec.decode(uncompressed, fileName, kind).rules
        } else {
            VietPhraseDictionaryCodec.decode(uncompressed, fileName, kind).rules
        }
    }

    private fun inflateGzip(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_INFLATED_BYTES) { "Tệp GZIP giải nén vượt giới hạn an toàn." }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun resolveTrusted(base: String, reference: String): String? {
        val clean = reference.replace("\\/", "/").replace("&amp;", "&").trim()
        if (clean.isBlank() || clean.startsWith('#') || clean.startsWith("data:") || clean.startsWith("javascript:")) return null
        val baseUrl = base.toHttpUrlOrNull() ?: return null
        val resolved = when {
            clean.startsWith("https://") -> clean.toHttpUrlOrNull()
            clean.startsWith("//") -> "https:$clean".toHttpUrlOrNull()
            else -> baseUrl.resolve(clean)
        } ?: return null
        return resolved.takeIf(::isTrusted)?.toString()
    }

    private fun isTrusted(url: HttpUrl): Boolean =
        url.isHttps && (url.host in TRUSTED_HOSTS || TRUSTED_HOSTS.any { url.host.endsWith(".$it") })

    private fun remoteKind(value: String): VietPhraseDictionaryKind? = when {
        "chinesephienamwords" in value || "phienam" in value -> VietPhraseDictionaryKind.PHIEN_AM
        "luatnhan" in value -> VietPhraseDictionaryKind.LUAT_NHAN
        "lacviet" in value -> VietPhraseDictionaryKind.LAC_VIET
        "names" in value -> VietPhraseDictionaryKind.NAMES
        "vietphrase" in value -> VietPhraseDictionaryKind.VIET_PHRASE
        else -> null
    }

    private fun looksLikePart(fileName: String): Boolean {
        val name = fileName.lowercase(Locale.ROOT).substringBefore('?')
        return "part" in name || "split" in name || Regex("(?:^|[_\\-.])[12](?:[_\\-.]|$)").containsMatchIn(name) ||
            Regex("vietphrase[_-]?[12]\\.").containsMatchIn(name)
    }

    private fun isGzip(bytes: ByteArray): Boolean = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
    private fun isZip(bytes: ByteArray): Boolean = bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private data class Discovery(
        val sourceRoot: String,
        val candidates: Map<VietPhraseDictionaryKind, List<RemoteCandidate>>,
    )
    private data class RemoteCandidate(val url: String, val fileName: String)
    private data class RemoteMetadata(val marker: String, val display: String)
    private data class ReachableCandidateSet(val candidates: List<RemoteCandidate>, val metadata: List<RemoteMetadata>)
    private data class InstalledCandidateSet(val rules: List<VietPhraseRule>, val markers: List<String>, val urls: List<String>)
    private data class Downloaded(val bytes: ByteArray, val finalUrl: String, val marker: String)

    companion object {
        private val ROOTS = listOf("https://vietphrase.pages.dev/", "https://vietphrase.app/")
        private val TRUSTED_HOSTS = setOf("vietphrase.pages.dev", "vietphrase.app")
        private val REQUIRED_KINDS = listOf(
            VietPhraseDictionaryKind.PHIEN_AM,
            VietPhraseDictionaryKind.NAMES,
            VietPhraseDictionaryKind.LAC_VIET,
            VietPhraseDictionaryKind.VIET_PHRASE,
            VietPhraseDictionaryKind.LUAT_NHAN,
        )
        private val MINIMUM_ENTRIES = mapOf(
            VietPhraseDictionaryKind.PHIEN_AM to 5_000,
            VietPhraseDictionaryKind.NAMES to 50_000,
            VietPhraseDictionaryKind.LAC_VIET to 20_000,
            VietPhraseDictionaryKind.VIET_PHRASE to 300_000,
            VietPhraseDictionaryKind.LUAT_NHAN to 5_000,
        )
        private val RESOURCE_REFERENCE = Regex(
            """(?is)(?:src|href)\s*=\s*[\"']([^\"']+)[\"']|[\"']([^\"']+\.(?:txt|gz|zip|js|json)(?:\?[^\"']*)?)[\"']""",
        )
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private const val MAX_REDIRECTS = 3
        private const val MAX_DISCOVERY_SCRIPTS = 12
        private const val MAX_CANDIDATE_SETS = 24
        private const val MAX_DISCOVERY_BYTES = 4 * 1024 * 1024
        private const val MAX_SCRIPT_BYTES = 4 * 1024 * 1024
        private const val MAX_DOWNLOAD_BYTES = 160 * 1024 * 1024
        private const val MAX_INFLATED_BYTES = 256 * 1024 * 1024
        private const val MIN_DOWNLOAD_BYTES = 1_024
    }
}
