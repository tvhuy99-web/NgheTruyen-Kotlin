# Chữ ký cập nhật APK

## Debug APK

Từ versionCode 29, mọi APK `debug` phải dùng cùng một chứng chỉ cố định để Android có thể cài bản mới lên bản cũ mà không báo xung đột chữ ký.

- Package: `vn.nghetruyen.app.debug`
- Signing mode: `stable-debug`
- Certificate SHA-256: `534bf882c96a59ccf4310d18d02b14b62aa68e2beff3b6d05bf308e9909eb2f4`
- Keystore SHA-256: `50bc9abc16db79853f4208879a2e9bd5af081102ad81e3001090697dc0a8f6cd`

Gradle giải mã `.github/signing/stable-debug.keystore.b64` vào `.gradle/nghetruyen-stable-debug.keystore`. Vị trí này nằm ngoài thư mục `build`, nên lệnh `clean` không xóa khóa giữa lúc cấu hình và lúc ký APK.

CI kiểm tra cả hash keystore trong mã nguồn và fingerprint chứng chỉ của APK thực tế bằng `apksigner`. Build sẽ thất bại nếu chữ ký thay đổi.

## Chuyển đổi từ APK debug cũ

APK debug trước đây dùng debug keystore phụ thuộc máy hoặc GitHub runner. Nếu điện thoại đang cài một APK có chứng chỉ cũ khác fingerprint trên, APK stable-debug mới không thể cài đè trực tiếp. Android cố ý chặn việc thay đổi chủ sở hữu chữ ký của cùng package.

Nếu private key cũ không còn, lần chuyển đổi này bắt buộc:

1. sao lưu dữ liệu cần giữ;
2. gỡ `vn.nghetruyen.app.debug` cũ;
3. cài APK stable-debug mới;
4. từ các bản sau chỉ cài đè, không cần gỡ, với điều kiện `versionCode` luôn tăng.

## Release APK/AAB

Khóa stable-debug không phải khóa production. Kho này public, vì vậy khóa debug được xem là khóa phát triển công khai và bất kỳ ai có mã nguồn đều có thể dùng nó để ký package `.debug`.

Bản production `vn.nghetruyen.app` phải tiếp tục dùng khóa riêng qua:

- `NGHETRUYEN_RELEASE_STORE_FILE`
- `NGHETRUYEN_RELEASE_STORE_PASSWORD`
- `NGHETRUYEN_RELEASE_KEY_ALIAS`
- `NGHETRUYEN_RELEASE_KEY_PASSWORD`

Không đưa private key production vào repository.
