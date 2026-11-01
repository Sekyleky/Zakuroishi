# Zakuroishi 0.1.9

The app is now **Zakuroishi**. This release targets the stutters you feel in play —
battle starts, the first field frame after the title screen, and new attacks or
cutscenes — and makes the in-game HUD actually appear. Installs over 0.1.8 and keeps
games, settings and saves (same package and signing key).

## Smoother play

Every result below is from an AYN Thor (Ultra Moon, 3× + HD textures, Turnip), same
build, switch on vs off, 3 alternating runs each unless noted.

- **Shader warm-up at launch.** Gameplay spikes came from new CPU vertex programs
  running on the slow interpreter while they compiled. Zakuroishi now remembers each
  game's vertex program sources (the game's shader words, never machine code) and the
  GPU pipelines it used, and prepares them in the background at the next launch.
  Worst battle-start frame **66.7 → 41.3 ms**. The first session of a game still
  compiles as it goes.
- **GPU pipelines persist on Android.** The pipeline cache never saved on Android
  before (no cache directory); every session recompiled everything. **Settings →
  Clear shader cache** removes a game's caches.
- **Fast texture decoding** for every 3DS texture format, not only ETC1 (bit-identical).
  Worst battle-start frame **53.8 → 37.6 ms**.
- **HD-pack lookups in the background.** The first field frame after the title screen
  brings ~28 new textures; matching them against the HD pack moved off the game
  thread. Field-entry frame **58.9 → 46.8 ms**.
- **HD picture uploads** use pooled GPU memory, a per-frame size limit and background
  copies; vertex-shader compiles queue on two workers instead of a thread each.
- **Vsync pacing** restored at normal speed (more on-time frames than 0.1.8).

Each change has its own switch under **Settings → Performance**, on by default, so it
can be compared on other devices.

## HUD and on-screen controls

- The clock, battery and FPS overlay never drew (the game's renderer owns the window
  surface). It now has its own window and shows battery temperature and power, FPS,
  emulation time per frame, the worst frame and slow frames of the last second, and
  shader compiles.
- **Touch controls** were invisible for the same reason and now show and work.
- The on-screen **Pause** button is now **Controls → Show pause button**, off by
  default; Back or the controller's menu button pauses.

## Fixes

- GPU vertex shaders on the stock Qualcomm driver fall back to Auto instead of crashing.

## Limits

- Battle start still costs ~35–45 ms on its worst frame (CPU vertex processing and
  texture decoding); the field-entry frame (~45 ms) comes before the warm-up finishes.
- Forcing GPU vertex shading was slower on the Thor; keep **Auto**.
- Results are from one device and one game; other devices and drivers may differ.

See the [verification record](https://github.com/Sekyleky/Zakuroishi/blob/android-v0.1.9/docs/testing.md)
and [changelog](https://github.com/Sekyleky/Zakuroishi/blob/android-v0.1.9/docs/changelog.md).
Host tests: zakuro-gpu 135, zakuro-core 145, settings 31, Java settings/contract checks.

## Install

Download **Zakuroishi-0.1.9.apk** below and install over the existing app; do not
uninstall first. Requires ARM64 Android 11+ and Vulkan 1.3. New users: see the
[setup guide](https://github.com/Sekyleky/Zakuroishi/blob/android-v0.1.9/docs/android.md).
Games still require separately built, compatible Android ABI-4 native game code;
texture packs are optional.

`SHA256SUMS.txt` accompanies the APK. No games, compiled game libraries, saves, keys,
firmware or texture packs are distributed. Credits to the upstream Zakuro, 3dsrecomp,
Pocket Native and libadrenotools projects and their contributors.
