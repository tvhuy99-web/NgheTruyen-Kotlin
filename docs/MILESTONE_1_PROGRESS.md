# Cột mốc 1, biến dự án thành ứng dụng Android hoàn chỉnh

Ngày bắt đầu: 2026-08-04

## Đã hoàn thành trong lượt đầu

### 1. Hợp đồng build có thể tái lập

- `gradlew` và `gradlew.bat` xác minh và tự bootstrap `gradle-wrapper.jar` nếu source archive chưa chứa binary.
- JAR được kiểm tra ở mỗi lần chạy và chỉ được chấp nhận khi trùng SHA-256 chính thức của Gradle 8.13.
- Downloader giới hạn kích thước, timeout, HTTPS, kiểm tra HTTP status và ghi tệp atomically.
- Bootstrap cũ không còn tải cả distribution 130+ MiB chỉ để tạo wrapper JAR.

### 2. Sửa migration Room chặn khởi động

Schema được nâng từ 6 lên 7.

Bản schema 6 đã khai báo `DownloadJobEntity` nhưng migration `5 → 6` trước đó không tạo bảng `download_jobs`. Điều này có thể làm Room từ chối mở database sau nâng cấp.

Đã sửa bằng hai lớp:

- `MIGRATION_5_6` tạo `download_jobs` cho đường nâng cấp trực tiếp.
- `MIGRATION_6_7` phục hồi `download_jobs` và dựng lại `following`, `story_tts_profiles`, `audio_export_jobs` để chuẩn hóa default SQL trên cả database version 6 cài mới lẫn database đã nâng cấp.
- Thêm `@ColumnInfo(defaultValue=...)` cho các cột non-null được thêm qua migration để schema Room và SQL migration thống nhất.
- Dữ liệu cũ được sao chép sang bảng mới trước khi đổi tên, không dùng destructive migration.

### 3. Kiểm thử migration

Đã thêm `AppDatabaseMigrationTest` để kiểm tra:

- `5 → 6` tạo đủ các bảng P4 và `download_jobs`.
- `6 → 7` phục hồi đúng bảng, chuẩn hóa năm default SQL và giữ nguyên dữ liệu theo dõi, cấu hình TTS, trạng thái xuất âm thanh.

Bài kiểm thử được biên dịch qua `assembleDebugAndroidTest` và workflow CI chạy `connectedDebugAndroidTest` trên emulator API 33.

### 4. Cổng chất lượng build

CI hiện yêu cầu hoàn thành:

- Python release gates.
- Unit test debug.
- Android Lint debug.
- Debug APK.
- Debug instrumentation-test APK.
- Room migration instrumentation test trên emulator API 33.
- Release AAB.

Build outputs, báo cáo và Room schema được lưu thành CI artifact.

### 5. Lệnh build thống nhất

- `scripts/build-milestone1.sh` chạy release gate, unit test, lint và tạo hai APK debug/test.
- `scripts/build-milestone1.ps1` cung cấp cùng hợp đồng trên Windows.
- Biến `MILESTONE1_EXTRA_TASKS=connected` chạy instrumentation test; giá trị `release` tạo AAB.

### 6. Giảm phụ thuộc môi trường

- Dùng `compileSdk = 36`, bỏ `compileSdkMinor = 1` vì mã nguồn không dùng API minor riêng.
- Thêm cấu hình xuất Room schema.
- Lint lỗi sẽ chặn build.
- Sửa `check_wave_assembler.py` dùng `java -jar`, tránh tiến trình Kotlin launcher giữ pipe và làm release gate treo.

## Đã xác minh trong môi trường hiện tại

- Wrapper downloader biên dịch bằng `javac`.
- Toàn bộ SQL `6 → 7` chạy được trên SQLite: tạo `download_jobs`, chuẩn hóa default và giữ nguyên dữ liệu mẫu.
- Wave assembler harness chạy thành công.
- Các fixture TruyenFull, TruyenCV, TruyenCom và TruyenYY đã đạt ở lượt kiểm tra đầu.
- Kotlin static compile gate và audio export static compile gate đã đạt.

## Chưa thể xác minh trong môi trường hiện tại

Môi trường thực thi này không có Android SDK, không có Gradle distribution/cache và chặn tải distribution trực tiếp. Vì vậy chưa thể tuyên bố các lệnh sau đã PASS tại đây:

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
./gradlew assembleDebugAndroidTest
./gradlew bundleRelease
```

CI mới được thêm để chạy chính các lệnh này trong môi trường Android SDK đầy đủ. Cột mốc 1 chỉ được đóng khi có build CI xanh và APK được cài kiểm thử trên thiết bị thật.

## Việc tiếp theo trong Cột mốc 1

1. Chạy CI lần đầu và sửa mọi lỗi Gradle/KAPT/Compose/Lint phát hiện.
2. Sinh và commit Room schema JSON version 1–7 hoặc ít nhất baseline được xác nhận từ bản phát hành cũ.
3. Mở rộng migration test từ API 33 sang API 26 và 36.
4. Cài debug APK lên thiết bị thật, kiểm tra cold start, nâng cấp database, WorkManager và foreground service.
5. Tạo release signing mẫu an toàn bằng biến môi trường/CI secret, không đưa khóa vào repository.

---

## Lượt khởi động lõi đọc theo lộ trình parity, 2026-08-05

- Hủy tìm kiếm/duyệt thể loại cũ để kết quả mạng chậm không ghi đè trạng thái khám phá mới.

Chủ dự án cho phép triển khai Mốc 1 trước khi Mốc 0 có thể build Android thật. Ngoại lệ này chỉ mở quyền sửa mã, không thay đổi tiêu chí nghiệm thu.

Đã hoàn thành lát cắt đầu tiên:

- tách chỉ số đoạn hiển thị khỏi các mảnh TTS;
- thêm ánh xạ speech chunk về đoạn gốc;
- giữ bookmark, ghi chú, phân vai, cue nhạc và checkpoint trên chỉ số ổn định;
- chuẩn hóa chương nhất quán giữa cache, UI và service;
- sửa điều hướng chương với mục lục có index không liên tục;
- hủy job tải truyện/chương cũ để tránh race condition;
- thêm executable gate `check_milestone1_reader_core.py`.

Chi tiết và cổng nghiệm thu mới nằm tại:

- `docs/MILESTONE_1_READER_STATUS.md`
- `docs/MILESTONE_1_READER_ACCEPTANCE.md`

Mốc 1 vẫn ở trạng thái **IN PROGRESS**.
