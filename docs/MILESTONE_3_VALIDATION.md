# Validation Cột mốc 3 hoàn chỉnh

## Gate không cần Android SDK

```bash
python3 scripts/validate_release.py
python3 scripts/check_milestone3_foundation.py
python3 scripts/check_milestone3_ui_static.py
python3 scripts/check_milestone3_download_static.py
python3 scripts/check_milestone3_kindle.py
python3 scripts/check_milestone2_complete.py
```

Các gate xác minh:

- Search rank, typo tolerance, dedupe và chỉ mục 10.000 chương.
- Reader cuộn/phân trang, tự lưu vị trí, ghi chú và phím âm lượng.
- Download request bền vững, storage guard và retry theo chương.
- PalmDOC, KF8-only, HUFF/CDIC và từ chối DRM bằng Kotlin chạy thật.
- Migration SQL `7 → 8 → 9` và dữ liệu mẫu.
- Hồi quy Cột mốc 1, Cột mốc 2, P1–P4 và Source Platform.

## Build Android đầy đủ

Linux/macOS:

```bash
./scripts/build-milestone3.sh
```

Windows PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build-milestone3.ps1
```

Đặt `MILESTONE3_EXTRA_TASKS="connected release"` để chạy instrumentation và tạo AAB release.

## Điều kiện môi trường

- JDK 17.
- Android SDK Platform 36 và Build Tools 36.0.0.
- Dependency Gradle/Android cho lần sync đầu.
- Emulator hoặc thiết bị thật cho `connectedDebugAndroidTest`.
