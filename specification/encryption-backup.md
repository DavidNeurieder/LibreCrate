# LibreCrate Encryption & Backup Specification

Version 1.0 — July 2026

This document specifies the encryption scheme and backup file format used by
LibreCrate. Any programming language can implement import, export, and
password-based key derivation by following the byte layouts, algorithms, and
merge semantics described below.

Device-specific features (Android Keystore, biometric unlock) are noted where
they exist on disk but are not specified in detail — they are not portable and
not required for a CLI or cross-platform reimplementation.

---

## 1. Overview

**Root secret:** a 256-bit *masterKey* that protects the SQLCipher database and
all per-file encrypted blobs.

**Password path (portable):** the user's password is stretched through Argon2id
to produce a *userKey*, which AES-wraps the *masterKey* and stores the result
on disk. To unlock, the procedure is reversed: Argon2id(password) → userKey →
AESUnwrap → masterKey.

**Device-key path (non-portable, noted only for awareness):** a random
*deviceKey* is encrypted via Android Keystore (AES-GCM). The *deviceKey*
AES-wraps the same *masterKey*, enabling password-less unlock on the same
device. On disk, `device_wrapped_key` and `encrypted_device_key` files appear
alongside the password-based files. A CLI reimplementation may ignore these
files; they are left in place during restore (Branch B) for the Android app
to continue working.

**Backup file:** a `.vault` container bundles the keyfiles, the SQLCipher
database, and all document blobs into a single encrypted file. The backup is
secured with a *vault password* (typically the same as the user's unlock
password). The KDF parameters used to derive the wrapping key are stored in a
JSON manifest in the clear, so the file can be decrypted at any time with the
correct password regardless of future parameter changes.

---

## 2. Cryptographic Primitives

| Primitive | Specification |
|---|---|
| Symmetric cipher | AES-256 (32-byte keys) |
| Authenticated encryption | AES-256-GCM, 128-bit auth tag (16 bytes), 12-byte IV, `AES/GCM/NoPadding` semantics; ciphertext output includes the tag appended |
| Key wrapping | RFC 3394 (`AESWrap`), wraps a 256-bit key → 40 bytes (8× 64-bit blocks) |
| Password-based KDF | Argon2id, version `0x13` (1.3) |
| Secure random | CSPRNG for all salts, IVs, and key generation |

### Byte layout conventions

- Multi-byte integers: **big-endian** (network byte order).
- Base64: **standard alphabet, no padding** (RFC 4648 §4 without `=`).
  `manifest.salt` is the only base64 field in the format.
  > **Interop note:** Decoders that require padding (e.g. Python's
  > `base64.b64decode`, Go's `base64.StdEncoding`) will reject unpadded
  > input. Fix: append `"===="` padding or use a no-padding variant
  > (Go: `base64.RawStdEncoding`; Python:
  > `base64.b64decode(s + "=" * (-len(s) % 4))`).
- Strings: UTF-8 when stored as bytes.

---

## 3. Key Hierarchy

```
                    ┌──────────────────┐
                    │    masterKey     │  32 bytes, random
                    │   (root secret)  │
                    └───────┬──────────┘
                            │
              ┌─────────────┼─────────────┐
              │             │             │
              ▼             ▼             ▼
        AESWrap(userKey)  AESWrap(deviceKey)  ──→  SQLCipher DB
              │             │                      + file blobs
              ▼             ▼
     wrapped_master_key   device_wrapped_key
```

### 3.1 masterKey

- 32 cryptographically random bytes.
- Generated once during first-launch initialization.
- Never stored in plaintext on disk.
- Used directly as the passphrase for the SQLCipher database and as the
  file-encryption key for document blobs (see §6).

### 3.2 userKey (password-derived)

- `userKey = Argon2id(password_utf8_bytes, salt, params)` → 32 bytes.
- Parameters defined in §4.
- Only used transiently to wrap/unwrap the *masterKey* via RFC 3394.
- Both the password bytes and the derived *userKey* MUST be zeroed after use.

### 3.3 wrapped_master_key

- File: `wrapped_master_key` (40 bytes) in the encryption directory.
- Contents: `AESWrap(userKey, masterKey)` per RFC 3394.

### 3.4 salt

- File: `salt` (16 bytes) in the encryption directory.
- Contents: the Argon2id salt used to derive the *userKey* that wraps the
  *masterKey*.

### 3.5 Unlock procedure (password path)

1. Read `salt` (16 bytes) from the encryption directory.
2. Read `wrapped_master_key` (40 bytes) from the encryption directory.
3. `userKey = Argon2id(password, salt)` using the parameters in §4.
4. `masterKey = AESUnwrap(wrapped_master_key, userKey)` (RFC 3394).
5. Zero `userKey`.
6. Use `masterKey` to open the SQLCipher database or decrypt files.

### 3.6 Device-key files (non-portable, awareness only)

| File | Size | Contents |
|---|---|---|
| `device_wrapped_key` | 40 B | `AESWrap(deviceKey, masterKey)` — *masterKey* wrapped by the device key |
| `encrypted_device_key` | 60 B | `IV(12) ‖ AES-GCM(deviceKey)` — device key encrypted via Android Keystore |
| `device_key` | 32 B | Legacy; raw device key in plaintext (migrated → deleted) |

A reimplementation may safely ignore these files. When restoring a backup
(Branch B, §11.2), they are NOT overwritten; the Android app recreates them
through `setupDeviceKeyForDailyUnlock()`, which is out of scope for this spec.

---

## 4. Argon2id Parameters

These are the **default** parameters used when deriving a *userKey* from a
password. On backup import (§7), parameters MUST be read from the manifest,
not hardcoded.

| Parameter | Value |
|---|---|
| Variant | `Argon2_id` |
| Version | `0x13` (1.3) |
| Memory cost (`m`) | 19 456 KiB |
| Iterations (`t`) | 2 |
| Parallelism (`p`) | 2 |
| Hash length | 32 bytes |
| Salt length | 16 bytes, freshly generated per-password-set via CSPRNG |

### Recommended library mapping

- **Java/Kotlin:** Bouncy Castle `Argon2BytesGenerator` with
  `Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)`.
- **Rust:** `argon2` crate with `Argon2::new(Algorithm::Argon2id, Version::V0x13, Params::new(memory_cost, 2, 2, Some(32)))`.
- **Python:** `argon2-cffi` with `Type.ID`, `memory_cost=19456`,
  `time_cost=2`, `parallelism=2`, `hash_len=32`.
- **Go:** `golang.org/x/crypto/argon2.IDKey(password, salt, 2, 19456, 2, 32)`.

---

## 5. On-Disk Keyfile Layout

The encryption directory is `{filesDir}/encryption/`. It contains:

| File | Size (bytes) | Present for | Purpose |
|---|---|---|---|
| `salt` | 16 | password path | Argon2id salt |
| `wrapped_master_key` | 40 | password path | AESWrap(userKey, masterKey) |
| `encrypted_device_key` | 60 | device-key path | IV(12) ‖ AES-GCM(deviceKey) |
| `device_wrapped_key` | 40 | device-key path | AESWrap(deviceKey, masterKey) |
| `device_key` | 32 | legacy (migrated) | raw device key in cleartext |

A CLI that only supports password unlock reads `salt` + `wrapped_master_key`
and ignores the device-key files. During a full backup restore (Branch B),
the device-key files are written from the backup ZIP's `keys/` entries
alongside the password files.

---

## 6. SQLCipher Database

### 6.1 Passphrase

The SQLCipher database passphrase is the **raw 32-byte masterKey**
(not hex-encoded, not a string). In JNI terms:

```
sqlite3_key(db, masterKey, 32);
```

The Android app opens the database via
`SupportFactory(passphrase, null, false)` — the third argument `false`
means the passphrase is **not** automatically zeroed from memory after use.
The app offsets this by zeroing its `cachedMasterKey` on lock (see §13).
A CLI binding should either opt into in-memory wiping or zero the
passphrase explicitly after each database operation.

### 6.2 Default cipher settings (SQLCipher 4.5.4)

| Setting | Default value |
|---|---|
| Cipher | `aes-256-cbc` |
| HMAC algorithm | `hmac-sha512` |
| KDF algorithm | PBKDF2-HMAC-SHA512 |
| KDF iterations | 256 000 |
| Page size | 4096 bytes |

### 6.3 Important note for CLI implementers

If a CLI opens the SQLCipher database directly, it MUST match the above
settings exactly. If the CLI uses a different version of SQLCipher (or a
different library like `sqlcipher` in Python), the implementer should verify
compatibility by:

1. Opening the database with the raw *masterKey* as passphrase.
2. Setting `PRAGMA cipher_compatibility = 3` or 4 as needed.
3. Explicitly setting `PRAGMA cipher_kdf_algorithm = sha512`,
   `PRAGMA cipher_hmac_algorithm = sha512`,
   `PRAGMA kdf_iter = 256000`,
   `PRAGMA cipher_page_size = 4096`,
   `PRAGMA cipher_default_kdf_iter = 256000`.

If the CLI only transports the database file (export/import without reading
its contents), no SQLCipher implementation is needed — the bytes are opaque.

For example, an older DB created with SQLCipher 3.x defaults would use
`hmac-sha1` and `kdf_iter=64000`. During a legacy merge (Branch C), the
current app key is used to open the backup DB, and the merger reads rows
via the `SqlHandle` abstraction — the SQLCipher library handles the cipher
negotiation from the file header. A CLI should use the same approach: for
Branch C, open the backup DB with the session *masterKey*; the SQLCipher
library (or binding) will detect the cipher parameters from the file header.

---

## 7. Vault Backup Container (.vault)

A backup file is a single binary blob with the following structure:

```
┌──────────────────────────────────────┐
│ MAGIC  "LIBRECRATE_VAULT"  16 bytes │  ASCII, no NUL terminator
├──────────────────────────────────────┤
│ VERSION            uint32 BE          │  currently 1
├──────────────────────────────────────┤
│ MANIFEST_LEN       uint32 BE          │  byte length of the JSON manifest
├──────────────────────────────────────┤
│ MANIFEST           UTF-8 JSON         │  MANIFEST_LEN bytes
├──────────────────────────────────────┤
│ ENCRYPTED_BLOB                        │  IV(12) ‖ AES-256-GCM(ciphertext ┴ tag)
└──────────────────────────────────────┘
```

### 7.1 Field details

| Field | Offset | Size | Value |
|---|---|---|---|
| MAGIC | 0 | 16 B | `4C 49 42 52 45 43 52 41 54 45 5F 56 41 55 4C 54` ("LIBRECRATE_VAULT") |
| VERSION | 16 | 4 B | `00 00 00 01` (version 1, big-endian) |
| MANIFEST_LEN | 20 | 4 B | big-endian uint32 length of the manifest JSON |
| MANIFEST | 24 | variable | UTF-8 encoded JSON (see §7.2) |
| ENCRYPTED_BLOB | 24+ML | variable | see §7.3 |

### 7.2 Manifest JSON

The manifest is serialized with `kotlinx.serialization` using
`ignoreUnknownKeys = true`. The field set is:

```json
{
  "version": 1,
  "kdf": "argon2id",
  "salt": "<base64-encoded 16-byte salt, no padding>",
  "argon2Memory": 19456,
  "argon2Iterations": 2,
  "argon2Parallelism": 2,
  "createdAt": 1720000000000,
  "documentCount": 0
}
```

| Field | Type | Required on read | Notes |
|---|---|---|---|
| `version` | int | yes | Must be 1. |
| `kdf` | string | yes | `"argon2id"`. |
| `salt` | string | yes | Base64 (standard, no padding) of the 16-byte Argon2id salt. |
| `argon2Memory` | int | yes | Argon2id memory cost in KiB. |
| `argon2Iterations` | int | yes | Argon2id time cost. |
| `argon2Parallelism` | int | yes | Argon2id parallelism. |
| `createdAt` | int | yes | Epoch millis of export time (defaults to current time); ignored on import. |
| `documentCount` | int | no | Number of document entries in the ZIP; informational only. |

**KDF parameters MUST be read from the manifest, not hardcoded**, so that
parameter changes in future versions remain backward compatible.

> **⚠ Important: `manifest.salt` ≠ `keys/salt`.** These are two distinct salts
> with different roles. The `manifest.salt` is a **freshly generated** salt used
> to derive the key that encrypts the ZIP blob (the vault container). The on-disk
> salt (`keys/salt` inside the ZIP) is the Argon2id salt used to unwrap
> `wrapped_master_key` during restore (§11.1). They are generated independently
> and carry different values. See also §8 and §11.1.

### 7.3 ENCRYPTED_BLOB

The blob is produced by:

```
derivedKey = Argon2id(vault_password, decode_base64(manifest.salt),
                       manifest.argon2Memory, manifest.argon2Iterations,
                       manifest.argon2Parallelism, hash_len=32)
(iv, ciphertext) = AES-256-GCM-encrypt(plain_zip, derivedKey, iv_len=12)
encrypted_blob = iv ‖ ciphertext
```

- `iv` = 12 bytes (random, fresh per export).
- `ciphertext` = AES-256-GCM output, which includes the 16-byte auth tag
  at the end (per JCE `Cipher.doFinal` semantics).
- Total `encrypted_blob` length = 12 + `len(plain_zip)` + 16.

### 7.4 Decryption procedure

```
derivedKey = Argon2id(password, salt_from_manifest, manifest_params)
iv = encrypted_blob[0:12]
ciphertext = encrypted_blob[12:]
plain_zip = AES-256-GCM-decrypt(ciphertext, derivedKey, iv, tag_len=128)
```

### 7.5 Legacy format (import fallback)

Backups created before the vault container format are also accepted during
import. The legacy format has **no magic, no version, no manifest**:

```
salt(16) ‖ iv(12) ‖ AES-256-GCM(plain_zip, derivedKey)
```

- `derivedKey = Argon2id(password, salt, default KDF params)`.
- Default KDF params are the hardcoded values in §4 (not read from any
  manifest).

Writers MUST emit only the new vault format. Readers MUST support both and
distinguish them by attempting the new format first (check magic bytes), then
falling back to the legacy format.

---

## 8. Inner ZIP Layout

The decrypted `plain_zip` from §7.3/§7.4 is a standard ZIP archive (may use
DEFLATE or STORE). Entry names and contents:

| Entry name | Content | Required |
|---|---|---|
| `keys/wrapped_master_key` | 40 bytes: AESWrap(userKey, masterKey) | If password path |
| `keys/salt` | 16 bytes: Argon2id salt (distinct from `manifest.salt` — see §7.2) | If password path |
| `keys/device_wrapped_key` | 40 bytes | If device-key path |
| `keys/encrypted_device_key` | 60 bytes: IV(12) ‖ AES-GCM(deviceKey) | If device-key path |
| `db/librecrate.db` | SQLCipher database file | May be absent (keys-only backup) |
| `files/<name>` | Document blobs (see §8.1) | Zero or more |

Additional `keys/` entries may appear for future key types. Unknown entries
in `keys/` should be preserved and written back.

The importer's legacy fallback also accepts a flat layout where entries
without a prefix (e.g. `document.pdf`) are treated as `files/<name>` and
`librecrate.db` at the root is treated as `db/librecrate.db`.

### 8.1 File blob format

Each `files/<name>` entry is stored as:

```
IV(12) ‖ AES-256-GCM-ciphertext(plaintext, masterKey, IV)
```

- Key = the **masterKey** (the root secret from §3.1), NOT the *userKey*.
  During restore this masterKey is recovered as `backupMasterKey` via §11.1
  (or may be the session key in Branch A with re-encryption).
- IV = 12 random bytes, unique per file.
- Ciphertext includes the 16-byte GCM tag appended.

Files and thumbnail images use the same encryption but reside at different
ZIP paths (`files/<name>` for documents, and the thumbnail path is stored
in the `documents.thumbnail_path` database column).

---

## 9. Export Procedure

Given a vault password and a collection of data to back up:

1. Generate a fresh 16-byte salt (CSPRNG).
2. `containerKey = Argon2id(password, salt)` with default KDF params (§4).
3. Build the ZIP entries map:
   - `keys/wrapped_master_key` = read from on-disk encryption directory.
   - `keys/salt` = read from on-disk encryption directory.
   - (device-key file entries are also included if present on disk).
   - `db/librecrate.db` = read the SQLCipher database file after running
     `PRAGMA wal_checkpoint(TRUNCATE)` to flush the WAL.
   - `files/<name>` = read each file blob from the files directory.
4. Serialize the entry map into a ZIP blob.
5. Generate a fresh 12-byte IV.
6. `encrypted_blob = IV ‖ AES-256-GCM(ZIP_blob, containerKey, IV)`.
7. Build the manifest JSON with the salt (base64), KDF params, and metadata.
8. Assemble the vault container:
   ```
   MAGIC(16) ‖ VERSION(4) ‖ MANIFEST_LEN(4) ‖ MANIFEST_JSON ‖ encrypted_blob
   ```
9. Zero `containerKey` and password bytes.

> **Note:** This `containerKey` is distinct from the `userKey` used for key
> unwrap in §3.2 and §11.1. The container key is derived from the fresh
> `manifest.salt`, while the unwrap key is derived from the on-disk
> `keys/salt`. They carry different values.

---

## 10. Import Procedure

Given a vault file (bytes) and a vault password:

1. Read MAGIC (16 bytes). If it matches `LIBRECRATE_VAULT`, proceed with
   the vault format (§10.1). Otherwise, attempt the legacy format (§10.2).
2. On failure of both, report an error.

### 10.1 Vault format import

1. Parse VERSION (4 bytes, must be 1).
2. Parse MANIFEST_LEN (4 bytes), read MANIFEST JSON.
3. Extract and base64-decode `salt`. Read `argon2Memory`, `argon2Iterations`,
   `argon2Parallelism` from the manifest.
4. `containerKey = Argon2id(password, salt, manifest_params)`.
5. Read `encrypted_blob`. Split into `IV(12) ‖ ciphertext`.
6. `plain_zip = AES-256-GCM-decrypt(ciphertext, containerKey, IV)`.
7. Unzip entries into `keys/*`, `db/librecrate.db`, `files/*`.
8. Zero `containerKey` and password bytes.

### 10.2 Legacy format import

1. Split bytes: `salt = bytes[0:16]`, `IV = bytes[16:28]`,
   `ciphertext = bytes[28:]`.
2. `containerKey = Argon2id(password, salt)` using **default** KDF params (§4).
3. `plain_zip = AES-256-GCM-decrypt(ciphertext, containerKey, IV)`.
4. Unzip as above.
5. Zero `containerKey` and password bytes.

### 10.3 Restore and merge (see §11)

After decryption and unzipping, the contents are applied to the local vault
according to the restore branches in §11.

---

## 11. Restore & Merge Semantics

After decrypting the backup and producing a `BackupContents` (keys map + DB
bytes + files map), the restore procedure applies the backup to the local
vault. The behavior depends on whether a local database already exists and
whether the backup contains key material for a known password.

### 11.1 Precondition: derive backup master key

```
if contents.keys contains "wrapped_master_key" AND "salt":
    salt = contents.keys["salt"]
    wrapped = contents.keys["wrapped_master_key"]
    userKey = Argon2id(vault_password, salt, default KDF params)
    backupMasterKey = AESUnwrap(wrapped, userKey)
else:
    backupMasterKey = null
```

- If the backup lacks either key file, `backupMasterKey = null`.
- The backup may have been created with a different KDF params version;
  the default KDF params (§4) are used here because the *keys* were wrapped
  with those params when the password was set (they are not stored in the
  vault manifest). The manifest's KDF params apply only to the vault
  container itself.
- The `salt` here is the **`keys/salt` from inside the ZIP** (the on-disk
  salt), **not** `manifest.salt`. These are two distinct values — see §7.2.

### 11.2 Restore branches

| Condition | Branch | Behavior |
|---|---|---|
| `currentDb != null && backupMasterKey != null` | **A** | Merge backup into existing DB; re-encrypt files if local key available. Key files NOT replaced. |
| `currentDb == null && backupMasterKey != null` | **B** | Fresh install: replace key files, replace DB, delete WAL/SHM. |
| `currentDb != null && backupMasterKey == null` | **C** | Legacy: merge using current session key to open backup DB. |
| `currentDb == null && backupMasterKey == null` | **error** | Cannot restore; no master key available. |

#### Branch A — Merge into existing vault

1. Open the backup DB with `backupMasterKey` (via SQLCipher).
2. Obtain the local session master key (`localKey`).
3. If `localKey != null`:
   - Use `mergeWithFileReencryption(backupDb, currentDb, files, backupMasterKey, localKey)`:
     - For each document row in the backup DB that has an `encryption_iv`:
       - Determine the relative filename from `file_path` (substring after
         the last `/`).
       - Look up the corresponding blob from the decrypted `files` map.
       - If the target file already exists on disk: update the document row's
         `file_path` and `encryption_iv` to point to the existing file (no
         crypto operations).
       - Otherwise: split the blob into `IV(12) ‖ ciphertext`; decrypt with
         `backupMasterKey`; re-encrypt with `localKey` with a fresh IV; write
         `new_IV ‖ new_ciphertext` to disk; update the document row's
         `file_path` and `encryption_iv`.
        - Thumbnail blobs (`thumbnail_path`): if target file exists on disk,
          skip entirely (no DB update); if absent, split blob into
          `IV(12) ‖ ciphertext`, decrypt with `backupMasterKey`,
          re-encrypt with `localKey`, write to disk. No DB row is modified
          for thumbnails.
     - Crypto failures per-file are silently skipped.
   - If `localKey == null`: use `merge(backupDb, currentDb)` (plain merge,
     no file re-encryption).
4. After merge: `PRAGMA wal_checkpoint(TRUNCATE)` on the current DB; delete
   `-wal` and `-shm` files from the database directory.
5. **Key files in the encryption directory are NOT modified.**
6. All document blobs (`files/*`) are written to the files directory **only
   if the target does not already exist** (no-overwrite policy). Files that
   were already placed by step 3 (re-encrypted) will already exist and are
   skipped.

#### Branch B — Fresh install / full restore

1. Save existing key files (`wrapped_master_key`, `salt`) in memory as
   rollback copies.
2. Write `keys/wrapped_master_key` and `keys/salt` from the backup into
   the encryption directory (overwriting any existing files).
3. Verify the vault password against the just-written `wrapped_master_key`:
   `AESUnwrap(wrapped_master_key, userKey)` — if this fails (wrong password):
   restore the saved originals and abort.
4. (On Android) `setupDeviceKeyForDailyUnlock()` is called to create
   `device_wrapped_key` and `encrypted_device_key`. This step is
   **Android-specific**; a CLI may skip it or emit a warning.
5. Copy `db/librecrate.db` from the backup to the database directory
   (overwrite if exists).
6. Delete `-wal` and `-shm` files from the database directory.
7. Document blobs (`files/*`) are written to the files directory **only if
   the target does not already exist** (no-overwrite).

If password verification fails at step 3, all written key files must be
rolled back to their saved originals (or deleted if none existed) before
aborting.

#### Branch C — Legacy merge (no backup key material)

1. The backup DB was encrypted with the same *masterKey* as the current
   vault (no wrapping layer because the backup predates the key-file scheme).
2. Open the backup DB using the session `masterKey` directly.
3. `merge(backupDb, currentDb)` — plain row-level merge, no file
   re-encryption.
4. No key files are modified.

### 11.3 Row-level merge algorithm

All merge operations run in a single SQL transaction on the **current**
database.

#### Collections

```
for each collection in backup:
    if exists in current by id:
        UPDATE collections SET name=?, icon=?, sort_order=?, parent_id=? WHERE id=?
    else:
        INSERT INTO collections(id, name, icon, sort_order, parent_id) VALUES (...)
```

Columns: `id TEXT PK, name TEXT, icon TEXT, sort_order INTEGER, parent_id TEXT`.

#### Tags

```
for each tag in backup:
    INSERT OR IGNORE INTO tags(id, name, color) VALUES (?, ?, ?)
```

Columns: `id TEXT PK, name TEXT UNIQUE, color INTEGER`.

#### Documents (core decision logic)

```
for each document in backup:
    existing = query by id
    if existing == null:
        INSERT document
        added++
    else:
        contentChanged = (fileSize != existing.fileSize) OR (mimeType != existing.mimeType)
        metadataChanged = (modifiedAt > existing.modifiedAt)

        if contentChanged:
            if fileAlreadyStored(currentDb, document):
                # Same file path and size — metadata-only change
                if metadataChanged:
                    UPDATE metadata
                    updated++
                else:
                    skipped++
            else:
                # Different file content — create conflict
                UPDATE existing SET is_conflict = 1
                INSERT copy with:
                  - id = "<origId>--<first 8 chars of UUIDv4>"
                  - is_conflict = false
                  - conflict_with = "<origId>"
                conflicted++
        else:
            # Same content, maybe metadata change
            if metadataChanged:
                UPDATE metadata
                updated++
            else:
                skipped++
```

`fileAlreadyStored` checks: a row exists with the same `file_path` AND
`file_size` (not the same `id`).

Conflict copies receive a new ID: `{originalId}--{8-char-uuid-prefix}` where
the UUID is a version-4 UUID with the first 8 hex characters appended after
`--`.

#### Document tags

```
for each document_tag in backup:
    INSERT OR IGNORE INTO document_tags(document_id, tag_id) VALUES (?, ?)
```

Columns: `document_id TEXT, tag_id TEXT`, composite PK.

### 11.4 Merge result counters

| Counter | Meaning |
|---|---|
| `documentsAdded` | New documents inserted (ID not present) |
| `documentsUpdated` | Existing documents with metadata updates |
| `documentsConflicted` | `contentChanged` and different file; conflict copies inserted |
| `documentsSkipped` | No changes detected |
| `collectionsAdded` | New collections inserted |
| `tagsAdded` | New tags inserted |

---

## 12. Database Schema

For implementers who need to read or write the SQLCipher database directly.

### 12.1 `documents`

```sql
CREATE TABLE IF NOT EXISTS documents (
    id              TEXT NOT NULL PRIMARY KEY,
    title           TEXT NOT NULL,
    file_name       TEXT NOT NULL,
    mime_type       TEXT NOT NULL,
    file_path       TEXT NOT NULL,
    file_size       INTEGER NOT NULL,
    page_count      INTEGER NOT NULL,
    author          TEXT NOT NULL,
    description     TEXT NOT NULL,
    thumbnail_path  TEXT,
    imported_at     INTEGER NOT NULL,
    last_opened_at  INTEGER NOT NULL,
    modified_at     INTEGER NOT NULL DEFAULT 0,
    is_favorite     INTEGER NOT NULL DEFAULT 0,
    is_conflict     INTEGER NOT NULL DEFAULT 0,
    conflict_with   TEXT,
    collection_id   TEXT,
    encryption_iv   BLOB,
    text_content    TEXT,
    barcode_format  TEXT,
    barcode_value   TEXT,
    current_page    INTEGER NOT NULL DEFAULT 0,
    reading_position TEXT
);
```

Key columns for merge/re-encryption:
- `file_path` — absolute path to encrypted blob on disk; relative filename
  = substring after last `/`.
- `encryption_iv` — 12-byte IV used to encrypt this file's blob.
- `file_size` — size of the **plaintext** file.
- `mime_type` — used for content-changed detection.
- `modified_at` — epoch millis; used for metadata-changed detection.
- `thumbnail_path` — absolute path to thumbnail blob (same encryption scheme).
- `is_conflict` / `conflict_with` — conflict tracking.

### 12.2 `collections`

```sql
CREATE TABLE IF NOT EXISTS collections (
    id          TEXT NOT NULL PRIMARY KEY,
    name        TEXT NOT NULL,
    icon        TEXT NOT NULL,
    sort_order  INTEGER NOT NULL,
    parent_id   TEXT
);
```

### 12.3 `tags`

```sql
CREATE TABLE IF NOT EXISTS tags (
    id     TEXT NOT NULL PRIMARY KEY,
    name   TEXT NOT NULL UNIQUE,
    color  INTEGER NOT NULL
);
```

### 12.4 `document_tags`

```sql
CREATE TABLE IF NOT EXISTS document_tags (
    document_id TEXT NOT NULL,
    tag_id      TEXT NOT NULL,
    PRIMARY KEY (document_id, tag_id),
    FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE,
    FOREIGN KEY (tag_id) REFERENCES tags(id) ON DELETE CASCADE
);
```

### 12.5 `documents_fts` (FTS5 virtual table)

```sql
CREATE VIRTUAL TABLE IF NOT EXISTS documents_fts USING fts5(
    title, author, description, text_content,
    content=documents,
    content_rowid=rowid
);
```

A reimplementation may rebuild this index after a merge by:
```
INSERT INTO documents_fts(documents_fts) VALUES('rebuild');
```

---

## 13. Security Considerations

1. **Zero secrets after use.** After any cryptographic operation, the
   password, *userKey*, and any intermediate derived material MUST be
   overwritten with zeros. In languages without explicit memory management,
   use pinned/unsafe buffers where feasible.

2. **Fresh IV each time.** AES-GCM IVs must never be reused with the same
   key. Generate a fresh 12-byte IV for every encryption operation.

3. **Unique salt per password set.** The Argon2id salt is regenerated on
   every password change. Backup exports generate their own salt (stored in
   the manifest), separate from the on-disk `salt` file.

4. **Tag verification.** Do not attempt to decrypt AES-GCM ciphertexts
   without verifying the authentication tag. Decryption MUST fail if the
   tag is invalid.

5. **Argon2id parameters are portable but should be reviewed.** The defaults
   in §4 are tuned for mobile-device performance (~1–2 s verification).
   A CLI running on server-class hardware may increase `memoryCost` for
   stronger defense.

6. **Conflict copies are user-visible.** The merge algorithm creates
   conflict copies with a distinct ID format (`<id>--<uuid-prefix>`). A
   reimplementation that merges should present these to the user for
   resolution or adopt the same ID scheme for consistency.

---

## 14. Known Limitations

The following are design limitations of the current backup and encryption
scheme. CLI implementers should be aware of them.

### 14.1 Password-KDF params are not stored in the backup

The Argon2id parameters used to derive the `userKey` that wraps
`wrapped_master_key` (§3.2) are hardcoded defaults (§4) and are **not**
stored in the backup manifest (which only holds the container-KDF params).
The on-disk `wrapped_master_key` file was created with the app version's
current defaults. On restore (§11.1), the unwrap always uses the same
hardcoded defaults.

**Consequence:** if a future version of LibreCrate changes the password-KDF
defaults (e.g. increases `memoryCost` from 19456 to 65536), the new version
will write `wrapped_master_key` using the new params. An older
implementation restoring that backup will attempt to unwrap with the old
defaults (which produce a different `userKey`) and fail —
`backupMasterKey` becomes `null` and the backup cannot be restored via
password path.

**Mitigation:** a future backup format revision should embed the
password-KDF params in the manifest alongside the container-KDF params.
For now, implementations should document which version they implement.

### 14.2 Branch C requires the same masterKey

Branch C (§11.2) handles legacy backups that lack key material. It opens
the backup database using the **current session's** masterKey directly,
which only succeeds if the legacy backup was created from **the same
vault** (same password → same masterKey). Legacy backups from a different
vault cannot be restored because the backup DB cannot be opened with the
wrong key, and there is no password-based recovery path (no
`keys/wrapped_master_key` in the backup).

The new vault format (§7) resolves this by including
`keys/wrapped_master_key` and `keys/salt`, enabling password-based recovery
in Branch B or cross-vault merging in Branch A.

### 14.3 Branch B is not crash-atomic

The Branch B restore procedure (§11.2) executes these steps sequentially
without transactional guarantees:

1. Save original key files for rollback.
2. **Write new key files** (overwriting originals on disk).
3. Verify password — rollback supported if this fails.
4. Set up device key — rollback supported if this fails.
5. **Copy the database file** — no rollback described.
6. Delete WAL/SHM files.

If step 5 fails after steps 2–4 have already committed (e.g. disk full,
interrupted write), the vault is left with new key files but a missing or
partial database. The Android app partially recovers because Room's
`openOrCreateDatabase` handles truncated files, but a CLI implementation
could reach an unrecoverable state.

**Recommendation:** CLI implementations should stage the new key files and
database in a temporary directory, verify the password, then atomically
swap them into place. This ensures the vault remains consistent even if the
process is interrupted.

---

## 15. Test Vectors (Conformance)

A conformance `.vault` backup file and password can be generated to verify
implementations. The reference implementation's test
`backupUninstallReinstallImportCloseReopen` exercises:

1. Export a backup with known password, files, and DB content.
2. Wipe the local vault.
3. Import the backup (Branch B — fresh install).
4. Close and reopen the database.
5. Verify document count and file contents.

To produce a conformance vector (note: step 5 requires a SQLCipher or
sqlcipher library). Two distinct salts are used: `unwrapSalt` for key
unwrapping (§11.1) and `containerSalt` for the vault container (§7.3):

```bash
# 1. Generate unwrapSalt (16 bytes) — on-disk salt
# 2. userKey = Argon2id("test-password", unwrapSalt, default params)
# 3. Generate 32-byte masterKey
# 4. wrapped_master_key = AESWrap(userKey, masterKey)
# 5. Create minimal SQLCipher DB encrypted with masterKey
#    containing one document row
# 6. Create one file blob: IV(12) ‖ AES-GCM("hello world", masterKey, IV)
# 7. Build ZIP with keys/salt = unwrapSalt,
#    keys/wrapped_master_key, db/librecrate.db, files/test.txt
# 8. Generate containerSalt (16 bytes) — fresh for container
# 9. containerKey = Argon2id("test-password", containerSalt,
#                            default params)
# 10. Encrypt ZIP with AES-GCM(containerKey, fresh_IV)
# 11. Build manifest with salt: base64(containerSalt), argon2Memory: 19456,
#     argon2Iterations: 2, argon2Parallelism: 2
# 12. Prepend MAGIC(16), VERSION(4), MANIFEST_LEN(4), manifest JSON
```

---

## Appendix A: Byte-Level Constants

```
MAGIC         = hex: 4C 49 42 52 45 43 52 41 54 45 5F 56 41 55 4C 54
               (ASCII: "LIBRECRATE_VAULT", 16 bytes)
MASTER_KEY_LEN  = 32 bytes
SALT_LEN        = 16 bytes
IV_LEN          = 12 bytes
GCM_TAG_LEN     = 16 bytes
WRAPPED_KEY_LEN = 40 bytes
ENCRYPTED_DEVICE_KEY_LEN = 60 bytes  (IV 12 + ciphertext 48; 48 = 32 data + 16 tag)
MANIFEST_JSON_FIELDS = version, kdf, salt, argon2Memory, argon2Iterations,
                       argon2Parallelism, createdAt, documentCount
```

## Appendix B: File Paths (Android layout for reference)

```
{filesDir}/
  encryption/
    salt                     (16 bytes)
    wrapped_master_key       (40 bytes)
    device_wrapped_key       (40 bytes)
    encrypted_device_key     (60 bytes)
    device_key               (32 bytes, legacy)
  files/
    <document_blobs>         (IV‖AES-GCM, named by hash or UUID)
databases/
  librecrate.db              (SQLCipher)
  librecrate.db-wal          (WAL, deleted after checkpoint during export/restore)
  librecrate.db-shm          (SHM, deleted after checkpoint during export/restore)
```

A CLI reimplementation may use any directory layout; the above is provided
to clarify the Android app's conventions.
