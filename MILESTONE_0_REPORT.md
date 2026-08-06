# Báo cáo thực thi Mốc 0

**Ngày:** 2026-08-05  
**Trạng thái:** `BLOCKED`  
**Mốc 1:** Chưa được mở

## Đã hoàn thành trong lượt khởi tạo

- Bổ sung preflight kiểm tra JDK, Android SDK, Gradle Wrapper và kho test.
- Bổ sung một cổng build duy nhất cho Linux/macOS và Windows.
- Giữ nguyên toàn bộ static gates cũ trong cổng mới.
- Cấu hình CI gọi cùng một cổng thay vì duy trì danh sách lệnh riêng dễ lệch.
- Bổ sung thu thập bằng chứng, hash SHA-256 và checklist nghiệm thu.
- Khóa JDK đường chuẩn ở phiên bản 17.
- Ghi rõ các Android SDK package bắt buộc.

## Kết quả thực chạy

| Hạng mục | Kết quả |
|---|---:|
| Project layout | PASS |
| Số tệp test Kotlin | 45 |
| `validate_release.py` | PASS |
| `check_milestone4_complete.py` | PASS |
| Biên dịch fallback `source-api` | PASS |
| Biên dịch fallback `source-diagnostics` | PASS |
| Biên dịch fallback `source-package` | PASS |
| Biên dịch fallback `source-store` | PASS |
| Biên dịch fallback `source-repository` | PASS |
| JDK 17 | BLOCKED, môi trường chỉ có JDK 21 |
| Android SDK 36 | BLOCKED, chưa được cài |
| Dependency resolution | BLOCKED, môi trường chặn mạng |
| APK/AAB | CHƯA TẠO |
| Instrumentation/device test | CHƯA CHẠY |

## Phán quyết

Mốc 0 chưa được đóng. Các kiểm tra tĩnh và biên dịch JVM phụ chỉ chứng minh một phần mã nguồn nhất quán. Chúng không thay thế Gradle Android build, Compose compiler, Room KAPT, Lint, APK/AAB hoặc kiểm thử thiết bị.

Lệnh nghiệm thu chuẩn:

```bash
./scripts/m0_gate.sh
```

Windows:

```powershell
.\scripts\m0_gate.ps1
```
