package vn.nghetruyen.app.freesound

import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class FreesoundConnectionResult(
    val success: Boolean,
    val message: String,
    val httpCode: Int? = null,
)

class FreesoundClient(
    private val credentialStore: FreesoundCredentialStore,
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {
    private data class CacheEntry(
        val page: FreesoundSearchPage,
        val storedAt: Long,
    )

    private val cacheLock = Any()
    private val pageCache = object : LinkedHashMap<String, CacheEntry>(CACHE_MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean =
            size > CACHE_MAX_ENTRIES
    }
    private val rateMutex = Mutex()
    private val requestTimes = ArrayDeque<Long>()

    suspend fun testConnection(): FreesoundConnectionResult = withContext(Dispatchers.IO) {
        val apiKey = credentialStore.apiKey()
            ?: return@withContext FreesoundConnectionResult(
                success = false,
                message = "Chưa lưu khóa API Freesound.",
            )
        awaitLocalRatePermit()
        val request = Request.Builder()
            .url(buildSearchUrl(FreesoundSearchRequest(query = "test", pageSize = 1), fields = "id"))
            .header("Authorization", "Token $apiKey")
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        runCatching {
            httpClient.newCall(request).execute().use { response ->
                resultForHttpCode(response.code)
            }
        }.getOrElse {
            FreesoundConnectionResult(
                success = false,
                message = "Không thể kết nối Freesound. Hãy kiểm tra mạng rồi thử lại.",
            )
        }
    }

    suspend fun search(request: FreesoundSearchRequest): FreesoundSearchResult = withContext(Dispatchers.IO) {
        val normalized = request.normalized()
        executeSoundListRequest(
            url = buildSearchUrl(normalized),
            request = normalized,
            networkFailureMessage = "Không thể tìm trên Freesound. Hãy kiểm tra mạng rồi thử lại.",
        )
    }

    suspend fun similar(
        soundId: Int,
        request: FreesoundSearchRequest,
    ): FreesoundSearchResult = withContext(Dispatchers.IO) {
        if (soundId <= 0) {
            return@withContext FreesoundSearchResult.Failure("ID âm thanh Freesound không hợp lệ.")
        }
        val normalized = request.normalized()
        executeSoundListRequest(
            url = buildSimilarUrl(soundId, normalized),
            request = normalized,
            networkFailureMessage = "Không thể tìm âm thanh tương tự. Hãy kiểm tra mạng rồi thử lại.",
        )
    }

    fun clearSearchCache() {
        synchronized(cacheLock) { pageCache.clear() }
    }

    private suspend fun executeSoundListRequest(
        url: HttpUrl,
        request: FreesoundSearchRequest,
        networkFailureMessage: String,
    ): FreesoundSearchResult {
        val cacheKey = url.toString()
        cachedPage(cacheKey)?.let { return FreesoundSearchResult.Success(it) }
        val apiKey = credentialStore.apiKey()
            ?: return FreesoundSearchResult.Failure("Chưa lưu khóa API Freesound.")

        suspend fun executeOnce(): Pair<FreesoundSearchResult, Long?> {
            awaitLocalRatePermit()
            val httpRequest = Request.Builder()
                .url(url)
                .header("Authorization", "Token $apiKey")
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            return runCatching {
                httpClient.newCall(httpRequest).execute().use { response ->
                    if (!response.isSuccessful) {
                        val retryAfter = response.header("Retry-After")
                            ?.trim()
                            ?.toLongOrNull()
                            ?.times(1_000L)
                        return@use searchFailureForHttpCode(response.code) to retryAfter
                    }
                    val page = parseSearchPage(response.body.string(), request)
                    cachePage(cacheKey, page)
                    FreesoundSearchResult.Success(page) to null
                }
            }.getOrElse {
                FreesoundSearchResult.Failure(message = networkFailureMessage) to null
            }
        }

        val first = executeOnce()
        val firstFailure = first.first as? FreesoundSearchResult.Failure
        if (firstFailure?.httpCode != 429) return first.first

        val retryDelay = (first.second ?: RATE_LIMIT_DEFAULT_RETRY_MS)
            .coerceIn(1_000L, RATE_LIMIT_MAX_AUTO_RETRY_MS)
        delay(retryDelay)
        return executeOnce().first
    }

    private fun cachedPage(key: String): FreesoundSearchPage? = synchronized(cacheLock) {
        val entry = pageCache[key] ?: return@synchronized null
        if (System.currentTimeMillis() - entry.storedAt > CACHE_TTL_MS) {
            pageCache.remove(key)
            null
        } else {
            entry.page
        }
    }

    private fun cachePage(key: String, page: FreesoundSearchPage) {
        synchronized(cacheLock) {
            pageCache[key] = CacheEntry(page, System.currentTimeMillis())
        }
    }

    private suspend fun awaitLocalRatePermit() {
        while (true) {
            val waitMs = rateMutex.withLock {
                val now = System.currentTimeMillis()
                while (requestTimes.isNotEmpty() && now - requestTimes.first() >= RATE_WINDOW_MS) {
                    requestTimes.removeFirst()
                }
                if (requestTimes.size < LOCAL_REQUESTS_PER_WINDOW) {
                    requestTimes.addLast(now)
                    0L
                } else {
                    (RATE_WINDOW_MS - (now - requestTimes.first()) + RATE_SAFETY_MS).coerceAtLeast(RATE_SAFETY_MS)
                }
            }
            if (waitMs <= 0L) return
            delay(waitMs)
        }
    }

    companion object {
        private const val SEARCH_URL = "https://freesound.org/apiv2/search/"
        private const val API_BASE_URL = "https://freesound.org/apiv2/"
        private const val USER_AGENT = "NgheTruyen-Android/Freesound"
        private const val SEARCH_FIELDS = "id,url,name,description,duration,previews,username,license,tags,category,subcategory,category_code,avg_rating,num_ratings,num_downloads,score"
        private const val CACHE_MAX_ENTRIES = 48
        private const val CACHE_TTL_MS = 5L * 60L * 1_000L
        private const val RATE_WINDOW_MS = 60_000L
        private const val RATE_SAFETY_MS = 250L
        private const val LOCAL_REQUESTS_PER_WINDOW = 50
        private const val RATE_LIMIT_DEFAULT_RETRY_MS = 5_000L
        private const val RATE_LIMIT_MAX_AUTO_RETRY_MS = 30_000L

        internal fun resultForHttpCode(code: Int): FreesoundConnectionResult = when (code) {
            in 200..299 -> FreesoundConnectionResult(
                success = true,
                message = "Kết nối Freesound thành công.",
                httpCode = code,
            )
            401, 403 -> FreesoundConnectionResult(
                success = false,
                message = "Khóa API Freesound không hợp lệ hoặc đã bị từ chối.",
                httpCode = code,
            )
            429 -> FreesoundConnectionResult(
                success = false,
                message = "Freesound đang giới hạn số yêu cầu. Ứng dụng sẽ tự giãn nhịp trước lần thử tiếp theo.",
                httpCode = code,
            )
            else -> FreesoundConnectionResult(
                success = false,
                message = "Freesound trả về lỗi HTTP $code.",
                httpCode = code,
            )
        }

        internal fun buildSearchUrl(
            request: FreesoundSearchRequest,
            fields: String = SEARCH_FIELDS,
        ): HttpUrl {
            val normalized = request.normalized()
            return SEARCH_URL.toHttpUrl().newBuilder()
                .addQueryParameter("query", normalized.query)
                .apply {
                    normalized.duration.apiFilter(normalized.category)?.let {
                        addQueryParameter("filter", it)
                    }
                }
                .addQueryParameter("sort", normalized.sort.apiValue)
                .addQueryParameter("page", normalized.page.toString())
                .addQueryParameter("page_size", normalized.pageSize.toString())
                .addQueryParameter("fields", fields)
                .build()
        }

        internal fun buildSimilarUrl(
            soundId: Int,
            request: FreesoundSearchRequest,
            fields: String = SEARCH_FIELDS,
        ): HttpUrl {
            require(soundId > 0)
            val normalized = request.normalized()
            return API_BASE_URL.toHttpUrl().newBuilder()
                .addPathSegment("sounds")
                .addPathSegment(soundId.toString())
                .addPathSegment("similar")
                .addPathSegment("")
                .apply {
                    normalized.duration.apiFilter(normalized.category)?.let {
                        addQueryParameter("filter", it)
                    }
                }
                .addQueryParameter("page", normalized.page.toString())
                .addQueryParameter("page_size", normalized.pageSize.toString())
                .addQueryParameter("fields", fields)
                .build()
        }

        internal fun parseSearchPage(
            payload: String,
            request: FreesoundSearchRequest,
        ): FreesoundSearchPage {
            val root = JSONObject(payload)
            val resultsJson = root.optJSONArray("results")
            val results = buildList {
                if (resultsJson != null) {
                    for (index in 0 until resultsJson.length()) {
                        val item = resultsJson.optJSONObject(index) ?: continue
                        val id = item.optInt("id", -1)
                        if (id <= 0) continue
                        val previews = item.optJSONObject("previews")
                        add(
                            FreesoundSound(
                                id = id,
                                name = item.optString("name").trim().ifBlank { "Sound #$id" },
                                description = item.optString("description").trim().take(4_000),
                                durationSeconds = item.optDouble("duration", 0.0).coerceAtLeast(0.0),
                                previewHqMp3 = previews?.optString("preview-hq-mp3")
                                    ?.trim()
                                    ?.takeIf { it.startsWith("https://", ignoreCase = true) },
                                previewHqOgg = previews?.optString("preview-hq-ogg")
                                    ?.trim()
                                    ?.takeIf { it.startsWith("https://", ignoreCase = true) },
                                username = item.optString("username").trim().take(120),
                                license = item.optString("license").trim().take(240),
                                webUrl = item.optString("url").trim()
                                    .takeIf { it.startsWith("https://freesound.org/", ignoreCase = true) }
                                    .orEmpty(),
                                tags = buildList {
                                    val tagsArray = item.optJSONArray("tags")
                                    if (tagsArray != null) {
                                        for (tagIndex in 0 until tagsArray.length()) {
                                            tagsArray.optString(tagIndex).trim().takeIf(String::isNotBlank)?.let(::add)
                                        }
                                    }
                                },
                                category = item.optString("category").trim().take(120),
                                subcategory = item.optString("subcategory").trim().take(160),
                                categoryCode = item.optString("category_code").trim().take(40),
                                avgRating = item.optDouble("avg_rating", 0.0).takeIf(Double::isFinite)?.coerceIn(0.0, 5.0) ?: 0.0,
                                numRatings = item.optInt("num_ratings", 0).coerceAtLeast(0),
                                numDownloads = item.optInt("num_downloads", 0).coerceAtLeast(0),
                                searchScore = item.optDouble("score", 0.0).takeIf(Double::isFinite)?.coerceAtLeast(0.0) ?: 0.0,
                            ),
                        )
                    }
                }
            }
            val normalized = request.normalized()
            return FreesoundSearchPage(
                count = root.optInt("count", results.size).coerceAtLeast(results.size),
                page = normalized.page,
                pageSize = normalized.pageSize,
                results = results,
                hasNext = !root.isNull("next") && root.optString("next").isNotBlank(),
                hasPrevious = !root.isNull("previous") && root.optString("previous").isNotBlank(),
            )
        }

        private fun searchFailureForHttpCode(code: Int): FreesoundSearchResult.Failure = when (code) {
            401, 403 -> FreesoundSearchResult.Failure(
                message = "Khóa API Freesound không hợp lệ hoặc đã bị từ chối.",
                httpCode = code,
            )
            429 -> FreesoundSearchResult.Failure(
                message = "Freesound đang giới hạn số yêu cầu. Ứng dụng đã tự giãn nhịp và thử lại một lần.",
                httpCode = code,
            )
            else -> FreesoundSearchResult.Failure(
                message = "Freesound trả về lỗi HTTP $code.",
                httpCode = code,
            )
        }

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
    }
}
