# NgheTruyen Android signing

This repository uses one permanent release signing identity for installable APKs.

## Stable app identity

- Application ID: `vn.nghetruyen.app`
- Keystore format: PKCS12
- Key alias: `nghetruyen`
- Certificate SHA-256 fingerprint: `EA:FE:C8:85:B5:26:B9:BE:48:6C:DC:5D:51:98:91:03:E0:17:4E:73:F3:E7:75:E1:00:F4:F8:7C:83:03:12:D1`

Do not rotate this signing key for normal builds. Android only allows an APK to update an installed app when the package identity and signing certificate are compatible.

## GitHub Actions secrets

The installable release workflow expects these repository secrets:

- `NGHETRUYEN_RELEASE_KEYSTORE_BASE64`
- `NGHETRUYEN_RELEASE_STORE_PASSWORD`
- `NGHETRUYEN_RELEASE_KEY_ALIAS`
- `NGHETRUYEN_RELEASE_KEY_PASSWORD`

The private keystore and passwords must never be committed to this public repository.

## Build rule

User-facing APK artifacts must be built with `:app:assembleRelease` and the permanent release key above. Do not distribute `assembleDebug` artifacts as upgrade APKs because ephemeral CI debug keystores can produce incompatible signatures.

The packaging workflow derives a monotonically higher CI `versionCode` while retaining the app's source version as the local fallback.

## Recovery

Keep at least two private backups of the original `nghetruyen-release.p12` and its credentials. Losing the private signing key means future APKs cannot update installations signed by this certificate.
