# Mốc 3: VietPhrase đầy đủ và vượt XPK

## Trạng thái

**SOURCE COMPLETE**

Toàn bộ phạm vi có thể hoàn thành và xác minh ở mức mã nguồn đã được đóng. Build Android, APK/AAB, emulator, thiết bị thật và kiểm tra trực tiếp được hoãn đến sau Mốc 9 theo quyết định của chủ dự án.

## 1. Parity với VietPhrase XPK v34

Engine mới đã bám theo contract được trích từ chính gói XPK đã cung cấp:

- revision engine `vp-r9.4-ai-final-replace-20260725`;
- revision parser `vp-import-safe-dat-v1`;
- SHA-256 nguồn VietPhrase XPK `74f8e442dfbbe5dc7120664f86a0c1a2ed7c09b24e042e238e74af351e21faab`;
- đúng bảy lớp từ điển và thứ tự ưu tiên;
- Luật Nhân có capture đánh số;
- Names/Pronouns tham gia capture;
- một nghĩa hoặc nhiều nghĩa;
- chuẩn hóa dấu câu;
- base pass không cascade;
- AIReplace chỉ chạy đúng một lượt cuối.

Golden fixtures kiểm tra trực tiếp các hành vi trên, không chỉ kiểm tra sự tồn tại của class hoặc tên hàm.

## 2. Engine và hiệu năng

- Trie tiền tố thay cho quét tuyến tính toàn bộ từ điển.
- Longest-match và priority selection xác định, không phụ thuộc thứ tự ngẫu nhiên.
- Scope toàn cục và riêng theo truyện.
- Match literal và template.
- Ignore-case có thể cấu hình từng rule.
- Trace chứa vị trí input, rule, lớp từ điển và capture.
- Cache LRU có fingerprint; cache thường không che mất trace chẩn đoán.
- Gate 100.000 quy tắc hoàn thành trong 889 ms ở lượt nghiệm thu cuối, dưới ngân sách 20 giây.

## 3. Import và export

### Text

- UTF-8, UTF-16LE, UTF-16BE.
- Delimiter tab, `=>`, `=` và `||`.
- Phát hiện duplicate và xung đột.

### Binary DIC

- Java modified UTF.
- .NET 7-bit UTF-8.
- U32 big-endian và little-endian.
- Layout paired và grouped.
- Giới hạn record, kích thước chuỗi và trailing payload.

### Compiled DAT

- Double-Array-Trie DAT.
- Kiểm tra node count, base/check index, value count và UTF-8 nghiêm ngặt.

### ZIP

- Path traversal bị từ chối.
- Giới hạn số entry, kích thước entry, archive và dữ liệu giải nén.
- Archive lossless là nguồn chuẩn; TXT chỉ là bản tương thích.
- Xuất rồi nhập lại giữ nguyên rule, kind, scope, story ID, match mode, enabled state và trạng thái bật/tắt bộ từ điển.
- Archive rule-only cũ vẫn được đọc tương thích.

## 4. Persistence và migration

Room được nâng từ schema 13 lên 14 bằng migration không phá dữ liệu:

- rule cũ được giữ và ánh xạ thành `VIET_PHRASE/GLOBAL/LITERAL`;
- thêm kind, scope, story ID, match mode và ignore-case;
- thêm bảng snapshot;
- thêm bảng trạng thái bộ từ điển;
- thêm bảng suggestion;
- migration được chạy thực bằng SQLite trong gate;
- repository và database được biên dịch với Room stubs trong gate riêng.

Khi import:

1. phân tích và tạo preview;
2. audit lỗi/cảnh báo;
3. tạo snapshot toàn bộ rule và dictionary state hiện tại;
4. commit trong transaction;
5. xóa đúng state của các kind bị thay thế, không để scope cũ bị sót;
6. ghi state từ archive nếu có, hoặc tạo state chuẩn từ dữ liệu mới;
7. prune snapshot theo hạn mức.

Rollback kiểm tra checksum, số rule và phục hồi cả rules lẫn dictionary states. Snapshot cũ chỉ có rules không xóa dictionary states hiện tại.

## 5. Giao diện quản lý

- Chọn một trong bảy loại từ điển.
- Chọn GLOBAL hoặc STORY và nhập story ID bắt buộc.
- Cấu hình ignore-case.
- Bật/tắt từng rule.
- Bật/tắt từng bộ từ điển.
- Nhập và xem trước trước khi commit.
- Hiển thị added, changed, removed, duplicate, lỗi và cảnh báo.
- Xác nhận hoặc hủy mà không sửa dữ liệu.
- Danh sách snapshot và rollback.
- Hàng đợi AIReplace suggestion với sửa kết quả, chấp nhận hoặc từ chối.
- Xuất ZIP lossless.

## 6. Backup

Backup format 11 lưu và phục hồi:

- advanced VietPhrase rules;
- snapshots dạng Base64 có checksum;
- dictionary states;
- pending suggestions.

Restore giới hạn độ dài, số lượng, payload và chỉ chấp nhận enum kind/scope/match/status hợp lệ. Rule STORY hoặc dictionary state STORY bắt buộc có story ID.

## 7. Không hồi quy

Không viết lại tìm kiếm, tải truyện, Reader, bình luận hoặc Source Platform. Sau tích hợp M3:

- 14 gate Mốc 0–2 đã được chạy lại và PASS;
- Source Platform foundation/network/repository smoke PASS;
- fixture và chữ ký nguồn PASS;
- Mốc 0–2 được khóa lại bằng 31 SHA-256;
- release validation PASS.

## 8. Gate nghiệm thu Mốc 3

- `ROADMAP_MILESTONE3_VIETPHRASE_COMPLETE_GATE=PASS`
- `ROADMAP_M3_PERSISTENCE_GATE=PASS`
- `P4_VIETPHRASE_TRANSFER_STATIC_COMPILE_OK`
- `P2_UI_STATIC_OK`
- `P4_FEATURE_CHECK_OK`
- `ROADMAP_MILESTONE3_SOURCE_COMPLETE_GATE=PASS`
- `MILESTONE3_FOUNDATION_CHECK_OK`
- `MILESTONE3_UI_STATIC_COMPILE_OK`
- `MILESTONE4_COMPLETE_CHECK_OK`
- `MILESTONES_0_2_SOURCE_EVIDENCE_OK`
- `RELEASE_VALIDATION_OK`

## 9. Phần hoãn đến sau Mốc 9

- Gradle Android dependency resolution và Compose compiler thật.
- Room KSP/KAPT và Android Lint thật.
- APK/AAB.
- Instrumentation, emulator và thiết bị thật.
- Hiệu năng trên điện thoại với bộ từ điển thực tế.
- SAF với các document provider Android thực tế.

Các phần trên là nghiệm thu môi trường chạy, không phải công việc mã nguồn còn thiếu của Mốc 3.

## Kết luận

Mốc 3 đã hoàn tất phía mã nguồn, đạt parity chức năng cốt lõi với VietPhrase XPK và vượt lên ở scope theo truyện, preview/diff, snapshot rollback, trạng thái từ điển, archive lossless, bảo vệ import và gate hiệu năng. Mốc 4 được phép bắt đầu.
