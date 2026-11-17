# Zakuroishi 0.6.1

A faster Pokémon Ultra Moon intro with frame generation, the upstream Zakuro changes after 0.2.32,
clearer save-state messages and a fix for Exit to library. It installs over 0.6.0 and keeps games,
settings and in-game saves. **Save states made with 0.6.0 or earlier no longer load**, see
Before you update.

Release smoke test (AYN Thor, 2026-11-17, this APK): installed over the 0.6.1 nightly with games and saves kept, no network permission, Pokémon X boots, and an old quick state is refused with the new message. Installing over 0.6.0 stable was not run (the Thor was already on a newer version code).

## Before you update

- **Make an in-game save first.** The new microphone service changed what a save state holds.
  Loading a state from 0.6.0 or earlier shows "Quick state not loaded: it was made by another
  version of Zakuroishi" (or "Slot N …") and changes nothing. The file is kept. In-game saves are
  not affected.
- Going back to 0.6.0 needs an uninstall, which can delete saves. Export your saves first.

## What's new

- **Faster intro with frame generation.** New switch **Prepare while the game loads**
  (`frame_generation_prebuild`, Advanced, on by default). It builds the generation graph before the
  game's first picture instead of at it. Measured on the AYN Thor with Ultra Moon at 3x + HD, frame
  generation x4, two launches each:
  - Intro window 1 ran at 99.5% / 98.2% speed with the switch on and 90.8% / 90.4% with it off.
  - The worst frame in that window was 45.5-121.7 ms instead of about 526 ms.
  - Loading takes about 0.4-0.5 s longer, because the graph is now built there.
- **Upstream Zakuro after 0.2.32 merged** (up to `3fe7b15`):
  - Faster GPU draws: only what changed is set, copied and hashed.
  - Faster recompiled code: it reads the clock, takes mutexes and skips clock-polling loops itself.
    Rebuild your `title.so` with `build-title` to get the full speedup. Older ABI 4 and ABI 5
    `title.so` files still load and run, and Import and Replace native game code accept ABI 4 to 8.
  - The 3DS microphone service. Games hear silence; there is no Android microphone input.
  - New Advanced switch **Keep vertex arrays on the GPU** (`kept_arrays`, on). On the Thor it made
    no measurable difference (Ultra Moon wild battle, two runs each, speed about 100.4% both ways).
    Turn it off to compare.
- **Fixes.**
  - Exit to library no longer crashes the game process. Before, the crash also lost the play time.
  - Save and load messages are clearer and name the quick state instead of calling it "slot 0".
  - "title.so" in the help text is no longer a link.

## Not in this build

The stable APK has no network permission. The in-app updater (Menu → Check for updates…), importing
a game from a folder with only a ROM, and Pin to home screen are in the nightly build only.

## Tested

One device, an AYN Thor, on 2026-11-17, with builds of this branch before the release APK. Details:
[testing](https://github.com/Sekyleky/Zakuroishi/blob/android-handheld/docs/testing.md#frame-generation-built-before-the-first-frame-unreleased-2026-11-17-build-e9d3d51)
and
[testing](https://github.com/Sekyleky/Zakuroishi/blob/android-handheld/docs/testing.md#upstream-0232mic1-merge-and-the-intro-stall-unreleased-2026-11-17-build-b08f214).

- Frame generation prebuild: the intro stall is gone and the numbers above were measured.
- `kept_arrays`: A/B on the Thor, no difference.
- Old save state: a Pokémon X quick state from 0.6.0 was refused, the game kept running and the
  file was kept. The new message was seen in full on the toast. The save-failure text and the text
  for a numbered slot were not seen.
- Not tested: Software 3D, stereo 3D, a 60 Hz panel, other games with the new prebuild, and a
  rebuilt ABI 8 `title.so`.

## Limits

- One device. Ultra Moon numbers are with Turnip.
- The known issues still apply, see
  [known issues](https://github.com/Sekyleky/Zakuroishi/blob/android-handheld/docs/known-issues.md).
- See the [changelog](https://github.com/Sekyleky/Zakuroishi/blob/android-handheld/docs/changelog.md).

## Install

Download **Zakuroishi-0.6.1.apk** below and install over the existing app; do not uninstall first.
Requires ARM64 Android 11+ and Vulkan 1.3. New users: see the
[setup guide](https://github.com/Sekyleky/Zakuroishi/blob/android-handheld/docs/android.md). Each game
needs separately built native game code; see the
[native library guide](https://github.com/Sekyleky/Zakuroishi/blob/android-handheld/docs/native-library.md).
Texture packs are optional.

## Verify

Check the APK against `SHA256SUMS.txt`, which comes with it. Signing certificate:
`CN=Zakuroishi Release, O=Zakuroishi`, SHA-256
`56d7615f627991436670380ccbb8b061b7bca717044d209607a98d44e4a82b0e`. No games, compiled game
libraries, saves, keys, firmware, texture packs or `Lossless.dll` files are distributed. Credits to
the upstream Zakuro, 3dsrecomp, libadrenotools, lsfg-vk, DXVK, pe-parse and volk projects and their
contributors.
