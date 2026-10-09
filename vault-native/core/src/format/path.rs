use crate::error::{Error, Result};
use std::path::{Component, Path, PathBuf};

/// Validate that an archive-controlled path is a safe relative path made only
/// of normal components — no absolute paths, no `..`, no `.`, no drive
/// prefixes, no UNC shares, no root-relative components.
///
/// This is the single sanctioned entry point for turning an entry name coming
/// out of a backup ZIP/DB into a file name. Do NOT `Path::join` archive
/// strings directly anywhere else.
pub fn safe_archive_path(name: &str) -> Result<PathBuf> {
    if name.is_empty() {
        return Err(Error::InvalidArchivePath("empty path".into()));
    }
    // Raw pre-scan for separators that component parsing normalizes away on
    // some platforms. "//" and backslashes make the same name parse
    // differently across OSes, so reject them outright.
    if name.contains("//") {
        return Err(Error::InvalidArchivePath(format!(
            "repeated separator in '{name}'"
        )));
    }
    if name.contains('\\') {
        return Err(Error::InvalidArchivePath(format!("backslash in '{name}'")));
    }
    // Lexical dot-segment scan. std::path collapses "." segments during
    // component parsing on some platforms (a/./b parses as a/b), so they must
    // be rejected here to guarantee identical behavior everywhere — plus "..".
    if name
        .split('/')
        .any(|segment| segment == "." || segment == "..")
    {
        return Err(Error::InvalidArchivePath(format!(
            "dot segment in '{name}'"
        )));
    }
    let path = Path::new(name);

    if path.is_absolute() {
        return Err(Error::InvalidArchivePath(format!("absolute path '{name}'")));
    }

    for component in path.components() {
        match component {
            Component::Normal(part) => {
                let part = part.to_string_lossy();
                // Defensive: refuse Windows-style drive letters appearing as a
                // segment ('C:').
                if part.len() >= 2 && part.as_bytes()[1] == b':' {
                    return Err(Error::InvalidArchivePath(format!(
                        "bad path segment '{part}' in '{name}'"
                    )));
                }
            }
            Component::ParentDir
            | Component::CurDir
            | Component::RootDir
            | Component::Prefix(_) => {
                return Err(Error::InvalidArchivePath(format!(
                    "disallowed path component in '{name}'"
                )));
            }
        }
    }
    Ok(path.to_path_buf())
}

/// Defensive containment check: verify that `base.join(relative)` stays inside
/// `base`, AFTER lexically normalizing the joined path. Call with a directory
/// that already exists (or after `create_dir_all(base)`).
pub fn contained_join(base: &Path, relative: &Path) -> Result<PathBuf> {
    use std::path::Component::{CurDir, Normal, ParentDir, Prefix, RootDir};
    let base_canon = base
        .canonicalize()
        .map_err(|e| Error::Io(format!("cannot canonicalize {}: {e}", base.display())))?;
    if relative.is_absolute() {
        return Err(Error::InvalidArchivePath(format!(
            "absolute path {} not allowed",
            relative.display()
        )));
    }

    // Collapse `.` and `..` lexically so a trailing `..`, or `..` mid-path,
    // cannot slip past the `starts_with` check.
    let mut normalized: Vec<std::ffi::OsString> = Vec::new();
    for component in relative.components() {
        match component {
            CurDir => {}
            ParentDir => {
                if normalized.pop().is_none() {
                    return Err(Error::InvalidArchivePath(format!(
                        "path {} escapes base {}",
                        relative.display(),
                        base_canon.display()
                    )));
                }
            }
            Normal(part) => normalized.push(part.to_os_string()),
            RootDir | Prefix(_) => {
                return Err(Error::InvalidArchivePath(format!(
                    "disallowed component in {}",
                    relative.display()
                )));
            }
        }
    }

    let mut dest = base_canon.clone();
    for part in normalized {
        dest.push(part);
    }
    if !dest.starts_with(&base_canon) {
        return Err(Error::InvalidArchivePath(format!(
            "path {} escapes base {}",
            dest.display(),
            base_canon.display()
        )));
    }
    Ok(dest)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepts_normal_relative_paths() {
        assert!(safe_archive_path("a.txt").is_ok());
        assert!(safe_archive_path("dir/sub/file.pdf").is_ok());
        assert!(safe_archive_path("nested/very/deep/name").is_ok());
    }

    #[test]
    fn rejects_parent_dir_traversal() {
        assert!(safe_archive_path("../foo").is_err());
        assert!(safe_archive_path("../../foo").is_err());
        assert!(safe_archive_path("files/../../outside").is_err());
        assert!(safe_archive_path("files/../../../tmp/test").is_err());
        assert!(safe_archive_path("files/../database").is_err());
        assert!(safe_archive_path("a/../../../../etc").is_err());
    }

    #[test]
    fn rejects_absolute_and_drive_paths() {
        assert!(safe_archive_path("/foo").is_err());
        assert!(safe_archive_path("files//absolute").is_err());
        assert!(safe_archive_path("files/C:/Windows/tmp").is_err());
        assert!(safe_archive_path("C:\\foo").is_err());
        assert!(safe_archive_path("C:/foo").is_err());
        assert!(safe_archive_path("\\\\server\\share").is_err());
        assert!(safe_archive_path("files/\\absolute").is_err());
        assert!(safe_archive_path("//double/leading").is_err());
    }

    #[test]
    fn rejects_dot_and_empty() {
        assert!(safe_archive_path(".").is_err());
        assert!(safe_archive_path("..").is_err());
        assert!(safe_archive_path("").is_err());
        assert!(safe_archive_path("/").is_err());
        // std::path collapses "." mid-path on some platforms — reject lexically.
        assert!(safe_archive_path("a/./b").is_err());
        assert!(safe_archive_path("a/.").is_err());
        assert!(safe_archive_path("./a").is_err());
        assert!(safe_archive_path("a/../b").is_err());
    }

    #[test]
    fn contained_join_stays_in_base() {
        let base = std::env::temp_dir().join("librecrate-path-test");
        std::fs::create_dir_all(&base).unwrap();
        let dest = contained_join(&base, Path::new("sub/file.bin")).unwrap();
        assert!(dest.starts_with(base.canonicalize().unwrap()));
        // A relative path that walks up is rejected by safe_archive_path first,
        // and contained_join rejects it as well via the escape check.
        assert!(contained_join(&base, Path::new("../outside")).is_err());
        assert!(contained_join(&base, Path::new("sub/../../../outside")).is_err());
        assert!(contained_join(&base, Path::new("/abs")).is_err());
    }
}
