# Báo cáo Priority 1, độ trung thực nguồn v2.6.0

Phiên bản: **`2.6.0-source-fidelity-priority1`**  
Version code: **26**  
Room schema: **18**  
Backup format: **15**

## Mục tiêu

Khắc phục khoảng cách lớn nhất được phát hiện trong lần kiểm tra v2.5.0: các SourcePack selector chung cùng ID đã được xét trước và che các adapter Kotlin chuyên biệt có nhiều logic website hơn.

## Thay đổi thực thi

### Chọn implementation theo mức parity

`SourceRegistry` xét adapter tích hợp trước và chỉ thay thế khi implementation mới có `selectionPriority` cao hơn.

- Adapter Kotlin tích hợp: priority 100.
- SourcePack compatibility: priority 50.
- Placeholder chưa port: priority 0.
- SourcePack muốn vượt priority 100 phải khai báo full parity **và** có đủ action cùng fixture cho `home`, `search`, `detail`, `toc`, `chapter`.
- Priority tự khai báo trên 100 bị hạ xuống 99 nếu gói chưa đạt chứng nhận.

Nhờ vậy sáu nguồn `truyenfull`, `truyencv`, `truyencom`, `truyenyy`, `wikidich`, `sangtacviet` dùng adapter Kotlin chuyên biệt. Wattpad vBook priority 50 vẫn thắng placeholder priority 0 mà không tự nhận full parity khi chưa có fixture.

### Trang chủ và gợi ý

Bổ sung hợp đồng `StorySource.home()` và `StorySource.suggestions()`:

- Sáu adapter Kotlin có route home tường minh.
- SourcePack gọi action `HOME` và `SUGGESTIONS` khi có.
- vBook chuẩn hóa kết quả home/search/genre thành `items + nextPageUrl` và suggestions thành danh sách chuỗi.
- Giao diện Khám phá có nút Trang chủ và danh sách gợi ý theo nguồn.
- Health check kiểm tra home và suggestions riêng.

### Phân trang mục lục thật

`SourcePackStorySource.chapterPage()` không còn gọi lại `story(url)` rồi bỏ qua số phần tử đã có. Runtime hiện:

- Gọi `TOC_PAGES` khi extension cung cấp action này, nếu không gọi `TOC` với URL hoặc token tiếp tục.
- Đọc `nextPageUrl`, `nextUrl`, `nextPage`, `next`, `data2` hoặc `cursor`.
- Chuẩn hóa URL tương đối theo URL hiện tại.
- Mã hóa token không phải URL thành continuation nội bộ.
- Rebase index chương theo `startIndex`, tránh trùng hoặc quay về 0 ở trang sau.

### Bảo toàn thứ tự website

Trang chủ và danh mục đã có thứ tự cập nhật hoặc xếp hạng do nguồn trả về. Chế độ `RELEVANCE` với truy vấn rỗng trước đây làm mọi kết quả đồng điểm rồi xếp alphabet. v2.6.0 giữ nguyên thứ tự website; chỉ TITLE/AUTHOR/SOURCE mới sắp lại. Khi quay về RELEVANCE, dữ liệu home/category được tải lại để phục hồi thứ tự gốc.

### Fixture và theo dõi trực tuyến

- Sáu SourcePack declarative tăng từ 4 lên 6 fixture mỗi gói: search, home, genre, detail, toc, chapter.
- Tổng fixture declarative tăng từ **24 lên 36**.
- Thêm gate `check_priority1_source_coverage.py`, `check_priority1_source_parity.py` và `check_priority1_registry.py`.
- Thêm `live_source_smoke.py` và workflow chạy định kỳ. Monitor chỉ kiểm tra HTTPS, redirect allowlist, dung lượng, anti-bot và dấu hiệu nội dung; không vượt CAPTCHA hoặc cơ chế bảo vệ website.

## Kết quả kiểm tra trong môi trường hiện tại

Đã đạt:

- `PRIORITY1_SOURCE_COVERAGE_OK`
- `PRIORITY1_RUNTIME_OK`
- `PRIORITY1_SOURCE_PARITY_OK`
- `PRIORITY1_REGISTRY_OK`
- `PRIORITY1_HOME_GENRE_FIXTURES_OK cases=12`
- `KOTLIN_STATIC_COMPILE_OK`
- `MILESTONE3_UI_STATIC_COMPILE_OK`
- `P2_SOURCE_CHECK_OK`
- `P2_UI_STATIC_OK`
- `VBOOK_STATIC_COMPILE_OK`
- `SOURCE_PLATFORM_ANDROID_STATIC_OK`
- Fixture parser TruyenFull, TruyenCV, TruyenCom và TruyenYY

Live smoke không chạy được trong môi trường hiện tại vì DNS/network bị cô lập. Gói nguồn vẫn không chứa `gradle-wrapper.jar`; wrapper cần tải JAR trước khi Gradle có thể khởi động. Vì vậy build Android đầy đủ, APK/AAB và kiểm thử thiết bị thật chưa được chứng nhận.

## Phạm vi chưa hoàn thành trong Priority 1

Bản này ưu tiên adapter Kotlin chuyên biệt để có độ trung thực thực tế ngay lập tức. Nó chưa chuyển toàn bộ logic đặc thù của sáu adapter trở lại định dạng SourcePack declarative. Các compatibility pack vẫn được giữ để minh họa và fallback, nhưng không che adapter Kotlin.

Wattpad vBook chưa có fixture offline cho home, suggestions, detail, toc và chapter. Vì không có adapter Kotlin tương ứng, nó vẫn là implementation hoạt động thay placeholder nhưng được ghi nhận là compatibility, không phải full-parity-certified.
