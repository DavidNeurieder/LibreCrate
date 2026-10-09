//! Argon2id KDF cost benchmark (PR 10).
//!
//! `cargo bench -p vault-native` (uses `harness = false`; no criterion dep).
//!
//! Purpose: measure wall-clock cost per derivation for the *production*
//! parameter sets so the `KdfPolicy` ceiling stays comfortably above normal
//! settings — while remaining a hard DoS ceiling for hostile input (a hostile
//! archive can never push Argon2 to an unbounded allocation, but a legitimate
//! caller never pays more than the normal cost).

use std::time::{Duration, Instant};
use vault_native::crypto::argon2::{self, Argon2Params};

fn bench_one(label: &str, params: &Argon2Params, rounds: usize) -> Duration {
    let salt = b"bench-salt-0123456789";
    // Warm up (allocates the Argon2 state, registers cost in the scheduler).
    let _ = argon2::derive_key("benchmark-password", salt, params);
    let start = Instant::now();
    for _ in 0..rounds {
        let _r = argon2::derive_key("benchmark-password", salt, params);
    }
    let elapsed = start.elapsed();
    let per = elapsed / rounds as u32;
    eprintln!("{label:<26} {rounds:>3} rounds  {elapsed:>8.1?} total  ({per:>8.1?} each)");
    per
}

fn main() {
    // Production parameter sets (kept below the policy ceiling).
    let desktop = Argon2Params::new(19456, 2, 2, 32);
    let android = Argon2Params::new(16384, 3, 2, 32);
    // The absolute ceiling itself — expensive by design.
    let ceiling = Argon2Params::new(64 * 1024, 10, 4, 32);

    let per_desktop = bench_one("desktop 19456/2/2", &desktop, 25);
    let per_android = bench_one("android 16384/3/2", &android, 20);
    let per_ceiling = bench_one("policy cap 64MiB/10/4", &ceiling, 3);

    println!(
        "kdf_bench per_derivation_ms desktop={:.1} android={:.1} policy_cap={:.1}",
        per_desktop.as_secs_f64() * 1000.0,
        per_android.as_secs_f64() * 1000.0,
        per_ceiling.as_secs_f64() * 1000.0
    );

    // Regression sanity (very generous for slow/loaded CI machines; fails
    // early if the desktop derivation ever degrades by an order of magnitude,
    // e.g. after an argon2 crate bump). CI does not run benches, but this
    // guard makes `cargo bench` self-checking for developers.
    assert!(
        per_desktop.as_secs_f64() < 3.0,
        "desktop KDF slower than 3 s per derivation — run with --release"
    );
}
