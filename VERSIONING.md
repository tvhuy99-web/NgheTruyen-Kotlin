# Quy tắc phiên bản Nghe Truyện

Dự án dùng định dạng phiên bản **MAJOR.MINOR.PATCH**.

## 1. Cập nhật lớn — MAJOR

Tăng số đầu khi có thay đổi lớn về chức năng, kiến trúc, luồng sử dụng hoặc giao diện chính.

- Tăng `MAJOR` thêm 1.
- Đặt `MINOR = 0`.
- Đặt `PATCH = 0`.
- Ví dụ: `2.9.0` → `3.0.0`.

Có thể gọi ngắn `3.0` khi trao đổi, nhưng `versionName` trong dự án luôn ghi đủ ba số: `3.0.0`.

## 2. Cập nhật nhỏ — MINOR

Tăng số giữa khi thêm hoặc cải thiện chức năng nhưng không phải thay đổi lớn của toàn ứng dụng.

- Giữ nguyên `MAJOR`.
- Tăng `MINOR` thêm 1.
- Đặt `PATCH = 0`.
- Ví dụ: `3.0.0` → `3.1.0`.

## 3. Sửa lỗi nhỏ — PATCH

Tăng số cuối khi chỉ sửa lỗi, chỉnh hành vi nhỏ hoặc tinh chỉnh không làm thay đổi chức năng chính.

- Giữ nguyên `MAJOR` và `MINOR`.
- Tăng `PATCH` thêm 1.
- Ví dụ: `3.1.0` → `3.1.1`.

## 4. Quy tắc Android `versionCode`

Mỗi APK được phát hành hoặc gửi cho người dùng phải có `versionCode` lớn hơn bản APK trước đó.

`versionCode` chỉ tăng tuần tự và không dùng để biểu diễn MAJOR/MINOR/PATCH.

## 5. Quy tắc tên phiên bản

- `versionName` chỉ dùng số theo dạng `MAJOR.MINOR.PATCH`.
- Không nối tên tính năng, tên nhánh hoặc mốc phát triển vào `versionName`.
- Bản debug có thể nhận hậu tố `-debug` tự động từ Gradle.
- Tên ứng dụng luôn là **Nghe Truyện**, không nối tên tính năng vào tên ứng dụng.

## Phiên bản hiện tại

- `versionName = 3.0.1`
- `versionCode = 35`
