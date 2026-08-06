# Định dạng `.ntsource` SourcePack v2

## Cấu trúc

```text
source.ntsource
├── source.json
├── FILES.sha256
├── SIGNATURE.es256 hoặc SIGNATURE.ed25519
├── actions/*.json
├── data/*
├── fixtures/*
└── icon.png                 # tùy chọn
```

`FILES.sha256` phải:

- dùng ASCII;
- kết thúc bằng newline;
- sắp xếp đường dẫn tăng dần;
- có đúng một dòng cho mọi payload ngoài chính hash/signature;
- dùng dạng `<64 hex><hai khoảng trắng><đường dẫn>`.

Chữ ký ký trực tiếp bytes canonical của `FILES.sha256`.

## Manifest

Schema máy đọc được: [`docs/schemas/source-pack-v2.schema.json`](schemas/source-pack-v2.schema.json).

Các action bắt buộc hiện tại:

- `detail`
- `toc`
- `chapter`

Action thường dùng thêm:

- `home`, `genre`, `search`, `tocPages`, `comments`, `suggestions`, `login`.

Mỗi manifest phải khai báo origin, capability, runtime budget, action entry và ít nhất một fixture để được app kích hoạt.

## Declarative runtime v1

Program action là JSON:

```json
{
  "version": 1,
  "steps": [
    { "op": "resourceJson", "path": "data/catalog.json", "as": "root" },
    { "op": "path", "from": "root", "path": "items", "as": "items" },
    {
      "op": "filterText",
      "from": "items",
      "fields": ["title", "author"],
      "queryInput": "query",
      "as": "filtered"
    },
    { "op": "paginate", "from": "filtered", "pageInput": "page", "pageSize": 20, "as": "result" },
    { "op": "emit", "from": "result" }
  ]
}
```

Các operation hiện có:

- dữ liệu cục bộ: `resourceJson`, `constant`;
- biến đổi: `template`, `path`, `parseJson`, `projectArray`, `projectObject`;
- lọc/tìm: `filterText`, `filterArrayContains`, `find`;
- phân trang/kết quả: `paginate`, `emit`;
- mạng qua capability broker: `fetch`;
- trình duyệt: `browser`;
- lưu trữ: `storageGet`, `storageSet`, `storageDelete`;
- mật mã và socket: `crypto`, `websocketExchange`;
- HTML: `selectHtmlArray`, `selectHtmlObject`, `htmlParagraphs`;
- ghép dữ liệu: `composeObject`.

`fetch` không mở socket trực tiếp. Broker kiểm tra lại phương thức, HTTPS origin, redirect origin, DNS public-only, header, body, rate, concurrency, response quota và deadline. Browser, storage, crypto và WebSocket đều có operation chạy thật trong bản 1.4.0 và luôn đi qua capability broker.

## Fixture

Manifest ví dụ cục bộ:

```json
{
  "name": "Tìm truyện mẫu",
  "action": "SEARCH",
  "input": "gió",
  "expected": "fixtures/search/gio.expected.json"
}
```

Fixture có network thêm trường `fixture` trỏ tới HTTP snapshot:

```json
{
  "name": "Tìm qua API",
  "action": "SEARCH",
  "input": "gió mùa",
  "fixture": "fixtures/search/gio.http.json",
  "expected": "fixtures/search/gio.expected.json"
}
```

Snapshot v1 chứa danh sách response được ghép chính xác theo `method + URL`. Không có response phù hợp thì self-test thất bại; không có fallback sang Internet thật.

Expected JSON dùng so khớp subset có kiểu. Object có thể chỉ khai báo các trường quan trọng; array phải giữ đúng thứ tự phần tử mong đợi.

## Đóng gói và ký

Công cụ mẫu:

```bash
python scripts/sourcepack/build_source_pack.py examples/sourcepack-demo \
  --private-key /path/outside/repository/source-private.pem \
  --output /tmp/source.ntsource
```

Tạo khóa P-256 bằng OpenSSL ở vị trí ngoài project:

```bash
openssl ecparam -name prime256v1 -genkey -noout -out /secure/source-private.pem
openssl ec -in /secure/source-private.pem -pubout -outform DER | base64
```

Public key DER Base64 được thêm vào trust registry của ứng dụng hoặc repository; private key tuyệt đối không đưa vào SourcePack, Git hoặc APK.


## Khai báo network

```json
{
  "origins": ["https://api.example.org", "https://*.mirror.example.org"],
  "redirectOrigins": ["https://cdn.example.org"],
  "capabilities": {
    "network": {
      "methods": ["GET", "HEAD", "POST"],
      "maxResponseBytes": 4194304,
      "maxRequestBytes": 262144,
      "requestsPerMinute": 60,
      "maxConcurrent": 2
    },
    "cookies": "NONE"
  }
}
```

Wildcard `https://*.example.org` chỉ khớp subdomain như `api.example.org`, không khớp `example.org`. Origin chuyển hướng không tự được cấp làm origin request ban đầu.

Ví dụ `fetch`:

```json
{
  "op": "fetch",
  "url": "https://api.example.org/search?q={{input.query|urlencode}}",
  "method": "GET",
  "headers": { "Accept": "application/json" },
  "response": "JSON",
  "as": "http"
}
```

Kết quả có `status`, `url`, `headers`, `body`, `redirectCount` và `fromReplay`. `BYTES` được tuần tự hóa thành Base64 vì runtime truyền dữ liệu qua JSON.


## Runtime `VBOOK_JS_COMPAT`

Gói tương thích vBook có thể chứa `plugin.json` và `src/*.js`. Manifest vẫn phải khai báo action entry rõ ràng và mọi file vẫn nằm trong `FILES.sha256`.

- Rhino chạy interpreter mode với instruction/time/output budget.
- Deny-all class shutter ngăn truy cập Java/Android class.
- Không dùng `addJavascriptInterface`, DEX hoặc thư viện native động.
- Script chỉ gọi các host API broker: network, browser, storage, crypto và WebSocket.
- Importer ưu tiên `homecontent`/`genrecontent` cho action dữ liệu nếu plugin còn có `home`/`genre` dùng làm menu.

## Browser capability

Browser broker kiểm tra URL ban đầu, redirect, subresource và Service Worker. File/content access, mixed content, popup, download và third-party cookies bị tắt. Cookie được chia partition theo SourcePack và mã hóa khi lưu.

## Trust key

Ngoài trust root tích hợp, người dùng có thể thêm public key sau khi kiểm tra fingerprint. Việc đổi key dùng một rotation statement do khóa cũ ký; package không thể tự thay thế khóa tích hợp sẵn.
