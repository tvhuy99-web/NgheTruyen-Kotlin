# Validation Roadmap Mốc 5 playback/TTS

## Cổng chính

```bash
python3 scripts/check_roadmap_milestone5_playback_complete.py
```

Cổng này kiểm tra:

- recovery hữu hạn và watchdog;
- generation/completion guard;
- media-button mapping;
- sleep deadline;
- speech-chunk checkpoint;
- playback-health stress 100.000 sự kiện;
- cache LRU/checksum/tamper;
- migration SQLite 14→15→16;
- Room queue tối đa năm chương;
- wiring service, settings, backup, UI và manifest.

## Build gate

Linux/macOS:

```bash
./scripts/build-milestone5.sh
```

Windows:

```powershell
.\scripts\build-milestone5.ps1
```

Build gate chạy cổng playback mới trước Gradle. Android build/device checks vẫn phải thực hiện sau Mốc 9 theo kế hoạch nghiệm thu của dự án.
