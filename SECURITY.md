# Security Policy

## Reporting a vulnerability

Please **do not open a public issue** for security bugs. Report them privately:

- Open a private disclosure through
  **[GitHub Security Advisories](https://github.com/<org>/LibreCrate/security/advisories/new)**
  (preferred), or
- Email the maintainers with full reproduction details.

Include, when available: affected version(s), a minimal reproducer (crafted
backup or `params.toml` is ideal, but not required), and the impact you
observed. You will receive an acknowledgement within 3 business days and a
remediation target.

We operate a 90-day disclosure window for confirmed, reproducible
vulnerabilities in supported releases, coordinated publicly after a fix ships.

## Supported versions

| Version | Supported          |
| ------- | ------------------ |
| Latest release | ✅ Security fixes |
| Older releases | ❌ Upgrade to latest |

## Security model

LibreCrate stores an encrypted vault: a `LIBCRATE_VAULT` container holding an
AES-256-GCM-encrypted ZIP (`v2`, the current format) with the Argon2id-derived
key, plus an encrypted SQLCipher database per vault directory. The following
controls are enforced in `vault-native` core and apply to every entry point
that consumes untrusted data (backup import, `params.toml`, FFI calls):

| Area | Control |
| ---- | ------- |
| KDF parameters | Absolute ceiling (`KdfPolicy`): 64 MiB memory / 10 iterations / 4 parallelism. Enforced **before** Argon2 executes on import, instance-KDF parse, and all FFI derivations. |
| Archive paths | `safe_archive_path` / `contained_join` — the only sanctioned path entry points. Reject `..`, `.`, absolute paths, drive letters, backslashes, `//`, and any dot-segment, deterministically across OSes. |
| Resource limits | `BackupLimits` (4 GiB package, 8 GiB total decompressed, 100 k entries, 4 GiB/entry, 256 KiB manifest, 1 M documents) bound every allocation driven by backup bytes. |
| Envelope integrity | v2 backups authenticate the JSON manifest (KDF params, salt, counts) as GCM AAD. Tampering with the header or payload fails authentication. Document counts are cross-checked against the extracted archive. |
| Backward compatibility | v1 backups remain importable only on the hardened, validated path. Unknown container versions are rejected before any crypto work. |
| Key material | Password-derived keys and master keys are zeroized on drop (`zeroize`); FFI-transferred key material is zeroized by callers. |
| Vault lock lifecycle | Locking closes the SQLCipher handle and zeroizes the session key; operations on a locked vault fail closed. |
| Document parsing | Thumbnail generation caps source size, image dimensions, archive metadata/image sizes and scanned entry counts to stop decompression bombs and zombie archives. |

## Key management notes

- The master key is wrapped with AES Key Wrap (RFC 3394) using a key derived
  from the vault password (Argon2id); the wrapped key is what `params.toml`
  persists. See `vault-native/core/src/kdf.rs`.
- In-memory key buffers are zeroized; UniFFI cannot carry zeroizing records,
  so FFI gets/returns `ByteArray`s that the Kotlin/GUI side is responsible for
  scrubbing immediately after use.
- KDF costs are deliberately bounded (see `KdfPolicy`) so a hostile manifest
  cannot force unbounded CPU or memory, while staying comfortably above the
  normal desktop (19456/2/2) and Android (16384/3/2) parameters. Measured
  costs are in `vault-native/core/benches/kdf.rs`.

## Hardened test & fuzz surface

- Regression suite: `vault-native/core/tests/security_*.rs` (KDF bounds,
  archive-path traversal, backup limits, corruption/tamper robustness).
- Fuzz targets: `fuzz/` (standalone, nightly + `cargo fuzz` only, not part of
  stable builds).
- CI runs fmt, `clippy -D warnings`, the full test suite, `cargo-audit`, and
  the Android build.

## Known limitations / design notes

- Decrypted ZIP extraction is bounded per-entry and cumulative, but the
  envelope is authenticated before extraction; streaming decryption is not
  performed (an architectural constraint of the current format).
- Thumbnail generation renders untrusted PDF/EPUB/CBZ content through MuPDF;
  the render path runs bounded by source-size and archive-entry caps but no
  sandbox.

See [`DEPENDENCIES.md`](DEPENDENCIES.md) for the third-party inventory and the
current `cargo-audit` status.