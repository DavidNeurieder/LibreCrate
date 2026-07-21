use clap::Args;
use std::time::Instant;
use vault_native::types::KeyValue;

#[derive(Args)]
pub struct BenchArgs {
    #[command(subcommand)]
    pub action: BenchAction,
}

#[derive(clap::Subcommand)]
pub enum BenchAction {
    /// Benchmark Argon2id key derivation
    Argon2 {
        /// Number of iterations
        #[arg(short, long, default_value = "10")]
        count: u32,
    },
    /// Benchmark AES-GCM encryption/decryption
    AesGcm {
        /// Data size in KB
        #[arg(short, long, default_value = "1024")]
        size_kb: u32,
    },
    /// Benchmark vault creation (export)
    VaultCreate {
        /// Number of iterations
        #[arg(short, long, default_value = "5")]
        count: u32,
    },
}

pub fn run(args: BenchArgs) -> anyhow::Result<()> {
    match args.action {
        BenchAction::Argon2 { count } => {
            let params = vault_native::crypto::argon2::Argon2Params::default();
            let salt = b"benchmark-salt-12345678";
            let start = Instant::now();
            for i in 0..count {
                let _key = vault_native::crypto::argon2::derive_key("password", salt, &params)
                    .ok_or_else(|| anyhow::anyhow!("key derivation failed"))?;
                println!("  Iteration {} done", i + 1);
            }
            let elapsed = start.elapsed();
            println!(
                "Argon2id: {} iterations in {:.2}s ({:.2}ms/iter)",
                count,
                elapsed.as_secs_f64(),
                elapsed.as_secs_f64() / count as f64 * 1000.0
            );
            Ok(())
        }
        BenchAction::AesGcm { size_kb } => {
            let key = vault_native::crypto::aes_kw::generate_master_key();
            let data = vec![0xABu8; (size_kb as usize) * 1024];
            let start = Instant::now();
            let (iv, ct) = vault_native::crypto::aes_gcm::encrypt_bytes(&data, &key)
                .expect("encrypt failed");
            let enc_time = start.elapsed();
            let start = Instant::now();
            let _pt = vault_native::crypto::aes_gcm::decrypt_bytes(&ct, &key, &iv)
                .expect("decrypt failed");
            let dec_time = start.elapsed();
            println!(
                "AES-GCM {}KB: encrypt={:.2}ms, decrypt={:.2}ms",
                size_kb,
                enc_time.as_secs_f64() * 1000.0,
                dec_time.as_secs_f64() * 1000.0
            );
            Ok(())
        }
        BenchAction::VaultCreate { count } => {
            let tmp = tempfile::TempDir::new()?;
            let enc_dir = tmp.path().join("encryption");
            let db_dir = tmp.path().join("databases");
            let files_dir = tmp.path().join("files");
            let output = tmp.path().join("test.lc-vault");

            std::fs::create_dir_all(&enc_dir)?;
            std::fs::create_dir_all(&db_dir)?;
            std::fs::create_dir_all(&files_dir)?;

            let kdf_params = vault_native::crypto::argon2::Argon2Params::default();
            let start = Instant::now();
            for i in 0..count {
                let out = format!("{}-{}", output.display(), i);
                let empty: &[KeyValue] = &[];
                let exported = vault_native::format::export::export(
                    empty,
                    None,
                    "bench-password",
                    empty,
                    &kdf_params,
                )?;
                std::fs::write(&out, &exported.data)?;
                println!("  Vault {} created", i + 1);
            }
            let elapsed = start.elapsed();
            println!(
                "Vault create: {} operations in {:.2}s ({:.2}s/op)",
                count,
                elapsed.as_secs_f64(),
                elapsed.as_secs_f64() / count as f64
            );
            Ok(())
        }
    }
}
