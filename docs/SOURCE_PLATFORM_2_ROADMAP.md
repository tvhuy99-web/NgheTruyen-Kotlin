# Source Platform 2, trạng thái sau Cột mốc 2

## Đã hoàn thành

### Chuỗi cung ứng và vòng đời

- SourcePack v2 strict manifest, canonical hashes và chữ ký ECDSA/Ed25519.
- Bounded ZIP, path safety, staging, atomic activation, multi-version và rollback.
- Signed repository, expiry, compatibility, exact package size/SHA-256 và self-test.
- Trust-key enrollment, fingerprint, revoke và signed key rotation.

### Capability runtime

- Network broker.
- RFC-oriented cookie partition và encrypted persistence.
- WebView browser broker và cookie bridge.
- Storage, crypto và WebSocket broker.
- Declarative JSON runtime có HTML selector/parser operations.
- Fixture snapshot replay hoàn toàn ngoại tuyến.

### Browser và diagnostics

- Navigation/redirect/subresource/service-worker allowlist.
- DOM snapshot, click/input/wait selector và request metadata.
- Renderer-gone handling và session recovery.
- Trace explorer và selector inspector trong ứng dụng.

### vBook compatibility

- `plugin.json`/ZIP importer.
- Rhino interpreter sandbox với deny-all class shutter.
- Broker-only host APIs; không Android API, DEX/SO động hoặc `addJavascriptInterface`.
- Action compatibility cho home/genre/search/detail/suggestions/toc/chapter.
- Wattpad được đóng gói như nguồn vBook ký số.

### Nguồn tích hợp

- Demo, Truyện Full, Truyện CV, Truyện Com, Truyện YY, WikiDich, Sáng Tác Việt và Wattpad.
- 24 fixture replay cho sáu nguồn declarative.

## Sau Cột mốc 2

Các công việc sau đây thuộc nghiệm thu Android hoặc cột mốc tiếp theo, không phải lỗ hổng runtime còn để trống:

- Gradle/Compose/Room/lint/APK verification trên Android SDK Platform 36.
- Instrumentation test WebView và Keystore trên emulator/thiết bị.
- Live selector monitoring và cập nhật SourcePack khi website đổi HTML hoặc anti-bot.
- Mở rộng corpus vBook ngoài Wattpad dựa trên plugin thực tế của người dùng.
