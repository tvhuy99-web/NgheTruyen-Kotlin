# Validation Cột mốc 4 hoàn chỉnh

## Gate ngoại tuyến bắt buộc

```bash
python3 scripts/check_milestone4_complete.py
python3 scripts/check_milestone4_foundation.py
python3 scripts/check_kotlin_static.py
python3 scripts/check_android_wiring_static.py
python3 scripts/check_audio_export_static.py
python3 scripts/check_p1_ui_static.py
python3 scripts/check_p2_ui_static.py
python3 scripts/check_p4_network_static.py
python3 scripts/check_p4_transfer_static.py
python3 scripts/check_milestone1_foundation.py
python3 scripts/check_milestone2_complete.py
python3 scripts/check_milestone3_foundation.py
python3 scripts/check_milestone5_foundation.py
python3 scripts/check_milestone5_complete.py
python3 scripts/validate_release.py
```

## Build Android đầy đủ

Linux/macOS:

```bash
./scripts/build-milestone4.sh
```

Windows PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build-milestone4.ps1
```

Build phải dùng JDK 17, Android SDK Platform 36 và dependency resolution hoạt động. Trước phát hành, chạy unit test, migration test, lint, debug APK, Android-test APK, instrumentation và signed release AAB.

## Ma trận thiết bị bắt buộc

- Android 13, 14 và 15.
- Tai nghe dây USB-C hoặc jack qua adapter.
- Tối thiểu ba thiết bị Bluetooth có media-key behavior khác nhau.
- Màn hình khóa, app bị vuốt khỏi recent, process kill và reboot.
- Cuộc gọi, báo thức, âm thanh điều hướng và chuyển loa/Bluetooth.
- Engine TTS mặc định và ít nhất một engine bên thứ ba.
- TTS liên tục tối thiểu 4 giờ, gồm đổi chương, đổi vai, Sonic và nhạc cảnh.
- TalkBack, font lớn và các điều khiển quota/playlist/voice-role.
