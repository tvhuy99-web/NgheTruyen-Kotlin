# Báo cáo Native Lua, vBook nâng cao, trình duyệt chẩn đoán và bình luận

Ngày hoàn tất: **2026-08-06**  
Phiên bản: **`2.4.0-native-lua-vbook-diagnostic-comments`**, versionCode **24**

## Tóm tắt

Bản 2.4.0 hoàn thành bốn hạng mục tương thích nguồn còn lại trên nền v2.3.0:

1. Nhập và chạy trực tiếp extension **Lua Native Source API 2**.
2. Mở rộng các API vBook thường gặp nhưng trước đây chưa được host Kotlin cung cấp.
3. Thêm trình duyệt chẩn đoán đăng nhập chuyên dụng theo từng nguồn.
4. Chuẩn hóa capability, health check, fixture và parser bình luận theo từng nguồn.

Room giữ nguyên schema **18** và backup giữ nguyên format **14**, vì các thay đổi này không cần bảng dữ liệu mới.

## 1. Native Source API 2

### Định dạng được nhập

- Tệp `.lua` UTF-8 trả về package Native Source API 2.
- ZIP có `source.lua`, `main.lua`, hoặc chỉ đúng một tệp Lua.
- ZIP đầu vào tối đa 4 MiB, tối đa 64 entry; mã Lua được chọn tối đa 1 MiB và tổng Lua giải nén tối đa 8 MiB.
- Bytecode Lua, đường dẫn ZIP traversal và tệp mơ hồ bị từ chối.

### Luồng thực thi

- Module `source-lua` dùng `org.luaj:luaj-jse:3.0.1`.
- Package được kiểm tra bằng `native_api.lua` đi kèm ứng dụng. Sandbox nguồn, sandbox validator và sandbox adapter là ba môi trường độc lập; extension không thể ghi đè `require`, `type` hoặc thư viện chuẩn để tác động bước kiểm định tin cậy.
- `native_v2_adapter.lua` chuyển actions, pipelines, hooks, permissions và metadata thành SourcePack/vBook nội bộ.
- Runtime mode mới là `NATIVE_LUA_COMPAT`.
- Pure-Lua hooks được gọi lại qua cầu `__bridge("native_hook", ...)`, chỉ nhận và trả dữ liệu JSON có giới hạn.
- Nguồn nhập cục bộ đi qua cùng màn hình kiểm tra quyền, origin allowlist, health check và rollback của Source Platform.

### Sandbox

Không cung cấp:

- `luajava` hoặc truy cập lớp Java/Android tùy ý.
- `io`, `os`, `debug`, `package`, `dofile`, `loadfile`, `load`, `loadstring` và module loader tự do.
- Bytecode động, DEX, native library, tiến trình hệ điều hành hoặc filesystem.

Có:

- Instruction hook theo lô 1.000 lệnh.
- Deadline thực thi.
- Giới hạn kích thước nguồn, input/output hook, độ sâu và số node JSON.
- `require` allowlist, hiện chỉ cho module Native API được đóng gói sẵn.

Đây là tương thích Native Source API 2, không phải môi trường AndroLua toàn quyền. Extension cần `luajava`, Canvas/Graphics Android hoặc đọc filesystem tùy ý sẽ bị từ chối có chủ đích.

## 2. API vBook nâng cao

Runtime Rhino an toàn hiện cung cấp thêm:

- `fetch`, `Http`/`HTTP`: GET, POST, headers, body và response wrapper.
- `Html`/`HTML`: parse HTML và DOM wrapper.
- `Storage`, `localConfig`, `localStorage`, `cacheStorage`: get/put/remove cùng alias `getItem`, `setItem`, `removeItem`.
- `Engine.newBrowser()` và `Browser`: navigate, wait selector/URL/request, DOM snapshot, evaluate JavaScript, click, cookies, session sync, request metadata, dialog policy và close.
- `Crypto`: SHA-256, HMAC-SHA256 và AES-GCM qua capability broker.
- `WebSocketHost.exchange` qua WebSocket broker.
- `Log`: `log`, `d/i/w/e`, `debug/info/warn/error`.
- `UserAgent` và `sleep` có giới hạn.

DOM wrapper hỗ trợ select/selectFirst, indexed elements, first/last/eq, text/ownText/wholeText, html/outerHtml, attr/absUrl, id/tag/class, parent/children và body/title/location.

Rhino vẫn chạy interpreted mode, ClassShutter chặn Java bridge, có instruction budget, timeout, memory/output bounds và capability checks cho network/browser/storage/crypto/WebSocket.

## 3. Trình duyệt chẩn đoán đăng nhập

Mỗi nguồn có nút mở trình duyệt chẩn đoán với:

- Back, forward, reload và URL bar.
- Kiểm tra JavaScript, cookie, DOM và thống kê request.
- Lưu phiên cookie vào kho mã hóa của nguồn.
- Mức log Basic/Verbose.
- Sao chép, xuất JSON và xóa log.

### Bảo vệ dữ liệu

- Chỉ HTTPS và host thuộc allowlist của nguồn.
- Chặn mixed content, file/content access, popup, third-party cookie và SSL lỗi.
- Safe Browsing được bật trên WebView.
- Subresource ngoài allowlist trả về 403 cục bộ.
- Log chỉ ghi URL đã bỏ query/fragment, method, loại resource, main-frame và **tên** header.
- Không ghi giá trị header, response body, form/password, giá trị cookie hoặc nội dung đăng nhập.
- Nhật ký tối đa 1.000 sự kiện và có bộ khử token/password/cookie trước khi hiển thị hoặc xuất.

## 4. Bình luận theo từng nguồn

Capability mới:

- `NONE`: không hỗ trợ.
- `EMBEDDED`: bình luận đã nhúng trong chi tiết truyện.
- `PAGED`: action comments phân trang trong ứng dụng.
- `DYNAMIC_BROWSER`: bình luận cần browser sandbox.

SourcePack manager hiển thị capability và số fixture comments. Health checker chạy bước “Bình luận nguồn” khi nguồn khai báo action comments. Native Lua adapter tự sinh `native_v2_comments.js` và gắn action comments vào manifest.

Parser bình luận chấp nhận thêm:

- `items`, `comments`, `results`, `data.items`, `data.comments`.
- `next`, `nextUrl`, `nextPageUrl`, `paging.next`, `pagination.nextUrl`.
- `text`, `content`, `message`, `body`.
- `user`, `username`, `displayName`, `display_name`, `nickname`, `user.name`.
- `time`, `date`, `createdAt`, `created_at`, `publishedAt`, `published_at`.

Mỗi trang vẫn bị giới hạn số item và độ dài nội dung trước khi đưa lên UI.

## Kiểm tra đã đạt

- `check_kotlin_static.py`
- `check_vbook_static.py`
- `check_v240_native_lua_vbook_diagnostics_comments.py`
- `check_source_diagnostic_browser_static.py`
- `check_p2_health_static.py`
- `check_p2_ui_static.py`
- `check_p2_android_wiring.py`
- `check_source_platform_android_static.py`
- `check_comment_fixtures.py`
- `check_v230_features_static.py`
- `check_p4_network_static.py`
- `check_p4_android_security.py`
- `check_p4_transfer_static.py`
- `check_ai_gemini_story_vietphrase.py`
- `check_audio_export_static.py`
- Cột mốc 1, Cột mốc 2 complete, Cột mốc 4 complete, Cột mốc 5 và roadmap playback.
- `validate_release.py`: `RELEASE_VALIDATION_OK`.

Hai gate tổng hợp Source Platform có thể chạm timeout của môi trường khi nối nhiều tiến trình `kotlinc`; các module thay đổi đã được biên dịch bằng gate tách riêng và các gate Android/Source Platform độc lập đã đạt.

## Chưa chứng nhận

- Gradle/Compose/Room KAPT/Android Lint đầy đủ.
- APK hoặc AAB ký phát hành.
- LuaJ và Rhino chạy trên thiết bị Android thật với tập extension rộng.
- WebView đăng nhập trên Android 13–15 và nhiều nhà sản xuất.
- Bình luận động trên mọi website thực tế, đặc biệt nguồn có CAPTCHA hoặc cơ chế chống bot thay đổi.

Gradle Wrapper JAR và Android SDK Platform 36 không có sẵn trong môi trường hiện tại; mạng ngoài không đủ để bootstrap toàn bộ toolchain.
