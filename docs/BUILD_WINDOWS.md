# Build thủ công trên Windows

## 1. Chuẩn bị

- Cài Android Studio phiên bản hỗ trợ Android Gradle Plugin 8.13.2.
- Cài Android SDK Platform **36** và Build Tools 36.0.0 trong SDK Manager.
- Chọn JDK 17 trong `Settings > Build, Execution, Deployment > Build Tools > Gradle`.
- Giải nén project vào đường dẫn không quá dài.

Project khai báo:

```kotlin
compileSdk = 36
targetSdk = 36
```

## 2. Sinh Gradle Wrapper có kiểm tra SHA-256

Mở PowerShell tại thư mục project:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\bootstrap-wrapper.ps1
```

Script tải đúng `gradle-wrapper.jar` của Gradle 8.13, giới hạn dung lượng và chỉ cài sau khi SHA-256 khớp checksum chính thức. `gradlew.bat` lặp lại phép xác minh ở mỗi lần chạy; JAR hợp lệ được dùng ngay mà không gọi mạng.

## 3. Kiểm tra offline

```powershell
python .\scripts\check_clean_rewrite.py
python .\scripts\check_truyenfull_fixtures.py
python .\scripts\check_truyencv_fixtures.py
python .\scripts\check_kotlin_static.py
python .\scripts\validate_release.py
```

`check_kotlin_static.py` sẽ bỏ qua phần compile nếu máy không có `kotlinc`; Gradle test vẫn là nguồn xác nhận chính.

## 4. Gradle Sync, test, lint và debug APK

Chạy toàn bộ cổng Cột mốc 1 bằng một lệnh:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build-milestone1.ps1
```

Hoặc chạy thủ công:

```powershell
.\gradlew.bat --version
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
.\gradlew.bat assembleDebugAndroidTest
```

Để chạy instrumentation test khi emulator/thiết bị đã kết nối:

```powershell
$env:MILESTONE1_EXTRA_TASKS = "connected"
powershell -ExecutionPolicy Bypass -File .\scripts\build-milestone1.ps1
```

APK:

```text
app\build\outputs\apk\debug\app-debug.apk
```

Không bỏ qua lỗi lint hoặc unit test chỉ để lấy APK.

## 5. Build release đã ký

Cách ít lỗi nhất:

1. Android Studio → **Build** → **Generate Signed App Bundle or APK**.
2. Chọn **APK**.
3. Tạo hoặc chọn keystore của bạn.
4. Chọn variant `release`.
5. Bật V1 và V2; Android Studio sẽ chọn thêm scheme phù hợp.

Không đưa keystore, alias hoặc mật khẩu vào project/Git.

## 6. Các lỗi thường gặp

- **SDK 36 not found:** mở SDK Manager, bật hiển thị chi tiết gói và cài Platform 36.
- **JDK mismatch:** chọn JDK 17, không dùng JRE cũ.
- **Dependency download failed:** kiểm tra proxy/DNS rồi chạy `gradlew.bat --refresh-dependencies`.
- **Wrapper checksum mismatch:** xóa `gradle\wrapper\gradle-wrapper.jar`, kiểm tra mạng/proxy và chạy lại; không tắt bước xác minh.
- **TTS không phát:** cài engine TTS tiếng Việt trong Android Settings, thử nguồn demo và kiểm tra audio focus.
- **Thông báo không hiện:** cấp quyền thông báo trên Android 13 trở lên.
- **Nguồn báo xác minh trình duyệt:** adapter cố ý không vượt CAPTCHA/Cloudflare; thử lại sau hoặc dùng nguồn khác.
- **TruyenCV lỗi selector:** giữ nguồn ở `DEGRADED`, chụp HTML hợp lệ thành fixture rồi cập nhật parser.

## 7. Điều cần kiểm thử trước khi phát hành

- Xoay màn hình, khóa màn hình, rút tai nghe, cuộc gọi đến và audio focus.
- Khôi phục đúng chương/đoạn sau khi process bị hệ thống hủy.
- Prefetch và tự chuyển chương khi mạng chậm hoặc chương kế bị lỗi.
- EPUB/DOCX/TXT lớn, BOM UTF-16, ZIP trùng đường dẫn và ZIP phình.
- Tạo backup, sửa byte để xác nhận checksum fail, restore merge và backup Android hệ thống.
- Tải truyện dài, hủy từ notification, retry và thay đổi mạng.
- TalkBack, cỡ chữ lớn, landscape và nút điều khiển tối thiểu 48dp.
