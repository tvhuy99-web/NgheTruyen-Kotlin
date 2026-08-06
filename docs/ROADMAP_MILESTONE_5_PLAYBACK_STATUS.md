# Roadmap Mốc 5: trạng thái playback/TTS

Trạng thái phía mã nguồn: **COMPLETE**  
Phiên bản: `2.1.0-milestone-5-playback-complete`  
Room schema: `16`  
Backup format: `12`

## Đã khóa

- Foreground TTS recovery hữu hạn.
- Audio focus và resume intent.
- Watchdog init/chunk.
- Engine/voice fallback.
- Sonic fallback.
- Sleep timer bền vững.
- Media mapping tùy chỉnh.
- TTS/Sonic cache checksum + LRU.
- PCM volume normalization.
- Speech chunk checkpoint.
- Room playback queue tối đa năm chương.
- Bounded health diagnostics.

## Không thuộc source acceptance hiện tại

Android build, APK/AAB, emulator, thiết bị thật, Bluetooth, cuộc gọi, OEM TTS, pin, soak nhiều giờ và TalkBack được hoãn đến sau Mốc 9 theo quyết định của chủ dự án.

## Phân biệt với tài liệu Mốc 5 lịch sử

Các tệp `docs/MILESTONE_5_*` cũ mô tả release audiobook `1.9.0`. Roadmap hiện tại dùng tài liệu có tên `ROADMAP_MILESTONE_5_PLAYBACK_*` và báo cáo `MILESTONE_5_PLAYBACK_COMPLETION_REPORT.md` để tránh trộn hai hệ đánh số.
