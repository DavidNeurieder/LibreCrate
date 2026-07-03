# DocWallet

**Version 0.2.0**

Encrypted document vault for Android — stores, views, organizes, and searches PDFs, EPUBs, PKPass files, comic archives (CBZ/CBR), images, and personal notes. All documents are encrypted at rest with optional password protection and zero network access.

## Features

- **Six document types**: PDF, EPUB, PKPass, CBZ/CBR, Images, Markdown notes
- **Encryption at rest**: AES-256-GCM per-file encryption; master key wrapped via Argon2id + AES-256-KW
- **Optional password**: Even with the phone unlocked, content can't be read without the password
- **No network**: Zero internet permission — your documents never leave the device
- **Library view**: Search, sort, filter by type, favorites with reading progress indicators
- **Reading position**: Remembers last page for PDFs and comics, last location for EPUBs; shows progress on document cards
- **Import**: Share intents, SAF file picker, bulk import
- **Backup**: Single encrypted `.docwallet-backup` file via SAF
- **F-Droid only**: No Google Play Services, Firebase, Crashlytics, or AdMob

## Security

| Layer | Mechanism |
|-------|-----------|
| Key derivation | Argon2id (19 MiB memory, 2 iterations, 2 parallelism) |
| Key wrapping | AES-256 Key Wrap (RFC 3394) |
| File encryption | AES-256-GCM (12-byte IV, 128-bit tag) |
| Password mode | Master key wrapped with password-derived key; device key deleted |
| Device-key mode | Master key wrapped with per-device AES key (no password) |
| Lock | Clears in-memory master key; requires password re-entry |
| Backup | Encrypted Zip bundle with wrapped master key + DB + files |

## Building

```sh
git clone https://github.com/anomalyco/docwallet-android
cd docwallet-android
./gradlew assembleDebug
```

APK at `app/build/outputs/apk/debug/app-debug.apk`.

Requires Android SDK 36 (`compileSdk`). Set `ANDROID_HOME` or create `local.properties`:

```
sdk.dir=/path/to/Android/Sdk
```

## Testing

```sh
# Unit tests (JUnit 4 + MockK + Robolectric + Turbine)
./gradlew testDebugUnitTest

# Instrumented tests (AndroidJUnit4 + Compose Test)
./gradlew connectedDebugAndroidTest
```

- 119+ unit tests
- 46+ instrumented tests (API 15+)

## AllowedAPKSigningKeys to verify Releases:

SHA-256: 11f860ee7ac19b8d992a52bf114a491f9b8b598091b7a5e94ce775b50e6e69fa

## License

GPL-3.0-only. See [LICENSE](LICENSE) for details.
