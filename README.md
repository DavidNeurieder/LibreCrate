# DocWallet

Encrypted document vault for Android — stores, views, organizes, and searches PDFs, EPUBs, PKPass files, comic archives (CBZ/CBR), images, and personal notes. All documents are encrypted at rest with optional password protection and zero network access.

## Features

- **Six document types**: PDF, EPUB, PKPass, CBZ/CBR, Images, Markdown notes
- **Encryption at rest**: AES-256-GCM per-file encryption; master key wrapped via Argon2id + AES-256-KW
- **Optional password**: Even with the phone unlocked, content can't be read without the password
- **No network**: Zero internet permission — your documents never leave the device
- **Library view**: Grid/list views, favorites, recents, search
- **Collections & Tags**: Organize documents your way
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
./gradlew testDebugUnitTest
```

86 unit tests covering encryption, database, search, view models, and type mapping (JUnit 4 + MockK + Robolectric + Turbine).

## License

GPL-3.0-only. See [COPYING](COPYING) for details.
