# Kiểm toán parity XPK → Kotlin 0.9.0, gói P3 âm thanh nâng cao

Ngày đối chiếu: 2026-08-03

## Phạm vi P3

P3 nâng lõi âm thanh bằng API nền tảng Android. Không sao chép Sonic native library, DEX bridge, Lua runtime hoặc model AI từ XPK.

## 1. Ma trận tổng quan

| Hạng mục | XPK gốc | Kotlin 0.9 | Phần còn lại |
|---|---|---|---|
| TTS nền và MediaSession | Có | Có | Cần device-test nhiều OEM |
| Chọn TTS engine | Có | Có, quét package đã cài | Cần test engine lỗi/mất gói |
| Chọn voice | Có | Có theo engine | Cần test dữ liệu voice thiếu |
| Rate/pitch | Có | Có | Gần tương đương |
| Âm lượng theo profile | Có | Có toàn cục, theo truyện và theo vai | Cần đo khác biệt giữa engine |
| Audio focus | Có | Có pause hoặc platform ducking | Chưa có UI duck level cho TTS |
| Nhạc nền | Có hệ scene music | Có một tệp local lặp + ducking | Chưa có timeline/crossfade/chọn bài theo cảnh |
| Hồ sơ theo truyện | Engine/voice/rate/pitch/volume/Sonic | Engine/voice/rate/pitch/volume | Chưa có Sonic mode/quality |
| Phân vai | AI + cache + profile nhân vật | Vai thủ công theo prefix/alias | Chưa tự nhận diện lời thoại |
| Người kể chuyện | Có | Có narrator fallback | Tương đương lõi |
| Sonic DSP | Có native bridge | Không | Cố ý chưa port vì native/runtime cũ |
| Xuất WAV | Có | Có, chuẩn hóa PCM16 | Cần device-test engine khác nhau |
| Xuất M4A/AAC | Có | Có AAC-LC qua MediaCodec/MediaMuxer | Chưa metadata/chapter marker |
| Xuất MP3 | Có | Chưa có | Cần encoder hợp pháp và test thiết bị |
| Trộn nhạc cảnh vào export | Có | Chưa có | Chưa triển khai mixer |

## 2. Chọn engine và voice

Kotlin 0.9 đã bổ sung:

- Quét danh sách engine TTS bằng Android `TextToSpeech`.
- Đánh dấu engine mặc định hệ thống.
- Khởi tạo voice catalog theo package engine đã chọn.
- Khi engine riêng không khởi tạo được, service thử lại bằng engine mặc định và báo trạng thái.
- Đổi engine làm xóa voice cũ để không giữ một voice thuộc package khác.
- Hồ sơ theo truyện có thể ghi đè engine toàn cục.

Khác XPK:

- Không cài engine hoặc voice package thay người dùng.
- Không giả lập engine bằng DEX/native bridge.
- Chưa có kiểm tra tự động từng voice bằng một đoạn benchmark.

## 3. Âm lượng và audio focus

Kotlin 0.9 lưu âm lượng ở ba lớp:

1. Cấu hình toàn cục.
2. Hồ sơ riêng theo truyện.
3. Vai giọng theo nhân vật.

Âm lượng được truyền vào `TextToSpeech.Engine.KEY_PARAM_VOLUME` khi đọc và được áp dụng như gain PCM khi xuất file. Audio interruption có hai chế độ:

- `PAUSE`: dừng tạm khi mất focus và chỉ tiếp tục nếu trước đó đang phát.
- `CONTINUE_DUCKED`: không chủ động pause khi nhận sự kiện có thể duck, để hệ thống Android điều tiết.

Cần kiểm thử thật vì một số TTS engine có thể xử lý volume hoặc audio focus khác nhau.

## 4. Vai giọng thủ công

Kotlin đã có bảng Room `voice_roles` với:

- Tên vai và danh sách bí danh.
- Voice, language, rate, pitch và volume.
- Vai Người kể chuyện.
- Bật/tắt và xóa từng vai.
- Backup/restore format 5.

Resolver cục bộ nhận các dạng:

- `Tên: lời thoại`
- `Tên - lời thoại`
- `Tên — lời thoại`
- `[Tên] lời thoại`

Alias dài hơn được ưu tiên, chuẩn hóa bỏ dấu và không gửi văn bản ra mạng. Đoạn không có speaker prefix dùng narrator nếu được cấu hình.

Chưa tương đương AI casting của XPK vì chưa có:

- Nhận diện tự động câu thoại không có nhãn.
- Duy trì identity nhân vật bằng AI qua nhiều chương.
- Biểu cảm, giới tính, tuổi hoặc học lựa chọn voice.
- Giao diện sửa timeline từng đoạn.

## 5. Nhạc nền

Kotlin 0.9 hỗ trợ một URI âm thanh local do người dùng chọn:

- Quyền đọc được giữ bằng Storage Access Framework.
- MediaPlayer phát lặp.
- Volume nền có giới hạn.
- Duck factor áp dụng trong khi TTS đọc.
- Pause cùng playback.
- Lỗi tệp không làm crash service và được báo trong notification.

Đây mới là nền tảng background music, chưa phải scene music của XPK. Chưa có catalog nhiều bài, scene boundary, crossfade, AI planner hoặc trộn vào file xuất.

## 6. WAV và M4A

Pipeline mới:

1. TTS tổng hợp từng đoạn.
2. Kiểm tra RIFF/WAVE.
3. Chuẩn hóa PCM unsigned 8-bit, signed 16/24/32-bit hoặc float32 thành PCM16.
4. Áp dụng gain của profile/vai.
5. Ghép WAV PCM16.
6. Với M4A, mã hóa AAC-LC bằng `MediaCodec` và đóng gói MPEG-4 bằng `MediaMuxer`.
7. Chỉ ghi URI đích sau khi tệp tạm hoàn tất.

Giới hạn:

- Chỉ xuất chương đã cache hoặc tải.
- Chưa resume giữa từng segment sau process death.
- Chưa cover, title/author metadata, chapter markers hoặc gapless chapter map.
- Chưa trộn nhạc nền.
- Chưa MP3.

## 7. Dữ liệu và migration

Room schema 5 thêm:

- `story_tts_profiles.volume`
- `story_tts_profiles.enginePackage`
- `audio_export_jobs.outputFormat`
- `audio_export_jobs.mimeType`
- bảng `voice_roles` và index theo truyện/tên vai

Migration `4 → 5` là explicit, không destructive. Backup format 5 lưu settings audio mới, profile mở rộng và voice roles; vẫn đọc format 1–4.

## 8. Điểm Kotlin tốt hơn XPK

- Không chạy Lua, DEX động hoặc native Sonic cũ.
- Voice routing có kết quả xác định và test được.
- PCM normalizer tách riêng khỏi worker.
- AAC/M4A dùng codec nền tảng, không đóng gói binary encoder ngoài.
- URI nhạc nền do người dùng cấp và không tải lên mạng.
- Room migration và backup có version rõ ràng.

## 9. Rủi ro còn phải xác minh

1. `assembleDebug`, Room KAPT, Compose compiler và Lint trên SDK 36.
2. Google TTS, Samsung TTS và engine offline bên thứ ba.
3. Engine tạo WAV extensible (`0xfffe`) hoặc format hiếm.
4. MediaCodec AAC trên thiết bị cũ/OEM.
5. Audio focus khi gọi điện, trợ lý giọng nói và navigation app chen vào.
6. URI nhạc nền sau reboot, đổi app cung cấp tệp hoặc thu hồi quyền.
7. Export dài, hủy, pin yếu, process death và quota WorkManager.

## 10. Đánh giá P3

Kotlin 0.9 đã đạt phần lớn P3 có thể triển khai an toàn bằng Android framework: engine selection, volume, per-story profile, manual multi-role routing, background ducking và M4A/AAC. Khoảng cách lớn còn lại so với XPK là Sonic DSP, AI casting tự động, scene music timeline, audio mixing và metadata/MP3 nâng cao.
