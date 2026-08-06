> **Lưu ý về hệ đánh số:** tài liệu này là Mốc 5 lịch sử của release audiobook 1.9.0. Roadmap hiện tại về TTS/tai nghe/phát nền được nghiệm thu trong `MILESTONE_5_PLAYBACK_COMPLETION_REPORT.md` và `ROADMAP_MILESTONE_5_PLAYBACK_*`.

# Cột mốc 5, trạng thái hoàn thành

Phiên bản: `1.9.0-milestone-5-complete`  
Room schema: `12`  
Backup format: `9`

## Phạm vi đã hoàn thành

### Xuất audiobook

- WAV PCM16, AAC-LC/M4A và MP3.
- Toàn bộ truyện đã lưu ngoại tuyến hoặc một khoảng chương do người dùng chọn.
- Một tệp liên tục hoặc mỗi chương một tệp trong thư mục SAF.
- Checkpoint theo từng đoạn TTS trong vùng riêng của ứng dụng.
- Hủy hoặc lỗi vẫn giữ đoạn hợp lệ; thành công mới dọn checkpoint.
- Fingerprint SHA-256 bao phủ nội dung, TTS engine/voice, rate/pitch/volume, vai, từ điển, định dạng, packaging, chapter marker và kế hoạch nhạc.
- Pipeline đọc/ghi theo block, không gom toàn bộ PCM của truyện vào RAM.

### Metadata và chapter marker

- ID3v2.3 gồm tên, tác giả, album và ghi chú nguồn xuất.
- MP3 một tệp có frame `CHAP` cho từng chương và `CTOC` có thứ tự.
- Tên tệp được chuẩn hóa và giới hạn độ dài.
- Ảnh bìa được chủ động loại khỏi phạm vi theo yêu cầu.

### Trộn nhạc cảnh

- Đọc `SceneMusicCueEntity` và track đang bật từ Room.
- Decode URI âm thanh bằng MediaExtractor/MediaCodec.
- Chuẩn hóa sample rate/số kênh sang WAV PCM16 trước khi trộn.
- Loop, gain, fade-in/fade-out và timeline theo cue.
- Track decode bị giới hạn kích thước; track lỗi bị bỏ qua mà không làm mất narration.
- File trung gian được tái sử dụng theo fingerprint và dọn sau khi job thành công.

### Dữ liệu và sao lưu

- Migration `10 → 11 → 12` giữ tiến độ job cũ.
- Schema 12 bổ sung `packaging` và `chapterMarkers`.
- Backup format 9 bổ sung:
  - Chapter transform/cache AI.
  - Voice assignment theo đoạn.
  - Thư viện nhạc cảnh.
  - Cue nhạc cảnh.
- Track khôi phục sang thiết bị khác mặc định `enabled=false`, vì quyền URI Android không thể chuyển qua file backup.
- API key, cookie và thông tin xác thực vẫn không được xuất.

### Chất lượng và phát hành

- Báo cáo chẩn đoán cục bộ: PSS, Java heap, trạng thái pin, thời gian build/search mục lục 10.000 chương.
- Hộp thoại xuất có nhãn rõ cho TalkBack, không dựa vào màu hoặc biểu tượng để truyền đạt lựa chọn.
- Release signing chỉ nhận khóa qua biến môi trường.
- Task `:app:verifyReleaseSigning` chặn phát hành khi thiếu cấu hình ký.
- Build script và GitHub Actions chạy complete gate, unit test, lint, APK, Android-test APK, AAB và migration instrumentation.

## Phần xác nhận ngoài môi trường hiện tại

Các chức năng trên đã hoàn thành ở cấp mã nguồn và gate ngoại tuyến. Trước khi phát hành công khai vẫn phải xác nhận:

1. Gradle dependency resolution, Compose compiler, Room KAPT và Android Lint.
2. MP3 thật bằng `co.ntbl:lame` trên Android.
3. MediaCodec với nhiều track MP3/M4A và nhiều nhà cung cấp SAF.
4. Cancel, process death, reboot và gần hết dung lượng trên thiết bị thật.
5. TalkBack, chữ lớn, contrast và chạy TTS/xuất audiobook nhiều giờ.
6. APK/AAB được ký bằng khóa phát hành thật.
