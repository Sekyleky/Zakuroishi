# Zakuroishi 0.4.0

Merged with upstream Zakuro 0.2.32, a new Tools menu, save-editor backups, and a dedicated signing
key. Installs over 0.3.1 and keeps games, settings and saves.

## Before you update

- **Quick states from earlier versions stop loading.** Trying to load one shows a message naming both
  versions. Make new ones. In-game saves are not affected.
- **This is the first release signed with the new release key.** It installs over earlier published
  APKs without an uninstall. Once it is installed, **older APKs won't install over it**, so going
  back to 0.3.1 would mean uninstalling, which deletes the app's data. Export your saves first. The
  fingerprint is in the
  [README](https://github.com/Sekyleky/Zakuroishi/blob/android-v0.4.0/README.md#verify-the-signing-certificate).

## What's new

- **Merged with upstream Zakuro 0.2.32.** Upstream's history is now part of this project instead of
  ported commits. The switches this app adds for shaders, textures and uploads stay. Upstream's
  macOS builds and desktop settings aren't part of the Android app.
- **Tools menu** in the library header:
  - **Storage** lists old imports, previous native code, texture and mod backups and unfinished
    imports with their sizes, and deletes one at a time after a confirm. It never lists an active
    import or anything in `savedata`. Deleting an old import also deletes its quick states.
  - **Export diagnostics** writes app, device and log details to a text file. Picker addresses are
    removed; file paths remain, so read it before you share it.
  - **Export settings** and **Import settings** use a JSON file of per-game settings, controller
    mappings and enhancements. No drivers, saves or ROMs. Import refuses the whole file on any
    invalid value.
- **Save editors:** before an editor writes, the 3 newest backups of the in-game saves are kept, and
  you can browse them read-only under `:backups` in each game's folder in the file picker.
- **Imports** keep the larger of 256 MiB and 5% of free space and report running out of space plainly.
  Replacing mod parts is all-or-nothing.
- New games, replaced native code and drivers show a SHA-256. Libraries from an older generator get a
  rebuild hint, and damaged table counts are rejected at import.
- **HUD:** battery watts appear only once the unit is certain.
- **Texture packs:** a failing reader no longer aborts or leaves an HD texture stuck; the original
  texture is used.
- `file://` launch links are no longer accepted. `zakuro://game/ID` still launches.

## Measured against the same work without the merge

AYN Thor, Ultra Moon 3× + HD, Turnip, frame generation on (x2 on this 60 Hz panel), three runs per
build.

| | Without merge | With merge |
|---|---|---|
| Speed | 100.1-100.4% | 100.0-100.7% |
| Presents per second | 60.01-60.03 | 60.02-60.03 |
| Latency, median | 38.0-40.4 ms | 40.1-40.5 ms |
| Average emulation time per frame | 4.77-5.06 ms | 4.93-5.73 ms |
| Worst emulation frame | 36.3-45.1 ms | 24.8-33.0 ms |
| Audio underruns after the first 5 s | 0 | 0 |

This isn't claimed as a speedup: there are only three runs per build, and the merge's runs were about
4 °C warmer.

## Limits and what wasn't tested

- Everything was measured on one device, an AYN Thor.
- **120 Hz was not measured with this merge:** the panel stayed at 60 Hz.
- **A Link Between Worlds was not run**, so upstream's `cecd` change is untried here.
- **Storage:** really deleting an item and the "A game is open" refusal have not been tried on a device.
- Not tried either: save backups in a real editor, the SHA-256 dialogs, a real out-of-space failure,
  battery watts while discharging, a long thermal run, and touch on wide layouts beyond one tap.
- **Known problems:** `orientation=portrait` stays landscape on the Thor (this merge doesn't change
  that code; an older build wasn't compared). Anaglyph stereo showed no red/cyan fringing in the one
  scene tried, with or without the merge.
- **The first five seconds of a game** with frame generation on a 60 Hz screen run at about 56%,
  with crackling sound (40-50 underruns) and one stall of about 400 ms, before and after this
  release. That is when frame generation switches from x4 to x2; the switch is the likely cause but
  that isn't confirmed.
- Frame generation is still experimental. It ran at x2 and 100.4% in one field scene on the stock
  driver; everything else was measured on Turnip.

See the [verification record](https://github.com/Sekyleky/Zakuroishi/blob/android-v0.4.0/docs/testing.md),
[known issues](https://github.com/Sekyleky/Zakuroishi/blob/android-v0.4.0/docs/known-issues.md) and
[changelog](https://github.com/Sekyleky/Zakuroishi/blob/android-v0.4.0/docs/changelog.md). Host tests:
zakuro-gpu 147 passed and 2 ignored, zakuro-core 151, zakuro-android 45, save archive 114, Java
settings and contract checks.

## Install

Download **Zakuroishi-0.4.0.apk** below and install over the existing app; do not uninstall first.
Requires ARM64 Android 11+ and Vulkan 1.3. New users: see the
[setup guide](https://github.com/Sekyleky/Zakuroishi/blob/android-v0.4.0/docs/android.md). Games need
separately built Android ABI-5 (or ABI-4) native game code; texture packs are optional.

`SHA256SUMS.txt` accompanies the APK. No games, compiled game libraries, saves, keys, firmware,
texture packs or Lossless Scaling files are distributed. Credits to the upstream Zakuro, 3dsrecomp,
Pocket Native, libadrenotools, lsfg-vk, DXVK, pe-parse and volk projects and their contributors.
