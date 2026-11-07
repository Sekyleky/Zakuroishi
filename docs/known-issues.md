# Known issues and untested areas

As of the 2026-11-07 Thor re-test ([verification record](testing.md)). Update this page
with each release. Nothing here has been measured on more than one device, an AYN Thor.

## Not tested

- **Azahar comparison.** On hold for now, by choice; none has been run. See the [benchmark](benchmark.md).
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

- **Town Map lines** show on the Town Map in Ultra Moon, with the second-display sharing
  switch on or off. That switch was dropped (see the testing record) and the lines remain.
- **Second display:** the bottom screen shows the game's native 320x240 picture, upscaled,
  not your render scale. Frame generation is top screen only.
- **Portrait:** `orientation=portrait` left the picture landscape on the Thor. Not yet compared with
  a build before the upstream merge, which doesn't change that code.
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
- **Setup is developer-only:** each game needs a native build made on a PC.
