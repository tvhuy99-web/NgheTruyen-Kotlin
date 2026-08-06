#!/usr/bin/env python3
"""Structural Android integration gate for Source Platform 2.

The JVM modules are compiled by check_milestone2_source_platform.py and
check_vbook_static.py. This gate checks Android-only WebView/UI wiring without
pretending an Android SDK is present.
"""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def require(relative: str, tokens: list[str], forbidden: list[str] | None = None) -> None:
    text = (ROOT / relative).read_text(encoding="utf-8")
    for token in tokens:
        assert token in text, f"{relative} missing {token}"
    for token in forbidden or []:
        assert token not in text, f"{relative} contains forbidden token {token}"


def main() -> None:
    require(
        "app/src/main/java/vn/nghetruyen/app/sourceplatform/AndroidSourceBrowserBroker.kt",
        [
            "SourceOriginPolicy.requireInitialUrl",
            "SourceOriginPolicy.requireRedirectUrl",
            "shouldOverrideUrlLoading",
            "shouldInterceptRequest",
            "service-worker-blocked",
            "resource-blocked",
            "blockedResponse()",
            "setAcceptThirdPartyCookies(webView, false)",
            "mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW",
            "allowFileAccess = false",
            "allowContentAccess = false",
            "onRenderProcessGone",
            "removeAllCookies",
            "degradedIsolation = true",
        ],
        ["addJavascriptInterface"],
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/sourceplatform/SourcePlatformManager.kt",
        [
            "AndroidSourceBrowserBroker",
            "PartitionedSourceCookieJar",
            "EncryptedSourceCookiePersistence",
            "OkHttpSourceWebSocketBroker",
            "FileSourceStorageBroker",
            "JcaSourceCryptoBroker",
            "VBookJsRuntime",
            "prepareVBookImport",
            "applyTrustKeyRotation",
            "diagnosticTraces",
            "inspectSelector",
            '"truyenfull.ntsource"',
            '"truyencv.ntsource"',
            '"truyencom.ntsource"',
            '"truyenyy.ntsource"',
            '"wikidich.ntsource"',
            '"sangtacviet.ntsource"',
            '"wattpad.ntsource"',
        ],
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/sourceplatform/SourceTrustRegistry.kt",
        ["fingerprint", "enroll", "revoke", "applyRotation", "SourceTrustRotationVerifier"],
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt",
        [
            "pendingSourceInstallWarnings",
            "sourceTrustKeys",
            "sourceTraces",
            "sourceSelectorInspection",
            "prepareVBookImport",
            "enrollSourceTrustKey",
            "revokeSourceTrustKey",
            "applySourceTrustRotation",
            "inspectSourceSelector",
        ],
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt",
        [
            "THÊM / LÀM MỚI REPOSITORY",
            "GỠ REPOSITORY",
            "TẢI & KIỂM TRA GÓI",
            "NHẬP TỆP XOAY KHÓA ĐÃ KÝ",
            "CÀI .NTSOURCE / VBOOK / LUA API 2",
            "KIỂM TRA SELECTOR",
            "sourceTraces",
            "sourceTrustKeys",
        ],
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/sourceplatform/SourcePlatformTrustRoots.kt",
        ["nghe-truyen-builtin-p256-v1", "nghe-truyen-m2-sources-p256-v1"],
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/sourceplatform/EncryptedSourceCookiePersistence.kt",
        ["AndroidKeyStore", "AES/GCM/NoPadding"],
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/sourceplatform/SourceRepositoryHttpClient.kt",
        ["PublicAddressPolicy", "expectedSha256", "MAX_REDIRECTS"],
    )
    print("SOURCE_PLATFORM_ANDROID_STATIC_OK")


if __name__ == "__main__":
    main()
