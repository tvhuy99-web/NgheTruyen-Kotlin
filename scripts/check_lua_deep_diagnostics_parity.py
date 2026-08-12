#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def text(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


runtime = text("app/src/main/java/vn/nghetruyen/app/sourceplatform/SourceDiagnosticRuntime.kt")
deep = text("app/src/main/java/vn/nghetruyen/app/sourceplatform/DiagnosticDeepBlackBox.kt")
browser = text("app/src/main/java/vn/nghetruyen/app/sourceplatform/AndroidSourceBrowserBroker.kt")
chromium = text("app/src/main/java/vn/nghetruyen/app/sourceplatform/AndroidChromiumVBookRuntime.kt")
models = text("app/src/main/java/vn/nghetruyen/app/sourceplatform/SourcePlatformModels.kt")
core = text("source-diagnostics/src/main/kotlin/vn/nghetruyen/source/diagnostics/SourceDiagnostics.kt")
retention_test = text("source-diagnostics/src/test/kotlin/vn/nghetruyen/source/diagnostics/DiagnosticRetentionStatsTest.kt")

lua_codes = (
    "NETWORK_API_MISSING",
    "NETWORK_HTTP_STATUS",
    "NETWORK_EMPTY_RESPONSE",
    "NETWORK_TIMEOUT",
    "NETWORK_START_FAILED",
    "NETWORK_RESPONSE_TOO_LARGE",
    "REPOSITORY_JSON_INVALID",
    "REPOSITORY_SCHEMA_INVALID",
    "REPOSITORY_NO_NOVEL",
    "REPOSITORY_ENTRY_INVALID",
    "REMOTE_URL_EMPTY",
    "REMOTE_URL_INVALID",
    "DIRECT_VBOOK_MANIFEST_ONLY",
    "ZIP_INVALID",
    "ZIP_MISSING_MANIFEST",
    "ZIP_LIMIT",
    "VBOOK_MANIFEST_INVALID",
    "INSTALL_VALIDATION_FAILED",
    "INSTALL_IO_FAILED",
    "INSTALL_FINAL_VALIDATION_FAILED",
    "UNKNOWN_INSTALL_ERROR",
)

checks = {
    "exact Lua error recipes retained": (
        "exactLuaCause" in models
        and "exactLuaSuggestion" in models
        and all(code in models for code in lua_codes)
        and 'first(attributes, "code", "errorCode", "error_code")' in models
    ),
    "operation deadline reconstruction": all(token in deep for token in (
        "OperationState",
        "timeoutMs",
        "deadlineEpochMs",
        "remainingMs",
        "pollCount",
        "pollElapsedMs",
        "active",
        "terminal",
    )),
    "Lua-style flow reconstruction": all(token in deep for token in (
        '"browser"',
        '"network"',
        '"native"',
        '"executor"',
        '"bridge"',
        '"download"',
        '"prefetch"',
        "flowLogs",
    )),
    "forensic reports exported": all(token in runtime for token in (
        'report/operations.json',
        'report/flows.json',
        'report/browser_sessions.json',
        'report/data_loss.json',
        'flows/${safePath(flow)}.log',
    )),
    "browser environment snapshot": all(token in browser for token in (
        "BROWSER_ENVIRONMENT_SNAPSHOT",
        "webViewPackage",
        "webViewVersion",
        "javaScriptEnabled",
        "domStorageEnabled",
        "databaseEnabled",
        "allowFileAccess",
        "allowContentAccess",
        "mixedContentMode",
        "safeBrowsingEnabled",
        "acceptCookies",
        "acceptThirdPartyCookies",
        "cookieCount",
    )),
    "Lua JS compatibility matrix and safe page forensics": all(token in (browser + text("app/src/main/java/vn/nghetruyen/app/sourceplatform/BrowserForensics.kt")) for token in (
        "BROWSER_PAGE_FORENSICS",
        "XMLHttpRequest",
        "WebSocket",
        "cryptoSubtle",
        "TextEncoder",
        "TextDecoder",
        "URLSearchParams",
        "IndexedDB",
        "WebAssembly",
        "MutationObserver",
        "IntersectionObserver",
        "ResizeObserver",
        "AbortController",
        "structuredClone",
        "recentResources",
        "mutationAgeMs",
        "htmlLength",
        "textLength",
        "elementCount",
    )),
    "page forensics excludes resource URLs and storage values": (
        'name:String(e.name' not in text("app/src/main/java/vn/nghetruyen/app/sourceplatform/BrowserForensics.kt")
        and "localStorageKeys" not in text("app/src/main/java/vn/nghetruyen/app/sourceplatform/BrowserForensics.kt")
        and "sessionStorageKeys" not in text("app/src/main/java/vn/nghetruyen/app/sourceplatform/BrowserForensics.kt")
        and "cookieNames" not in text("app/src/main/java/vn/nghetruyen/app/sourceplatform/BrowserForensics.kt")
    ),
    "browser HTTP/progress/dialog timeline": all(token in browser for token in (
        "BROWSER_HTTP_ERROR",
        "onReceivedHttpError",
        "BROWSER_PROGRESS",
        "onProgressChanged",
        "BROWSER_JS_DIALOG",
    )),
    "browser evaluate forensic timeline": all(token in browser for token in (
        "BROWSER_EVAL_STARTED",
        "BROWSER_EVAL_CALLBACK",
        "BROWSER_EVAL_COMPLETED",
        "BROWSER_EVAL_TIMEOUT",
        "BROWSER_EVAL_ERROR",
        "AtomicBoolean",
    )),
    "browser session generation metadata": all(token in browser for token in (
        "sessionId",
        "navigationGeneration",
        "UUID.randomUUID",
    )),
    "browser page state snapshot": all(token in browser for token in (
        "BROWSER_STATE_SNAPSHOT",
        "document.readyState",
        "pageCookieCount",
        "pageDomStorage",
        "pageStartedCount",
        "pageFinishedCount",
        "lateCallbacks",
        "rendererGone",
        "pendingError",
    )),
    "cookie probe is non-mutating": (
        "document.cookie ? document.cookie.split" in browser
        and "manager.getCookie(pageUrl)" in browser
        and "diagnostic_probe_cookie=" not in browser
        and "document.cookie =" not in browser
    ),
    "late browser callbacks are counted": (
        "val late = pageLatch == null" in browser
        and "lateCallbacks += 1" in browser
        and '"lateCallbacks" to lateCallbacks.toString()' in browser
    ),
    "selector polling carries flow deadline state": (
        browser.count('"stage" to "selector_probe"') >= 3
        and '"remainingMs" to (deadline - clockMs()).coerceAtLeast(0L).toString()' in browser
        and '"polls" to polls.toString()' in browser
    ),
    "Chromium bridge calls expose deadline without payload": (
        "CHROMIUM_BRIDGE_CALL" in chromium
        and '"flow" to "bridge"' in chromium
        and '"remainingMs" to remainingMs().toString()' in chromium
        and '"bridgeCalls" to calls.toString()' in chromium
    ),
    "RAM event loss is explicit": (
        "DiagnosticRecorderStats" in core
        and "evictedEvents" in core
        and "fun stats(): DiagnosticRecorderStats" in core
        and 'put("ramEventEvicted"' in runtime
    ),
    "RAM evidence truncation is explicit": (
        "truncatedItems" in core
        and "ramEvidenceTruncatedItems" in runtime
        and "evidenceReportsTruncation" in retention_test
    ),
    "deep data-loss report includes all counters": all(token in deep for token in (
        '"ramEventEvicted"',
        '"ramEvidenceEvictedItems"',
        '"ramEvidenceTruncatedItems"',
        '"eventsMarkedTruncated"',
        '"eventsReportingDroppedData"',
        '"lossVisible"',
    )),
    "raw flow logs are redacted": "DiagnosticRedactor.redact(event.attributes)" in deep,
}

missing = [name for name, ok in checks.items() if not ok]
if missing:
    raise SystemExit("LUA_DEEP_DIAGNOSTICS_PARITY missing: " + "; ".join(missing))

print("LUA_DEEP_DIAGNOSTICS_PARITY=PASS")
