# Đối chiếu P4, XPK gốc và Kotlin 1.0.0

## Phạm vi được yêu cầu

Bản 1.0.0 chỉ triển khai:

- VietPhrase cục bộ.
- Dịch chương bằng AI online.
- Consent và API key mã hóa.
- Phân vai AI.
- Nhạc cảnh.

Roleplay đầy đủ, model offline, Sonic DSP và extension runtime không thuộc phạm vi.

## Ma trận parity

| Hạng mục | XPK gốc | Kotlin 1.0.0 | Đánh giá |
|---|---|---|---|
| VietPhrase chạy local | Có | Có | Tương đương lõi |
| Thêm/bật/tắt/xóa rule | Có | Có | Tương đương |
| Import/export từ điển | Có nhiều file | TSV và `source=target`, bounded | Kotlin đơn giản hơn nhưng dùng được |
| Học rule tự động | Có | Chưa có | Thiếu |
| Chuỗi ChinesePhienAmWords/AIReplace | Có | Chưa có | Thiếu |
| AI online | Có nhiều provider | OpenAI-compatible endpoint | Tương đương một phần |
| Consent trước khi gửi | Không đồng nhất trong Lua | Bắt buộc | Kotlin chặt hơn |
| API key mã hóa | Có secure preferences | AES-GCM + Android Keystore | Kotlin rõ ràng hơn |
| Chặn endpoint nội bộ | Không nổi bật | Có URL policy và public-only DNS | Kotlin tốt hơn |
| Dịch chương | Có | Có | Tương đương lõi |
| Giữ cấu trúc đoạn | Có pipeline riêng | Marker bắt buộc, strict validation | Kotlin chặt hơn |
| Cache bản dịch | Có | Có, gắn source/config fingerprint | Tương đương |
| Dịch lại | Có thể chạy lại | Có nút DỊCH LẠI | Tương đương |
| Phân vai AI | Có | Có theo paragraph assignment | Tương đương lõi |
| Tự gán voice khác nhau | Có học/chọn giọng | Vai mới dùng profile hiện tại, người dùng chỉnh sau | Kotlin còn hạn chế |
| Giữ cấu hình vai người dùng | Có | Có | Tương đương |
| Biểu cảm nhân vật | Có giới hạn biểu cảm | Chưa có | Thiếu |
| Nhạc cảnh | Có nhiều bài và AI cue | Có local track library và paragraph cue | Tương đương lõi |
| Tệp nhạc được gửi lên AI | Không cần | Không | Đạt riêng tư |
| Crossfade | Có pipeline nâng cao | Chưa có | Thiếu |
| Trộn nhạc vào audiobook | Có | Chưa có | Thiếu |
| Scene continuity nhiều chương | Có | Cue riêng từng chương | Thiếu một phần |

## Những điểm Kotlin đã làm tốt hơn

- Không nạp mã AI hoặc extension động.
- Không gửi chương nếu consent hoặc công tắc AI chưa bật.
- API key không nằm trong backup và ô nhập được che.
- Endpoint HTTPS bị kiểm tra cả URL lẫn địa chỉ DNS dùng thật.
- Redirect AI bị chặn.
- Request/response có giới hạn.
- Dịch sai marker bị từ chối thay vì thay thế nội dung mù quáng.
- Assignment và cue cũ bị vô hiệu hóa khi nội dung chương thay đổi.
- Plan AI chỉ là dữ liệu typed; không được thực thi như mã.

## Những phần vẫn khác XPK

- Chưa có Gemini-native adapter riêng, chỉ có giao thức OpenAI-compatible.
- Chưa có AI offline.
- Chưa học VietPhrase từ quyết định của người dùng.
- Chưa có tự nhận diện cảm xúc và tham số biểu cảm.
- Chưa có nhiều engine TTS theo từng nhân vật.
- Chưa crossfade hoặc trộn nhạc vào WAV/M4A.
- Chưa có roleplay/timeline thế giới.

## Mức hoàn thành trong phạm vi P4 đã chốt

| Nhóm | Trạng thái |
|---|---|
| VietPhrase local | Hoàn thành, cần Android build/device test cho import SAF |
| AI consent và key mã hóa | Hoàn thành về code, cần Keystore/provider test thật |
| Dịch chương | Hoàn thành về luồng và cache, cần provider test thật |
| Phân vai AI | Hoàn thành về protocol, persistence, playback và export |
| Nhạc cảnh | Hoàn thành về library, cue và playback local |

Không tuyên bố parity toàn bộ XPK, chỉ parity trong bốn nhóm người dùng yêu cầu.
