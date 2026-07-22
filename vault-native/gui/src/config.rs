use anyhow::Result;
use serde::{Deserialize, Serialize};
use std::path::PathBuf;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Config {
    pub vault_dir: Option<PathBuf>,
    pub theme: String,
    pub window_width: u32,
    pub window_height: u32,
    pub sort_by: String,
    pub sort_desc: bool,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            vault_dir: None,
            theme: "system".to_string(),
            window_width: 1200,
            window_height: 800,
            sort_by: "imported_at".to_string(),
            sort_desc: true,
        }
    }
}

impl Config {
    pub fn path() -> PathBuf {
        let base = dirs::config_dir()
            .unwrap_or_else(|| PathBuf::from("."))
            .join("librecrate");
        std::fs::create_dir_all(&base).ok();
        base.join("config.toml")
    }

    pub fn load() -> Self {
        let path = Self::path();
        if path.exists() {
            std::fs::read_to_string(&path)
                .ok()
                .and_then(|s| toml::from_str(&s).ok())
                .unwrap_or_default()
        } else {
            Self::default()
        }
    }

    pub fn save(&self) -> Result<()> {
        let path = Self::path();
        let s = toml::to_string_pretty(self)?;
        std::fs::write(&path, s)?;
        Ok(())
    }

    pub fn vault_data_dir() -> PathBuf {
        dirs::data_dir()
            .unwrap_or_else(|| PathBuf::from("."))
            .join("librecrate")
    }
}
