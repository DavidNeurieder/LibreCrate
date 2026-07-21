use std::path::Path;
use vault_native::types::KeyValue;

/// Recursively walk a directory, returning (absolute_path, relative_path) pairs.
pub fn walk_files(dir: &Path) -> anyhow::Result<Vec<(std::path::PathBuf, std::path::PathBuf)>> {
    let mut files = Vec::new();
    if !dir.exists() {
        return Ok(files);
    }
    walk_dir_recursive(dir, dir, &mut files)?;
    Ok(files)
}

fn walk_dir_recursive(
    base: &Path,
    dir: &Path,
    files: &mut Vec<(std::path::PathBuf, std::path::PathBuf)>,
) -> anyhow::Result<()> {
    for entry in std::fs::read_dir(dir)? {
        let entry = entry?;
        let path = entry.path();
        if path.is_file() {
            let rel = path.strip_prefix(base).unwrap_or(&path).to_path_buf();
            files.push((path, rel));
        } else if path.is_dir() {
            walk_dir_recursive(base, &path, files)?;
        }
    }
    Ok(())
}

/// Read all files in a directory into KeyValue pairs.
pub fn read_dir_files(dir: &Path) -> anyhow::Result<Vec<KeyValue>> {
    let mut entries = Vec::new();
    if dir.exists() {
        for entry in std::fs::read_dir(dir)? {
            let entry = entry?;
            let path = entry.path();
            if path.is_file() {
                let name = path.file_name().unwrap().to_string_lossy().to_string();
                let data = std::fs::read(&path)?;
                entries.push(KeyValue { key: name, value: data });
            }
        }
    }
    Ok(entries)
}

/// Write imported contents to a vault directory structure.
pub fn write_contents(
    dir: &Path,
    contents: &vault_native::format::import::ImportedContents,
) -> anyhow::Result<()> {
    std::fs::create_dir_all(dir.join("encryption"))?;
    std::fs::create_dir_all(dir.join("databases"))?;
    std::fs::create_dir_all(dir.join("files"))?;
    for kv in &contents.keys {
        std::fs::write(dir.join("encryption").join(&kv.key), &kv.value)?;
    }
    if let Some(db) = &contents.db_file {
        std::fs::write(dir.join("databases").join("librecrate.db"), db)?;
    }
    for kv in &contents.files {
        let path = dir.join("files").join(&kv.key);
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        std::fs::write(path, &kv.value)?;
    }
    Ok(())
}
