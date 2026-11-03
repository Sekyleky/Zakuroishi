# Zakuroishi 0.2.0

Zakuroishi now runs the newer recompiled game code (3dsrecomp ABI 5) and brings in
upstream Zakuro 0.2.31's renderer and CPU work. Installs over 0.1.9 and keeps games,
settings and saves (same package and signing key).

## What's new

- **ABI-5 game code.** `build-title` now makes ABI-5 libraries (3dsrecomp `73b8f9c`),
  which run svcs in place. ABI-4 libraries still load; rebuild them for the faster
  code and use **Settings → Replace native game code** (keeps ROM, textures, saves).
- **Upstream 0.2.31 renderer and CPU work.** Draws are shaded on other threads ahead
  of the walk that draws them (**Performance → Shade draws on other threads**, on by
  default), textures are decoded again only when their memory changed, mipmaps
  sample their smaller sizes, and the CPU vertex JIT compiles fast first and in full
  once a program is hot. Plus framebuffer flip, projected texture and stencil fixes.
- **Save editors can open saves directly.** Each game's saves show in Android's file
  picker as **Zakuroishi saves**, so editors such as PKForge can open and write them
  in place. Writes are refused while the game runs and back up to `user.previous`
  first.

## Tested

On an AYN Thor (Turnip), every game with a rebuilt ABI-5 `title.so`:

- Pokémon Ultra Moon at 3× + HD textures: save loads, field and map changes hold
  60 fps, wild battles run at 60 fps (worst battle-start frame 44 ms).
- Booted to title, menus or attract demo at ~60 fps with no crash: Ultra Sun, Alpha
  Sapphire, X, Y, Rutile Ruby, Ocarina of Time 3D, Majora's Mask 3D, Super Mario 3D
  Land, New Super Mario Bros. 2, Super Monkey Ball 3D. Only booting was tested.

Host tests: zakuro-gpu 142, zakuro-core 147, settings 31, Java settings/contract checks.
See the [verification record](https://github.com/Sekyleky/Zakuroishi/blob/android-v0.2.0/docs/testing.md)
and [changelog](https://github.com/Sekyleky/Zakuroishi/blob/android-v0.2.0/docs/changelog.md).

## Limits

- Battle start still costs ~35–45 ms on its worst frame.
- Omega Ruby and Rutile Ruby share a title id, so only one fits in the library.
- Results are from one device; other devices and drivers may differ.

## Install

Download **Zakuroishi-0.2.0.apk** below and install over the existing app; do not
uninstall first. Requires ARM64 Android 11+ and Vulkan 1.3. New users: see the
[setup guide](https://github.com/Sekyleky/Zakuroishi/blob/android-v0.2.0/docs/android.md).
Games need separately built Android ABI-5 (or ABI-4) native game code; texture packs
are optional.

`SHA256SUMS.txt` accompanies the APK. No games, compiled game libraries, saves, keys,
firmware or texture packs are distributed. Credits to the upstream Zakuro, 3dsrecomp,
Pocket Native and libadrenotools projects and their contributors.
