use std::{
    fs::{self, File},
    io::{Read, Write},
    path::{Path, PathBuf},
};

/// `data/states/<PROGRAM_ID>/<slot>.state`; slot 0 is the quick state.
pub fn state_path(data: &Path, program_id: u64, slot: u8) -> PathBuf {
    data.join("states")
        .join(format!("{program_id:016X}"))
        .join(format!("{slot}.state"))
}

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
    fn slot_zero_keeps_the_quick_state_path() {
        let data = Path::new("data");
        let id = 0x00040000001B5100;
        assert_eq!(
            state_path(data, id, 0),
            data.join("states").join("00040000001B5100").join("0.state")
        );
        assert_eq!(state_path(data, id, 7).file_name().unwrap(), "7.state");
    }
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
