# Stable debug signing

Kho này dùng một khóa **debug cố định** để mọi APK `vn.nghetruyen.app.debug` do CI tạo ra có cùng chứng chỉ và có thể cài cập nhật lên nhau.

- Alias: `androiddebugkey`
- Mật khẩu store/key: `android`
- Certificate SHA-256: `534bf882c96a59ccf4310d18d02b14b62aa68e2beff3b6d05bf308e9909eb2f4`
- Keystore SHA-256: `50bc9abc16db79853f4208879a2e9bd5af081102ad81e3001090697dc0a8f6cd`
- Subject: `CN=NgheTruyen Stable Debug, O=tvhuy99-web, C=VN`

## Phạm vi sử dụng

Khóa này chỉ dành cho build type `debug`, package `vn.nghetruyen.app.debug`. Kho là public nên bất kỳ ai đọc được kho cũng có thể lấy khóa debug này. Vì vậy đây **không phải** khóa chứng thực phát hành và tuyệt đối không được dùng để ký `vn.nghetruyen.app` hoặc bản phát hành production.

Đường ký release hiện tại bằng các biến `NGHETRUYEN_RELEASE_*` phải tiếp tục dùng khóa production riêng, được bảo vệ ngoài mã nguồn.

## Cài cập nhật

Các APK debug tiếp theo cài đè bình thường miễn là:

1. package vẫn là `vn.nghetruyen.app.debug`;
2. certificate SHA-256 vẫn là giá trị ở trên;
3. `versionCode` tăng ở mỗi bản cập nhật.

Nếu một APK debug cũ được ký bằng khóa khác thì Android sẽ từ chối cập nhật đè và không có cách hợp lệ để vượt kiểm tra chữ ký nếu không có private key cũ.
