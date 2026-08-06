# Báo cáo hoàn tất Priority 1, độ trung thực nguồn v2.7.0

Phiên bản: **`2.7.0-source-fidelity-priority1-complete`**  
Version code: **27**  
Room schema: **18**  
Backup format: **15**

## Mục tiêu

Hoàn tất phần còn lại của Priority 1 mà bản v2.6.0 chưa đóng gói trọn vẹn: không để SourcePack đơn giản che adapter chuyên biệt, đưa thay đổi vào chính các `.ntsource` tích hợp, hoàn chỉnh hợp đồng action/fixture và chứng minh replay ngoại tuyến cho cả nguồn declarative lẫn Wattpad vBook.

## Kiến trúc hiệu lực

Sáu nguồn website sử dụng **hybrid SourcePack**:

- SourcePack sở hữu manifest, quyền, trust, version, cài đặt, cập nhật, rollback và fixture.
- Adapter Kotlin cùng stable ID thực hiện các thao tác website trực tuyến đã có logic riêng: home, search, genre, detail, latest chapter, TOC, TOC continuation, chapter và comments fallback.
- Registry chỉ gắn bridge khi `delegateBuiltInId`, `legacyId` và ID adapter trùng nhau. Gói không thể yêu cầu một adapter khác.
- Descriptor của hybrid giữ health, login URL, host, comment capability và trạng thái degraded của adapter, không giả thành READY chỉ vì package hợp lệ.

Các nguồn hybrid:

1. Truyện Full
2. Truyện CV
3. Truyện Com
4. Truyện YY
5. WikiDich
6. Sáng Tác Việt

Wattpad chạy bằng vBook JavaScript và không dùng placeholder.

## Hợp đồng action hoàn chỉnh

Mỗi trong bảy nguồn thực tế có chín action:

- `home`
- `genre`
- `search`
- `suggestions`
- `detail`
- `latest_chapter`
- `toc`
- `tocPages`
- `chapter`

`SourceActionName.LATEST_CHAPTER` được thêm vào API nguồn và importer vBook. TOC continuation nhận URL hoặc token phân trang, giữ đúng index chương khi ghép trang.

## Fixture và self-test

### Sáu nguồn declarative

Mỗi nguồn có chín fixture và được replay thật bằng HTTP snapshot, template, selector HTML, URL tuyệt đối, SHA-256 ID, pagination và so sánh JSON:

- **6 nguồn × 9 = 54 ca**

### Wattpad

Chín action JavaScript được chạy bằng Node với replay broker và normalize theo contract ứng dụng:

- **9 ca**

### Tổng

- **63 ca fixture nguồn ngoại tuyến**

`SourceFixtureRunner` giờ đọc JSON tại đường dẫn `fixture.input`. Trước đây đường dẫn có thể bị dùng như một chuỗi query/URL. Self-test thống nhất chạy fixture cho declarative, vBook và Native Lua khi package có fixture.

## Asset phát hành

Bảy asset sau đã được xây lại từ đúng thư mục nguồn và đặt vào `app/src/main/assets/sourcepacks`:

- `truyenfull.ntsource`
- `truyencv.ntsource`
- `truyencom.ntsource`
- `truyenyy.ntsource`
- `wikidich.ntsource`
- `sangtacviet.ntsource`
- `wattpad.ntsource`

Từng payload trong ZIP được so byte với thư mục source, kiểm `FILES.sha256` và chữ ký ECDSA P-256. Signer mới:

`nghe-truyen-priority1-p256-v2`

Chỉ public trust root nằm trong dự án. Khóa riêng dùng để ký không được đưa vào source hoặc ZIP bàn giao.

## Gate đã đạt

- `PRIORITY1_DECLARATIVE_FIXTURES_OK cases=54`
- `PRIORITY1_WATTPAD_FIXTURES_OK cases=9`
- `PRIORITY1_HYBRID_PACKS_OK count=6`
- `PRIORITY1_SIGNED_ASSETS_OK count=7`
- `PRIORITY1_ACTION_FIXTURE_COVERAGE_OK`
- `PRIORITY1_COMPLETE_OK`
- `KOTLIN_STATIC_COMPILE_OK`
- `VBOOK_STATIC_COMPILE_OK`
- `SOURCE_PLATFORM_ANDROID_STATIC_OK`
- `MILESTONE2_BUILTIN_PACKS_OK count=8`
- `MILESTONE2_COMPLETE_CHECK_OK`
- `P1_UI_STATIC_COMPILE_OK`
- `AI_SETTINGS_STATIC_COMPILE_OK`
- `AUDIO_EXPORT_STATIC_COMPILE_OK`
- `MILESTONE1_FOUNDATION_CHECK_OK`
- `MILESTONE4_COMPLETE_CHECK_OK`
- `ROADMAP_MILESTONE5_PLAYBACK_COMPLETE_GATE=PASS`
- `RELEASE_VALIDATION_OK` ở chế độ wiring-only sau khi các child gate được chạy riêng.

## Ranh giới còn lại

Priority 1 ở cấp source code, package và fixture đã hoàn tất. Các kiểm chứng phụ thuộc môi trường ngoài vẫn chưa thể tuyên bố:

- Live smoke với website hiện tại, redirect, anti-bot và thay đổi DOM.
- Build Gradle Android đầy đủ vì ZIP vẫn chưa có `gradle-wrapper.jar` và môi trường cô lập không tải được wrapper/dependency.
- APK/AAB, Room KAPT, Android Lint và kiểm thử thiết bị thật.

Hybrid pack là lựa chọn có chủ đích: nó đạt fidelity thực tế ngay bằng adapter chuyên biệt, nhưng parser website chưa được chuyển hoàn toàn thành JSON/JavaScript tự trị. Nếu adapter Kotlin bị xóa, sáu pack declarative chỉ còn là fallback fixture-level, không phải bản port độc lập tương đương.
