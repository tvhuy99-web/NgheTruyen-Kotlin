package vn.nghetruyen.app.freesound

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

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

        val url = SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("query", "test")
            .addQueryParameter("page_size", "1")
            .addQueryParameter("fields", "id")
            .build()
        val request = Request.Builder()
            .url(url)
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

    companion object {
        private const val SEARCH_URL = "https://freesound.org/apiv2/search/"
        private const val USER_AGENT = "NgheTruyen-Android/Freesound"

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

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
