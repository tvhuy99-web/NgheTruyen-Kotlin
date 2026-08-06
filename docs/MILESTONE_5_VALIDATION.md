> **Lưu ý về hệ đánh số:** tài liệu này là Mốc 5 lịch sử của release audiobook 1.9.0. Roadmap hiện tại về TTS/tai nghe/phát nền được nghiệm thu trong `MILESTONE_5_PLAYBACK_COMPLETION_REPORT.md` và `ROADMAP_MILESTONE_5_PLAYBACK_*`.

# Validation Cột mốc 5 hoàn chỉnh

## Gate ngoại tuyến

```bash
python3 scripts/validate_release.py
python3 scripts/check_milestone5_foundation.py
python3 scripts/check_audio_export_static.py
python3 scripts/check_p1_ui_static.py
python3 scripts/check_p2_ui_static.py
python3 scripts/check_p4_transfer_static.py
```

Complete gate kiểm tra:

- versionCode 19, versionName `1.9.0-milestone-5-complete`;
- migration SQLite `10 → 11` và `11 → 12`;
- mixer PCM16, ID3 và chapter marker;
- API Java của LAME;
- MP3, range, split-per-chapter, scene music, checkpoint và resume;
- backup format 9 và dữ liệu AI/nhạc cảnh;
- release signing task và wiring CI.

## Build Android đầy đủ

Linux/macOS:

```bash
./scripts/build-milestone5.sh
```

Windows PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build-milestone5.ps1
```

Mặc định script chạy release gate, unit test, lint, debug APK và Android-test APK.

Instrumentation:

```bash
MILESTONE5_EXTRA_TASKS="connected" ./scripts/build-milestone5.sh
```

Release có ký:

```bash
export NGHETRUYEN_RELEASE_STORE_FILE=/secure/path/release.jks
export NGHETRUYEN_RELEASE_STORE_PASSWORD='...'
export NGHETRUYEN_RELEASE_KEY_ALIAS='...'
export NGHETRUYEN_RELEASE_KEY_PASSWORD='...'
MILESTONE5_EXTRA_TASKS="release" ./scripts/build-milestone5.sh
```

Luồng release gọi `:app:verifyReleaseSigning` trước `bundleRelease`.

## Ma trận thiết bị bắt buộc

- Android 10, 12, 13, 14 và 15.
- Một thiết bị RAM thấp và một thiết bị Samsung/Xiaomi hoặc tương đương có quản lý nền mạnh.
- Bộ nhớ trong, thẻ SD nếu có, và ít nhất một DocumentsProvider đám mây.
- WAV/M4A/MP3, một tệp và mỗi chương một tệp.
- Nhạc WAV/MP3/M4A với sample rate và số kênh khác narration.
- Cancel, process death, reboot, gần hết dung lượng và quyền URI bị thu hồi.
- TalkBack, font 200%, dark/light mode.
- TTS và xuất liên tục nhiều giờ.
