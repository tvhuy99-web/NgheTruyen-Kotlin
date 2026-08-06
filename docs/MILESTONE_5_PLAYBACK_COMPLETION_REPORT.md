# Báo cáo hoàn tất Mốc 5: TTS, tai nghe và phát nền

Ngày nghiệm thu phía mã nguồn: 2026-08-06  
Phiên bản: `2.1.0-milestone-5-playback-complete`  
Room schema: `16`  
Backup format: `12`

## Kết luận

Mốc 5 được hoàn tất ở cấp mã nguồn và kiểm thử ngoại tuyến. Các kiểm tra phụ thuộc Android SDK, APK/AAB, emulator, thiết bị thật, Bluetooth, cuộc gọi và TTS engine thực tế được chủ dự án chủ động hoãn đến sau Mốc 9.

Việc hoàn tất không dựa vào việc viết lại service. Các thay đổi được giới hạn ở các lớp policy, cache, persistence và wiring có gate riêng; Reader, tìm kiếm, nguồn truyện và VietPhrase chỉ được sửa khi gate chỉ ra lỗi cụ thể.

## Phạm vi hoàn tất

### Recovery và audio focus

- Recovery hữu hạn theo thứ tự: bỏ Sonic, thử lại engine hiện tại một lần, chuyển về engine hệ thống, dừng an toàn.
- Watchdog khởi tạo TTS 12 giây và watchdog speech chunk 15–240 giây.
- Generation token ngăn callback của engine cũ đè trạng thái engine mới.
- Completion guard ngăn callback trùng hoàn tất cùng một speech chunk.
- Lỗi submit không còn ghi checkpoint đang phát hoặc giữ wake lock/audio focus.
- Mất audio focus tạm thời giữ ý định tiếp tục; mất vĩnh viễn mới ghi trạng thái dừng.

### Sleep timer

- Lưu deadline tuyệt đối, không chỉ lưu số phút còn lại.
- Khôi phục sau service recreation.
- Receiver phục hồi alarm sau reboot và package replacement.
- AlarmManager dùng `setAndAllowWhileIdle`.
- Deadline được clamp tối đa 180 phút và hết hạn theo phép so sánh xác định.

### Tai nghe và media key

- Cho phép cấu hình một, hai, ba lần bấm và nhấn giữ.
- Mapping được giới hạn vào tập lệnh hợp lệ.
- Dedicated transport keys giữ hành vi chuẩn hệ thống.
- Ngoài Reader, phím âm lượng và media event không bị chiếm sai ngữ cảnh.

### Cache và âm lượng

- Cache TTS/Sonic LRU, atomic commit và kiểm tra SHA-256.
- Khóa cache bao phủ text fingerprint, engine, voice, language, rate, pitch, volume, Sonic và revision phát âm.
- Giới hạn cấu hình 8–512 MiB.
- Tệp bị sửa hoặc checksum sai bị loại bỏ.
- Chuẩn hóa âm lượng được thực hiện trên PCM, có gain clamp để tránh khuếch đại vô hạn.

### Fallback engine và voice

- Engine tùy chọn bị gỡ hoặc khởi tạo lỗi sẽ thử engine hệ thống.
- Voice không còn tồn tại sẽ chọn voice cùng ngôn ngữ phù hợp nhất.
- Chẩn đoán hiển thị mã lỗi có giới hạn, không ghi nội dung chương hoặc credential.

### Checkpoint và hàng đợi nhiều chương

- Checkpoint lưu `speechChunkIndex`, URL chương trước/sau, deadline sleep timer và session ID.
- Speech chunk phục hồi chỉ áp dụng khi cùng paragraph index, không làm trôi bookmark/ghi chú/vị trí đọc.
- Room lưu chương hiện tại và tối đa bốn chương kế tiếp đã cache.
- Hàng đợi được dựng lại sau process recreation và làm mới sau prefetch.
- Hàng đợi tạm thời không được đưa vào backup portable.

### Stress và khả năng chẩn đoán

- Playback health ledger có giới hạn 128 sự kiện.
- Gate chạy 100.000 sự kiện start/done/fail mà kích thước ledger vẫn bị chặn.
- Không lưu chapter text trong health snapshot.
- Runtime gate kiểm tra cache LRU, tamper, watchdog, recovery, media mapping và sleep deadline.

## Migration và backup

### Migration 14 → 15

Bổ sung vào `playback_checkpoint`:

- `speechChunkIndex`
- `nextChapterUrl`
- `previousChapterUrl`
- `sleepTimerEndsAtMillis`
- `sessionId`

### Migration 15 → 16

Tạo `playback_queue_chapters` và index truy vấn theo truyện/chương.

### Backup format 12

Sao lưu thiết lập:

- mapping media button;
- bật/tắt và giới hạn TTS cache;
- bật/tắt normalization;
- target LUFS.

Không sao lưu cache audio, playback queue, wake-lock state hoặc deadline phiên đang chạy.

## Lỗi được phát hiện trong quá trình nghiệm thu

1. Gate migration Mốc 1 nuốt nhầm migration mới do regex không dừng trước `val MIGRATION_*`; đã sửa gate, không thay đổi chức năng ứng dụng.
2. Audio-focus pause tạm thời từng ghi nhầm trạng thái người dùng đã dừng; đã sửa checkpoint intent.
3. Safe-stop tham chiếu biến không tồn tại; đã sửa đúng nhánh lỗi.
4. Callback engine cũ có thể tới muộn; đã khóa bằng generation token.
5. Cache mới thiếu hằng buffer khi biên dịch sớm; đã sửa trước khi nối service.
6. UI static stub thiếu trường cài đặt Mốc 5; đã cập nhật stub thay vì sửa UI ổn định.
7. `stopPlayback()` có cleanup lặp; đã loại phần trùng, giữ nguyên hành vi.

## Điều đã hoãn

- Gradle Android, Compose compiler, Room KSP/KAPT và Android Lint.
- APK/AAB, instrumentation và emulator.
- Android 13–15 trên thiết bị thật.
- Tai nghe dây/Bluetooth, cuộc gọi, alarm, route switching.
- Nhiều TTS engine/voice của nhà sản xuất.
- Soak nhiều giờ, pin, nhiệt, memory profiler và TalkBack thực tế.

Các mục trên là chứng nhận runtime, không phải đầu việc mã nguồn còn thiếu của Mốc 5.

## Kết quả khóa nghiệm thu

```text
M5_PLAYBACK_SOURCE_STRUCTURE_OK
M5_PLAYBACK_PURE_RUNTIME_OK events=128 cache=4194304
M5_MIGRATION_14_16_SQLITE_OK statements=7
ROADMAP_MILESTONE5_PLAYBACK_COMPLETE_GATE=PASS
MILESTONES_0_2_SOURCE_EVIDENCE_OK files=31 gates=14
ROADMAP_M3_VIETPHRASE_COMPLETE_EVIDENCE_OK files=45 gates=11
ROADMAP_M5_PLAYBACK_COMPLETE_EVIDENCE_OK files=33 gates=28
RELEASE_VALIDATION_OK
```

Source Platform cũng được biên dịch theo module và chạy smoke test chữ ký, fixture, origin policy, public-address policy và repository tamper:

```text
SOURCE_PLATFORM_FOUNDATION_SMOKE_OK events=20
SOURCE_PLATFORM_FOUNDATION_CHECK_OK
MILESTONE2_SOURCE_PLATFORM_SMOKE_OK
MILESTONE2_SOURCE_PLATFORM_CHECK_OK
```

## Provenance của gói bàn giao

Gói này được xây trên archive nguồn mới nhất **thực sự có mặt trong workspace của phiên**, là `NgheTruyen_Kotlin_M3_VietPhrase_SourceComplete_v2.0.0.zip`. Artifact `M4_AI_SourceComplete` từng được nhắc trong hội thoại không có tệp vật lý trong workspace để tích hợp hoặc xác minh lại. Vì vậy:

- Báo cáo này chứng nhận **phạm vi Mốc 5 playback/TTS** trên cây nguồn hiện có.
- Không tuyên bố gói ZIP này chứa các thay đổi của một artifact Mốc 4 riêng nhưng không thể truy xuất.
- Những chức năng AI vốn đã tồn tại trong cây nền vẫn được giữ và các gate P4/Mốc 4 lịch sử vẫn PASS.
- Khi artifact Mốc 4 riêng được cung cấp lại, cần merge có kiểm soát và chạy lại toàn bộ manifest trước khi gọi gói là chuỗi tích hợp M0→M5 tuyệt đối.
