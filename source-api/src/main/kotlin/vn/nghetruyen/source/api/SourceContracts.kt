package vn.nghetruyen.source.api

import java.util.UUID

enum class SourceErrorCode {
    PACKAGE_INVALID,
    PACKAGE_TOO_LARGE,
    PACKAGE_PATH_INVALID,
    PACKAGE_HASH_MISMATCH,
    PACKAGE_SIGNATURE_INVALID,
    PACKAGE_SIGNATURE_UNSUPPORTED,
    MANIFEST_INVALID,
    PERMISSION_DENIED,
    ACTION_NOT_FOUND,
    RUNTIME_BUDGET_EXCEEDED,
    RUNTIME_OUTPUT_TOO_LARGE,
    RUNTIME_INVALID_PROGRAM,
    RUNTIME_RESOURCE_MISSING,
    RUNTIME_TYPE_ERROR,
    INSTALL_FAILED,
    ROLLBACK_UNAVAILABLE,
    NETWORK_UNAVAILABLE,
    NETWORK_URL_INVALID,
    NETWORK_DNS_BLOCKED,
    NETWORK_METHOD_DENIED,
    NETWORK_RATE_LIMITED,
    NETWORK_CONCURRENCY_LIMITED,
    NETWORK_REQUEST_TOO_LARGE,
    NETWORK_RESPONSE_TOO_LARGE,
    NETWORK_REDIRECT_DENIED,
    NETWORK_HTTP_ERROR,
    NETWORK_TIMEOUT,
    NETWORK_IO_ERROR,
    COOKIE_INVALID,
    BROWSER_UNAVAILABLE,
    BROWSER_NAVIGATION_DENIED,
    BROWSER_SELECTOR_NOT_FOUND,
    BROWSER_RENDERER_GONE,
    BROWSER_TIMEOUT,
    BROWSER_OUTPUT_TOO_LARGE,
    GRAPHICS_UNAVAILABLE,
    TRANSLATION_UNAVAILABLE,
    STORAGE_UNAVAILABLE,
    STORAGE_QUOTA_EXCEEDED,
    CRYPTO_UNAVAILABLE,
    WEBSOCKET_UNAVAILABLE,
    WEBSOCKET_MESSAGE_TOO_LARGE,
    WEBSOCKET_TIMEOUT,
    VBOOK_IMPORT_INVALID,
    VBOOK_RUNTIME_UNAVAILABLE,
    VBOOK_SCRIPT_ERROR,
    VBOOK_COMPATIBILITY_UNSUPPORTED,
    NATIVE_LUA_IMPORT_INVALID,
    NATIVE_LUA_RUNTIME_UNAVAILABLE,
    NATIVE_LUA_SCRIPT_ERROR,
    NATIVE_LUA_HOOK_NOT_FOUND,
    TRUST_KEY_INVALID,
    TRUST_KEY_ROTATION_INVALID,
    REPOSITORY_INVALID,
    REPOSITORY_SIGNATURE_INVALID,
    REPOSITORY_EXPIRED,
    REPOSITORY_DOWNLOAD_FAILED,
    INTERNAL_ERROR,
}

data class SourcePlatformFailure(
    val code: SourceErrorCode,
    val message: String,
    val traceId: String? = null,
    val cause: Throwable? = null,
)

sealed interface SourcePlatformResult<out T> {
    data class Success<T>(val value: T) : SourcePlatformResult<T>
    data class Failure(val error: SourcePlatformFailure) : SourcePlatformResult<Nothing>
}

data class SourceActionRequest(
    val sourceId: String,
    val action: SourceActionName,
    val input: JsonValue.Obj = JsonValue.Obj(),
    val traceId: String = UUID.randomUUID().toString(),
)

data class SourceActionResponse(
    val value: JsonValue,
    val traceId: String,
    val instructionCount: Int,
)

data class SourcePermissionSnapshot(
    val origins: Set<String>,
    val redirectOrigins: Set<String>,
    val networkMethods: Set<String>,
    val cookieMode: SourceCookieMode,
    val browser: SourceBrowserCapability,
    val storageBytes: Int,
    val crypto: Set<SourceCryptoCapability>,
    val websocketEnabled: Boolean,
) {
    companion object {
        fun from(manifest: SourceManifest): SourcePermissionSnapshot = SourcePermissionSnapshot(
            origins = manifest.origins,
            redirectOrigins = manifest.redirectOrigins,
            networkMethods = manifest.capabilities.network?.methods.orEmpty(),
            cookieMode = manifest.capabilities.cookies,
            browser = manifest.capabilities.browser,
            storageBytes = manifest.capabilities.storageBytes,
            crypto = manifest.capabilities.crypto,
            websocketEnabled = manifest.capabilities.websocket.enabled,
        )
    }
}

data class SourcePermissionDiff(
    val addedOrigins: Set<String> = emptySet(),
    val addedRedirectOrigins: Set<String> = emptySet(),
    val addedNetworkMethods: Set<String> = emptySet(),
    val cookieEscalated: Boolean = false,
    val browserEscalations: Set<String> = emptySet(),
    val storageIncreaseBytes: Int = 0,
    val addedCrypto: Set<SourceCryptoCapability> = emptySet(),
    val websocketEnabled: Boolean = false,
) {
    val requiresApproval: Boolean get() =
        addedOrigins.isNotEmpty() || addedRedirectOrigins.isNotEmpty() || addedNetworkMethods.isNotEmpty() ||
            cookieEscalated || browserEscalations.isNotEmpty() || storageIncreaseBytes > 0 ||
            addedCrypto.isNotEmpty() || websocketEnabled

    companion object {
        fun between(old: SourcePermissionSnapshot?, new: SourcePermissionSnapshot): SourcePermissionDiff {
            if (old == null) return SourcePermissionDiff(
                addedOrigins = new.origins,
                addedRedirectOrigins = new.redirectOrigins,
                addedNetworkMethods = new.networkMethods,
                cookieEscalated = new.cookieMode != SourceCookieMode.NONE,
                browserEscalations = enabledBrowserCapabilities(new.browser),
                storageIncreaseBytes = new.storageBytes,
                addedCrypto = new.crypto,
                websocketEnabled = new.websocketEnabled,
            )
            return SourcePermissionDiff(
                addedOrigins = new.origins - old.origins,
                addedRedirectOrigins = new.redirectOrigins - old.redirectOrigins,
                addedNetworkMethods = new.networkMethods - old.networkMethods,
                cookieEscalated = cookieRank(new.cookieMode) > cookieRank(old.cookieMode),
                browserEscalations = enabledBrowserCapabilities(new.browser) - enabledBrowserCapabilities(old.browser),
                storageIncreaseBytes = (new.storageBytes - old.storageBytes).coerceAtLeast(0),
                addedCrypto = new.crypto - old.crypto,
                websocketEnabled = new.websocketEnabled && !old.websocketEnabled,
            )
        }

        private fun cookieRank(value: SourceCookieMode): Int = when (value) {
            SourceCookieMode.NONE -> 0
            SourceCookieMode.READ, SourceCookieMode.WRITE -> 1
            SourceCookieMode.READ_WRITE -> 2
            SourceCookieMode.BROWSER_SHARED -> 3
        }

        private fun enabledBrowserCapabilities(value: SourceBrowserCapability): Set<String> = buildSet {
            if (value.navigate) add("navigate")
            if (value.domSnapshot) add("domSnapshot")
            if (value.click) add("click")
            if (value.input) add("input")
            if (value.requestMetadata) add("requestMetadata")
            if (value.serviceWorkerCapture) add("serviceWorkerCapture")
            if (value.pageJavaScript) add("pageJavaScript")
        }
    }
}
