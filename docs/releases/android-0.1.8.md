# Zakuro Android 0.1.8

Map a controller button to held fast-forward, launch installed games from a
frontend, and use the latest normal-speed pacing improvements. This remains an
experimental, unofficial Android fork of Zakuro.

## Controller mapping and speed

- **Settings → Button mapping** is a dedicated per-game menu with visible custom
  assignments, clearing and reset. Existing Nintendo/Xbox presets remain.
- Map **Fast-forward (hold)** to a physical controller button and choose its
  speed. The default hotkey speed is **150%**; release restores the game's saved
  normal speed. No button is reassigned automatically on a fresh installation.
- **Performance → Game speed limit** offers normal, 1.5x, 2x, 3x, 4x and unlimited.
  The presentation cap is separate; actual speed depends on the game and device.

## Frontend library

- **Frontends → Export library** creates a folder of small `.zkr` references for
  already imported games, with setup instructions and a Cocoon platform JSON.
  ROMs, native code, textures and saves stay in Zakuro.
- **Prepare iiSU config** reads your current emulator catalog and saves a
  separate edited copy, preserving other consoles/players and adding Zakuro plus
  the `.zkr` extension. Import that copy through iiSU's Updates settings.
- External launches use the installed game's settings, native-code validation
  and saves. Original decrypted ROM URIs can also identify installed games.
  Reopening the active title reuses its activity; switching titles asks before
  discarding unsaved progress.

Follow the [frontend setup guide](https://github.com/Sekyleky/Zakuroishi/blob/android-v0.1.8/docs/frontends.md).
Custom-player configuration is required; Zakuro is not yet in the frontends'
built-in emulator catalogs.

## Pacing and rendering

- Normal speed uses the same preferred mailbox presentation path as faster
  speed settings. A bounded scheduling catch-up prevents a slow tick from
  unnecessarily delaying the next cheap tick.
- Unchanged CPU screen images are reused instead of uploaded again. HD upload
  allowances apply per emulated refresh, and vertex batching avoids unnecessary
  output-register copies.
- The overlay and benchmark log distinguish emulation speed from presentation
  submissions. Android performance hints follow the active speed target.
- Includes the fix that retains rendering resolution after switching apps.

## Verification and limits

On the AYN Thor: held R3 changed Ultra Moon from 100% to 150% and back; a real
Android Files content-URI launch opened an exported game reference; repeated
launches reused one game activity; repeated exports retained two game references.
The iiSU assistant produced a full catalog copy and left the original unchanged.
Native Android host tests (30), Java settings/contracts/reference checks, and
Android-runtime reference/configuration checks passed.

**Not yet verified:** scanning and launching inside Cocoon/iiSU themselves,
switching between two live titles, and a long held-speed gameplay session.
Some isolated stutters remain at high resolution. No across-game 3x/4x speed
guarantee or controlled performance claim is made.

Turnip had more uncapped headroom than the system driver in one matched Ultra
Moon overworld scene, but the tested custom driver can show a corner artifact.
Driver choice remains device/game dependent. Keep vertex processing on **Auto**;
forcing **GPU** on the tested stock driver can crash.

See the [verification record](https://github.com/Sekyleky/Zakuroishi/blob/android-v0.1.8/docs/testing.md)
and [changelog](https://github.com/Sekyleky/Zakuroishi/blob/android-v0.1.8/docs/changelog.md).

## Install

Download **Zakuro-Android-0.1.8.apk** below. Install over the existing app to keep
imported games, settings and saves; do not uninstall first. Requires ARM64 Android
11+ and Vulkan 1.3. New users should follow the
[setup guide](https://github.com/Sekyleky/Zakuroishi/blob/android-v0.1.8/docs/android.md).
Games still require separately built, compatible Android ABI-4 native game code.
Texture packs are optional.

`SHA256SUMS.txt` accompanies the APK. No games, compiled game libraries, saves,
keys, firmware or texture packs are distributed. Credits to the upstream Zakuro,
3dsrecomp, Pocket Native and libadrenotools projects and their contributors.
