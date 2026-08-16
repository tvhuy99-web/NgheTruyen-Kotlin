#!/usr/bin/env python3
"""Milestone 0 preflight: verify the machine can perform a real Android build."""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
from dataclasses import asdict, dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


@dataclass
class Check:
    id: str
    status: str
    detail: str
    remediation: str = ""


def run(*args: str) -> tuple[int, str]:
    try:
        p = subprocess.run(args, cwd=ROOT, text=True, stdout=subprocess.PIPE,
                           stderr=subprocess.STDOUT, timeout=20, check=False)
        return p.returncode, p.stdout.strip()
    except Exception as exc:  
        return 99, f"{type(exc).__name__}: {exc}"


def java_major() -> tuple[int | None, str]:
    code, out = run("java", "-version")
    if code != 0:
        return None, out
    match = re.search(r'version "(\d+)', out)
    return (int(match.group(1)) if match else None), out.splitlines()[0] if out else ""


def sdk_root() -> Path | None:
    for key in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        value = os.environ.get(key)
        if value and Path(value).is_dir():
            return Path(value)
    local = ROOT / "local.properties"
    if local.is_file():
        for line in local.read_text(encoding="utf-8", errors="replace").splitlines():
            if line.startswith("sdk.dir="):
                value = line.split("=", 1)[1].replace("\\:", ":").replace("\\\\", "\\")
                path = Path(value)
                if path.is_dir():
                    return path
    return None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--json", dest="json_path", type=Path)
    parser.add_argument("--markdown", dest="md_path", type=Path)
    parser.add_argument("--strict", action="store_true", help="return non-zero when a required check is blocked")
    args = parser.parse_args()

    checks: list[Check] = []
    required_files = [
        "settings.gradle.kts", "build.gradle.kts", "gradle.properties",
        "gradlew", "gradle/wrapper/gradle-wrapper.properties", "app/build.gradle.kts",
        "app/src/main/AndroidManifest.xml",
    ]
    missing = [p for p in required_files if not (ROOT / p).is_file()]
    checks.append(Check("project-layout", "PASS" if not missing else "BLOCKED",
                        "Đủ tệp build bắt buộc." if not missing else f"Thiếu: {', '.join(missing)}",
                        "Khôi phục các tệp build từ nguồn kiểm soát phiên bản."))

    major, description = java_major()
    java_ok = major == 17
    checks.append(Check("jdk-17", "PASS" if java_ok else "BLOCKED",
                        description or "Không tìm thấy Java.",
                        "Cài Temurin/OpenJDK 17 và đặt JAVA_HOME trỏ đến JDK 17."))

    wrapper = ROOT / "gradle/wrapper/gradle-wrapper.jar"
    downloader = ROOT / "gradle/wrapper/WrapperDownloader.java"
    if wrapper.is_file():
        wrapper_status, wrapper_detail = "PASS", "Có gradle-wrapper.jar cục bộ."
    elif downloader.is_file():
        wrapper_status, wrapper_detail = "WARN", "Chưa có wrapper JAR; gradlew sẽ tải và kiểm SHA-256 khi có mạng."
    else:
        wrapper_status, wrapper_detail = "BLOCKED", "Không có wrapper JAR hoặc bootstrap downloader."
    checks.append(Check("gradle-wrapper", wrapper_status, wrapper_detail,
                        "Chạy ./gradlew --version trên máy có Internet hoặc bổ sung wrapper JAR đã xác minh."))

    sdk = sdk_root()
    if sdk is None:
        checks.append(Check("android-sdk", "BLOCKED", "Không tìm thấy Android SDK.",
                            "Đặt ANDROID_SDK_ROOT hoặc sdk.dir trong local.properties."))
    else:
        checks.append(Check("android-sdk", "PASS", str(sdk)))
        packages = {
            "platform-36": sdk / "platforms/android-36/android.jar",
            "build-tools-36": sdk / "build-tools/36.0.0/aapt2",
            "platform-tools": sdk / "platform-tools/adb",
        }
        for cid, path in packages.items():
            checks.append(Check(cid, "PASS" if path.exists() else "BLOCKED", str(path),
                                'sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"'))

    checks.append(Check("python3", "PASS" if shutil.which("python3") else "BLOCKED",
                        shutil.which("python3") or "Không tìm thấy python3.", "Cài Python 3.10+."))

    test_files = list(ROOT.glob("**/src/test/**/*.kt")) + list(ROOT.glob("**/src/androidTest/**/*.kt"))
    checks.append(Check("test-inventory", "PASS" if test_files else "BLOCKED",
                        f"Phát hiện {len(test_files)} tệp test Kotlin.", "Bổ sung unit/instrumentation tests."))

    payload = {
        "milestone": 0,
        "project": ROOT.name,
        "overall": "READY" if all(c.status in {"PASS", "WARN"} for c in checks) else "BLOCKED",
        "checks": [asdict(c) for c in checks],
    }

    if args.json_path:
        args.json_path.parent.mkdir(parents=True, exist_ok=True)
        args.json_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if args.md_path:
        args.md_path.parent.mkdir(parents=True, exist_ok=True)
        rows = ["| Kiểm tra | Trạng thái | Chi tiết |", "|---|---:|---|"]
        for c in checks:
            rows.append(f"| `{c.id}` | **{c.status}** | {c.detail.replace('|', '\\|')} |")
        args.md_path.write_text(
            "# Mốc 0: Báo cáo preflight\n\n"
            f"**Trạng thái tổng:** `{payload['overall']}`\n\n" + "\n".join(rows) + "\n",
            encoding="utf-8",
        )

    for c in checks:
        print(f"{c.status:7} {c.id:22} {c.detail}")
    print(f"M0_PREFLIGHT={payload['overall']}")
    return 2 if args.strict and payload["overall"] == "BLOCKED" else 0


if __name__ == "__main__":
    raise SystemExit(main())
