# Source Platform 2, từ foundation 1.1.0 đến network/repository 1.3.0

## Phạm vi đã chạy thật

Bản 1.1.0 đưa nền tảng nguồn vào cùng project Android thay vì chỉ tồn tại dưới dạng thiết kế:

- `source-api`: manifest, permission snapshot/diff, request/response và lỗi có kiểu.
- `source-package`: ZIP bounded, canonical path, hash coverage và chữ ký ECDSA P-256 hoặc Ed25519 khi nền tảng hỗ trợ.
- `source-store`: staging, atomic activation, enable/disable, giữ nhiều phiên bản, rollback và kiểm tra tree hash sau khi bung gói.
- `source-runtime`: máy pipeline khai báo có instruction, memory, timeout và output budget.
- `source-diagnostics`: trace có cấu trúc, bounded recorder và xuất JSON đã che secret.
- `app/sourceplatform`: bridge từ SourcePack sang `StorySource`, UI cài đặt, phê duyệt quyền, rollback và xem diagnostics.

Nguồn mẫu tích hợp sẵn chạy qua chính package verifier, fixture runner, store và declarative runtime. Adapter Kotlin cũ chỉ là fallback nếu bootstrap SourcePack thất bại.

## Chuỗi kích hoạt

```text
OpenDocument(.ntsource)
    ↓
bounded ZIP + canonical paths
    ↓
FILES.sha256 coverage
    ↓
signature trust root
    ↓
strict source.json + compatibility
    ↓
fixture self-test
    ↓
permission diff shown to user
    ↓ explicit approval
staging → tree-hash verify → atomic activation
    ↓
SourceRegistry refresh
```

Gói không có fixture hoặc có fixture lỗi sẽ không xuất hiện ở bước phê duyệt. Phiên bản trong private store bị sửa/hỏng sẽ không được nạp trở lại registry.

## Bổ sung trong 1.3.0

- Capability network broker chạy thật cho declarative action.
- HTTPS origin/redirect allowlist, public DNS, rate/concurrency/body quota và deadline.
- HTTP snapshot replay ngoại tuyến cho fixture.
- Signed repository index, cache atomic và luồng tải/cập nhật SourcePack.

## Những điều chưa tuyên bố hoàn tất

- Chưa có trust-key enrollment/rotation UI cho publisher bên thứ ba.
- Cookie partition hiện chưa phải cookie jar RFC đầy đủ theo origin/domain/path.
- Chưa có browser profile riêng, cookie bridge, request capture hoặc DOM snapshot.
- Chưa có selector inspector và offline replay UI.
- Chưa có runtime `VBOOK_JS_COMPAT`.
- Chưa chuyển các nguồn website thật sang SourcePack.

Các phần trên là giai đoạn tiếp theo. Không action nào được cấp HTTP/browser trực tiếp trước khi capability broker và diagnostics tương ứng hoàn thành.

## Trust root

Bản foundation pin một public key P-256 dành cho SourcePack tích hợp sẵn. Private key không nằm trong project hoặc APK. Nhiều trust key cùng thuật toán được hỗ trợ để chuẩn bị cho key rotation và repository độc lập.

## Giới hạn xác nhận

Các module JVM và Android wiring đã qua compiler harness ngoại tuyến. Android SDK thật, Gradle Sync, Compose compiler, Room KAPT và cài APK vẫn chưa chạy trong môi trường tạo project.
