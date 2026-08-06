# Ma trận tương thích nguồn XPK cũ

Nguồn kiểm kê: `Nghe_20260804_sua_phan_trang_chuong_tu_truyen_v34 (1).xpk`

> Đây là phân tích tĩnh. Công cụ không thực thi Lua, DEX hoặc thư viện native.

| Tệp | Runtime cũ | Actions phát hiện | Port hiện có | Trạng thái |
|---|---|---|---|---|
| `nguon_sangtacviet_native.lua` | NATIVE | CHAPTER, DETAIL, GENRE, SEARCH, TOC | `examples/sourcepacks/sangtacviet` | PORTED_SOURCE_PRESENT |
| `nguon_truyencom_native.lua` | NATIVE | CHAPTER, DETAIL, SEARCH | `examples/sourcepacks/truyencom` | PORTED_SOURCE_PRESENT |
| `nguon_truyencv_native.lua` | NATIVE | CHAPTER, DETAIL, SEARCH | `examples/sourcepacks/truyencv` | PORTED_SOURCE_PRESENT |
| `nguon_truyenfull_native.lua` | NATIVE | CHAPTER, DETAIL, GENRE, SEARCH | `examples/sourcepacks/truyenfull` | PORTED_SOURCE_PRESENT |
| `nguon_truyenyy_native.lua` | NATIVE | CHAPTER, DETAIL, SEARCH, TOC | `examples/sourcepacks/truyenyy` | PORTED_SOURCE_PRESENT |
| `nguon_wattpad_vbook.lua` | VBOOK | DETAIL, GENRE, HOME, SEARCH, TOC | `examples/sourcepacks/wattpad` | PORTED_SOURCE_PRESENT |
| `nguon_wikidich_native.lua` | NATIVE | CHAPTER, DETAIL, SEARCH | `examples/sourcepacks/wikidich` | PORTED_SOURCE_PRESENT |

## Ghi chú theo nguồn

### nguon_sangtacviet_native.lua

- Capability phát hiện: cookies, storage. 
- Đã có SourcePack tương ứng; chỉ bổ sung capability còn thiếu khi fixture chứng minh cần thiết.

### nguon_truyencom_native.lua

- Capability phát hiện: không có dấu hiệu đặc biệt. 
- Đã có SourcePack tương ứng; chỉ bổ sung capability còn thiếu khi fixture chứng minh cần thiết.

### nguon_truyencv_native.lua

- Capability phát hiện: storage. 
- Đã có SourcePack tương ứng; chỉ bổ sung capability còn thiếu khi fixture chứng minh cần thiết.

### nguon_truyenfull_native.lua

- Capability phát hiện: không có dấu hiệu đặc biệt. 
- Đã có SourcePack tương ứng; chỉ bổ sung capability còn thiếu khi fixture chứng minh cần thiết.

### nguon_truyenyy_native.lua

- Capability phát hiện: storage. 
- Đã có SourcePack tương ứng; chỉ bổ sung capability còn thiếu khi fixture chứng minh cần thiết.

### nguon_wattpad_vbook.lua

- Capability phát hiện: không có dấu hiệu đặc biệt. 
- Đã có SourcePack tương ứng; chỉ bổ sung capability còn thiếu khi fixture chứng minh cần thiết.

### nguon_wikidich_native.lua

- Capability phát hiện: không có dấu hiệu đặc biệt. 
- Đã có SourcePack tương ứng; chỉ bổ sung capability còn thiếu khi fixture chứng minh cần thiết.

## Quy tắc port

1. Giữ nguyên SourcePack đã có nếu fixture và gate vẫn PASS.
2. Không nhúng Lua/Dex/native bridge cũ vào ứng dụng mới.
3. Chỉ bật capability mới sau khi có fixture đầu vào, đầu ra mong đợi và giới hạn tài nguyên.
4. Khác biệt với XPK phải được ghi là thiếu, khác biệt chủ ý hoặc cải tiến.
