# Source Repository v1

Source Repository là một JSON index được ký tách rời. Index chỉ mô tả các gói `.ntsource`; mỗi gói vẫn phải tự vượt qua chữ ký SourcePack, kiểm tra hash và fixture self-test.

## Quy trình tin cậy

```text
repository URL HTTPS
    ↓ public-only DNS, redirect HTTPS, bounded response
signed index JSON
    ↓ canonical payload + trusted public key + expiry
package metadata
    ↓ exact byte count + SHA-256
.ntsource verifier
    ↓ SourcePack signature + manifest + fixtures
permission diff
    ↓ user approval
atomic activation / rollback
```

Index hợp lệ không thể bỏ qua bước xác minh gói. Ngược lại, một `.ntsource` có chữ ký hợp lệ cũng không được coi là đúng phiên bản repository nếu `sourceId`, `version`, kích thước hoặc SHA-256 không khớp index.

## Ví dụ

```json
{
  "schemaVersion": 1,
  "repositoryId": "vn.example.repositories.main",
  "name": "Example Sources",
  "generatedAtEpochMs": 1785866400000,
  "expiresAtEpochMs": 1786471200000,
  "signerKeyId": "example-p256-v1",
  "signatureAlgorithm": "ECDSA_P256_SHA256",
  "packages": [
    {
      "sourceId": "vn.example.sources.story",
      "name": "Example Story",
      "version": "1.2.0",
      "description": "Nguồn truyện mẫu",
      "packageUrl": "https://downloads.example.vn/story-1.2.0.ntsource",
      "packageSha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
      "packageBytes": 8421,
      "minAppVersion": "1.3.0",
      "maxAppVersion": "2.0.0",
      "adult": false,
      "changelog": "Sửa mục lục và selector chương"
    }
  ],
  "signature": "BASE64_DER_ECDSA_SIGNATURE"
}
```

## Canonical payload

Chữ ký không bao gồm trường `signature`. Ứng dụng dựng lại JSON minified UTF-8 theo đúng thứ tự sau:

1. `schemaVersion`
2. `repositoryId`
3. `name`
4. `generatedAtEpochMs`
5. `expiresAtEpochMs`
6. `signerKeyId`
7. `signatureAlgorithm`
8. `packages`

Mỗi package được dựng theo thứ tự:

`sourceId`, `name`, `version`, `description`, `packageUrl`, `packageSha256`, `packageBytes`, `minAppVersion`, `maxAppVersion`, `adult`, `changelog`.

Trường tùy chọn bị thiếu tiếp tục bị thiếu trong canonical payload. Thứ tự trường trong file index đầu vào không ảnh hưởng đến chữ ký.

## Giới hạn

- Index tối đa 1 MiB.
- Tối đa 500 package.
- Mỗi package tối đa 16 MiB.
- URL index và package phải là HTTPS, không user-info hoặc fragment.
- Thời hạn index tối đa 90 ngày.
- Cho phép sai lệch đồng hồ 5 phút.
- Không chấp nhận `sourceId` trùng nhau trong cùng repository.
- Public key phải có trong trust registry của ứng dụng.

Bản 1.3.0 mới dùng trust root đã pin của ứng dụng. Giao diện nhập và luân chuyển khóa nhà phát hành độc lập nằm ở lượt tiếp theo của Cột mốc 2.

## Tạo index đã ký

Dùng builder với private key P-256 nằm ngoài project:

```bash
python scripts/sourcepack/build_source_repository.py \
  examples/source-repository-demo/index.unsigned.json \
  --private-key /secure/repository-private.pem \
  --output /tmp/index.json
```

Ví dụ đầy đủ: [`examples/source-repository-demo`](../examples/source-repository-demo/README.md).
