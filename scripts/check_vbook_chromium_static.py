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
    prelude = read("app/src/main/java/vn/nghetruyen/app/sourceplatform/ChromiumVBookPrelude.kt")
    projection = read("app/src/main/java/vn/nghetruyen/app/sourceplatform/ChromiumVBookNetworkProjectionBroker.kt")
    browser_parity = read("app/src/main/java/vn/nghetruyen/app/sourceplatform/ChromiumVBookDispatcherParityRuntime.kt")
    application = read("app/src/main/java/vn/nghetruyen/app/NgheTruyenApplication.kt")
    selector = read("source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/PrimaryFallbackVBookActionRuntime.kt")
    registry = read("source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookActionRuntimeRegistry.kt")
    compatibility = read("source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookCompatibilityRuntime.kt")

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
        "blockNetworkLoads = true",
        "allowFileAccess = false",
        "allowContentAccess = false",
        "SourceHostKernelWireExecutor.execute(",
        "brokers.network.execute(",
        "brokers.browser.execute(",
        "MAX_BRIDGE_CALLS",
    )
    require(
        prelude,
        "Chromium compatibility prelude",
        "global.prompt.bind(global)",
        "Object.defineProperty(global,'__bridge'",
        "factory.call(global)",
        "return String(Script.execute($entry,'execute',__payload));",
        "out.waitRequest=function(pattern,timeoutMs)",
        "out.loadHtml=function(baseUrl,html)",
        "out.setCookies=function(cookies,url)",
    )
    require(
        projection,
        "Chromium raw-network projection",
        'envelope.int("__ngheVBookFetch") != 1',
        "VBookRawNetworkBroker.INTERNAL_RESPONSE_KEY",
        "VBookRawNetworkBroker.INTERNAL_RAW_SIZE",
        "VBookRawNetworkBroker.INTERNAL_STATUS_TEXT",
    )
    require(
        browser_parity,
        "Chromium browser ABI parity",
        "ChromiumVBookDispatcherParityRuntime",
        "CHROMIUM_PATCH_MARKER",
        "nativeBrowser.launch=function(url,timeoutMs)",
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
        "ChromiumVBookNetworkProjectionBroker(brokers.network)",
        "ChromiumVBookDispatcherParityRuntime(",
        "IdentityHashMap<Any, VBookActionRuntime>()",
    )
    require(
        selector,
        "side-effect-safe fallback",
        "PRE_EXECUTION_UNAVAILABLE_PREFIXES",
        '"CHROMIUM_WEBVIEW_UNAVAILABLE:"',
        '"CHROMIUM_MAIN_THREAD_CALLER_UNSUPPORTED"',
        "result.error.code != SourceErrorCode.VBOOK_RUNTIME_UNAVAILABLE",
    )
    require(registry, "platform runtime registry", "AtomicReference<VBookActionRuntimeFactory?>(null)", "platformRuntime(")
    require(compatibility, "runtime-neutral compatibility facade", "private val runtime: VBookActionRuntime")

    combined = "\n".join((runtime, prelude, browser_parity))
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
