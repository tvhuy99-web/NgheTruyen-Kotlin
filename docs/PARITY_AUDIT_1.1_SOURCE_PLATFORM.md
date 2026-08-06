# Đối chiếu Source Platform 2 sau Cột mốc 2 với XPK/vBook

| Khả năng | XPK/vBook | Kotlin 1.4 | Đánh giá |
|---|---|---|---|
| Gói nguồn độc lập | Có | `.ntsource` | Đạt |
| Cài, enable, disable, update | Có | Có repository và cài tệp | Đạt |
| Multi-version và rollback | Tùy runtime | Atomic store, rollback | Kotlin tốt hơn |
| Hash/chữ ký package | Không đồng nhất | Canonical hash + trusted signature | Kotlin chặt hơn |
| Trust-key rotation | Tùy môi trường | Enrollment, revoke, signed rotation | Kotlin chặt hơn |
| Permission declaration | Có một phần | Typed capabilities + diff | Kotlin chặt hơn |
| HTTP/cookie | Có | Broker, partition, encrypted persistence | Đạt |
| Browser automation | Có | WebView broker, DOM/click/input/wait | Đạt |
| Navigation/subresource policy | Phụ thuộc host | Allowlist cho navigation, redirects, resources, SW | Kotlin chặt hơn |
| WebSocket/storage/crypto | Có | Broker có quota | Đạt |
| JavaScript vBook | Có | Rhino sandbox compatibility | Đạt cho contract đã port |
| Android/Java API từ script | Host có thể mở rộng | Bị cấm; chỉ broker RPC | Kotlin an toàn hơn |
| Self-test | Tool hoặc thủ công | Fixture bắt buộc trước activation | Kotlin chặt hơn |
| Offline replay | Có mức khác nhau | HTTP snapshot chính xác, không live fallback | Đạt |
| Selector inspector/trace | Có tool | Có trong app | Đạt |
| Nguồn tích hợp dùng runtime động | Có | 7 nguồn website + demo | Đạt |

## Kết luận

Cột mốc 2 đã đóng khoảng cách nền tảng nguồn và tiện ích ở cấp kiến trúc/mã nguồn. Ứng dụng Kotlin không phụ thuộc môi trường XPK, đồng thời thêm chữ ký, permission, budget, rollback và isolation chặt hơn. Việc còn phải làm là nghiệm thu build Android và kiểm thử website trực tiếp, vì HTML/anti-bot của bên thứ ba có thể thay đổi sau khi package được phát hành.
