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
    sandbox = read("source-js-sandbox/src/main/kotlin/com/nghetruyen/source/sandbox/JsSandbox.kt")
    boundary = read("source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookRhinoValues.kt")
    importer = read("source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookPluginImporter.kt")

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
    require(
        boundary,
        "vBook host boundary",
        "No JVM collection wrapper reaches extension code",
        "VBOOK_HOST_VALUE_UNSAFE",
        "context.newArray",
        "context.newObject",
    )
    assert "Context.javaToJS" not in runtime, "vBook runtime must not expose JVM collections through Context.javaToJS"
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
