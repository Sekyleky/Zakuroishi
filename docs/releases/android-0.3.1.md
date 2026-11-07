# Zakuroishi 0.3.1

A fix for panels that stay at 120 Hz, mods that change game code, and optional, experimental frame
generation that uses your own copy of Lossless Scaling. Installs over 0.3.0 and keeps games, settings and saves (same package
and signing certificate as the published APKs; an APK you build yourself is signed with a different key and does not update over it).

## Before you try frame generation

- It is **experimental**, off by default, and meant for testers.
- It needs a **Turnip driver** and **your own paid copy of Lossless Scaling's `Lossless.dll`**. Zakuroishi is not affiliated with Lossless Scaling or its developer, and nothing from it is included.
- Screens that draw at 60, such as the title and intro, reach only **about 80% speed** at x4.
- It adds input delay of about 25 ms by design plus queue, not measured end to end, and text and menus can smear.

## What's new

- **Games no longer run fast on a panel that stays at 120 Hz.** On such a panel games ran at 102%
  with the sound lag sawing between 100 and 450 ms. **0.3.0 was affected.** Nothing to set.
- **Mods that change game code can be imported.** **Import mod…** (was Import romfs mod…) now also
  takes `code.ips`, `code.bps`, `code.bin` and `exheader.bin`. Such a mod runs best with a `title.so`
  built with it: `zakuro-build-title` takes a data folder holding `mods/TITLE_ID/` as a fourth
  argument. Tested with the
  [Ocarina of Time 3D free camera](https://github.com/Roberto-Nessy/OoT3D_Standalone_Free_Cam) mod
  (USA, its Citra files): the right stick moves the camera. The mod is not included.
- **Frame generation: experimental and off by default.** It shows pictures made between the game's
  own, so a 30 fps game looks much smoother. Turn it on per game in Performance settings.

  It **requires your own copy of Lossless Scaling**, a paid app. Its files are never included in
  Zakuroishi or downloaded by it: Performance → **Import your Lossless.dll…** translates the shaders
  of your own `Lossless.dll` on the device and deletes the app's copy. The rest is the MIT lsfg-vk
  1.0 code. Zakuroishi is not affiliated with Lossless Scaling or its developer.

  It **needs a Turnip driver**; the stock driver has not been measured. It replaces Frame
  interpolation while on: turning on one turns off the other.

  | Ultra Moon, 3× + HD, AYN Thor, Turnip, x4 | Presents/s | Game speed |
  |---|---|---|
  | Field, game at 30 fps | 119 | 99.7% |
  | Title and intro screens, which draw at 60 | about 95 | **about 80%** |

## Limits and what wasn't tested

- Frame generation adds input delay: about 25 ms by design plus queue, not measured end to end.
- Text and menus can smear, because they are guessed like everything else. Top screen only.
- There has been no long thermal test.
- Code mods: only that one mod was run, and its L + R settings overlay was not checked. With a
  `title.so` built without the mod, the changed functions run in the interpreter; that was not run
  on a device. ABI-4 libraries refuse code mods.
- Title and intro screens run slow with x4, as in the table.
- Everything was measured on one device, an AYN Thor, with one game and Lossless.dll 3.2.2.0. Other
  devices, drivers, games and DLL versions may differ.
- With 4× and Android holding the screen at 60 Hz, the game ran at half speed in testing. It now
  switches itself to 2× and says so. At release that switch had not been run on a 60 Hz screen.
  **After release (2026-11-07, build `2fc37e4`):** it ran on the Thor with a 60 Hz panel. It switched
  to x2 with about 60 presents/s, the game at 30 fps and 58-60 distinct, 100.4% speed and 0 underruns.
  Before the fallback that setup ran at 15 game fps, 50.1% speed and 67-78 underruns.
- Starting with nothing imported, meant to run the game without frame generation and say so, has
  not been run on a device either.
- With a second display, the bottom screen still shows the game's native 320×240 picture, not
  your render scale.

See the [verification record](https://github.com/Sekyleky/Zakuroishi/blob/android-v0.3.1/docs/testing.md)
and [changelog](https://github.com/Sekyleky/Zakuroishi/blob/android-v0.3.1/docs/changelog.md).
Host tests: zakuro-gpu 142 (plus 2 frame generation tests in the Vulkan build), zakuro-core 149,
zakuro-android 45, Java settings/contract checks.

## Install

Download **Zakuroishi-0.3.1.apk** below and install over the existing app; do not uninstall
first. Requires ARM64 Android 11+ and Vulkan 1.3. New users: see the
[setup guide](https://github.com/Sekyleky/Zakuroishi/blob/android-v0.3.1/docs/android.md).
Games need separately built Android ABI-5 (or ABI-4) native game code; texture packs are optional.

`SHA256SUMS.txt` accompanies the APK. No games, compiled game libraries, saves, keys, firmware,
texture packs or Lossless Scaling files are distributed. Credits to the upstream Zakuro, 3dsrecomp,
Pocket Native, libadrenotools, lsfg-vk, DXVK, pe-parse and volk projects and their contributors.
