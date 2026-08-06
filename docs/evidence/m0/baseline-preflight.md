# Mốc 0: Báo cáo preflight

**Trạng thái tổng:** `BLOCKED`

| Kiểm tra | Trạng thái | Chi tiết |
|---|---:|---|
| `project-layout` | **PASS** | Đủ tệp build bắt buộc. |
| `jdk-17` | **BLOCKED** | openjdk version "21.0.10" 2026-01-20 |
| `gradle-wrapper` | **WARN** | Chưa có wrapper JAR; gradlew sẽ tải và kiểm SHA-256 khi có mạng. |
| `android-sdk` | **BLOCKED** | Không tìm thấy Android SDK. |
| `python3` | **PASS** | /opt/pyvenv/bin/python3 |
| `test-inventory` | **PASS** | Phát hiện 45 tệp test Kotlin. |
