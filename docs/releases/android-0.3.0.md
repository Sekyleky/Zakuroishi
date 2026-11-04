# Zakuroishi 0.3.0

New orange menus built for controllers, romfs mods, sound that stays in step with the
picture, and frame interpolation measured on Ultra Moon. Installs over 0.2.0 and keeps
games, settings and saves (same package and signing key).

## What's new

- **Menus.** One orange palette from the logo, in dark and light mode, across the library,
  settings and in-game menus. Every control shows focus. The pause menu is a grid with Resume
  focused; Load state and Exit ask first with Cancel focused. The library opens with Play
  focused; L1/R1 page, Y opens Settings, X Saves, and B no longer exits the app.
- **Settings reorganised.** Everyday options come first, and troubleshooting switches moved to
  a new Advanced page. Start = Done, B asks "Discard changes?", and X clears a button binding.
- **Romfs mods.** Game settings → **Import romfs mod…** takes a Luma3DS or Citra mod folder.
  Mods that change game code are refused, because the game runs its prebuilt native code.
- **Sound stays in step with the picture.** On the Thor, sound now holds 100–125 ms behind
  instead of drifting to about 230 ms. On by default.
- **Quick taps are no longer lost.** A press shorter than one frame now reaches the game. Title
  to field with two quick A taps worked in 23 of 23 runs, against 11 of 16 before. On by default.
- **Frame interpolation, measured.** The existing **Frame interpolation** setting draws a picture
  between the game's own, at normal game speed. Off by default; turn it on per game with a Turnip
  driver:

  | Ultra Moon, 3× + HD, AYN Thor | Distinct pictures/s, off → on | Game speed, on |
  |---|---|---|
  | Turnip | 30 → 58–60 | 100% |
  | Stock Qualcomm driver | 30 → 39–46 | **65–77%, with sound dropouts: don't use it** |

  It should add about one refresh (16.7 ms) of input delay.
- **Shorter display queue** (**Vsync: show frames sooner**, off by default). On Turnip a finished
  frame reaches the screen in about 40 ms instead of 57 ms. No gain on the stock driver.
- **Optional shader warm-up before the game starts** (off by default). In testing it added about
  10 s to startup and barely changed the worst frame, so most people can leave it off.
- **HUD.** The FPS overlay now shows `game` and `distinct` pictures per second next to frames shown.

## Limits and what wasn't tested

- Everything was measured on one device, an AYN Thor, mostly with one game. Other devices,
  drivers and games may differ.
- Light mode of the new menus has not been checked on a device.
- The new sound queue was measured with counters. Nobody has judged by ear whether its
  time-stretching is audible.
- Frame interpolation: no door or map-change test, no other 30 fps games, and artifacts were
  judged from a small set of screenshots. Worst frames while walking into a battle ranged from
  33 to 92 ms across runs, and the cause of the spike isn't known.
- With a second display, the bottom screen still shows the game's native 320×240 picture, not
  your render scale. A fix is in progress and is not in this release.
- The **Low-latency sound** switch (off) gained nothing on the Thor.

See the [verification record](https://github.com/Sekyleky/Zakuroishi/blob/android-v0.3.0/docs/testing.md)
and [changelog](https://github.com/Sekyleky/Zakuroishi/blob/android-v0.3.0/docs/changelog.md).
Host tests: zakuro-gpu 142, zakuro-core 148, zakuro-android 43, Java settings/contract checks.

## Install

Download **Zakuroishi-0.3.0.apk** below and install over the existing app; do not uninstall
first. Requires ARM64 Android 11+ and Vulkan 1.3. New users: see the
[setup guide](https://github.com/Sekyleky/Zakuroishi/blob/android-v0.3.0/docs/android.md).
Games need separately built Android ABI-5 (or ABI-4) native game code; texture packs are optional.

`SHA256SUMS.txt` accompanies the APK. No games, compiled game libraries, saves, keys, firmware
or texture packs are distributed. Credits to the upstream Zakuro, 3dsrecomp, Pocket Native and
libadrenotools projects and their contributors.
