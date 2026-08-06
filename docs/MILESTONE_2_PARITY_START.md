# Mốc 2 Parity: Nguồn truyện, bình luận và extension

## Trạng thái

**IN PROGRESS**

Mốc 2 được mở theo chỉ đạo của chủ dự án trong khi Mốc 0 và Mốc 1 vẫn còn nợ nghiệm thu Android thực tế. Không được dùng tài liệu này để tuyên bố Mốc 2 đã hoàn tất.

## Lát cắt đã triển khai: bình luận native

- Thêm model `StoryComment` và danh sách bình luận trong `StoryDetail`.
- Mở rộng `StorySource` bằng capability `supportsComments` và action `comments(url)`.
- SourcePack tự công bố khả năng bình luận khi manifest có action `COMMENTS`.
- Hỗ trợ bình luận nhúng trong kết quả `DETAIL` và kết quả riêng từ action `COMMENTS`.
- Chuẩn hóa các tên trường phổ biến:
  - `user`, `name`, `author`;
  - `time`, `date`;
  - `text`, `content`, `description`.
- Giới hạn tối đa 100 bình luận, 20.000 ký tự nội dung và 200 ký tự metadata.
- Loại ký tự điều khiển, thu gọn khoảng trắng và số dòng trống dư thừa.
- Thêm tab **BÌNH LUẬN** native chỉ khi nguồn thực sự hỗ trợ hoặc đã trả dữ liệu.
- Có tải lười, tải lại khi nguồn hỗ trợ action động, trạng thái rỗng và thông báo lỗi.
- Bình luận nhúng vẫn hiển thị nhưng không tạo nút làm mới giả khi nguồn không có action `COMMENTS`.
- Hủy job bình luận khi chuyển truyện hoặc quay lại.
- Kết quả từ truyện cũ không được phép ghi đè truyện mới.
- Vẫn giữ nút mở trang bình luận gốc khi nguồn cung cấp URL.

## vBook compatibility

Runtime vBook hiện chuẩn hóa action `COMMENTS` về contract thống nhất và giữ bình luận thực được nhúng trong `DETAIL`. Descriptor script không có nội dung bình luận sẽ bị bỏ qua, không bị hiển thị như bình luận giả.

## Gate mới

```bash
python3 scripts/check_milestone2_comments.py
```

Gate kiểm tra cả wiring tĩnh và chạy Kotlin thật cho parser bình luận.

Gate Mốc 2 hiện gọi gate bình luận này trước khi xác nhận package, chữ ký và fixture.

## Kết quả xác minh offline

- `check_milestone2_comments.py`: PASS.
- `check_p1_ui_static.py`: PASS.
- `check_p2_sources.py`: PASS trước thay đổi vBook, contract nguồn không hồi quy.
- `check_source_platform_android_static.py`: PASS sau thay đổi parser.
- `check_vbook_static.py`: PASS sau chuẩn hóa bình luận.
- `check_milestone2_complete.py`: PASS sau tích hợp gate bình luận.
- `validate_release.py`: PASS sau cập nhật tiêu chí release từ “không có tab” sang wiring bình luận native.

`check_kotlin_static.py` không hoàn tất trong giới hạn thời gian của môi trường hiện tại. Không xem đây là PASS.

## Phần còn lại của Mốc 2

1. Fixture bình luận cho từng nguồn được tuyên bố hỗ trợ.
2. Kiểm tra website trực tiếp cho 7 nguồn thật.
3. Phân trang bình luận nếu nguồn có nhiều trang.
4. Làm mới độc lập và cache có thời hạn.
5. Port hoặc adapter cho extension XPK cũ.
6. Ma trận compatibility cho từng vBook thực tế.
7. Renderer recovery, cookie bridge và login trên thiết bị Android thật.
8. Build APK/AAB, instrumentation test và test thiết bị.

## Điều kiện đóng Mốc 2

Mốc 2 chỉ được chuyển sang `PASS` khi tất cả nguồn được cam kết đều có fixture và test trực tiếp, bình luận native hoạt động trên nguồn hỗ trợ, extension lỗi bị cô lập, package giả mạo bị từ chối và build Android thật vượt gate.
