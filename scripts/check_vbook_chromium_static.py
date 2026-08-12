#!/usr/bin/env python3
"""Offline M3 architecture gate for the Chromium-primary raw-vBook runtime."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def require(text: str, label: str, *tokens: str) -> None:
    for token in tokens:
        assert token in text, f"{label} missing M3 invariant: {token}"


def main() -> None:
    runtime = read("app/src/main/java/vn/nghetruyen/app/sourceplatform/AndroidChromiumVBookRuntime.kt")
    replay = read("app/src/main/java/vn/nghetruyen/app/sourceplatform/ChromiumVBookBrowserReplayRuntime.kt")
    prelude = read("app/src/main/java/vn/nghetruyen/app/sourceplatform/ChromiumVBookPrelude.kt")
    dispatch_decoder = read("app/src/main/java/vn/nghetruyen/app/sourceplatform/ChromiumVBookDispatchDecoder.kt")
    browser_parity = read("app/src/main/java/vn/nghetruyen/app/sourceplatform/ChromiumVBookDispatcherParityRuntime.kt")
    application = read("app/src/main/java/vn/nghetruyen/app/NgheTruyenApplication.kt")
    selector = read("source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/PrimaryFallbackVBookActionRuntime.kt")
    registry = read("source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookActionRuntimeRegistry.kt")
    compatibility = read("source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookCompatibilityRuntime.kt")
    safe_fetch = read("source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookFetchSafePrelude.kt")
    raw_network = read("source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookRawNetworkBroker.kt")

    require(
        runtime,
        "Chromium action runtime",
        'HandlerThread("NgheTruyen-VBook-Chromium")',
        "private val main = Handler(Looper.getMainLooper())",
        'unavailable("CHROMIUM_MAIN_THREAD_CALLER_UNSUPPORTED", request)',
        "main.post {",
        "engine.post {",
        "override fun onJsPrompt(",
        "override fun onPageFinished(view: WebView, url: String?)",
        'webView.loadUrl("about:blank")',
        "result.confirm(response)",
        "ChromiumVBookDispatchDecoder.decode(raw)",
        "__ngheChromiumEvalError",
        "view.evaluateJavascript(guardedProgram)",
        "blockNetworkLoads = true",
        "allowFileAccess = false",
        "allowContentAccess = false",
        "SourceHostKernelWireExecutor.execute(",
        "brokers.network.execute(",
        "brokers.browser.execute(",
        "MAX_BRIDGE_CALLS",
    )
    require(
        replay,
        "Chromium Browser replay coordinator",
        'CHROMIUM_BROWSER_REPLAY_REQUIRED = "SOURCE_BROWSER_REPLAY_REQUIRED"',
        "ChromiumVBookBrowserReplayRuntime",
        "ChromiumVBookReplayCoordinator",
        "pendingBrowser",
        "browserCache",
        "networkCache",
        "resolvePendingBrowser",
        "networkReplayHits",
        "MAX_BROWSER_REPLAYS",
        "replayAwareChromiumDiagnostics",
        "CHROMIUM_BROWSER_REPLAY_YIELDED",
    )
    require(
        dispatch_decoder,
        "Chromium dispatcher ABI decoder",
        "object ChromiumVBookDispatchDecoder",
        'RAW_RESULT_KEY = "__ngheVBookRawResult"',
        'EVAL_ERROR_KEY = "__ngheChromiumEvalError"',
        'error("CHROMIUM_EVAL_ERROR:$evaluationError")',
        "MAX_LAYERS = 8",
        "code == 0 -> obj.values[\"data\"] ?: JsonValue.Null",
        'error("CHROMIUM_DISPATCH_RESULT_DEPTH_EXCEEDED")',
    )
    require(
        prelude,
        "Chromium compatibility prelude",
        "global.prompt.bind(global)",
        "Object.defineProperty(global,'__bridge'",
        "Object.defineProperty(global,name,{value:value,writable:true,configurable:true,enumerable:true})",
        "__installHostGlobal('localStorage',__vbookLocalStorage)",
        "factory.call(global)",
        "return String(Script.execute($entry,'execute',__payload));",
        "out.waitRequest=function(pattern,timeoutMs)",
        "out.loadHtml=function(baseUrl,html)",
        "out.setCookies=function(cookies,url)",
    )
    require(
        safe_fetch,
        "raw vBook fetch contract",
        "var response=__vbookNativeFetch(url,nativeOptions);",
        "envelope=JSON.parse(String(response.body || '{}'))",
        "envelope.__ngheVBookFetch !== 1",
        "__vbookSafeCachedResponse",
    )
    require(
        raw_network,
        "raw vBook network broker",
        "responseEnvelopeJson(response, key ?: \"\")",
        '"__ngheVBookFetch" to JsonValue.Num(1.0, "1")',
        "INTERNAL_REQUEST_KEY",
        "INTERNAL_OPERATION",
    )
    require(
        browser_parity,
        "Chromium browser ABI parity",
        "ChromiumVBookDispatcherParityRuntime",
        "CHROMIUM_PATCH_MARKER",
        "nativeBrowser.launch=function(url,timeoutMs)",
        "nativeBrowser.loadHtml=function(baseUrl,html)",
        "lowLoadHtml.call(nativeBrowser,String(baseUrl||''),String(html==null?'':html))",
        "nativeBrowser.html=function(waitMs)",
        "nativeBrowser.waitRequest=function(raw,timeoutMs,options)",
        "nativeBrowser.cookieSnapshot=function(url)",
        "nativeBrowser.waitDialog=function(options,timeoutMs)",
    )
    require(
        application,
        "Android Chromium primary selection",
        "VBookActionRuntimeRegistry.install",
        "WebView.getCurrentWebViewPackage() == null",
        '"CHROMIUM_WEBVIEW_UNAVAILABLE:provider-missing"',
        "ChromiumVBookReplayCoordinator(",
        "brokers = brokers.copy(",
        "browser = replay.browserBroker",
        "network = replay.networkBroker",
        "replayAwareChromiumDiagnostics(diagnostics)",
        "ChromiumVBookBrowserReplayRuntime(",
        "ChromiumVBookDispatcherParityRuntime(",
        "IdentityHashMap<Any, VBookActionRuntime>()",
    )
    assert "ChromiumVBookNetworkProjectionBroker" not in application, "Chromium must preserve the raw vBook metadata envelope"
    require(
        selector,
        "side-effect-safe fallback",
        "PRE_EXECUTION_UNAVAILABLE_PREFIXES",
        '"CHROMIUM_WEBVIEW_UNAVAILABLE:"',
        '"CHROMIUM_MAIN_THREAD_CALLER_UNSUPPORTED"',
        "result.error.code != SourceErrorCode.VBOOK_RUNTIME_UNAVAILABLE",
    )
    assert "requiresPortableBrowserRuntime" not in selector, "Browser scripts must stay on Chromium and use broker replay"
    assert "BROWSER_HOST_ACCESS" not in selector, "Static Browser routing must not bypass Chromium replay"
    require(registry, "platform runtime registry", "AtomicReference<VBookActionRuntimeFactory?>(null)", "platformRuntime(")
    require(compatibility, "runtime-neutral compatibility facade", "private val runtime: VBookActionRuntime")

    combined = "\n".join((runtime, replay, prelude, dispatch_decoder, browser_parity))
    for forbidden in (
        "addJavascriptInterface(",
        "setAllowUniversalAccessFromFileURLs(",
        "setAllowFileAccessFromFileURLs(",
        "Class.forName(",
        "Runtime.getRuntime(",
        "ProcessBuilder(",
    ):
        assert forbidden not in combined, f"Chromium action runtime exposes forbidden escape: {forbidden}"

    for frozen_decorator in (
        "global.Html=global.HTML=global.Document=Object.freeze(",
        "global.Engine=Object.freeze(",
        "global.Qt=Object.freeze(",
    ):
        assert frozen_decorator not in prelude, f"Compatibility decorator was frozen too early: {frozen_decorator}"

    print("VBOOK_CHROMIUM_ARCHITECTURE_OK")


if __name__ == "__main__":
    main()
