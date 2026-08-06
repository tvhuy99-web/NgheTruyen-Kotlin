# Báo cáo tương thích tối đa với công cụ XPK, v2.5.0

Ngày hoàn tất: **2026-08-06**  
Phiên bản: **`2.5.0-xpk-max-compatibility`**  
Version code: **25**  
Room schema: **18**  
Backup format: **15**

## Mục tiêu

Bản v2.5.0 xử lý các khoảng cách còn lại được phát hiện khi đối chiếu lại dự án Kotlin với công cụ XPK: package Lua nhiều tệp, API vBook nâng cao, WebView chẩn đoán, bình luận theo nguồn và sao lưu tài nguyên vật lý. Thiết kế ưu tiên tương thích hành vi, nhưng không mở `luajava` hoặc quyền truy cập Android tùy ý cho extension.

## Native Source API 2

- Nhập `.lua` đơn hoặc ZIP tối đa 256 entry và 48 MiB sau giải nén.
- Chọn `source.lua`, `main.lua`, `init.lua` hoặc tệp Lua duy nhất làm entry.
- Giữ toàn bộ module và tài nguyên package dưới cây cài đặt SourcePack.
- `require()` nội bộ hỗ trợ alias theo đường dẫn, đường dẫn tương đối và module `init.lua`.
- Hook có `context.resource.exists/text/base64/list` để đọc tài nguyên package có giới hạn.
- Tách ba sandbox: đánh giá nguồn, kiểm định package và dựng adapter.
- Chặn bytecode, `luajava`, Java/Android class, `io`, `os`, `debug`, package loader, process và filesystem tùy ý.
- Giới hạn thời gian, số lệnh, độ sâu, số node, input/output, module, tài nguyên và bộ nhớ xấp xỉ.

## vBook

Các API tương thích chính:

- `fetch`, `Http`, `HTML/Html/Document`, DOM selector và traversal.
- `Storage`, `localStorage`, `cacheStorage`, `localConfig`, `localCookie` với `getItem/setItem/removeItem/key/length/clear`.
- `Engine.newBrowser`, điều hướng, HTML snapshot, selector, click/input, JavaScript đồng bộ và bất đồng bộ.
- User-agent, URL/block patterns, request metadata, dialog policy/history/wait, cookie/session sync và đóng session không xóa cookie.
- `Graphics.createImage/createCanvas/drawImage/capture` qua Android Bitmap/Canvas có giới hạn.
- `WebSocket` và `WebSocketHost` qua broker có origin policy.
- `Response.success/error` theo `code/data/data2`.
- `Script.execute`, `load`, `Console`, `Log`, `UserAgent`.
- `Qt.translate(text, to, extras)` qua AI đã cấu hình, chỉ khi extension khai báo gửi nội dung cho bên thứ ba.
- `CryptoJS` có WordArray, Hex/Base64/Utf8/Latin1, MD5, SHA-1/256/512, HMAC, AES-CBC/AES-ECB, PKCS7/NoPadding và passphrase OpenSSL `Salted__`.

Không hỗ trợ DES, TripleDES, RC4, PBKDF2 hoặc extension gọi Java/Android trực tiếp. Đây cũng là nhóm thuật toán ngoài phạm vi tương thích đã xác định của công cụ tham chiếu.

## Trình duyệt chẩn đoán

- Điều hướng, quay lại, tiến tới, reload và URL bar.
- Chế độ origin nghiêm ngặt hoặc tương thích.
- Chặn hoặc quan sát tài nguyên ngoài origin.
- Chính sách dialog dismiss/accept/passthrough, lịch sử dialog và phản hồi prompt.
- Đổi user-agent, probe JavaScript/DOM/storage/cookie/request.
- Xóa cookie hoặc session rõ ràng; đóng browser không xóa cookie nguồn.
- Log Basic/Verbose, copy, xuất JSON và xóa log.
- Chỉ lưu metadata, che token và không ghi password, form body, cookie value hoặc response body.

## Bình luận

Luồng bình luận theo thứ tự:

1. Action `COMMENTS` của nguồn nếu có.
2. Bình luận nhúng trong payload chi tiết truyện.
3. Parse HTML trực tiếp bằng selector tương thích.
4. WebView động và DOM snapshot nếu nguồn có browser capability.

Parser nhận các cấu trúc `items`, `comments`, `results`, `data.items`, `data.comments`, nhiều tên trường author/content/time và phân trang. Kết quả bị giới hạn trước khi đưa lên giao diện. Khả năng lấy bình luận thực tế vẫn phụ thuộc DOM, đăng nhập và chống bot hiện tại của website.

## Backup format 15

Ngoài `manifest.json` và `data.json`, archive có thể chứa:

- SourcePack, extension Lua/vBook và repository cache đã cài.
- Storage nguồn không chứa cookie, credential, secret, token, lock hoặc tệp tạm.
- Trust metadata.
- Tệp nhạc cảnh vật lý, được phục hồi sang URI mới trong vùng riêng của ứng dụng.

Mỗi attachment có kích thước và SHA-256 trong manifest. Restore kiểm tra entry count, đường dẫn, kích thước, checksum và component được chọn trước khi ghi. Thư mục nguồn được thay thế bằng staging/backup rollback ở cấp filesystem.

## Kiểm tra đã đạt

- `check_v250_tool_parity.py`
- `check_vbook_static.py`
- `check_v240_native_lua_vbook_diagnostics_comments.py`
- `check_kotlin_static.py`
- `check_ai_settings_static.py`
- `check_audio_export_static.py`
- `check_android_wiring_static.py`
- `check_p4_network_static.py`
- `check_p4_android_security.py`
- `check_p4_transfer_static.py`
- `check_source_platform_android_static.py`
- `check_source_diagnostic_browser_static.py`
- Cột mốc 1, 2, 4, 5 và roadmap playback
- 8 SourcePack và 24 fixture
- `validate_release.py`: `RELEASE_VALIDATION_OK`

Các lệnh tổng hợp dài có thể chạm timeout của môi trường sau khi nhiều compiler gate con đã hoàn tất; những gate quan trọng được chạy lại độc lập.

## Giới hạn chứng nhận

- Chưa có APK/AAB được build và ký trong môi trường hiện tại.
- Chưa chạy Compose compiler, Room KAPT, Android Lint hoặc instrumentation bằng Android SDK Platform 36.
- Chưa thử ma trận thiết bị Android, TTS engine, WebView và extension thực tế.
- `luajava` và quyền Android tùy ý bị từ chối có chủ đích. Extension phụ thuộc chúng cần được chuyển sang API sandbox.
- Website có thể đổi DOM, CAPTCHA, đăng nhập hoặc chống bot sau ngày kiểm tra.
