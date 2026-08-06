# SourcePack mẫu

Đây là payload chưa ký của gói nguồn mẫu tích hợp trong ứng dụng.

Đóng gói bằng khóa P-256 nằm ngoài repository:

```bash
python scripts/sourcepack/build_source_pack.py examples/sourcepack-demo \
  --private-key /duong-dan-ben-ngoai/source-private.pem \
  --output /tmp/demo.ntsource
```

Không commit khóa riêng tư vào project. `FILES.sha256` và `SIGNATURE.es256` được script tự tạo.
