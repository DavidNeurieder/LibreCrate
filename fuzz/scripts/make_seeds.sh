#!/usr/bin/env bash
# Regenerate the committed fuzz seed corpus under fuzz/seeds/.
#
# The seeds are small, curated inputs that give libFuzzer a head start on the
# structured formats (valid containers, valid ZIPs, valid manifest JSON). They
# are also replayed on stable Rust by vault-native/core/tests/fuzz_seed_corpus.rs
# so the corpus is exercised in normal CI, not only in the nightly fuzz job.
#
# Run from anywhere:  bash fuzz/scripts/make_seeds.sh
# Then commit the resulting fuzz/seeds/** files.
set -euo pipefail

cd "$(dirname "$0")/.."           # -> fuzz/
ROOT="$(cd .. && pwd)"            # -> repo root
FIXTURES="$ROOT/vault-native/core/tests/fixtures/data-integrity"

seeds() { mkdir -p "seeds/$1"; }

echo "writing fuzz/seeds ..."

# --- fuzz_archive_path: one candidate name per file (no trailing newline) ----
seeds fuzz_archive_path
printf '%s' 'a.txt'                    > seeds/fuzz_archive_path/normal.txt
printf '%s' 'dir/sub/file.pdf'         > seeds/fuzz_archive_path/nested.txt
printf '%s' 'nested 1/ünïcödé.pdf'     > seeds/fuzz_archive_path/unicode.txt
printf '%s' '../escape'                > seeds/fuzz_archive_path/parent.txt
printf '%s' 'a/../../b'                > seeds/fuzz_archive_path/deep_parent.txt
printf '%s' '/etc/passwd'              > seeds/fuzz_archive_path/absolute.txt
printf '%s' 'files//repeated'          > seeds/fuzz_archive_path/repeated_sep.txt
printf '%s' 'C:/windows'               > seeds/fuzz_archive_path/drive.txt
printf '%s' 'files\..\evil'            > seeds/fuzz_archive_path/backslash.txt
printf '%s' '.'                        > seeds/fuzz_archive_path/dot.txt

# --- fuzz_manifest_json: raw manifest JSON strings ---------------------------
seeds fuzz_manifest_json
printf '%s' '{"version":2,"kdf":"argon2id","salt":"c2FsdA==","argon2Memory":19456,"argon2Iterations":2,"argon2Parallelism":2,"documentCount":0}' \
    > seeds/fuzz_manifest_json/valid.json
printf '%s' '{"version":2,"kdf":"argon2id","salt":"c2FsdA=="}' \
    > seeds/fuzz_manifest_json/missing_fields.json
printf '%s' '{"version":4294967295,"kdf":"x","salt":"","argon2Memory":18446744073709551615,"argon2Iterations":0,"argon2Parallelism":0,"documentCount":4294967295}' \
    > seeds/fuzz_manifest_json/huge_numbers.json
printf '%s' 'not json at all'          > seeds/fuzz_manifest_json/not_json.json
printf '%s' '{"version":1,"kdf":"argon2id","salt":"!!!not-base64!!!","argon2Memory":1,"argon2Iterations":1,"argon2Parallelism":1,"documentCount":0}' \
    > seeds/fuzz_manifest_json/bad_salt.json

# --- fuzz_extract_zip: real ZIP archives -------------------------------------
seeds fuzz_extract_zip
python3 - <<'PY'
import io, zipfile
from pathlib import Path

base = Path("seeds/fuzz_extract_zip")

def write_zip(name, entries):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for entry, data in entries:
            z.writestr(entry, data)
    (base / name).write_bytes(buf.getvalue())

write_zip("valid.zip", [
    ("keys/salt", b"0123456789abcdef"),
    ("keys/wrapped_master_key", b"\x00" * 40),
    ("db/librecrate.db", b"SQLite format 3\x00" + b"\x00" * 64),
    ("files/readme.txt", b"hello world"),
])
write_zip("zip_slip.zip", [
    ("files/ok.txt", b"ok"),
    ("files/../../evil.txt", b"pwned"),
])
valid = (base / "valid.zip").read_bytes()
(base / "truncated.zip").write_bytes(valid[: len(valid) // 2])
PY

# --- fuzz_package_read / fuzz_import: real containers (frozen fixtures) ------
seeds fuzz_package_read
cp "$FIXTURES/legacy-v1/vault.librecrate-backup" seeds/fuzz_package_read/v1.backup
cp "$FIXTURES/legacy-v2/vault.librecrate-backup" seeds/fuzz_package_read/v2.backup
printf '%s' 'NOT_THE_MAGIC_BYTES_HERE' > seeds/fuzz_package_read/bad_magic.bin
head -c 5 /dev/zero                    > seeds/fuzz_package_read/short.bin
# MAGIC + version 2 + a 0xFFFFFFFF manifest length (must be refused unread).
printf 'LIBCRATE_VAULT\0\0' > seeds/fuzz_package_read/huge_manifest_len.bin
printf '\x02\x00\x00\x00\xff\xff\xff\xff' >> seeds/fuzz_package_read/huge_manifest_len.bin

seeds fuzz_import
cp "$FIXTURES/legacy-v1/vault.librecrate-backup" seeds/fuzz_import/v1.backup
cp "$FIXTURES/legacy-v2/vault.librecrate-backup" seeds/fuzz_import/v2.backup
head -c 80 "$FIXTURES/legacy-v2/vault.librecrate-backup" > seeds/fuzz_import/truncated.backup
printf '%s' 'garbage that is not a container' > seeds/fuzz_import/garbage.bin

# --- fuzz_thumbnail: tiny images + a minimal PDF -----------------------------
seeds fuzz_thumbnail
python3 - <<'PY'
import struct, zlib
from pathlib import Path

base = Path("seeds/fuzz_thumbnail")

def png_1x1():
    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))
    ihdr = struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0)  # 1x1, 8-bit RGB
    raw = b"\x00\xff\x00\x00"                              # filter + one red pixel
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))

(base / "tiny.png").write_bytes(png_1x1())
(base / "tiny.gif").write_bytes(
    b"GIF89a\x01\x00\x01\x00\x80\x00\x00\x00\x00\x00\xff\xff\xff"
    b"!\xf9\x04\x00\x00\x00\x00\x00,\x00\x00\x00\x00\x01\x00\x01\x00"
    b"\x00\x02\x02D\x01\x00;"
)
(base / "minimal.pdf").write_bytes(
    b"%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n"
    b"2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n"
    b"3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 10 10]>>endobj\n"
    b"trailer<</Root 1 0 R>>\n%%EOF\n"
)
(base / "not_an_image.bin").write_bytes(b"\x00\x01\x02\x03 not an image \xff\xfe")
PY

echo "done: $(find seeds -type f | wc -l) seed files"
