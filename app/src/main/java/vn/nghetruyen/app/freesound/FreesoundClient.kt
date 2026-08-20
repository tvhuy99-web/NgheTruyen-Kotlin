package vn.nghetruyen.app.freesound

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
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
    suspend fun testConnection(): FreesoundConnectionResult = withContext(Dispatchers.IO) {
        val apiKey = credentialStore.apiKey()
            ?: return@withContext FreesoundConnectionResult(
                success = false,
                message = "Chưa lưu khóa API Freesound.",
            )

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
        val apiKey = credentialStore.apiKey()
            ?: return@withContext FreesoundSearchResult.Failure("Chưa lưu khóa API Freesound.")

        val httpRequest = Request.Builder()
            .url(buildSearchUrl(normalized))
            .header("Authorization", "Token $apiKey")
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        runCatching {
            httpClient.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use searchFailureForHttpCode(response.code)
                }
                val body = response.body.string()
                FreesoundSearchResult.Success(parseSearchPage(body, normalized))
            }
        }.getOrElse {
            FreesoundSearchResult.Failure(
                message = "Không thể tìm trên Freesound. Hãy kiểm tra mạng rồi thử lại.",
            )
        }
    }

    companion object {
        private const val SEARCH_URL = "https://freesound.org/apiv2/search/"
        private const val USER_AGENT = "NgheTruyen-Android/Freesound"
        private const val SEARCH_FIELDS =
            "id,name,tags,username,license,duration,previews,avg_rating,num_ratings,num_downloads,url,type,channels,samplerate"

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
                message = "Freesound đang giới hạn số yêu cầu. Hãy thử lại sau.",
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
                    normalized.category.filter?.let { addQueryParameter("filter", it) }
                }
                .addQueryParameter("sort", normalized.sort.apiValue)
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
                        val tagsJson = item.optJSONArray("tags")
                        val tags = buildList {
                            if (tagsJson != null) {
                                for (tagIndex in 0 until tagsJson.length()) {
                                    tagsJson.optString(tagIndex)
                                        .trim()
                                        .takeIf(String::isNotBlank)
                                        ?.let(::add)
                                }
                            }
                        }
                        add(
                            FreesoundSound(
                                id = id,
                                name = item.optString("name").ifBlank { "Sound #$id" },
                                username = item.optString("username").ifBlank { "Không rõ" },
                                license = item.optString("license").ifBlank { "Không rõ" },
                                durationSeconds = item.optDouble("duration", 0.0).coerceAtLeast(0.0),
                                tags = tags,
                                previewHqMp3 = previews?.optString("preview-hq-mp3")
                                    ?.trim()
                                    ?.takeIf { it.startsWith("https://", ignoreCase = true) },
                                previewHqOgg = previews?.optString("preview-hq-ogg")
                                    ?.trim()
                                    ?.takeIf { it.startsWith("https://", ignoreCase = true) },
                                avgRating = item.optDouble("avg_rating", 0.0).coerceIn(0.0, 5.0),
                                numRatings = item.optInt("num_ratings", 0).coerceAtLeast(0),
                                numDownloads = item.optInt("num_downloads", 0).coerceAtLeast(0),
                                webUrl = item.optString("url")
                                    .trim()
                                    .takeIf { it.startsWith("https://", ignoreCase = true) },
                                fileType = item.optString("type").ifBlank { "?" },
                                channels = item.optInt("channels", 0).coerceAtLeast(0),
                                sampleRate = item.optInt("samplerate", 0).coerceAtLeast(0),
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
                message = "Freesound đang giới hạn số yêu cầu. Hãy thử lại sau.",
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
