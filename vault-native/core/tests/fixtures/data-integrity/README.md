# Data-integrity fixtures

Frozen, version-controlled inputs for the backward-compatibility regression
suite. See `specification/data-integrity-tests.md` for the full plan.

## Purpose

These fixtures prove that data written by earlier LibreCrate format versions can
still be read by the current code, and that the read path never authenticates or
returns tampered data. They are **historical bytes**: they must never be
regenerated from the current writer just to make a failing test pass.

## Layout

```
data-integrity/
├── manifest.json                 # fixture metadata + SHA-256 (see schema below)
├── legacy-v1/
│   ├── vault.librecrate-backup   # v1 container: AES-GCM payload with NO AAD
│   └── expected.json             # exact expected logical contents
└── legacy-v2/
    ├── vault.librecrate-backup   # v2 container: manifest authenticated as GCM AAD
    └── expected.json
```

Both fixtures are encrypted backups in the `LIBCRATE_VAULT` container format. The
on-disk vault directory layout (`encryption/`, `databases/`, `files/`) did not
change between v1 and v2 — v2 only changed the backup **envelope**, so a
container fixture is the meaningful historical artifact for both.

## Credentials

Synthetic, test-only — never real vault passwords:

| Fixture | Password |
|---|---|
| `legacy-v1` | `fixture-password-v1` |
| `legacy-v2` | `fixture-password-v2` |

## `manifest.json` schema

```jsonc
{
  "schema": 1,
  "generated_by": "vault-native <version> (tests/make_fixtures.rs)",
  "fixtures": [
    {
      "path": "legacy-v1/vault.librecrate-backup", // relative to this directory
      "expected": "legacy-v1/expected.json",       // relative to this directory
      "format_version": 1,
      "container": "backup",
      "purpose": "...",
      "sha256": "<hex of the fixture file bytes>"
    }
  ]
}
```

The `sha256` is recomputed by `core/tests/compat_legacy.rs::fixtures_present_and_unmodified`,
which fails CI if a fixture is missing or has been modified.

## Update policy

1. Fixtures are **append-only and immutable**. Do not edit committed bytes.
2. To add a genuinely new historical fixture, mint it with the one-off generator
   and commit the resulting bytes plus `expected.json` and the updated
   `manifest.json`:
   ```sh
   cargo test -p vault-native --test make_fixtures -- --ignored --nocapture
   ```
3. Never regenerate an existing fixture to satisfy a failing compatibility test —
   that would let a breaking format change silently redefine correctness.
