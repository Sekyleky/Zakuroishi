# Known issues and untested areas

As of 0.6.0 and the 2026-11-15/16 Thor checks ([verification record](testing.md)). Update this
page with each release. Nothing here has been measured on more than one device, an AYN Thor.

## Not tested

- **The software keyboard dialog** (0.5.0). Ocarina of Time 3D's file name screen uses the game's
  own keyboard, so the dialog was not seen on a device.
- **Mod stacking** (0.5.0) on a device. Remove mods only appeared on the Game files page.
- **Release APKs, smoke test only (2026-11-16):** installing over the released 0.5.0, play past
  the first screen, saves, and a game with the second screen on were not run with them.
- **Nightly build only, not run:** pinning a game to the home screen, importing a ROM-only folder
  and adding native code later. 0.6.0 hides Pin to home screen and refuses a folder without
  native code.
- **Updates from inside the app (nightly build only, not run):** the GitHub check, download,
  checksum and signer checks, the Install / Later prompt, the system install confirmation, the
  skipped confirmation on Android 12+ after a self-installed update, and the refusal while a game
  is running. Only the version comparison, release choice, checksum-line parsing and allowed hosts
  are host-checked (`UpdaterCheck`); reading GitHub's JSON needs a device.
- **0.6.0, not run:** touch on the second-screen buttons, light theme, large font, and saving
  settings while only the HUD changed in a running game. For cheats: keeping the file across a
  re-import of the game, a rejected (invalid) file, and a cheat that really changes game memory.
- **Local wireless rooms (nightly build only, host-checked, not run on device):** hosting and
  joining a room, two consoles trading packets, the "Hosting room on" address, the lost-room
  message, the pause menu's Leave/Join, and the stable build hiding the settings. Two Zakuroishi
  consoles in one room (each its own console number) have not been tried. A game that read the
  console number before the room was joined may keep the default one. Only the settings parsing, the Java/native
  contract and the core room tests (loopback, one PC) ran.
- **Switching cheats in a running game (host-checked, not run on device):** the in-game Cheats…
  list and the reload that makes a switch apply at once. Only the Java/native command contract,
  the aarch64 check and an APK build ran.
- **Updates and DLC (host-checked, not run on device):** importing a real update or DLC, a game
  seeing them, an update with a matching or mismatched title.so, removal, and space running out
  mid-copy, and deleting them from the Storage screen.
- **Add cheat… (host-checked, not run on device):** typing a cheat in with the controller and
  on-screen keyboard, and the cheat working in game.
- **Mods… (host-checked, not run on device):** the list, removing one part and Remove all mods.
- **Presets** (Battery, Balanced, Quality): their values are a first guess, not measured.
- **Storage screen:** the "A game is open" refusal and actually deleting an item. Only the
  listing and a cancelled confirm were seen.
- **Save editor backups** (`user.backup-<ms>`, `:backups` in the picker) in a real editor.
- **The SHA-256 dialogs** for new games, replaced native code and drivers.
- **A real out-of-space failure** during import.
- **Battery watts while discharging.** On AC at 80% the HUD showed 0.1 W.
- **A long thermal run.**
- **The stock Qualcomm driver** with frame generation, beyond one field scene.
- **120 Hz on the upstream 0.2.32 merge.** The panel stayed at 60 Hz for that test.
- **A Link Between Worlds**, not installed here, so upstream's cecd change has not been run.
- **Touch on wide layouts**, beyond one tap.
- Other devices (only one is available for testing), other games (Ultra Moon is the main one), a Mali GPU, a panel that cannot
  reach 120 Hz, and any `Lossless.dll` other than 3.2.2.0.
- Code mods: only the Ocarina of Time 3D free camera was run, and not its L + R overlay.

## Known problems

- **Seen once on the Thor on 2026-11-16, cause unknown, open:** with injected key presses held
  150 ms the library highlight moved unexpectedly and one A press started the neighbouring game
  (plain presses worked; not tried with physical buttons). A game run for about 40 s still showed
  "Not played yet". In Setup help `title.so` is drawn as an underlined link. The HUD text is
  slightly overlapped by the corner of the pause dialog.

- **Slow intro with frame generation:** Ultra Moon's intro ran at 75-80% speed with frame
  generation at x4 and at 97-100% with it off (one launch each). On 2026-11-16 at x4 (120 Hz panel) the intro stayed at
  88.7-93.8% after the first two windows with the new Rest switch off, and held 100.1-100.5% with
  it on (two launches per side). With it on the panel shows about 60 distinct pictures a second
  in the intro instead of about 107. The switch is on by default, so the slow intro at x4 now only
  applies with the switch turned off (or a game that has it stored off). It was the better side in
  every measured window and changed nothing in 30 FPS scenes.
- **Drop to the home screen:** Back in a game, or Save state in the pause menu, dropped to the home
  screen twice on 2026-11-15 with builds before 0.6.0. It was not reproduced in 4 runs on
  2026-11-16 (full logs, no crash or ANR); cause unknown. A code review found no path to the home
  screen.
- **Play time** is recorded when a game is left normally; a game that is killed loses that
  session's time.
- **Save slot list in 0.5.0:** press the D-pad once before A; no row is selected when the list
  opens. Fixed in 0.6.0.
- **Town Map lines** show on the Town Map in Ultra Moon, with the second-display sharing
  switch on or off. That switch was dropped (see the testing record) and the lines remain.
- **Second display:** the bottom screen shows the game's native 320x240 picture, upscaled,
  not your render scale. Frame generation is top screen only.
- **Portrait:** `orientation=portrait` left the picture landscape on the Thor. Not yet compared with
  a build before the upstream merge, which doesn't change that code.
- **First five seconds with frame generation on a 60 Hz panel:** about 56% speed, 40-50 audio
  underruns and one stall of about 400 ms while it switches from x4 to x2. Likely that switch; not
  confirmed.
- **Anaglyph stereo** showed no red/cyan fringing in the one scene tried, before and after the
  upstream merge. Side by side does show parallax. Cause unknown.
- **Frame generation** is experimental. It needs your own `Lossless.dll` and is mostly measured on
  Turnip (one field scene on the stock driver ran at x2, 100.4%), adds
  input delay (not measured end to end), can smear text and menus, and screens that draw at
  60 reach about 80% speed at x4.
- **Per-app CPU profiles:** a tuner may have been active during the last test, so speeds
  may not match a stock device.
- **Signing:** APKs published up to 0.3.1 use a development certificate; later ones use the release
  certificate with a lineage from the old one. See the [README](../README.md#verify-the-signing-certificate).
- **Every game needs a PC build:** in 0.6.0 every new game needs a native build made on a PC. The
  nightly build can import a game without one, which then runs in the interpreter and is slower
  (interpreter only, one scene each, one device: New Super Mario Bros. 2 100%, Super Mario 3D Land
  97-98%, Ultra Moon about 74%). Other games and scenes were not run, and the import itself was
  not run on a device.
