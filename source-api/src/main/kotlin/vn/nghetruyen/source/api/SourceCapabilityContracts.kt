package vn.nghetruyen.source.api

import java.util.UUID

enum class SourceBrowserAction {
    NAVIGATE,
    LOAD_HTML,
    WAIT_SELECTOR,
    DOM_SNAPSHOT,
    CLICK,
    INPUT,
    EVALUATE_PAGE_SCRIPT,
    EVALUATE_PAGE_SCRIPT_ASYNC,
    REQUEST_METADATA,
    SET_USER_AGENT,
    SET_BLOCK_PATTERNS,
    SET_DIALOG_POLICY,
    DIALOGS,
    WAIT_DIALOG,
    SYNC_SESSION,
    SET_COOKIES,
    CLEAR_COOKIES,
    CLOSE_SESSION,
    CLEAR_SESSION,
}

data class SourceBrowserRequest(
    val sourceId: String,
    val action: SourceBrowserAction,
    val url: String? = null,
    val selector: String? = null,
    val value: String? = null,
    val script: String? = null,
    val values: List<String> = emptyList(),
    val options: Map<String, String> = emptyMap(),
    val timeoutMs: Long = 30_000,
    val maxOutputBytes: Int = 2 * 1024 * 1024,
    val traceId: String = UUID.randomUUID().toString(),
)

data class SourceBrowserRequestMetadata(
    val url: String,
    val method: String,
    val mainFrame: Boolean,
    val resourceType: String? = null,
    val headerNames: Set<String> = emptySet(),
    val timestampEpochMs: Long,
)

data class SourceBrowserDialog(
    val id: Long,
    val type: String,
    val message: String,
    val defaultValue: String? = null,
    val pageUrl: String? = null,
    val accepted: Boolean? = null,
    val responseValue: String? = null,
    val timestampEpochMs: Long,
)

data class SourceBrowserResponse(
    val finalUrl: String?,
    val title: String? = null,
    val value: String? = null,
    val requestMetadata: List<SourceBrowserRequestMetadata> = emptyList(),
    val dialogs: List<SourceBrowserDialog> = emptyList(),
    val degradedIsolation: Boolean = false,
    val rendererRecovered: Boolean = false,
    val traceId: String,
)

fun interface SourceBrowserBroker {
    fun execute(manifest: SourceManifest, request: SourceBrowserRequest): SourcePlatformResult<SourceBrowserResponse>

    companion object {
        val DENY_ALL = SourceBrowserBroker { _, request ->
            SourcePlatformResult.Failure(SourcePlatformFailure(
                SourceErrorCode.BROWSER_UNAVAILABLE,
                "SOURCE_BROWSER_BROKER_UNAVAILABLE",
                request.traceId,
            ))
        }
    }
}

data class SourceStorageRequest(
    val sourceId: String,
    val key: String,
    val value: ByteArray? = null,
    val traceId: String = UUID.randomUUID().toString(),
)

interface SourceStorageBroker {
    fun get(manifest: SourceManifest, request: SourceStorageRequest): SourcePlatformResult<ByteArray?>
    fun put(manifest: SourceManifest, request: SourceStorageRequest): SourcePlatformResult<Unit>
    fun delete(manifest: SourceManifest, request: SourceStorageRequest): SourcePlatformResult<Unit>
    fun keys(manifest: SourceManifest, sourceId: String, prefix: String = "", traceId: String = UUID.randomUUID().toString()): SourcePlatformResult<List<String>> =
        SourcePlatformResult.Failure(SourcePlatformFailure(SourceErrorCode.STORAGE_UNAVAILABLE, "SOURCE_STORAGE_KEYS_UNAVAILABLE", traceId))
    fun clearPrefix(manifest: SourceManifest, sourceId: String, prefix: String, traceId: String = UUID.randomUUID().toString()): SourcePlatformResult<Unit> =
        SourcePlatformResult.Failure(SourcePlatformFailure(SourceErrorCode.STORAGE_UNAVAILABLE, "SOURCE_STORAGE_CLEAR_PREFIX_UNAVAILABLE", traceId))
    fun clear(sourceId: String): SourcePlatformResult<Unit>

    companion object {
        val DENY_ALL = object : SourceStorageBroker {
            private fun failure(request: SourceStorageRequest) = SourcePlatformResult.Failure(
                SourcePlatformFailure(SourceErrorCode.STORAGE_UNAVAILABLE, "SOURCE_STORAGE_BROKER_UNAVAILABLE", request.traceId),
            )

            override fun get(manifest: SourceManifest, request: SourceStorageRequest) = failure(request)
            override fun put(manifest: SourceManifest, request: SourceStorageRequest) = failure(request)
            override fun delete(manifest: SourceManifest, request: SourceStorageRequest) = failure(request)

            override fun keys(
                manifest: SourceManifest,
                sourceId: String,
                prefix: String,
                traceId: String,
            ): SourcePlatformResult<List<String>> = SourcePlatformResult.Success(emptyList())

            override fun clear(sourceId: String) = SourcePlatformResult.Failure(
                SourcePlatformFailure(SourceErrorCode.STORAGE_UNAVAILABLE, "SOURCE_STORAGE_BROKER_UNAVAILABLE"),
            )
        }
    }
}

enum class SourceCryptoOperation { MD5, SHA1, SHA256, SHA512, HMAC_MD5, HMAC_SHA1, HMAC_SHA256, HMAC_SHA512, AES_GCM_ENCRYPT, AES_GCM_DECRYPT }

data class SourceCryptoRequest(
    val sourceId: String,
    val operation: SourceCryptoOperation,
    val payload: ByteArray,
    val keyMaterial: ByteArray? = null,
    val associatedData: ByteArray = ByteArray(0),
    val traceId: String = UUID.randomUUID().toString(),
)

fun interface SourceCryptoBroker {
    fun execute(manifest: SourceManifest, request: SourceCryptoRequest): SourcePlatformResult<ByteArray>

    companion object {
        val DENY_ALL = SourceCryptoBroker { _, request ->
            SourcePlatformResult.Failure(SourcePlatformFailure(
                SourceErrorCode.CRYPTO_UNAVAILABLE,
                "SOURCE_CRYPTO_BROKER_UNAVAILABLE",
                request.traceId,
            ))
        }
    }
}

data class SourceWebSocketRequest(
    val sourceId: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val messages: List<String> = emptyList(),
    val maxResponses: Int = 1,
    val timeoutMs: Long = 30_000,
    val traceId: String = UUID.randomUUID().toString(),
)

data class SourceWebSocketFrame(
    val type: String,
    /** Text payload for text frames; base64 payload for binary frames. */
    val data: String,
) {
    init {
        require(type in setOf("text", "binary")) { "SOURCE_WEBSOCKET_FRAME_TYPE_INVALID" }
    }
}

data class SourceWebSocketResponse(
    val messages: List<String>,
    val closeCode: Int?,
    val closeReason: String?,
    val traceId: String,
    /** Rich frame representation for vBook-compatible hosts. Empty means legacy text-only broker. */
    val frames: List<SourceWebSocketFrame> = emptyList(),
)

fun interface SourceWebSocketBroker {
    fun exchange(manifest: SourceManifest, request: SourceWebSocketRequest): SourcePlatformResult<SourceWebSocketResponse>

    companion object {
        val DENY_ALL = SourceWebSocketBroker { _, request ->
            SourcePlatformResult.Failure(SourcePlatformFailure(
                SourceErrorCode.WEBSOCKET_UNAVAILABLE,
                "SOURCE_WEBSOCKET_BROKER_UNAVAILABLE",
                request.traceId,
            ))
        }
    }
}

data class SourceTranslationRequest(
    val sourceId: String,
    val text: String,
    val storyId: String? = null,
    val chapterId: String? = null,
    val sourceLanguage: String? = null,
    val targetLanguage: String = "vi",
    val instruction: String = "",
    val maxOutputBytes: Int = 2 * 1024 * 1024,
    val traceId: String = UUID.randomUUID().toString(),
    /** Ecosystem-specific, string-only options. Generic translators may safely ignore them. */
    val options: Map<String, String> = emptyMap(),
)

data class SourceTranslationSegment(
    val srcStart: Int,
    val srcLen: Int,
    val transStart: Int,
    val transLen: Int,
    val type: Int,
)

data class SourceTranslationResponse(
    val translatedText: String,
    val segments: List<String> = emptyList(),
    val provider: String? = null,
    val traceId: String,
    /** Offset metadata used by vBook Quick Translator; empty means unavailable. */
    val segmentMetadata: List<SourceTranslationSegment> = emptyList(),
)

fun interface SourceTranslationBroker {
    fun translate(manifest: SourceManifest, request: SourceTranslationRequest): SourcePlatformResult<SourceTranslationResponse>

    companion object {
        val DENY_ALL = SourceTranslationBroker { _, request ->
            SourcePlatformResult.Failure(SourcePlatformFailure(
                SourceErrorCode.TRANSLATION_UNAVAILABLE,
                "SOURCE_TRANSLATION_BROKER_UNAVAILABLE",
                request.traceId,
            ))
        }
    }
}

data class SourceNativeHookRequest(
    val sourceId: String,
    val sourceCode: ByteArray,
    val hookName: String,
    val inputJson: String,
    val instructionBudget: Int,
    val timeoutMs: Long,
    val memoryBudgetBytes: Int = 32 * 1024 * 1024,
    val moduleSources: Map<String, ByteArray> = emptyMap(),
    val resourceSources: Map<String, ByteArray> = emptyMap(),
    val maxOutputBytes: Int = 1024 * 1024,
    val traceId: String = UUID.randomUUID().toString(),
)

fun interface SourceNativeHookBroker {
    fun execute(manifest: SourceManifest, request: SourceNativeHookRequest): SourcePlatformResult<String>

    companion object {
        val DENY_ALL = SourceNativeHookBroker { _, request ->
            SourcePlatformResult.Failure(SourcePlatformFailure(
                SourceErrorCode.NATIVE_LUA_RUNTIME_UNAVAILABLE,
                "SOURCE_NATIVE_HOOK_BROKER_UNAVAILABLE",
                request.traceId,
            ))
        }
    }
}

data class SourceGraphicsImage(
    val base64: String,
)

data class SourceGraphicsDrawOperation(
    val imageBase64: String,
    val args: List<Double> = emptyList(),
    val alpha: Double = 1.0,
)

data class SourceGraphicsRequest(
    val sourceId: String,
    val width: Int,
    val height: Int,
    val operations: List<SourceGraphicsDrawOperation>,
    val format: String = "PNG",
    val quality: Int = 100,
    val maxOutputBytes: Int = 8 * 1024 * 1024,
    val traceId: String = UUID.randomUUID().toString(),
)

fun interface SourceGraphicsBroker {
    fun render(manifest: SourceManifest, request: SourceGraphicsRequest): SourcePlatformResult<String>

    companion object {
        val DENY_ALL = SourceGraphicsBroker { _, request ->
            SourcePlatformResult.Failure(SourcePlatformFailure(
                SourceErrorCode.GRAPHICS_UNAVAILABLE,
                "SOURCE_GRAPHICS_BROKER_UNAVAILABLE",
                request.traceId,
            ))
        }
    }
}

data class SourceCapabilityBrokers(
    val network: SourceNetworkBroker = SourceNetworkBroker.DENY_ALL,
    val browser: SourceBrowserBroker = SourceBrowserBroker.DENY_ALL,
    val storage: SourceStorageBroker = SourceStorageBroker.DENY_ALL,
    val crypto: SourceCryptoBroker = SourceCryptoBroker.DENY_ALL,
    val websocket: SourceWebSocketBroker = SourceWebSocketBroker.DENY_ALL,
    val nativeHooks: SourceNativeHookBroker = SourceNativeHookBroker.DENY_ALL,
    val graphics: SourceGraphicsBroker = SourceGraphicsBroker.DENY_ALL,
    /** Generic translation extension/provider path. */
    val translation: SourceTranslationBroker = SourceTranslationBroker.DENY_ALL,
    val cookies: SourceCookiePartition = SourceCookiePartition.NONE,
    /** vBook Qt.translate vp/hv path. Kept separate from generic AI/translate extensions. */
    val quickTranslation: SourceTranslationBroker = SourceTranslationBroker.DENY_ALL,
    /** NgheTruyen-owned UI/reader/library/TTS host command boundary. */
    val hostKernel: SourceHostKernelBroker = SourceHostKernelBroker.UNAVAILABLE,
    /** Host-to-extension lifecycle/event delivery boundary. */
    val hostEvents: SourceHostEventSink = SourceHostEventSink.NONE,
)
