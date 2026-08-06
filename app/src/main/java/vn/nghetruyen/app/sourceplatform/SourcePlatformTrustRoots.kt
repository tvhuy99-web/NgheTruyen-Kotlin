package vn.nghetruyen.app.sourceplatform

import vn.nghetruyen.source.packagekit.SourceSignatureAlgorithm
import vn.nghetruyen.source.packagekit.SourceTrustKey

object SourcePlatformTrustRoots {
    val BUILTIN_V1: SourceTrustKey = SourceTrustKey.fromBase64(
        keyId = "nghe-truyen-builtin-p256-v1",
        algorithm = SourceSignatureAlgorithm.ECDSA_P256_SHA256,
        base64 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEs6eNZFFhbwH7mNN7TC3UpWrhVu6NM5iSSZQX8OZN3ECbFQgpmDGH7PdKMLrg0RVh3Y65l4fGNCSU4IAJLsaIbw==",
    )

    val BUILTIN_MILESTONE2_V1: SourceTrustKey = SourceTrustKey.fromBase64(
        keyId = "nghe-truyen-m2-sources-p256-v1",
        algorithm = SourceSignatureAlgorithm.ECDSA_P256_SHA256,
        base64 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEl6QUIl55IZHiHkcRET+T9h+BnUXBOBk03xTECmhMvYuZ1AvYYygel2KYJyKCI0Xn9j4mPrE0cXDyBOkzU6w/dA==",
    )

    val BUILTIN_PRIORITY1_V2: SourceTrustKey = SourceTrustKey.fromBase64(
        keyId = "nghe-truyen-priority1-p256-v2",
        algorithm = SourceSignatureAlgorithm.ECDSA_P256_SHA256,
        base64 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAECj7hdCY9Ru4X9cenxUMctWjKaCIiK3eNiXW/eyE6Wt00Dzpd3hSsFnQMrWcDpQ67bvQ6I3dUk66N55yb0o2gRg==",
    )

    val all: List<SourceTrustKey> = listOf(
        BUILTIN_V1,
        BUILTIN_MILESTONE2_V1,
        BUILTIN_PRIORITY1_V2,
    )
}
