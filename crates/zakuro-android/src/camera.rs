//! the picture a game's cameras see, chosen in Game files → Camera picture…,
//! as the desktop's Choose camera picture. The launcher decodes and shrinks
//! it (GameImport.cameraBytes) to "ZKCAM1", width and height as little-endian
//! u32, then red, green and blue for each pixel.

use std::path::Path;
use std::sync::Arc;

use zakuro_core::camera::{Fit, Picture, Still};

const MAGIC: &[u8; 6] = b"ZKCAM1";
const HEADER: usize = 14;
/// what the launcher writes is at most 640 across; this is the line a damaged
/// or foreign file stops at, before it is read whole.
const LARGEST: u32 = 1280;

pub fn load(path: &Path) -> Result<Still, String> {
    let most = HEADER as u64 + u64::from(LARGEST) * u64::from(LARGEST) * 3;
    let size = std::fs::metadata(path).map_err(|error| format!("could not read it, {error}"))?.len();
    if size > most {
        return Err(damaged());
    }
    read(&std::fs::read(path).map_err(|error| format!("could not read it, {error}"))?)
}

fn damaged() -> String {
    "the file is damaged. Choose the picture again in Game files.".into()
}

fn read(bytes: &[u8]) -> Result<Still, String> {
    if bytes.len() < HEADER || &bytes[..6] != MAGIC {
        return Err(damaged());
    }
    let size = |at: usize| u32::from_le_bytes(bytes[at..at + 4].try_into().unwrap());
    let (width, height) = (size(6), size(10));
    if !(1..=LARGEST).contains(&width) || !(1..=LARGEST).contains(&height) || bytes.len() - HEADER != (width * height * 3) as usize {
        return Err(damaged());
    }
    Ok(Still(Arc::new(Picture { width, height, rgb: bytes[HEADER..].to_vec(), fit: Fit::Whole })))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// the bytes GameImportCheck expects GameImport.cameraBytes to write for
    /// two pixels, 0x112233 and 0x445566: both sides agree on the format.
    const TWO_PIXELS: &[u8] = b"ZKCAM1\x02\x00\x00\x00\x01\x00\x00\x00\x11\x22\x33\x44\x55\x66";

    #[test]
    fn reads_what_the_launcher_writes() {
        let Still(picture) = read(TWO_PIXELS).unwrap();
        assert_eq!((picture.width, picture.height), (2, 1));
        assert_eq!(picture.rgb, [0x11, 0x22, 0x33, 0x44, 0x55, 0x66]);
        assert!(matches!(picture.fit, Fit::Whole), "a QR code is shown whole");
    }

    #[test]
    fn refuses_a_damaged_file() {
        assert!(read(&TWO_PIXELS[..TWO_PIXELS.len() - 1]).is_err(), "short");
        assert!(read(b"ZKCAM1").is_err(), "no size");
        let mut wrong = TWO_PIXELS.to_vec();
        wrong[0] = b'X';
        assert!(read(&wrong).is_err(), "not ours");
        let mut huge = TWO_PIXELS.to_vec();
        huge[6..10].copy_from_slice(&(LARGEST + 1).to_le_bytes());
        assert!(read(&huge).is_err(), "too wide");
        let mut empty = TWO_PIXELS.to_vec();
        empty[10..14].copy_from_slice(&0u32.to_le_bytes());
        assert!(read(&empty).is_err(), "no rows");
    }

    #[test]
    fn load_refuses_a_file_too_big_before_reading_it() {
        let dir = std::env::temp_dir().join(format!("zakuro-camera-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("big.rgb");
        std::fs::File::create(&path).unwrap().set_len(HEADER as u64 + 1280 * 1280 * 3 + 1).unwrap();
        assert!(load(&path).is_err());
        std::fs::write(&path, TWO_PIXELS).unwrap();
        assert!(load(&path).is_ok());
        std::fs::remove_dir_all(dir).unwrap();
    }
}
