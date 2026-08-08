#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def require(path: str, *tokens: str) -> None:
    file = ROOT / path
    if not file.exists():
        raise SystemExit(f"Missing required P2 UI file: {path}")
    text = file.read_text(encoding="utf-8")
    missing = [token for token in tokens if token not in text]
    if missing:
        raise SystemExit(f"{path}: missing required P2 UI wiring: {missing}")


def main() -> None:
    # PersonalScreen is now the full production Android/Compose settings surface.
    # The previous fake-Compose compiler no longer models its real dependencies
    # (BackHandler, menus, scrolling, reference components, etc.). M0 performs the
    # authoritative Gradle test/lint/assemble compile against the real Android SDK.
    require(
        "app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt",
        "fun PersonalScreen(",
        "onAddVietPhrase",
        "onUpdateVietPhrase",
        "onVietPhraseMasterEnabledChange",
        "onSaveAiSettings",
        "ReferenceActionButton",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/ui/NgheTruyenApp.kt",
        "PersonalScreen(",
    )

    print("P2_UI_STATIC_WIRING_OK")
    print("P2_UI_FULL_ANDROID_COMPILE=DEFERRED_TO_M0_GRADLE")


if __name__ == "__main__":
    main()
