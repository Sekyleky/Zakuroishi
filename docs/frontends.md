# Controller hotkeys and frontend launching

These features are available in Android 0.1.8. Existing game imports, saves,
controller presets and performance settings remain in place.

## Map fast-forward to a button

Open a game's **Settings → Button mapping**. Select **Fast-forward (hold)**,
then press and release a physical controller button. Its Android button name
appears beside the action. Set **Fast-forward speed (%)**, select **Use these
settings**, then **Save**. During play use **Save & restart** to apply changes.

Hold the mapped button to use the selected speed; release it to restore the
game's saved **Performance → Game speed limit**. The initial hotkey speed is
150%; unlimited, 100%, 200%, 300% and 400% are also available. This changes the
emulation clock limit, independently of the presentation cap, and cannot make
a device run faster than its CPU/GPU allow. No button is taken over by default.

The same menu maps console buttons, disables physical inputs (**None**) and
maps the motion gate. A new assignment replaces that physical button's previous
action. Multiple physical buttons can share an action. Hold a row in the menu
to clear that action's custom assignments; **Reset controller bindings** restores
the preset. Both remain drafts until the group and root dialogs are saved.
Touch controls retain their printed actions. Leaving the app clears held keys.

## Export a frontend library

1. Import games into Zakuro and check that they launch normally.
2. Choose **Frontends → Export library** in Zakuro's library and select a folder
   through Android's document picker.
3. Add the exported **Zakuro frontend/Games** directory to your frontend.
4. Configure Zakuro as its player, using the instructions below.

The export contains a 33-byte `.zkr` reference for each installed title, a README
and configuration examples. It does not duplicate ROMs, native game libraries,
textures or saves. References keep working when an import is replaced because
they identify its title ID rather than its private storage path. Removing a game
from Zakuro makes its reference unavailable; delete its old reference in the
frontend folder. Export again to add newly imported games. Renaming a game can
leave an older-named reference alongside the new one; remove the older entry.

The exporter updates its own setup files but refuses to overwrite unrelated
files with conflicting names. Scan only after Zakuro reports success. If a failed
write leaves a partial file, pick a new empty folder if that conflict cannot be
resolved. Folder access is used for the explicit export, not background sync.

## Cocoon

Cocoon's published [Nintendo 3DS platform definition](https://github.com/inssekt/CocoonFE/blob/main/platforms/Nintendo3DS.json)
uses `acceptedFilenameRegex` and an `amStartArguments` player template with
`{file.uri}`. The export follows that schema.

Import **config/Cocoon-Zakuro.json** as a custom platform, point it at **Games**,
and select **Zakuro Android** as its player. If configuring a player manually:

```text
-n io.github.zakuro.android/.FrontendLaunch
-a android.intent.action.VIEW
-d {file.uri}
--grant-read-uri-permission
```

Accept `.zkr`, `.3ds`, `.cci` and `.cxi`; scan only the Games directory rather than
the setup files. Cocoon's [emulator setup guide](https://cocoon-shell.com/wiki/emulator-setup/)
describes platform folders, per-game players and rescanning.

## iiSU

For setup without hand-editing JSON, choose **Frontends → Prepare iiSU config**.
Select iiSU's current **emuladores.json**, then a new output file. Zakuro produces
a full catalog copy, preserving existing consoles and players, adding its own
player to Nintendo 3DS and `.zkr` to `romExtensions`. Import this edited copy in
**iiSU Settings → Updates → Import emuladores.json**, select Zakuro as the player,
and scan the exported Games folder. Your original catalog is unchanged. On the
Thor, the catalog is normally under
`Android/media/com.iisulauncher/iiSULauncher/Emuladores/`. iiSU catalog updates may
replace custom entries; prepare a fresh copy of the new catalog if needed.

Use a Nintendo 3DS console's custom launch command where available:

```text
io.github.zakuro.android/.FrontendLaunch -a android.intent.action.VIEW -d %ROM_URI% --grant-read-uri-permission
```

Unlike Cocoon's `amStartArguments`, iiSU's emulator `command` begins with the
component name, without `-n`. For builds configured through **emuladores.json**,
the export includes **config/iiSU-Zakuro-entry.json**. This is one emulator entry,
not a full catalog. Back up your own catalog, add the entry to the Nintendo 3DS
console's `emulators` array in a copy, then import the complete edited catalog
through iiSU's Updates settings. Do not replace the entire catalog with the snippet.

If iiSU filters out `.zkr`, scan your original decrypted ROM folder instead.
Zakuro accepts the original `.3ds`, `.cci` or `.cxi` URI, validates its header and
looks up the title already installed in Zakuro. It does not import or execute
files supplied by the frontend. Configure this per game if other 3DS games use
another emulator. Catalog updates may require adding the entry again.

iiSU's [official guide](https://github.com/iisu-network/iiSU#readme) explains ROM
folders and emulator selection. The component-first `%ROM_URI%` convention and
catalog-entry format are also documented in a maintained
[Android emulator integration](https://github.com/lidor30/pokedaisy/blob/main/docs/iisu/README.md).
Zakuro is not yet listed in either frontend's built-in emulator catalog.

## Other launchers / developer contract

Use exported component `io.github.zakuro.android/.FrontendLaunch` with
`android.intent.action.VIEW`. Supply one of:

- A `content://` URI for a `.zkr` reference or a decrypted NCSD/NCCH ROM. Grant
  read access in the launch intent; providers must support seeking.
- A `file://` URI to a file Zakuro already has permission to read. Scoped storage
  often prevents this; content URIs are preferred.
- `zakuro://game/00040000001B5100`, replacing the final segment with the imported
  game's 16-digit title ID. A direct URI needs no ROM file access.

The reference format is exact ASCII: `ZAKURO-LAUNCH-1\nTITLE_ID\n`. Readers reject
trailing data, malformed IDs and truncated files. The native Game activity stays
private; both the internal library and public entry resolve paths and compatible
native code through the same installed-game launcher. External extras cannot
select ROM paths, executable libraries or runtime settings. Reopening the active
title reuses its activity; switching to another title asks before discarding
unsaved progress. These references are not ROMs: do not hash them for achievements
or launch them in other emulators.

Compatibility with every frontend version is not implied by this contract.
Physical-device checks and remaining gaps are recorded in [Testing](testing.md).
