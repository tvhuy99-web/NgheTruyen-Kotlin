# Cột mốc 4, trạng thái hoàn thành

Phiên bản: `2.0.0-milestone-4-complete-integrated`
Ngày: 2026-08-05

## Kết luận

Cột mốc 4 đã hoàn thành ở cấp mã nguồn và kiểm định ngoại tuyến, được tích hợp trên nền Cột mốc 5 thay vì quay lùi dự án. Không còn hạng mục chức năng Cột mốc 4 được đánh dấu để trống.

## TTS và hồ sơ vai

- Foreground service, MediaSession, audio focus, wake lock, checkpoint và phục hồi phiên nghe được giữ nguyên.
- Mỗi vai có thể lưu engine package, voice, ngôn ngữ, rate, pitch, volume, biểu cảm, độ mạnh biểu cảm, Sonic speed và Sonic pitch.
- Khi đổi vai, service chuyển engine nếu cần; lỗi khởi tạo engine có fallback thay vì làm dừng truyện.
- Biểu cảm hỗ trợ `NEUTRAL`, `CALM`, `WARM`, `SAD`, `TENSE`, `ANGRY`, `EXCITED` và `WHISPER`.
- Bộ phát hiện biểu cảm tiếng Việt cục bộ dùng từ khóa và dấu câu; kế hoạch AI có thể ghi đè khi được phép.
- Sonic-style PCM16 được áp dụng cho cả playback trực tiếp và audiobook export, có giới hạn kích thước, số kênh và định dạng WAV.

## Nút tai nghe và MediaSession

- Receiver `ACTION_MEDIA_BUTTON` khai báo rõ trong manifest.
- API 31+ dùng `setMediaButtonBroadcastReceiver`; Android cũ dùng explicit PendingIntent.
- Receiver và `onMediaButtonEvent` đi qua cùng bộ khử trùng sự kiện.
- Một lần bấm phát/tạm dừng, hai lần sang đoạn tiếp, ba lần về đoạn trước, nhấn giữ dừng.
- Phím transport chuyên dụng và `ACTION_AUDIO_BECOMING_NOISY` được hỗ trợ.
- Unit soak tạo 20.000 sự kiện để phát hiện lệnh trùng và pending click bị giữ lại.

## AI phân vai và kiểm soát chi phí

- Prefetch kế hoạch theo cửa sổ 1–5 chương.
- Cache/fingerprint tránh gọi lại khi nội dung và cấu hình chưa đổi.
- Quota theo ngày giới hạn số request và số ký tự đầu vào.
- Lưu cục bộ số request, ký tự vào/ra, retry và mã lỗi cuối; không lưu prompt hoặc response.
- Retry giới hạn theo cấp số nhân cho lỗi mạng và HTTP tạm thời, có tôn trọng `Retry-After` số giây.
- Hết quota hoặc AI lỗi sẽ fallback sang phân vai/biểu cảm cục bộ và TTS thường.

## Nhạc cảnh

- Playlist tuần tự, shuffle có seed và smart avoid-repeat.
- Lịch sử phát, số lần phát và order index được lưu trong Room.
- Loudness được ước tính cục bộ từ PCM, sau đó tính target gain có giới hạn. Đây là ước tính thực dụng, không tuyên bố là đo EBU R128 đầy đủ.
- Ducking khi lời đọc phát, crossfade giữa track và continuity xuyên chương được giữ nguyên.
- Track lỗi, URI mất quyền hoặc format không hỗ trợ không làm dừng TTS.

## Dữ liệu, cài đặt và backup

- Room schema 13, migration `12 → 13` không destructive.
- Backup format 10 vẫn đọc được format cũ và bổ sung engine/biểu cảm/Sonic, quota/retry AI, playlist/loudness và lịch sử track.
- Quota AI hằng ngày, API key, cookie, nội dung request/response và checkpoint playback không được đưa vào backup.
- Track khôi phục sang thiết bị khác mặc định tắt cho đến khi người dùng cấp lại quyền URI.

## Kiểm định đã hoàn thành

- Core Kotlin, Android wiring, UI stubs và audio-export static compile.
- Smoke test biểu cảm, Sonic, loudness và selector nhạc.
- SQLite migration `12 → 13` giữ dữ liệu.
- Hồi quy độc lập Cột mốc 1–5.
- 8 SourcePack ký số và 24 fixture vẫn đạt.
- Release validation và workflow YAML đạt.

## Chứng nhận phát hành còn phải chạy

Đây là công việc xác nhận môi trường, không phải chức năng còn thiếu:

- Gradle dependency resolution, Compose compiler, Room KAPT và Android Lint trên SDK Platform 36.
- APK/AAB được ký bằng khóa production.
- Ma trận Android 13, 14 và 15 với tai nghe dây, nhiều mẫu Bluetooth, cuộc gọi, báo thức và chuyển audio route.
- TTS/export nhiều giờ, process death, reboot, pin, MediaCodec và TalkBack trên thiết bị thật.
