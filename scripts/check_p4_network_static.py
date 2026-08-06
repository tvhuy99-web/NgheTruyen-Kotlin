#!/usr/bin/env python3
"""Compile online AI client and public-only DNS policy against narrow JVM stubs."""
from __future__ import annotations
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
K = shutil.which("kotlinc")


def w(root: Path, path: str, content: str) -> Path:
    target = root / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")
    return target


def main() -> None:
    if not K:
        print("P4_NETWORK_STATIC_SKIPPED")
        return
    with tempfile.TemporaryDirectory(prefix="nghe_p4_net_") as td:
        root = Path(td)
        files: list[Path] = []
        files.append(w(root, "kotlinx/coroutines/Core.kt", '''package kotlinx.coroutines
object Dispatchers { object IO }
suspend fun <T> withContext(ctx: Any, block: suspend () -> T): T = block()
suspend fun delay(value: Long) {}
'''))
        files.append(w(root, "okhttp3/Ok.kt", '''package okhttp3
import java.io.StringReader
import java.io.Reader
import java.net.InetAddress
import java.util.concurrent.TimeUnit
interface Dns {
    fun lookup(hostname: String): List<InetAddress>
    companion object {
        val SYSTEM: Dns = object : Dns {
            override fun lookup(hostname: String) = listOf(InetAddress.getLoopbackAddress())
        }
    }
}
class MediaType { companion object { fun String.toMediaType() = MediaType() } }
open class RequestBody { companion object { fun String.toRequestBody(type: MediaType) = RequestBody() } }
class Request private constructor() {
    class Builder {
        fun url(url: String) = this
        fun header(name: String, value: String) = this
        fun post(body: RequestBody) = this
        fun get() = this
        fun build() = Request()
    }
}
class ResponseBody {
    fun charStream(): Reader = StringReader("")
    fun string(): String = ""
}
class Response : AutoCloseable {
    val isRedirect = false
    val isSuccessful = true
    val code = 200
    val body: ResponseBody? = ResponseBody()
    fun header(name: String): String? = null
    override fun close() {}
}
class Call { fun execute() = Response() }
class OkHttpClient {
    fun newCall(request: Request) = Call()
    class Builder {
        fun connectTimeout(v: Long, u: TimeUnit) = this
        fun readTimeout(v: Long, u: TimeUnit) = this
        fun writeTimeout(v: Long, u: TimeUnit) = this
        fun dns(v: Dns) = this
        fun followRedirects(v: Boolean) = this
        fun followSslRedirects(v: Boolean) = this
        fun build() = OkHttpClient()
    }
}
'''))
        files.append(w(root, "org/json/Json.kt", '''package org.json
class JSONObject {
    constructor()
    constructor(raw: String)
    fun put(k: String, v: Any?): JSONObject = this
    fun optJSONObject(k: String): JSONObject? = null
    fun optJSONArray(k: String): JSONArray? = null
    fun optString(k: String): String = ""
    fun optString(k: String, fallback: String): String = fallback
    fun getJSONArray(k: String) = JSONArray()
    fun getJSONObject(k: String) = JSONObject()
    fun getString(k: String) = ""
    fun get(k: String): Any = ""
    override fun toString() = "{}"
}
class JSONArray {
    fun put(v: Any?): JSONArray = this
    fun getJSONObject(i: Int) = JSONObject()
    fun optJSONObject(i: Int): JSONObject? = null
    fun optString(i: Int): String = ""
    fun length(): Int = 0
}
'''))
        files.append(w(root, "vn/nghetruyen/app/data/settings/Settings.kt", '''package vn.nghetruyen.app.data.settings
enum class AiProvider { OPENAI_COMPATIBLE, GEMINI }
data class AiOnlineSettings(
    val provider: AiProvider = AiProvider.OPENAI_COMPATIBLE,
    val enabled: Boolean = false,
    val consentGranted: Boolean = false,
    val endpoint: String = "https://api.example/v1/chat/completions",
    val model: String = "model",
    val temperature: Float = .2f,
    val translationInstruction: String = "",
    val dailyRequestLimit: Int = 30,
    val dailyInputCharsLimit: Int = 500000,
    val maxRetries: Int = 2,
    val retryBaseDelayMillis: Int = 1500,
)
data class AppSettings(val aiOnline: AiOnlineSettings = AiOnlineSettings())
class SettingsRepository { suspend fun snapshot() = AppSettings() }
'''))
        files.append(w(root, "vn/nghetruyen/app/data/local/StoryAi.kt", '''package vn.nghetruyen.app.data.local
data class VoiceRoleEntity(
    val roleName: String = "",
    val aliasesCsv: String = "",
    val enabled: Boolean = true,
    val isNarrator: Boolean = false,
    val expression: String = "NEUTRAL",
)
data class StoryAiProfileEntity(
    val storyId: String,
    val mode: String = "INHERIT",
    val overrideProvider: Boolean = false,
    val provider: String = "OPENAI_COMPATIBLE",
    val endpoint: String = "",
    val model: String = "",
    val temperature: Float = -1f,
    val useCustomPrompts: Boolean = false,
    val translationPrompt: String = "",
    val improvePrompt: String = "",
    val autoRunOnOpen: Boolean = false,
    val useCustomVoiceCastPrompt: Boolean = false,
    val voiceCastPrompt: String = "",
    val voiceCastNote: String = "",
    val voiceCastDialogueOnly: Boolean = true,
    val voiceCastStableNarrator: Boolean = true,
    val expressiveAdjustment: Boolean = true,
    val expressionPrompt: String = "",
    val expressionSpeedLimitPct: Int = 10,
    val expressionPitchLimitPct: Int = 10,
    val expressionVolumeLimitPct: Int = 10,
    val updatedAt: Long = 0,
)
'''))
        files.append(w(root, "vn/nghetruyen/app/data/repository/Library.kt", '''package vn.nghetruyen.app.data.repository
class LibraryRepository {
    suspend fun getStoryAiProfile(storyId: String): vn.nghetruyen.app.data.local.StoryAiProfileEntity? = null
    suspend fun listVoiceRoles(storyId: String): List<vn.nghetruyen.app.data.local.VoiceRoleEntity> = emptyList()
}
'''))
        files.append(w(root, "vn/nghetruyen/app/ai/Credential.kt", '''package vn.nghetruyen.app.ai
import vn.nghetruyen.app.data.settings.AiProvider
interface AiCredentialStore {
    fun hasApiKey(provider: AiProvider): Boolean
    fun apiKey(provider: AiProvider): String?
    fun saveApiKey(provider: AiProvider, value: String)
    fun clearApiKey(provider: AiProvider)
}
class AiRequestGovernor {
    data class Permit(val dayEpoch: Int = 0, val maxRetries: Int = 2, val retryBaseDelayMillis: Int = 1500)
    suspend fun reserve(inputChars: Int): vn.nghetruyen.app.core.common.AppResult<Permit> =
        vn.nghetruyen.app.core.common.AppResult.Success(Permit())
    suspend fun finish(permit: Permit, outputChars: Int, retryCount: Int, errorCode: String?) {}
}
'''))
        files += [
            ROOT / "app/src/main/java/vn/nghetruyen/app/core/common/AppResult.kt",
            ROOT / "app/src/main/java/vn/nghetruyen/app/ai/AiServices.kt",
            ROOT / "app/src/main/java/vn/nghetruyen/app/ai/AiLineProtocol.kt",
            ROOT / "app/src/main/java/vn/nghetruyen/app/ai/AiEndpointPolicy.kt",
            ROOT / "app/src/main/java/vn/nghetruyen/app/ai/AiPublicDns.kt",
            ROOT / "app/src/main/java/vn/nghetruyen/app/ai/OnlineAiServices.kt",
        ]
        compiled = subprocess.run(
            [K, *map(str, files), "-d", str(root / "out.jar")],
            cwd=ROOT,
            text=True,
            capture_output=True,
        )
        if compiled.returncode:
            print(compiled.stdout)
            print(compiled.stderr)
            raise SystemExit(compiled.returncode)
    print("P4_NETWORK_STATIC_COMPILE_OK")


if __name__ == "__main__":
    main()
