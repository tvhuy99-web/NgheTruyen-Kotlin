# Kiểm toán parity XPK → Kotlin 0.8.0, gói P2 mở rộng nguồn

Ngày đối chiếu: 2026-08-02

## Phạm vi P2

P2 tập trung vào adapter nguồn, phiên xác thực và khả năng chẩn đoán. Không đưa runtime Lua, DEX động hoặc hệ tiện ích vBook cũ trở lại.

## 1. Nguồn truyện

| Nguồn | XPK gốc | Kotlin 0.8 | Trạng thái còn lại |
|---|---|---|---|
| Truyện Full | Adapter Lua | Adapter Kotlin đầy đủ | READY; vẫn cần regression live định kỳ |
| TruyenCV | Adapter Lua/browser | Adapter Kotlin + fixture | DEGRADED; cần live-test selector/challenge |
| Truyện Com | Adapter Lua | Adapter Kotlin + fixture | DEGRADED; cần live-test |
| TruyenYY | Adapter Lua/reader | Adapter Kotlin Markdown | DEGRADED; phụ thuộc reader trung gian |
| WikiDich | Adapter Lua | Adapter Kotlin HTTP + fixture | DEGRADED; cần live-test selector/redirect |
| Sáng Tác Việt | Native Lua + cookie/browser | Adapter Kotlin HTML/API + session mã hóa | DEGRADED; cần live-test đăng nhập/API |
| Wattpad/vBook | Runtime tiện ích động | Placeholder typed | Chưa triển khai |

## 2. WikiDich

Kotlin đã có:

- Danh sách mới, full, hot và thể loại.
- Tìm kiếm và nhập URL truyện trực tiếp.
- Metadata truyện, tác giả, thể loại, trạng thái và mô tả.
- Mục lục phân trang, xác định trang cuối và chương mới nhất.
- Nội dung chương, điều hướng trước/sau.
- HTTPS-only và allowlist cho các miền WikiDich đã khai báo.
- Fixture HTML và test parser.

Còn lại:

- Xác minh live trên Android với HTML hiện hành.
- Cơ chế lưu fixture mới khi health check phát hiện selector đổi.
- Cookie/login nếu website bắt đầu yêu cầu xác thực trong tương lai.

## 3. Sáng Tác Việt

Kotlin đã có:

- Danh sách/tìm kiếm metadata từ trang HTML.
- Route typed cho truyện và chương.
- API mục lục `chapterlist` và API nội dung `readchapter`.
- Paging nội bộ 100 chương cho catalog lớn.
- Ánh xạ lỗi cookie/session thành `SOURCE_LOGIN_REQUIRED`.
- Phiên cookie dùng chung giữa WebView đăng nhập và HTTP adapter.
- Fixture HTML/JSON cho list, detail, toc, chapter và login-required.

Khác XPK:

- Không có browser automation hoặc network capture chung.
- Không có JavaScript bridge và không chạy script tiện ích.
- Không lưu mật khẩu.
- Chưa hỗ trợ mọi biến thể domain/API cũ nếu server thay đổi.

## 4. Phiên đăng nhập

| Hạng mục | XPK | Kotlin 0.8 |
|---|---|---|
| Lưu cookie | Shared session/cookie modes | Một session mã hóa theo source ID |
| Mã hóa | Phụ thuộc secure preferences/runtime | AES-GCM, khóa Android Keystore |
| Mật khẩu | Nhập trong WebView | Nhập trực tiếp trên trang, không đọc/lưu |
| Cookie backup | Có thể đi cùng dữ liệu runtime | Cố ý loại khỏi backup |
| Giới hạn | Runtime-specific | 128 cookie, tối đa 32 KiB/source |
| Xóa phiên | Browser/session utilities | Xóa encrypted store và CookieManager |
| Cookie policy | shared/none/read-only/write-only | Chưa có các mode chi tiết |

## 5. WebView đăng nhập cô lập

Đã có:

- Activity riêng, không dùng WebView trong reader hoặc parser thông thường.
- Chỉ HTTPS và chỉ host trong allowlist.
- Không `addJavascriptInterface`.
- Tắt file access, content access, multiple windows và third-party cookies.
- Chặn mixed content, bật Safe Browsing.
- Thu cookie sau navigation và mã hóa ngay.
- Dọn WebView khi Activity bị hủy.

Còn lại:

- Device test với Android System WebView/OEM WebView khác nhau.
- UI xác nhận miền và danh sách tên cookie trước khi lưu.
- Expiry/domain/path jar đầy đủ; hiện adapter dùng cookie header phẳng theo source.
- OAuth/custom-tab flow cho nguồn có nhà cung cấp đăng nhập ngoài miền.

## 6. Health check trong ứng dụng

Đã có pipeline:

1. Danh sách hoặc fallback danh mục đầu tiên.
2. Chi tiết và mục lục.
3. Trang mục lục tiếp theo nếu cần.
4. Nội dung chương.

Mỗi bước có timeout, thời gian thực thi, mã lỗi và trạng thái PASS/FAIL/SKIPPED. Kết quả được quy về READY, DEGRADED, NEEDS_LOGIN hoặc DISABLED. Kiểm tra tất cả nguồn chạy tuần tự để tránh burst request.

Còn lại:

- Lịch kiểm tra định kỳ opt-in.
- Lưu lịch sử báo cáo trong Room.
- Xuất gói chẩn đoán đã ẩn cookie/nội dung nhạy cảm.
- Fixture capture và diff selector bán tự động.
- Circuit breaker khi website đang lỗi diện rộng.

## 7. Hệ tiện ích nguồn

XPK vẫn vượt Kotlin ở:

- Cài/cập nhật/gỡ extension.
- Repository extension.
- vBook JavaScript runtime.
- Browser replay và network capture.
- WebSocket bridge.
- Cookie policy nhiều chế độ.

Kotlin tốt hơn ở:

- Adapter được biên dịch và kiểm thử cùng ứng dụng.
- Không chạy mã nguồn tải từ mạng.
- Allowlist, response bound và redirect policy mặc định.
- Session mã hóa và WebView tối thiểu quyền.

## 8. Kết luận P2

P2 đã đưa WikiDich và Sáng Tác Việt từ `NOT_PORTED` sang `DEGRADED`, đồng thời bổ sung nền xác thực/chẩn đoán cần thiết cho nguồn có phiên. P2 chưa tái tạo hệ extension động của XPK; đây là lựa chọn chủ động để giữ clean rewrite an toàn và có thể kiểm toán.

Điều kiện để nâng một adapter từ `DEGRADED` lên `READY`:

1. Gradle build thật thành công.
2. Fixture/unit test đạt bằng dependency thật.
3. Live test list/detail/toc/chapter trên thiết bị.
4. Test redirect, timeout, captcha/login và session expiry.
5. Chạy health check nhiều lần ở các mạng khác nhau mà không rò cookie hoặc vượt allowlist.
