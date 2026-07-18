# LibreCrate Encryption & Backup Spec — Review Issues

This document captures findings from a cryptographic and functional review of
`encryption-backup.md`. Issues are ordered by severity. Each includes the
section(s) affected, the discrepancy, and a recommended fix.

---

## Critical — will break a CLI implementation

### C1. MAGIC string length wrong (16 bytes, not 15) — **FIXED**

**Sections:** §7, §7.1 table, §9 step 8, §10.1 step 1, Appendix A

**Issue:** The spec originally claimed MAGIC was 15 bytes and listed offsets
0/15/19/23. `"LIBRECRATE_VAULT"` is 16 characters. This shifted every
container field offset by one byte. A reader following the old offsets would
slice the magic incorrectly and fail to parse any backup file.

**Status:** Fixed in current revision. Offsets now correct at
0/16/20/24+ML.

---

## Must-fix — will cause implementer bugs if not addressed

### M1. `userKey` name collision — two different keys share one name

**Sections:** §3.2, §9 (step 2/6/9), §11.1

**Issue:** The variable name `userKey` is used for two distinct 32-byte keys
that happen to be derived with the same password but **different salts**,
producing different values.

| Where | Derivation | Role | Salt source |
|---|---|---|---|
| §3.2, §11.1 | `Argon2id(password, salt)` | unwrap `wrapped_master_key` | on-disk `keys/salt` |
| §9 step 2 | `Argon2id(password, salt)` | encrypt outer ZIP blob | `manifest.salt` (fresh per export) |

An implementer who reads "userKey" in §9 then skips §11.1's re-derivation
and reuses the cached container key for AESUnwrap will get a 40-byte
ciphertext instead of a key, or silent failure on the wrong unwrap result.

**Fix applied:** §9 now uses `containerKey` for the container encryption
key, with a note explicitly distinguishing it from `userKey`. §10.1 (vault
format import) and §10.2 (legacy import) also renamed to `containerKey`.
The test vector in §15 now uses two distinct salts (`unwrapSalt` +
`containerSalt`) to exercise the separation. The `userKey` name in §3.2
and §11.1 is now unambiguous — it refers only to the on-disk-salt-derived
unwrap key.

### M2. Base64 no-padding breaks some decoders

**Sections:** §2, §7.2

**Issue:** The spec says "standard alphabet, no padding" — this is what
Java's `Base64.getEncoder()` produces. However, several languages' base64
decoders **reject** unpadded input by default:

- **Python:** `base64.b64decode(s)` raises `binascii.Error` on unpadded.
  Fix: `base64.b64decode(s + "=" * (-len(s) % 4))`.
- **Rust:** `base64::Engine::decode()` requires the `PAD` config or explicit
  `NO_PAD`.
- **Go:** `base64.StdEncoding.DecodeString` returns an error on unpadded.
  Use `base64.RawStdEncoding.DecodeString` instead.

Since only `manifest.salt` is base64-encoded, a CLI implementer in one of
these languages will hit this on the very first field they try to decode.

**Fix applied:** Warning added in §2 under Byte layout conventions, with
language-specific workarounds.

---

## Should-fix — document limitations and gaps

### L1. Password-KDF params not stored in the backup

**Sections:** §11.1

**Issue:** The *container* KDF params (argon2Memory, etc.) are stored in the
manifest, so future changes to container encryption remain backward
compatible. However, the **password** KDF params used to wrap
`wrapped_master_key` (§11.1) are NOT stored anywhere in the backup — the
unwrap always uses the hardcoded defaults from §4. If a future version of
the app increases memoryCost or iterations, the on-disk `wrapped_master_key`
file will be produced with the new params, and old backups will fail to
unwrap (`backupMasterKey → null` → Branch C falls through to error).

This is a known design limitation: the password-KDF params are implicit in
the app version that created the key files.

**Fix applied:** §14 (Known Limitations) added after §13, documenting this
as §14.1 with the exact text above.

### L2. Branch C requires same masterKey (no password path)

**Sections:** §11.2 Branch C

**Issue:** Branch C (legacy backup without key material) opens the backup DB
using the session `masterKey` directly. This only succeeds when the legacy
backup comes from the **same vault** (same password → same `masterKey`). If
a user attempts to restore a legacy backup from a different vault, the
backup DB won't open with the current session key, and the merge silently
fails (the `SqlCipherOpener` throws, caught by `DatabaseMerger`, returns
false — but the error is only logged). The new vault format avoids this by
packaging `keys/salt` and `wrapped_master_key`, enabling password-based
recovery in Branch B or key-derivation in Branch A.

**Fix applied:** §14.2 documents Branch C limitation.

### L3. Branch B non-atomic / partial-failure rollback gap

**Sections:** §11.2 Branch B

**Issue:** The steps execute sequentially:
1. Save originals.
2. **Write new key files** (overwriting originals).
3. Verify password (rollback supported — restore originals on failure).
4. Set up device key (rollback supported — restore originals on failure).
5. **Copy DB file** (overwrites if exists).
6. Delete WAL/SHM.

If step 5 fails after steps 2–4 have already committed (e.g. disk full,
permission denied), the vault is left with new key files but no (or a
partial) database file. There is no rollback described for this case. The
Android app mitigates this partially because the DB is opened via
`openOrCreateDatabase` which handles partial files, but a CLI implementation
could leave the vault in an unrecoverable state.

**Fix applied:** §14.3 documents non-atomicity and recommends staging for
CLI implementations.

---

## Nice-to-fix — clarity and defensive documentation

### N1. §7.2 JSON example `createdAt` value contradicts note

**Section:** §7.2

**Issue:** The manifest example shows `"createdAt": 0`, but the field table
says it "defaults to current time" and is "always present". The
`VaultManifest` Kotlin data class defaults to `System.currentTimeMillis()`,
so `createdAt` will be a large positive value (~1.7 trillion for 2024+),
never zero.

**Fix applied:** Example changed to `"createdAt": 1720000000000`.

### N2. `SupportFactory` wipe behavior undocumented

**Section:** §6.1

**Issue:** The spec states the passphrase is the raw 32-byte masterKey, but
does not mention that `SupportFactory(passphrase, null, false)` passes
`false` for the `wipe` parameter. The Android SQLCipher `SupportFactory`
constructor signature is `SupportFactory(byte[] passphrase, byte[] rekey,
boolean wipe)` where `false` means the passphrase is **not** zeroed from
memory after use. The app offsets this by zeroing `cachedMasterKey` on lock
(§13). A CLI implementer using a different SQLCipher binding should be aware
that keeping the masterKey in memory is the default behavior.

**Fix applied:** Informational note added to §6.1.

### N3. File-blob ↔ backupMasterKey cross-reference already fixed

**Sections:** §8.1

**Status:** Fixed in current revision (added sentence tying file-blob
masterKey to `backupMasterKey` from §11.1). No further action needed.

### N4. Two-salt warning already fixed

**Sections:** §7.2, §8, §11.1

**Status:** Fixed — warning block added in §7.2, cross-reference in §8
`keys/salt` row, and note in §11.1 precondition.

---

## All issues resolved

| ID | Severity | Status |
|---|---|---|
| C1 (MAGIC 16 bytes) | Critical | **Fixed** |
| M1 (userKey name) | Must-fix | **Fixed** |
| M2 (base64 padding) | Must-fix | **Fixed** |
| L1 (password-KDF params) | Should-fix | **Fixed** |
| L2 (Branch C limitation) | Should-fix | **Fixed** |
| L3 (Branch B atomicity) | Should-fix | **Fixed** |
| N1 (createdAt example) | Nice | **Fixed** |
| N2 (SupportFactory wipe) | Nice | **Fixed** |
| N3 (file-blob cross-ref) | Nice | **Fixed** |
| N4 (two-salt warning) | Nice | **Fixed** |

All fixes have been applied to `encryption-backup.md`.
