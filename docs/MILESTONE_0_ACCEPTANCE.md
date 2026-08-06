# Mốc 0: Build thật và thiết lập đường chuẩn

Mốc 0 chỉ được đóng khi tất cả tiêu chí bắt buộc bên dưới có bằng chứng từ cùng một commit.

## Cổng bắt buộc

- [ ] Preflight trả về `M0_PREFLIGHT=READY` trên JDK 17.
- [ ] `./gradlew clean` PASS.
- [ ] Toàn bộ unit test PASS.
- [ ] `lintDebug` PASS, không tắt `abortOnError`.
- [ ] `assembleDebug` tạo APK cài được.
- [ ] `assembleDebugAndroidTest` tạo test APK.
- [ ] `bundleRelease` tạo AAB.
- [ ] `connectedDebugAndroidTest` PASS trên emulator hoặc thiết bị API được hỗ trợ.
- [ ] Room migration test PASS với toàn bộ schema được lưu trong repository.
- [ ] APK khởi động lạnh và mở lại không crash trên thiết bị thật.
- [ ] Có `build/reports/m0/evidence.json` và `SHA256SUMS`.
- [ ] CI tái tạo được kết quả từ checkout sạch.
- [ ] P0 = 0, P1 = 0, P2 thuộc Mốc 0 = 0.

## Lệnh chuẩn duy nhất

```bash
./scripts/m0_gate.sh
```

Để chạy instrumentation test khi đã có emulator hoặc thiết bị:

```bash
M0_RUN_CONNECTED=1 ./scripts/m0_gate.sh
```

## Quy tắc trạng thái

- `BLOCKED`: thiếu toolchain, dependency, SDK hoặc thiết bị.
- `FAILED`: toolchain sẵn sàng nhưng một cổng build/test/lint thất bại.
- `PASS`: tất cả cổng tự động PASS, nhưng chưa đồng nghĩa với Accepted.
- `ACCEPTED`: PASS cộng kiểm thử thiết bị thật và ký nghiệm thu.
