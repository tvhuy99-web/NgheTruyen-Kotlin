#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def text(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


app = text("app/src/main/java/vn/nghetruyen/app/ui/ReferenceNgheTruyenApp.kt")
reader = text("app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt")
personal = text("app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt")
chrome = text("app/src/main/java/vn/nghetruyen/app/ui/components/ReferenceDiagnosticsChrome.kt")
runtime = text("app/src/main/java/vn/nghetruyen/app/sourceplatform/SourceDiagnosticRuntime.kt")
vm = text("app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt")
tts = text("app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt")
ai = text("app/src/main/java/vn/nghetruyen/app/ai/XpkNarrationAiServices.kt")
ai_text = text("app/src/main/java/vn/nghetruyen/app/ai/OnlineAiServices.kt")
vp_engine = text("app/src/main/java/vn/nghetruyen/app/ai/vietphrase/VietPhraseEngine.kt")
vp_export = text("app/src/main/java/vn/nghetruyen/app/ai/vietphrase/VietPhraseDiagnosticExporter.kt")
download = text("app/src/main/java/vn/nghetruyen/app/downloads/ChapterDownloadWorker.kt")
browser = text("app/src/main/java/vn/nghetruyen/app/sourceplatform/AndroidSourceBrowserBroker.kt")
login = text("app/src/main/java/vn/nghetruyen/app/sources/SourceLoginActivity.kt")
diagnostic_browser = text("app/src/main/java/vn/nghetruyen/app/sources/SourceDiagnosticBrowserActivity.kt")
vbook_runtime = text("source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookJsRuntime.kt")
source_manager = text("app/src/main/java/vn/nghetruyen/app/sourceplatform/SourcePlatformManager.kt")
audio = text("app/src/main/java/vn/nghetruyen/app/audio/AudioExportWorker.kt")

checks = {
    "global bottom diagnostics chrome": (
        "ReferenceDiagnosticsChrome(" in app
        and "bottomBar = {" in app
        and app.index("ReferenceDiagnosticsChrome(") < app.index("ReferencePrimaryBottomBar(")
    ),
    "OFF hides diagnostics completely": 'if (state.diagnosticsMode == "off") return' in chrome,
    "Lua recording label": "ĐANG GHI NHẬT KÝ..." in chrome,
    "Lua view label": "XEM NHẬT KÝ" in chrome,
    "Lua empty label": "CHƯA CÓ NHẬT KÝ" in chrome,
    "Reader has no private always-visible log button": 'ReaderButton("XEM NHẬT KÝ"' not in reader,
    "Reader has no private diagnostic dialog": "showDiagnosticLogDialog" not in reader,
    "settings log card hidden while OFF": 'if (state.diagnosticsMode != "off") {' in personal,
    "settings diagnostics includes source diagnostics": (
        '"settings_diagnostics" -> PersonalSubPage("CHẨN ĐOÁN")' in personal
        and "SourceDiagnosticsSection(" in personal
    ),
    "large live event window": "diagnosticSummaries(200)" in vm,
    "large live trace window": "diagnosticTraces(100)" in vm,
    "shared app diagnostic mark API": "fun mark(" in runtime and "DIAGNOSTICS_MODE_CHANGED" in runtime,
    "dual Advanced profiles": all(token in runtime for token in ("advanced_ram", "advanced_crash", "crashSafe")),
    "active operation tracker": "DiagnosticActivityTracker" in runtime and "diagnosticActiveOperations" in vm and "ĐANG HOẠT ĐỘNG" in chrome,
    "critical breadcrumbs while OFF": "shouldRetainWhenDiagnosticsOff" in runtime and "MAX_CRITICAL_EVENTS = 100" in runtime,
    "runtime snapshot exported": "report/app_runtime.json" in runtime,
    "backup log tail exported": "report/backup_tail.log" in runtime,
    "TTS lifecycle diagnostics": all(
        marker in tts
        for marker in (
            "TTS_SERVICE_CREATED",
            "TTS_COMMAND",
            "TTS_ENGINE_READY",
            "TTS_ENGINE_INIT_FAILED",
            "TTS_UTTERANCE_START",
            "TTS_UTTERANCE_ERROR",
            "TTS_AUDIO_FOCUS_FAILED",
            "TTS_VOICE_ENGINE_SWITCH",
        )
    ),
    "AI narration diagnostics": all(
        marker in ai
        for marker in (
            "AI_NARRATION_PLAN_START",
            "AI_NARRATION_PLAN_COMPLETED",
            "AI_NARRATION_FAILURE",
        )
    ),
    "AI text HTTP diagnostics": all(marker in ai_text for marker in (
        "AI_TRANSLATION_STARTED",
        "AI_TRANSLATION_COMPLETED",
        "AI_VIETPHRASE_IMPROVEMENT_STARTED",
        "AI_HTTP_ATTEMPT_STARTED",
        "AI_HTTP_RESPONSE_RECEIVED",
        "AI_HTTP_ENDPOINT_FALLBACK",
        "AI_HTTP_RETRY_SCHEDULED",
    )),
    "AI Advanced request response evidence": "captureAiEvidence" in ai_text and "DiagnosticEvidence" in ai_text,
    "VietPhrase candidate probe diagnostics": all(token in vp_engine for token in ("literalCandidates", "templateAttempts", "templateBudgetExceeded", "unmatchedCodePoints", "diagnosticProbeLimit")),
    "VietPhrase rich diagnostic bundle": all(token in vp_export for token in ("engine_stats.json", "probes.tsv", "VIETPHRASE_ENGINE_STATS", "vietphrase-source.txt")),
    "real login browser diagnostics": all(marker in login for marker in (
        "SOURCE_LOGIN_STARTED",
        "SOURCE_LOGIN_PAGE_STARTED",
        "SOURCE_LOGIN_PAGE_FINISHED",
        "SOURCE_LOGIN_REQUEST",
        "SOURCE_LOGIN_WEB_ERROR",
        "SOURCE_LOGIN_SSL_BLOCKED",
        "SOURCE_LOGIN_SESSION_CAPTURED",
        "SOURCE_LOGIN_STOPPED",
        "EXTRA_TRACE_ID",
    )) and "getInt(KEY_LOG_LEVEL, 1)" in login and "getBoolean(KEY_AUTO_CLEAR_LOG_ON_CLOSE, true)" in login,
    "diagnostic browser mirrors global trace": "mirrorGlobal" in diagnostic_browser and "DIAGNOSTIC_BROWSER_STARTED" in diagnostic_browser and "DIAGNOSTIC_BROWSER_STOPPED" in diagnostic_browser,
    "deep browser WebView timeline": all(marker in browser for marker in (
        "BROWSER_PAGE_STARTED",
        "BROWSER_PAGE_FINISHED",
        "BROWSER_WEB_ERROR",
        "BROWSER_SSL_ERROR",
        "BROWSER_SAFE_BROWSING_BLOCKED",
        "BROWSER_RENDERER_GONE",
        "BROWSER_SELECTOR_PROBE",
        "BROWSER_SELECTOR_FOUND",
        "BROWSER_SELECTOR_TIMEOUT",
        "BROWSER_ASYNC_SCRIPT_POLL",
        "BROWSER_ASYNC_SCRIPT_RESOLVED",
    )),
    "vBook executor bridge diagnostics": all(marker in vbook_runtime for marker in (
        "VBOOK_STAGE_SANDBOX_ENTERED",
        "VBOOK_STAGE_HOST_API_READY",
        "VBOOK_STAGE_BOOTSTRAP_EVALUATED",
        "VBOOK_RESOURCE_LOADED",
        "VBOOK_STAGE_EXECUTOR_CALL",
        "VBOOK_STAGE_EXECUTOR_RETURNED",
        "VBOOK_STAGE_RESULT_NORMALIZED",
        "VBOOK_BRIDGE_NATIVE_HOOK_STARTED",
        "VBOOK_BRIDGE_NATIVE_HOOK_COMPLETED",
        "VBOOK_BRIDGE_NATIVE_HOOK_FAILED",
        "executor-result-raw.json",
    )),
    "extension install critical boundary": "SOURCE_EXTENSION_INSTALL_FAILED" in source_manager and "recordExtensionFailure" in source_manager,
    "crash-safe text evidence redacted on disk": "redactEvidenceForDisk" in runtime and "redactHtmlPreservingStructure" in runtime,
    "download diagnostics": all(
        marker in download
        for marker in (
            "DOWNLOAD_JOB_STARTED",
            "DOWNLOAD_ITEM_COMPLETED",
            "DOWNLOAD_JOB_COMPLETED",
            "DOWNLOAD_SOURCE_FAILURE",
            "DOWNLOAD_RUNTIME_ERROR",
        )
    ),
    "audio runtime error marker is not duplicated": audio.count("AUDIO_EXPORT_RUNTIME_ERROR") == 1,
    "audio export diagnostics": all(
        marker in audio
        for marker in (
            "AUDIO_EXPORT_STARTED",
            "AUDIO_EXPORT_SEGMENT_COMPLETED",
            "AUDIO_EXPORT_COMPLETED",
            "AUDIO_EXPORT_CANCELLED",
            "AUDIO_EXPORT_RUNTIME_ERROR",
        )
    ),
    "VietPhrase diagnostics joined to black box": all(
        marker in reader
        for marker in (
            "VIETPHRASE_DIAGNOSTIC_STARTED",
            "VIETPHRASE_DIAGNOSTIC_COMPLETED",
            "VIETPHRASE_DIAGNOSTIC_FAILED",
        )
    ),
}

missing = [name for name, ok in checks.items() if not ok]
if missing:
    raise SystemExit("LUA_DIAGNOSTICS_UI_PARITY missing: " + "; ".join(missing))

print("LUA_DIAGNOSTICS_UI_PARITY=PASS")
