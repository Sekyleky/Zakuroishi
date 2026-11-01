# Zakuro Android 0.1.7

Smoother play and three fixes. Install over any earlier version — games and saves
are kept.

## Smoother

Measured on an AYN Thor with Pokémon Ultra Moon:

| | 0.1.6 | 0.1.7 |
|---|---|---|
| 3× resolution + HD textures | 43–50 FPS, uneven | **60 FPS**, 125 of 126 frames on time |
| 2× resolution + HD textures | 54–57 FPS | **60 FPS** |

What changed: the screens were being rebuilt every frame since 0.1.4 (now fixed);
frames are paced to the display's refresh; the app tells Android how long each
frame's work takes so it keeps the CPU clocked up; and a compiler library no
longer floods the system log.

**Recommended settings:** system Vulkan driver, Vertex shader processing **Auto**,
60 FPS cap, 2× or 3× resolution. Higher resolutions use more battery.

## Fixed

- **Pokémon Ultra Sun/Moon:** the Ultra Wormhole no longer shows a grey screen.
- **HD textures:** turning them off now sticks, including after Restart.
- **Save restore:** accepts backups you unzipped, edited and zipped again on
  Windows, macOS or Linux, and explains what's wrong when a ZIP can't be used.

## Known issue

On the stock Qualcomm driver, setting Vertex shader processing to **GPU** crashes
the game. Leave it on Auto.

## Install

Download **Zakuro-Android-0.1.7.apk** below (ARM64, Android 11+, Vulkan 1.3).
New users: [setup guide](https://github.com/Sekyleky/Zakuroishi/blob/android-v0.1.7/docs/android.md).
Details: [changelog](https://github.com/Sekyleky/Zakuroishi/blob/android-v0.1.7/docs/changelog.md),
[verification record](https://github.com/Sekyleky/Zakuroishi/blob/android-v0.1.7/docs/testing.md).
No games, compiled game libraries, keys or firmware are included. `SHA256SUMS.txt`
accompanies the APK.
