#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def require(path: str, *tokens: str) -> None:
    file = ROOT / path
    if not file.exists():
        raise SystemExit(f"Missing required Milestone 3 UI file: {path}")
    text = file.read_text(encoding="utf-8")
    missing = [token for token in tokens if token not in text]
    if missing:
        raise SystemExit(f"{path}: missing required Milestone 3 UI wiring: {missing}")


def main() -> None:
    
    
    
    
    
    require(
        "app/src/main/java/vn/nghetruyen/app/ui/screens/ExploreScreen.kt",
        "fun ExploreScreen(",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/ui/screens/LibraryScreen.kt",
        "fun LibraryScreen(",
        "ReadingHistoryEntity",
        "onHistoryClick",
        "onClearReadingHistory",
        "onPrioritizeDownload",
        "ReferenceTabButton",
    )

    print("MILESTONE3_UI_STATIC_WIRING_OK")
    print("MILESTONE3_UI_FULL_ANDROID_COMPILE=DEFERRED_TO_M0_GRADLE")


if __name__ == "__main__":
    main()
