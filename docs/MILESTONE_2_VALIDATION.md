# Validation Cột mốc 2 hoàn chỉnh

## Gate hoàn chỉnh không cần Android SDK

```bash
python3 scripts/check_milestone2_complete.py
```

Gate này kiểm tra:

- 8 SourcePack tích hợp có canonical `FILES.sha256` đầy đủ.
- Chữ ký ECDSA của từng package khớp trust root tương ứng.
- Action/runtime entry tồn tại trong archive.
- 24 fixture replay của sáu nguồn declarative khớp expected JSON.
- Wattpad dùng `VBOOK_JS_COMPAT`, `homecontent` và `genrecontent` đúng action.
- Compiler gate của các module Source Platform.
- Compiler/sandbox gate của Rhino vBook.
- Wiring Android của browser, cookie, trust, UI, diagnostics và các gói bootstrap.

Các gate bổ sung:

```bash
python3 scripts/validate_release.py
python3 scripts/check_kotlin_static.py
python3 scripts/check_android_wiring_static.py
python3 scripts/check_p2_ui_static.py
python3 scripts/check_p3_features.py
python3 scripts/check_p4_features.py
```

## Build Android đầy đủ

Linux/macOS:

```bash
./scripts/build-milestone2.sh
```

Windows PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build-milestone2.ps1
```

Script chạy complete gate trước, sau đó chạy unit test, lint, debug APK và Android-test APK. Có thể thêm:

```text
MILESTONE2_EXTRA_TASKS="connected release"
```

để chạy instrumentation test và tạo release AAB.

## Điều kiện môi trường

- JDK 17.
- Android SDK Platform 36.
- Kết nối tải dependency cho lần Gradle sync đầu.
- Emulator hoặc thiết bị thật cho `connectedDebugAndroidTest`.
