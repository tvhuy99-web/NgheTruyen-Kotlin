# Tạo APK cho NgheTruyen v2.8.0

## Cách nhanh nhất: GitHub Actions

1. Giải nén thư mục dự án và đưa toàn bộ mã nguồn lên một repository GitHub.
2. Mở tab **Actions** của repository.
3. Chọn workflow **Build APK**.
4. Bấm **Run workflow**.
5. Khi workflow hoàn tất, mở run vừa chạy và tải artifact **NgheTruyen-v2.8.0-debug-apk**.
6. Giải nén artifact để lấy `app-debug.apk` và file checksum `app-debug.apk.sha256`.

APK debug được Android ký bằng debug key tự động, có thể cài trực tiếp để kiểm thử. Package ID của bản debug là `vn.nghetruyen.app.debug`.

## Build trên Windows bằng Android Studio

Yêu cầu:

- JDK 17
- Android SDK Platform 36
- Android Build Tools 36.0.0
- Internet để tải Gradle và dependencies lần đầu

Các bước:

1. Mở thư mục dự án trong Android Studio.
2. Chọn JDK 17 cho Gradle.
3. Cài Android SDK Platform 36 và Build Tools 36.0.0 trong SDK Manager.
4. Mở Terminal tại thư mục dự án và chạy:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\bootstrap-wrapper.ps1
.\gradlew.bat :app:assembleDebug
```

APK nằm tại:

```text
app\build\outputs\apk\debug\app-debug.apk
```

## Build bản phát hành

Bản release cần keystore riêng. Không dùng debug key để phát hành chính thức. Có thể dùng Android Studio: **Build > Generate Signed App Bundle or APK > APK > release**.
