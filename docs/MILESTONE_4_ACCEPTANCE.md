# Biên bản nghiệm thu Cột mốc 4

Phiên bản: `2.0.0-milestone-4-complete-integrated`
Ngày nghiệm thu ngoại tuyến: 2026-08-05

## Phạm vi nghiệm thu

Cột mốc 4 bao gồm TTS nền, nút tai nghe Android 13+, engine/voice theo vai, biểu cảm, Sonic DSP, AI phân vai có quota/retry và nhạc cảnh có loudness/playlist/crossfade/continuity.

## Tiêu chí và kết quả

| Tiêu chí | Kết quả |
|---|---|
| Foreground TTS, MediaSession, audio focus, wake lock và checkpoint | Đạt |
| Media-button receiver, callback, dedupe và gesture mapping | Đạt |
| Engine, voice và thông số riêng theo vai | Đạt |
| Biểu cảm cục bộ và AI override | Đạt |
| Sonic speed/pitch cho playback và export | Đạt |
| Prefetch AI nhiều chương, cache và fallback | Đạt |
| Quota, usage counter, retry/backoff và Retry-After | Đạt |
| Playlist, tránh lặp và lịch sử phát | Đạt |
| Loudness estimate, target gain, ducking, crossfade, continuity | Đạt |
| Migration Room `12 → 13` giữ dữ liệu | Đạt |
| Backup format 10 và loại dữ liệu nhạy cảm | Đạt |
| Hồi quy Cột mốc 1–5 | Đạt |
| 8 SourcePack / 24 fixture | Đạt |
| APK/AAB và chứng nhận thiết bị thật | Chưa chạy trong môi trường hiện tại |

## Quyết định

Cột mốc 4 được xem là **hoàn thành ở cấp triển khai và validation ngoại tuyến**. Trước phát hành công khai, release checklist vẫn bắt buộc build Android đầy đủ và ma trận thiết bị thật. Không được diễn giải trạng thái nghiệm thu này thành bằng chứng rằng mọi mẫu tai nghe Bluetooth hoặc engine TTS của nhà sản xuất đã được kiểm tra vật lý.
