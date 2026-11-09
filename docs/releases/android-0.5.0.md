# Zakuroishi 0.5.0

Save states in 9 slots beside the quick save, mods that stack, the software keyboard games use for
names, and core fixes from Pocket Native. Installs over 0.4.0 and keeps games, settings and saves.

**This release has not been run on a device.** It passes the host checks and CI, and its APK is
signed with the release key, but none of the changes below has been tried on a handheld yet. If
something breaks, please open an issue; 0.4.0 stays available on the releases page, but going back
needs an uninstall, so export your saves first.

## What's new

- **Software keyboard.** A game asking for a name or other text, such as Ocarina of Time 3D's file
  name screen, now opens an Android text box with the game's own prompt, length limit and buttons.
  Before, nothing appeared and the game waited forever.
- **Save slots.** **Save to slot…** and **Load from slot…** in the game menu keep up to 9 states per
  game, listed with the time each was saved. **Save state** and **Load state** still use the quick
  slot, as before.
- **Mods.**
  - Import mod… asks whether to add the mod to the game's current mods or replace them. When files
    clash the newer mod wins. Two mods that both change game code are refused.
  - **Remove mods** deletes a game's mods and keeps its texture pack.
  - A mod folder is found when you pick a folder above it, and `exefs/` banner, icon and logo files
    are ignored. ZIPs still need extracting first.
- **From Pocket Native:**
  - A request to a system service no longer waits when no other thread is ready, so an idle core
    doesn't wait for the GPU on every request. The old wait is the Performance switch
    `request_wait_idle`, off by default; turn it on if a game misbehaves.
  - Country code lookups, a sound filter that switched on by mistake, and geometry shaders in fixed
    primitive mode (Kid Icarus: Uprising).

## Before you update

- Quick states saved by 0.4.0 may fail to load, with a message, because a sound filter's state
  changed. Make new ones. In-game saves are not affected.

## Limits

- Not run on a device, as above. Not measured: whether the request change makes games faster.
- The known issues from 0.4.0 still apply, see
  [known issues](https://github.com/Sekyleky/Zakuroishi/blob/android-v0.5.0/docs/known-issues.md).

Host tests: zakuro-gpu 148 passed and 2 ignored, zakuro-core 158, zakuro-android 46, save archive
114, Java settings, contract and import checks. See the
[changelog](https://github.com/Sekyleky/Zakuroishi/blob/android-v0.5.0/docs/changelog.md).

## Install

Download **Zakuroishi-0.5.0.apk** below and install over the existing app; do not uninstall first.
Requires ARM64 Android 11+ and Vulkan 1.3. New users: see the
[setup guide](https://github.com/Sekyleky/Zakuroishi/blob/android-v0.5.0/docs/android.md). Games need
separately built Android ABI-5 (or ABI-4) native game code; texture packs are optional.

`SHA256SUMS.txt` accompanies the APK. No games, compiled game libraries, saves, keys, firmware,
texture packs or Lossless Scaling files are distributed. Credits to the upstream Zakuro, 3dsrecomp,
Pocket Native, libadrenotools, lsfg-vk, DXVK, pe-parse and volk projects and their contributors.
