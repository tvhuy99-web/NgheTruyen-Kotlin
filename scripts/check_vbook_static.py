#!/usr/bin/env python3
"""Offline architecture/safety gate for the vBook compatibility runtime.

Compilation belongs to Gradle. This gate intentionally checks stable contracts instead of
maintaining a second, incomplete set of Rhino/JSoup/Kotlin compiler stubs.
"""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def require(text: str, label: str, *tokens: str) -> None:
    for token in tokens:
        assert token in text, f"{label} missing safety/ABI token: {token}"


def main() -> None:
    runtime = read("source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookJsRuntime.kt")
    compatibility_runtime = read("source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookCompatibilityRuntime.kt")
    feature_matrix = read("source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookFeatureMatrix.kt")
    contract_model = read("source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookContractModel.kt")
    corpus_analyzer = read("source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookCorpusAnalyzer.kt")
    sandbox = read("source-js-sandbox/src/main/kotlin/com/nghetruyen/source/sandbox/JsSandbox.kt")
    boundary = read("source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookRhinoValues.kt")
    importer = read("source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookPluginImporter.kt")
    browser = read("app/src/main/java/vn/nghetruyen/app/sourceplatform/AndroidSourceBrowserBroker.kt")
    config_service = read("source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookConfigService.kt")
    config_store = read("app/src/main/java/vn/nghetruyen/app/sourceplatform/AndroidVBookConfigStore.kt")
    secret_store = read("app/src/main/java/vn/nghetruyen/app/sourceplatform/AndroidVBookSecretStore.kt")
    encrypted_storage = read("app/src/main/java/vn/nghetruyen/app/sourceplatform/EncryptedSourceStorageBroker.kt")
    session_cookies = read("app/src/main/java/vn/nghetruyen/app/sourceplatform/VBookSessionCookiePartition.kt")
    backup_rules = read("app/src/main/res/xml/backup_rules.xml")
    extraction_rules = read("app/src/main/res/xml/data_extraction_rules.xml")
    source_models = read("app/src/main/java/vn/nghetruyen/app/sourceplatform/SourcePlatformModels.kt")
    unified_manager = read("app/src/main/java/vn/nghetruyen/app/sourceplatform/UnifiedSourcePlatformManager.kt")
    source_ui = read("app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt")
    config_ui = read("app/src/main/java/vn/nghetruyen/app/ui/screens/SourcePackConfigDialog.kt")
    story_source = read("app/src/main/java/vn/nghetruyen/app/sourceplatform/VBookStorySource.kt")
    reference_capture = read("scripts/capture_vbook_reference.py")
    reference_matrix = read("scripts/capture_vbook_reference_matrix.py")
    compatibility_lab = read("scripts/run_vbook_compat_lab.py")
    differential_coverage = read("scripts/check_vbook_differential_coverage.py")
    provider_plan = read("scripts/vbook-reference-plan-providers.json")

    require(
        sandbox,
        "shared JS sandbox",
        "class SafeRhinoExecutor",
        "setClassShutter(ClassShutter { false })",
        "instructionObserverThreshold",
        "maxHeapGrowthBytes",
        "JsSandboxFailure.MEMORY_LIMIT",
        "removeInteropGlobals(scope)",
    )
    require(
        runtime,
        "vBook runtime",
        "SafeRhinoExecutor",
        "VBookRhinoValues",
        "SourceActionName.COMMENTS -> normalizeComments",
        "private fun normalizeComments",
        ".take(100)",
        'putProperty(scope, "Document"',
        'putProperty(scope, "localCookie"',
        'putProperty(scope, "Script"',
        'putProperty(scope, "Qt"',
        'putProperty(scope, "WebSocket"',
    )
    require(compatibility_runtime, "vBook diagnostic wiring", "diagnostics: DiagnosticSink", "diagnostics,")
    require(
        feature_matrix,
        "vBook feature truth",
        "EXPLICITLY_UNSUPPORTED",
        "VBookFeature.WEBSOCKET,",
        "VBookFeature.COMMENTS,",
        "VBookFeature.SUGGESTIONS,",
        "VBookFeature.CONFIG_UNSUPPORTED_DESCRIPTOR,",
    )
    require(
        contract_model,
        "vBook manifest contract",
        'COMMENT("comment")',
        'SUGGEST("suggest", setOf("suggests"))',
        "VBookConfigFormat.MULTILINE",
    )
    require(
        corpus_analyzer,
        "vBook corpus classification",
        "VBookFeature.CONFIG_UNSUPPORTED_DESCRIPTOR",
        "VBookFeature.COMMENTS",
        "VBookFeature.SUGGESTIONS",
    )
    require(
        boundary,
        "vBook host boundary",
        "No JVM collection wrapper reaches extension code",
        "VBOOK_HOST_VALUE_UNSAFE",
        "context.newArray",
        "context.newObject",
    )
    assert "Context.javaToJS" not in runtime, "vBook runtime must not expose JVM collections through Context.javaToJS"
    require(
        browser,
        "Android browser broker",
        "PublicAddressPolicy.requirePublic",
        "WebStorage.getInstance().deleteAllData()",
        "allowFileAccess = false",
        "allowContentAccess = false",
    )
    require(
        config_service,
        "vBook config/secret split",
        "class VBookCompositeConfigReader",
        "private val secretStore: VBookConfigStore",
        "manifest.config[key]?.sensitive == true",
    )
    require(config_store, "portable vBook config", 'PREFERENCES = "vbook_config_v2"')
    assert "AndroidKeyStore" not in config_store, "portable vBook config must not depend on a device-bound key"
    require(
        secret_store,
        "encrypted vBook secrets",
        'PREFERENCES = "encrypted_vbook_secrets_v1"',
        'KEYSTORE = "AndroidKeyStore"',
        "AES/GCM/NoPadding",
        "cipher.updateAAD(extensionKey.toByteArray(Charsets.UTF_8))",
    )
    require(
        encrypted_storage,
        "encrypted vBook local storage",
        "class EncryptedSourceStorageBroker",
        "AES/GCM/NoPadding",
        "cipher.updateAAD(associatedData(sourceId, key))",
        "Quotas are enforced against plaintext",
    )
    require(session_cookies, "vBook manual login bridge", "class VBookSessionCookiePartition", "syncFromManualLogin", "mirrorDelegate")
    for rules, label in ((backup_rules, "backup rules"), (extraction_rules, "data extraction rules")):
        require(rules, label, 'path="encrypted_vbook_secrets_v1.xml"', 'path="encrypted_vbook_config_v1.xml"')
    require(source_models, "unified source UI model", "val ecosystem: String", "val configFields: List<SourceConfigFieldUi>")
    require(unified_manager, "unified vBook diagnostics", "vBook.diagnosticsSnapshot(sourceId)", "vBook.clearDiagnostics()")
    require(
        source_ui,
        "unified source UI",
        '"VBOOK" to "VBOOK"',
        'text = "KIỂM TRA"',
        'text = "CẤU HÌNH"',
        'text = "ĐĂNG NHẬP"',
        'text = "KHÔI PHỤC PHIÊN BẢN TRƯỚC"',
        'text = "NHẬT KÝ"',
    )
    require(
        config_ui,
        "native vBook config editor",
        "PasswordVisualTransformation()",
        "field.sensitive && value.isBlank()",
        "Giá trị này sẽ được mã hóa",
        'singleLine = field.format != "MULTILINE"',
    )
    require(
        story_source,
        "vBook optional story roles",
        "override suspend fun suggestions",
        "override suspend fun commentsPage",
        "VBookScriptRole.COMMENT",
        "VBookScriptRole.SUGGEST",
    )
    require(reference_capture, "reference capture CLI", '"--plan"', "plan_path = args.plan_option or args.plan")
    require(
        reference_matrix,
        "reference matrix schema",
        '"referenceServer": args.server',
        '"capturedAtEpochMs"',
        '"planSha256": plan_hash.hexdigest()',
    )
    require(compatibility_lab, "complete vBook lab", '"scripts/capture_vbook_reference_matrix.py"', 'cmd.append("--resume")')
    require(differential_coverage, "vBook differential states", 'METADATA_ONLY = "METADATA_ONLY"', "METADATA_ONLY,")
    require(provider_plan, "S11 provider reference coverage", '"CONTENT_COMIC"', '"CONTENT_VIDEO"', '"CONTENT_AUDIO"', '"CONTENT_TTS"', '"CONTENT_TRANSLATE"')
    for forbidden in ("addJavascriptInterface", "ProcessBuilder(", "Class.forName("):
        assert forbidden not in runtime, f"Forbidden vBook bridge token: {forbidden}"

    assert 'if (plugin.scripts.containsKey("homecontent")) "homecontent" else "home"' in importer
    assert 'if (plugin.scripts.containsKey("genrecontent")) "genrecontent" else "genre"' in importer

    wattpad = ROOT / "examples/sourcepacks/wattpad"
    for script in wattpad.glob("src/*.js"):
        text = script.read_text(encoding="utf-8", errors="replace")
        for forbidden in ("importClass", "JavaAdapter", "Packages.", "java.", "javax."):
            assert forbidden not in text, f"{script} uses forbidden Java bridge: {forbidden}"

    print("VBOOK_STATIC_ARCHITECTURE_OK")


if __name__ == "__main__":
    main()
