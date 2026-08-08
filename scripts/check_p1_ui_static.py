#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def require(path: str, *tokens: str) -> None:
    file = ROOT / path
    if not file.exists():
        raise SystemExit(f"Missing required P1 UI file: {path}")
    text = file.read_text(encoding="utf-8")
    missing = [token for token in tokens if token not in text]
    if missing:
        raise SystemExit(f"{path}: missing required P1 UI wiring: {missing}")


def main() -> None:
    # ReaderScreen and StoryDetailScreen are now production Android/Compose surfaces.
    # The old gate tried to compile them against a tiny hand-written fake Android/Compose
    # API, which no longer represents their real dependency graph. Keep this gate focused
    # on P1 wiring; m0_gate.sh performs the authoritative Gradle test/lint/assemble build
    # with the real Android SDK immediately after the static gates.
    require(
        "app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt",
        "fun ReaderScreen(",
        "ReaderPlaybackService",
        "ReferenceActionButton",
        "VietPhraseDiagnosticExporter",
        "onParagraphSelected",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/ui/screens/StoryDetailScreen.kt",
        "fun StoryDetailScreen(",
        "onDownloadUnread",
        "onDownloadRange",
        "onDownloadSelected",
        "onExportAudio",
        "ReferenceVoiceRoleExtras",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/sources/StorySearch.kt",
        "object StorySearch",
        "ReferenceSearchRuntime.groupDuplicates",
        "ReferenceSearchRuntime.accepts",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/sources/ReferenceSearchRuntime.kt",
        "object ReferenceSearchRuntime",
        "fun accepts(sourceId: String)",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/sources/ChapterCatalogIndex.kt",
        "object ChapterCatalogIndex",
    )

    print("P1_UI_STATIC_WIRING_OK")
    print("P1_UI_FULL_ANDROID_COMPILE=DEFERRED_TO_M0_GRADLE")


if __name__ == "__main__":
    main()
