# SourcePack network demo

Ví dụ này chứng minh `fetch` không cần mạng thật để vượt fixture. `fixtures/search/gio.http.json` phát lại response đúng theo `GET + URL`.

Đóng gói bằng private key nằm ngoài repository:

```bash
python scripts/sourcepack/build_source_pack.py examples/sourcepack-network-demo \
  --private-key /secure/source-private.pem \
  --output /tmp/network-demo.ntsource
```

URL `api.example.org` chỉ là địa chỉ minh họa. Khi chạy live, broker vẫn áp dụng origin, public DNS, quota, redirect và timeout từ manifest.
