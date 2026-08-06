# Mốc 0: Trạng thái khởi tạo

**Ngày kiểm tra:** 2026-08-05  
**Trạng thái:** `BLOCKED`  
**Được phép mở Mốc 1:** Không

## Kết quả đã xác nhận

- Cấu trúc dự án Gradle đa module tồn tại.
- Có 45 tệp unit/instrumentation test Kotlin.
- `scripts/validate_release.py`: PASS.
- `scripts/check_milestone4_complete.py`: PASS.
- Phiên bản build khai báo: AGP 8.13.2, Gradle 8.13, Kotlin 2.3.21, compile/target SDK 36.
- Đã bổ sung cổng Mốc 0 thống nhất, preflight và bộ thu bằng chứng SHA-256.

## Vật cản hiện tại của môi trường kiểm tra

1. Máy kiểm tra chỉ có JDK 21, trong khi đường chuẩn Mốc 0 khóa ở JDK 17.
2. Không có Android SDK, platform 36, build-tools 36.0.0 hoặc adb.
3. Gói nguồn không chứa `gradle-wrapper.jar`; bootstrap cần Internet.
4. Môi trường hiện tại chặn DNS và tải dependency, nên Gradle không thể dependency resolution.
5. Không có emulator hoặc thiết bị Android gắn vào.

Các vật cản trên không được đổi thành PASS bằng kiểm tra tĩnh. Mốc 0 vẫn mở cho đến khi CI hoặc máy build chuẩn tạo APK/AAB và hoàn tất test.

## Bước chạy trên máy build chuẩn

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_SDK_ROOT=/path/to/android-sdk
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
./scripts/m0_gate.sh
```

Sau đó chạy trên emulator hoặc thiết bị:

```bash
M0_RUN_CONNECTED=1 ./scripts/m0_gate.sh
```
