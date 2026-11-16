# Zakuroishi 0.6.0

A new library with game tiles and details on the second screen, a short settings page with presets
and Default settings for every game, a pause menu with save-state lists, hotkeys, save-state
thumbnails, cheats, and frame generation that rests in 60 FPS scenes. Installs over 0.5.0 and keeps
games, settings and saves.

**Checked on one device**, an AYN Thor, on 2026-11-15 and 2026-11-16, mostly with Pokémon X and
Pokémon Ultra Moon. What was and was not seen is in the verification record:
[2026-11-15](https://github.com/Sekyleky/Zakuroishi/blob/android-handheld/docs/testing.md#library-settings-and-pause-menu-rework-2026-11-15)
and
[2026-11-16](https://github.com/Sekyleky/Zakuroishi/blob/android-handheld/docs/testing.md#rework-re-check-and-rest-in-60-fps-scenes-2026-11-16).
Two features that were not run on the device are only in the nightly build, see below.

## What's new

- **Library.** Games are tiles with their own icon, most recently played first, with play time
  ("Played 20 hours ago"). The focused game's details show under the header and, on a second
  screen, large with Play, Settings and Saves buttons. The D-pad moves between tiles. On the Thor
  10 games fit on the screen at once.
- **Menu.** The header has Add game and Menu (Start). Menu holds Default settings, Setup help,
  Frontends, Storage and the settings and diagnostics exports. It replaces the Tools menu.
- **Default settings.** Settings in Menu that every game uses until that game changes them. A
  game's changed rows are marked, and **Use defaults for this game** drops its own values. On the
  Thor, changing a default reached two games that had no value of their own, and changing it back
  restored both.
- **Simple settings page.** A game's settings open on one short page: a preset (Battery,
  Balanced, Quality), resolution, the texture pack and twelve common settings. Everything else is
  under All settings, Button mapping and Game files. The comparison switches moved to Advanced.
  Existing settings are carried over once; on the Thor, Ultra Moon's settings read back the same
  as the day before.
- **Pause menu.**
  - Save state and Load state each open one list: the quick slot, then slots 1-9.
  - X quick-saves; Y asks whether to load the quick slot.
  - B or Cancel goes back to the pause menu instead of resuming the game.
  - **HUD** tile: shows or hides the clock and battery at once, with no restart.
  - **Screenshot** tile: saves the top display as a PNG in `Pictures/Zakuroishi`, without the menu
    or HUD.
- **Hotkeys.** A controller key can quick save, quick load (asks first), swap screens, take a
  screenshot or open the pause menu. All five were seen on the Thor, each firing once per press.
- **Save-state thumbnails.** Each slot shows a small picture of where it was saved. A state saved
  by an older version shows a blank tile and is listed as before.
- **Cheats.** Game files has **Import cheats…** (one text file in the common text cheat format),
  **Cheats…** with a switch per cheat, and **Remove cheats**. Seen on the Thor with a harmless
  two-cheat file: import through the file picker, the switch list, the next start logging one of the
  two cheats on, and Remove cheats.
- **Frame generation rests in 60 FPS scenes.** New switch **Rest in 60 FPS scenes**
  (`frame_generation_rest`), on by default. Where the game itself draws 60 pictures a second, the
  game's own pictures are shown instead of generated ones. Measured on the Thor with Ultra Moon at
  3x + HD on Turnip:
  - At 4x on a 120 Hz panel, the intro held 100.1-100.5% speed with the switch on and stayed at
    88.7-93.8% with it off, after the first two windows (two launches per side). The panel then
    shows about 60 distinct pictures a second there instead of about 107.
  - At 2x on a 60 Hz panel, the first window was 88.4% with it on and 52.9% with it off (one launch
    each).
  - In the field, where the game draws 30, generation runs as before: 99.6-99.7% speed on and 99.7%
    off at 4x, about 119 pictures a second on both sides.
- **Fixes.**
  - Confirmation dialogs inside game settings (Discard changes?, Remove cheats?, Clear shader
    cache?) open with Cancel or Keep editing lit. Before, no button was lit. Seen on the Thor for
    Discard changes?.
  - The save slot list opens with its first row selected. In 0.5.0, A did nothing until the D-pad
    moved. Rechecked on the Thor.
  - Importing your Lossless.dll from the in-game settings closed the game. The button is now only
    in the library. Not run on the device.

## In the nightly build only

The nightly build (version name `0.6.0-nightly`) is the same code with two more features turned
on. Neither was run on a device. It has the same version code and signing key as 0.6.0, so either
installs over the other and keeps games, settings and saves.

- **Importing a game without native code.** A folder with only a decrypted ROM can be added. The
  game runs in the interpreter, and native code can be added later from its settings. Interpreter
  speeds, measured with compiled code switched off on games that have it, one launch each on the
  Thor, title and first scene: New Super Mario Bros. 2 100.4-100.5%, Super Mario 3D Land 97-98%,
  Ultra Moon 73-75%. The import itself was not run on a device. 0.6.0 refuses such a folder before
  copying anything and says that the nightly build can import it. Installing 0.6.0 over the nightly
  keeps a game imported this way.
- **Pin to home screen** in a tile's touch menu (⋯). The entry was seen on the Thor; pinning a game
  was not run. 0.6.0 hides it.

## Before you update

- The library header's Tools menu is now **Menu** (Start), with the same items plus Default
  settings and Setup help.
- Existing per-game settings are carried over to the new settings page once.
- **Rest in 60 FPS scenes** is on by default. With frame generation on, 60 FPS scenes now show
  the game's own 60 pictures a second instead of generated ones. Turn **Frame generation: rest in
  60 FPS scenes** off under Performance to get the 0.5.0 behaviour.
- **Import your Lossless.dll…** is only in a game's settings opened from the library now, not in
  the in-game settings.
- Going back to 0.5.0 needs an uninstall, which can delete saves. Export your saves first.

## Limits

- One device (an AYN Thor), mostly two games (Pokémon X and Pokémon Ultra Moon). Driven with
  controller keys over adb; touch was not used. The hotkeys were bound through the adb settings
  hook, not through the Button mapping page.
- Not run: keeping cheats across a re-import of the game, a rejected (invalid) cheat file, a cheat
  that really changes game memory, mod stacking, the software keyboard dialog, touch on the
  second-screen buttons, light theme, large font, and saving settings in a running game when only
  the HUD changed.
- Preset values (Battery, Balanced, Quality) are a first guess, not measured.
- Back in a game, or Save state in the pause menu, dropped to the home screen twice on 2026-11-15,
  with builds before this release. It was not reproduced in 4 runs on 2026-11-16, with full logs
  and no crash. The cause is not known.
- Rest in 60 FPS scenes was measured with one game on Turnip. Not measured: other games, the stock
  driver, input delay, and more than two launches per side.
- The known issues still apply, see
  [known issues](https://github.com/Sekyleky/Zakuroishi/blob/android-handheld/docs/known-issues.md).

Java host checks pass (settings, Java/native contract, driver, frontend, debug settings, import and
native library checks). See the
[changelog](https://github.com/Sekyleky/Zakuroishi/blob/android-handheld/docs/changelog.md).

## Install

Download **Zakuroishi-0.6.0.apk** below and install over the existing app; do not uninstall first.
Requires ARM64 Android 11+ and Vulkan 1.3. New users: see the
[setup guide](https://github.com/Sekyleky/Zakuroishi/blob/android-handheld/docs/android.md). Each game
needs separately built Android ABI-5 (or ABI-4) native game code; texture packs are optional.

`SHA256SUMS.txt` accompanies the APK. Signing certificate: `CN=Zakuroishi Release, O=Zakuroishi`,
SHA-256 `56d7615f627991436670380ccbb8b061b7bca717044d209607a98d44e4a82b0e`. No games, compiled game
libraries, saves, keys, firmware, texture packs or `Lossless.dll` files are distributed. Credits to
the upstream Zakuro, 3dsrecomp, libadrenotools, lsfg-vk, DXVK, pe-parse and volk projects and their
contributors.
