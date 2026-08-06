# Source Repository demo

`index.unsigned.json` là index chưa ký. Private key phải nằm ngoài project và ngoài gói bàn giao.

Tạo index đã ký bằng P-256:

```bash
python scripts/sourcepack/build_source_repository.py \
  examples/source-repository-demo/index.unsigned.json \
  --private-key /secure/repository-private.pem \
  --output /tmp/index.json
```

Ứng dụng chỉ chấp nhận index khi public key tương ứng đã nằm trong trust registry. Bản 1.3.0 hiện chỉ có trust root được pin sẵn của ứng dụng; giao diện nhập và luân chuyển khóa bên thứ ba chưa được triển khai.

Trước khi phát hành, thay URL, SHA-256, kích thước package, thời gian sinh/hết hạn và `signerKeyId` bằng dữ liệu thật.
