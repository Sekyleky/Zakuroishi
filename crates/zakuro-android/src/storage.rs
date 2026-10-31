use std::{
    fs::{self, File},
    io::{Read, Write},
    path::Path,
};

/// A failed save leaves the previous slot untouched.
pub fn save(path: &Path, bytes: &[u8]) -> Result<(), String> {
    let parent = path.parent().ok_or("state path has no parent")?;
    fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    let temporary = path.with_extension("tmp");
    let result = (|| {
        let mut file = File::create(&temporary)?;
        file.write_all(bytes)?;
        file.sync_all()?;
        drop(file);
        fs::rename(&temporary, path)
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result.map_err(|e| format!("Could not save state: {e}"))
}
pub fn load(path: &Path) -> Result<Vec<u8>, String> {
    const LIMIT: u64 = 512 * 1024 * 1024;
    let file = File::open(path).map_err(|e| format!("Could not open saved state: {e}"))?;
    if file.metadata().map_err(|e| e.to_string())?.len() > LIMIT {
        return Err("Saved state is too large".into());
    }
    let mut bytes = Vec::new();
    file.take(LIMIT + 1)
        .read_to_end(&mut bytes)
        .map_err(|e| e.to_string())?;
    if bytes.len() as u64 > LIMIT {
        return Err("Saved state grew while being read".into());
    }
    Ok(bytes)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn replaces_a_slot_and_preserves_it_when_parent_is_unwritable() {
        let root = std::env::temp_dir().join(format!(
            "zakuro-state-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        let path = root.join("slot.zs");
        save(&path, b"old").unwrap();
        save(&path, b"new").unwrap();
        assert_eq!(load(&path).unwrap(), b"new");
        assert!(save(&path.join("invalid-child"), b"bad").is_err());
        assert_eq!(load(&path).unwrap(), b"new");
        fs::remove_file(path).unwrap();
        fs::remove_dir(root).unwrap();
    }
}
