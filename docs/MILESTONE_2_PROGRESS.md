# Cột mốc 2: Nền tảng nguồn truyện và tiện ích

## Trạng thái

**Hoàn thành ở cấp mã nguồn và kiểm định ngoại tuyến.**

Phiên bản: `1.4.0-milestone-2-complete`.

Cột mốc này chuyển hệ thống nguồn từ adapter đóng cứng thành một nền tảng tiện ích có chữ ký, quyền hạn, sandbox, cập nhật độc lập và khả năng chẩn đoán trong ứng dụng.

## Các khối đã hoàn thành

### 1. Network, cookie và WebSocket broker

- HTTPS origin và redirect-origin allowlist.
- Public-only DNS, chặn localhost/private/link-local/multicast và IPv4-mapped IPv6 nội bộ.
- Kiểm soát method, header, request/response size, redirect, tốc độ, concurrency và deadline tổng.
- Cookie jar phân vùng theo SourcePack, hỗ trợ host-only/domain/path/secure/expiry.
- Cookie persistence mã hóa AES-GCM bằng Android Keystore.
- WebSocket chỉ đi qua capability broker, có giới hạn URL, message, số message và timeout.

### 2. Browser broker và cookie bridge

- WebView được dùng tuần tự theo từng nguồn; khi Android không hỗ trợ profile riêng, kết quả được gắn `degradedIsolation`.
- Không dùng `addJavascriptInterface`.
- Cấm file/content access, mixed content, popup và download.
- Điều hướng đầu tiên, redirect, tài nguyên con và Service Worker đều phải thuộc allowlist.
- Cookie được nhập/xuất giữa WebView và cookie jar của nguồn.
- Có DOM snapshot, selector wait/click/input, request metadata và renderer recovery.
- Khi đổi nguồn, WebView state và cookie process-global được dọn trước khi nhập partition mới.

### 3. Storage và crypto capability

- Storage có quota riêng từng SourcePack, đường dẫn chuẩn hóa và ghi atomic.
- Crypto broker cung cấp hash, HMAC, random bytes và AES-GCM theo capability; secret key không lộ cho script.

### 4. Repository, trust key và rotation

- Repository index ký số, canonical payload, expiry và compatibility.
- Package tải về phải khớp exact size, SHA-256, chữ ký, source ID/version và self-test.
- Người dùng có thể thêm/revoke public key với fingerprint.
- Key rotation phải được khóa cũ ký xác nhận; khóa tích hợp sẵn không thể bị gói bên ngoài thay thế.
- Cài đặt, enable/disable, giữ nhiều phiên bản và rollback atomic.

### 5. Diagnostics workbench

- Trace explorer theo trace ID và source ID.
- Selector inspector hiển thị số match và mẫu nội dung.
- HTTP fixture replay không bao giờ fallback sang Internet.
- Snapshot được giới hạn và dữ liệu nhạy cảm bị redaction.

### 6. Runtime khai báo

Ngoài các operation dữ liệu/mạng, runtime có:

- `browser`
- `storageGet`, `storageSet`, `storageDelete`
- `crypto`
- `websocketExchange`
- `selectHtmlArray`
- `selectHtmlObject`
- `htmlParagraphs`
- `composeObject`

Runtime vẫn bị giới hạn instruction, memory, timeout và output.

### 7. vBook JavaScript compatibility

- Import `plugin.json` hoặc ZIP vBook cục bộ.
- Rhino chạy interpreter mode, không JIT.
- Deny-all `ClassShutter`; script không được truy cập lớp Java/Android.
- Xóa các global Java package và không dùng JavaScript bridge của WebView.
- Host API chỉ là các capability broker có quyền và quota.
- Hỗ trợ action `home`, `genre`, `search`, `detail`, `suggestions`, `toc`, `chapter`.
- `homecontent` và `genrecontent` được ưu tiên làm action dữ liệu khi plugin có cả menu lẫn content script.

### 8. Nguồn tích hợp đã đóng gói

Ứng dụng bootstrap tám gói ký số:

1. Demo SourcePack.
2. Truyện Full.
3. Truyện CV.
4. Truyện Com.
5. Truyện YY.
6. WikiDich.
7. Sáng Tác Việt.
8. Wattpad vBook compatibility.

Sáu nguồn declarative có 24 fixture replay: search, detail, TOC và chapter. Wattpad được kiểm tra cấu trúc gói, script/action mapping, chữ ký và sandbox policy.

## Tiêu chí đã đạt

- 8/8 package có hash coverage và chữ ký đúng trust root.
- 24/24 fixture declarative khớp expected JSON.
- Toàn bộ module Source Platform JVM và test được compiler gate kiểm tra.
- Runtime vBook vượt static sandbox/compile gate.
- UI quản lý repository, trust key, import vBook, trace và selector inspector vượt Compose stub compile.
- P1 đến P4 và release gate cũ tiếp tục đạt sau thay đổi.

## Việc xác nhận ngoài môi trường hiện tại

Mã nguồn Cột mốc 2 đã hoàn thành, nhưng nghiệm thu phát hành Android vẫn cần máy có SDK Platform 36 để chạy Gradle Sync, Compose/Room compiler, lint, APK/AAB và instrumentation test. Cũng cần thiết bị thật để xác nhận cookie WebView, renderer recovery, Android Keystore và hành vi website trực tiếp. Đây là bước xác nhận môi trường, không phải chức năng còn để trống trong Cột mốc 2.
