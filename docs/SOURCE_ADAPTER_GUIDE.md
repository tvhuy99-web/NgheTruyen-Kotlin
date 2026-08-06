# Hướng dẫn viết adapter nguồn truyện

## Cấu trúc bắt buộc

Một nguồn thật phải triển khai `StorySource` và tách parser HTML khỏi HTTP. Không đặt selector trực tiếp trong ViewModel hoặc Compose.

```text
sources/
├── StorySource.kt
├── HttpHtmlClient.kt
└── TenNguonSource.kt

app/src/test/resources/tennguon/
├── list.html
├── detail-page-1.html
└── chapter.html
```

## Quy tắc an toàn

- Chỉ HTTPS.
- Khai báo allowlist chính xác; kiểm tra URL cuối sau redirect.
- Không nhận URL `javascript:`, `file:`, `content:` hoặc miền ngoài danh sách.
- Giới hạn kích thước phản hồi và timeout.
- Không dùng WebView để lách challenge.
- Không lưu cookie đăng nhập dạng plaintext.
- Không chạy request song song không giới hạn.

## Mã lỗi khuyến nghị

| Mã | Ý nghĩa |
|---|---|
| `SOURCE_NOT_PORTED` | Adapter chưa viết |
| `SOURCE_URL_REJECTED` | URL hoặc miền bị từ chối |
| `SOURCE_HTTP_429` | Bị rate limit |
| `SOURCE_RESPONSE_TOO_LARGE` | Phản hồi vượt giới hạn |
| `SOURCE_BROWSER_VERIFICATION_REQUIRED` | Website yêu cầu xác minh trình duyệt |
| `SOURCE_LAYOUT_CHANGED` | Selector không còn khớp |

## Truyện Full hiện có

`TruyenFullSource` hỗ trợ:

- Danh sách truyện mới.
- Tìm kiếm từ khóa và nhập URL truyện trực tiếp.
- Danh mục phổ biến.
- Thông tin truyện, thể loại, trạng thái.
- Mục lục nhiều trang.
- Nội dung chương đã làm sạch, chương trước và sau.

Fixture test nằm tại `app/src/test/resources/truyenfull`. Chạy nhanh:

```bash
python scripts/check_truyenfull_fixtures.py
```

Khi có Android SDK/Gradle wrapper:

```bash
./gradlew test --tests '*TruyenFullSourceTest'
```

## Khi website đổi HTML

1. Lưu HTML mẫu mới, loại dữ liệu riêng tư.
2. Thêm fixture tái hiện lỗi.
3. Viết test thất bại trước.
4. Sửa parser nhỏ nhất có thể.
5. Không nới allowlist hoặc bật WebView chỉ để làm test xanh.
