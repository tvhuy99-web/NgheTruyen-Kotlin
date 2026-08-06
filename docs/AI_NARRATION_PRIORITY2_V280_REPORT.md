# Báo cáo hoàn tất Priority 2, điều phối AI và hồ sơ giọng v2.8.0

Phiên bản: **`2.8.0-ai-narration-priority2-complete`**  
Version code: **28**  
Room schema: **18**  
Backup format: **15**

## 1. AI bắt buộc hoàn tất trước playback

`PlaybackQueueStore` có trạng thái `READY`, `PREPARING`, `FAILED`. Khi hồ sơ truyện bật auto-translation, nội dung gốc được giữ để hiển thị nhưng playback bị khóa. Nút phát, MediaSession và phím tai nghe chỉ ghi nhận yêu cầu chờ. Sau khi dịch thành công, queue được thay bằng bản dịch và yêu cầu phát đang chờ được tiếp tục. Nếu AI lỗi, playback không tự rơi về bản gốc; người dùng phải thử lại hoặc chọn **BẢN GỐC**. Chọn bản gốc trong lúc đang dịch sẽ hủy tác vụ đang chạy.

## 2. Kế hoạch kể chuyện thống nhất

`NarrationPlanCoordinator` gọi một yêu cầu AI khi cần đồng thời phân vai và nhạc cảnh. Payload gồm:

- nội dung chương hiện tại theo chỉ số đoạn;
- các vai đã lưu và bí danh;
- giới hạn biểu cảm theo truyện;
- danh mục track hợp lệ;
- sáu đoạn cuối chương trước;
- cue và cảm xúc cuối chương trước;
- track đang phát xuyên chương.

Phản hồi dùng một giao thức gồm `ROLE`, `ASSIGN`, `CUE`, sau đó được kiểm tra, giới hạn và lưu nguyên tử theo từng nhóm dữ liệu. Cơ chế được dùng cho thao tác thủ công, playback tự động và prefetch chương kế tiếp.

## 3. Hồ sơ giọng đầy đủ

Mỗi vai có thể chỉnh:

- tên, bí danh và cờ người kể chuyện;
- TTS engine, voice và ngôn ngữ;
- rate, pitch và volume;
- expression và expression strength;
- Sonic speed và Sonic pitch;
- trạng thái bật/tắt.

Mỗi hồ sơ có nút nghe thử. Preview áp dụng engine, voice, ngôn ngữ, tốc độ, cao độ, âm lượng và biểu cảm của vai. Sonic tiếp tục được áp dụng trong playback/export qua pipeline PCM hiện có. Khi đổi tên vai, bản ghi cũ được dọn để không tạo profile trùng.

## 4. Tương thích dữ liệu

Không thêm bảng hoặc cột nên Room giữ schema 18. Backup format vẫn là 15 và các hồ sơ vai hiện hữu tiếp tục được đọc nguyên trạng.

## 5. Kiểm tra

- `PRIORITY2_COMPLETE_OK`
- `PRIORITY2_COORDINATOR_STATIC_COMPILE_OK`
- `PRIORITY2_COMBINED_PROTOCOL_OK`
- `P4_NETWORK_STATIC_COMPILE_OK`
- `P1_UI_STATIC_COMPILE_OK`
- `KOTLIN_STATIC_COMPILE_OK`
- `AI_SETTINGS_STATIC_COMPILE_OK`
- `AUDIO_EXPORT_STATIC_COMPILE_OK`
- `ANDROID_WIRING_DATABASE_OK`
- `ANDROID_WIRING_FOLLOWING_OK`
- `ANDROID_WIRING_VOICE_OK`
- `PRIORITY1_COMPLETE_OK`
- `MILESTONE2_COMPLETE_CHECK_OK`
- `MILESTONE4_COMPLETE_CHECK_OK`
- `ROADMAP_MILESTONE5_PLAYBACK_COMPLETE_GATE=PASS`
- `SOURCE_PLATFORM_ANDROID_STATIC_OK`
- `VBOOK_STATIC_COMPILE_OK`

Chưa chứng nhận full Gradle/AGP build, APK/AAB hoặc thiết bị thật do gói nguồn vẫn thiếu `gradle-wrapper.jar` và môi trường hiện tại không có dependency cache/Android SDK Platform 36.
