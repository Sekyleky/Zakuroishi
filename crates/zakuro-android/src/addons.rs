//! A game's updates, DLC and Download Play child, imported into its addons folder, paired the
//! way the desktop pairs files beside a game (zakuro/src/library.rs addons_beside).
use std::path::{Path, PathBuf};
use zakuro_fs::TitleKind;

#[derive(Debug, Default, PartialEq)]
pub struct Addons {
    /// the newest update for the game.
    pub update: Option<PathBuf>,
    pub dlc: Vec<PathBuf>,
    pub dlp_child: Option<PathBuf>,
    /// files that are not used, and why, for one message at game start.
    pub problems: Vec<String>,
}

/// the .cia files in dir that belong to the game program_id.
pub fn find(dir: &Path, program_id: u64) -> Addons {
    let mut found = Addons::default();
    let Ok(entries) = std::fs::read_dir(dir) else { return found };
    let mut paths: Vec<PathBuf> = entries.flatten().map(|entry| entry.path()).filter(|path| path.extension().is_some_and(|ext| ext.eq_ignore_ascii_case("cia"))).collect();
    paths.sort();
    let mut newest = 0u16;
    for path in paths {
        let name = path.file_name().map(|name| name.to_string_lossy().into_owned()).unwrap_or_default();
        let cia = match zakuro_fs::RomImage::open(&path).ok().and_then(|image| zakuro_fs::Cia::read(image.data()).ok()) {
            Some(cia) => cia,
            None => {
                found.problems.push(format!("{name} is not a CIA file"));
                continue;
            }
        };
        if cia.title_id & 0xFFFF_FFFF != program_id & 0xFFFF_FFFF || cia.kind() == TitleKind::Game {
            found.problems.push(format!("{name} is not an update or DLC for this game"));
        } else if cia.contents.iter().any(|content| content.offset.is_some() && content.encrypted()) {
            found.problems.push(format!("{name} is encrypted; import a decrypted dump"));
        } else {
            match cia.kind() {
                TitleKind::Update if found.update.is_none() || cia.version > newest => {
                    newest = cia.version;
                    found.update = Some(path);
                }
                TitleKind::Update => {}
                TitleKind::Dlc => found.dlc.push(path),
                TitleKind::DlpChild => found.dlp_child = Some(path),
                TitleKind::Game => unreachable!("games are refused above"),
            }
        }
    }
    found
}

#[cfg(test)]
mod tests {
    use super::*;
    use zakuro_fs::testing::{cia, ncch};

    #[test]
    fn the_newest_update_dlc_and_child_go_with_the_game() {
        let dir = std::env::temp_dir().join(format!("zakuro-android-addons-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let game = 0x0004_0000_00AB_CD00u64;
        let update_id = game | 0x000E_0000_0000;
        let update = |version: u16| cia(update_id, version, &[(0, Some(&ncch(update_id, Some(&[2; 8]), &[])))]);
        std::fs::write(dir.join("a-new.CIA"), update(0x0410)).unwrap();
        std::fs::write(dir.join("b-old.cia"), update(0x0400)).unwrap();
        let dlc = game | 0x008C_0000_0000;
        std::fs::write(dir.join("dlc.cia"), cia(dlc, 1, &[(0, Some(&ncch(dlc, None, &[])))])).unwrap();
        let child = 0x0004_0001_00AB_CD00u64;
        std::fs::write(dir.join("child.cia"), cia(child, 0, &[(0, Some(&ncch(child, Some(&[4; 8]), &[])))])).unwrap();
        let other = 0x0004_000E_0000_0001u64;
        std::fs::write(dir.join("other.cia"), cia(other, 1, &[(0, Some(&ncch(other, Some(&[3; 8]), &[])))])).unwrap();
        std::fs::write(dir.join("game.cia"), cia(game, 0, &[(0, Some(&ncch(game, Some(&[1; 8]), &[])))])).unwrap();
        std::fs::write(dir.join("junk.cia"), b"not a cia").unwrap();
        std::fs::write(dir.join("notes.txt"), b"ignored").unwrap();

        let found = find(&dir, game);
        assert_eq!(found.update, Some(dir.join("a-new.CIA")));
        assert_eq!(found.dlc, [dir.join("dlc.cia")]);
        assert_eq!(found.dlp_child, Some(dir.join("child.cia")));
        assert_eq!(
            found.problems,
            ["game.cia is not an update or DLC for this game", "junk.cia is not a CIA file", "other.cia is not an update or DLC for this game"]
        );
        assert_eq!(find(&dir.join("missing"), game), Addons::default());
        let _ = std::fs::remove_dir_all(&dir);
    }
}
