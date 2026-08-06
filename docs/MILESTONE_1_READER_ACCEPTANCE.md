# Cổng nghiệm thu Mốc 1, lõi đọc truyện

Không được đóng Mốc 1 nếu còn bất kỳ ô bắt buộc nào chưa đạt.

## A. Build và hồi quy

- [ ] `./gradlew clean testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` PASS.
- [ ] APK debug cài và khởi động được.
- [ ] AAB release được tạo từ CI.
- [x] Release validation offline PASS.
- [x] Reader core executable gate PASS.
- [x] P1 UI static compile PASS.
- [x] P1 feature gate PASS.

## B. Khám phá và mở truyện

- [ ] Chọn nguồn và thể loại hoạt động trên thiết bị.
- [ ] Tìm một nguồn và đa nguồn hoạt động.
- [ ] Hủy tìm kiếm không để kết quả cũ ghi đè.
- [ ] Nhập URL truyện trực tiếp hoạt động.
- [ ] Mở truyện liên tiếp trên mạng chậm không hiển thị nhầm truyện.

## C. Mục lục

- [x] Có chỉ mục bất biến và tìm không dấu.
- [x] Có kiểm tra 10.000 chương ở mức JVM.
- [x] Điều hướng không phụ thuộc index liên tục.
- [ ] Tải thêm và tải toàn bộ mục lục được kiểm thử với nguồn thật.
- [ ] Chương đầu, giữa, cuối và mục lục phân trang được kiểm thử trên thiết bị.

## D. Trình đọc

- [x] Chế độ cuộn có cập nhật đoạn hiện tại.
- [x] Chế độ phân trang có điều hướng đoạn.
- [x] Tìm trong chương.
- [x] Sao chép đoạn và toàn chương.
- [x] Theme, cỡ chữ, giãn dòng, lề và khoảng cách đoạn được lưu.
- [x] Chỉ số đoạn không bị thay đổi khi TTS chia đoạn dài.
- [ ] UI test cho cuộn và phân trang PASS.
- [ ] Không crash với chương rỗng, chương cực dài và Unicode phức tạp.

## E. Dữ liệu người đọc

- [x] Lịch sử đọc và đọc tiếp dùng Room.
- [x] Bookmark có khóa duy nhất theo truyện/chương/đoạn.
- [x] Ghi chú có tạo, sửa, xóa và mở lại.
- [x] Quay lại hủy tác vụ tải chương đang treo.
- [ ] Process death giữ đúng chương và đoạn.
- [ ] Reboot giữ đúng chương và đoạn.
- [ ] Nâng cấp database giữ lịch sử, bookmark và ghi chú.

## F. Thiết bị và khả năng tiếp cận

- [ ] Phím âm lượng điều hướng đúng khi bật và trả lại âm lượng hệ thống khi tắt.
- [ ] TalkBack đọc được tiêu đề, nút và nội dung theo thứ tự hợp lý.
- [ ] Cỡ chữ hệ thống lớn không che mất hành động quan trọng.
- [ ] Android 13, 14 và 15 PASS.

## G. Lỗi tồn đọng

- [ ] 0 lỗi P0.
- [ ] 0 lỗi P1.
- [ ] 0 lỗi P2 thuộc phạm vi Mốc 1.
- [ ] Báo cáo nghiệm thu có artifact, hash và commit tương ứng.
