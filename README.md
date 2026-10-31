# Zakuro Android

Experimental Android frontend for [Zakuro](https://github.com/fearkov/zakuro), based on
v0.2.30 (`e174910`). It runs legally obtained decrypted 3DS games with separately
built native game code. It includes no games, firmware, keys or texture packs.

**Start here:** [Install, import and play](docs/android.md). For an existing import
that reports an incompatible native library, use the
[native-code compatibility and repair guide](docs/native-library.md). An ARM64
file from Pocket Native is not automatically compatible: this host uses ABI 4,
while the inspected Pocket Native fastmem generator uses ABI 5.

## Documentation

| Need | Canonical page |
| --- | --- |
| Requirements, game folder, optional textures, controls and saves | [Android user guide](docs/android.md) |
| Build a matching Android game library; repair without recopying a ROM | [Native library contract](docs/native-library.md) |
| Every audited Pocket Native setting and upstream Zakuro feature | [Feature parity matrix](docs/feature-parity.md) |
| Architecture, APK toolchains, developer tests | [Development](docs/development.md) |
| What actually ran, on which source and architecture | [Verification record](docs/testing.md) |
| Versioned changes, limitations and integration status | [Changelog](docs/changelog.md) |
| Controlled Azahar comparison without performance claims | [Benchmark protocol](docs/benchmark.md) |
| Desktop Zakuro usage from the pinned upstream baseline | [Upstream desktop reference](docs/upstream-desktop.md) |

## Current scope

The Android app is `io.github.zakuro.android`, with a native Android library UI,
validate-first game import and the existing Zakuro Vulkan renderer. Per-game
settings control HD texture replacement, display layout/stretch/stereo, compiled
code, supported shader/debug paths, motion, controllers, audio and console mode.
A pause menu provides save/load, restart, screen swap and gyro recalibration.

Dual-display Presentation routing and optional custom Adreno Vulkan driver packs
are implemented, with system-driver recovery. The external display uses a
readback path; physical Thor routing, sensors and driver compatibility still need
device verification. Original ROMs and Pocket Native installations are separate.

Version 0.1.3 repairs the ABI mismatch through a pinned Android game-code builder
and code-only replacement. Texture packs are optional at import and can be
attached later without copying a ROM again. In-game saves stay at a stable
per-title path. Settings are saved only on confirmation.

The [feature matrix](docs/feature-parity.md) maps every audited source setting to
its actual consumer or an explicit limitation. Pocket Native's GLES-specific
speed hacks are not automatically equivalent to this Vulkan backend; unsupported
heuristics are not presented as working controls. This is an experimental test
build, with [recorded checks and hardware limits](docs/testing.md). A performance
win over Azahar remains a benchmark goal rather than a measured result.

## Build the APK

From this repository in PowerShell, with the documented tools installed:

```powershell
./android/scripts/build-windows.ps1
# Or Docker Desktop with a working Linux engine:
./android/scripts/build.ps1
```

These build the emulator APK, not a game's `title.so`. Both use the lockfile and
write uniquely named signed test APKs. Tool versions, signing identities and the
separate native game-code build are explained in [Development](docs/development.md)
and [Native library contract](docs/native-library.md). No publication or installation
on a physical device is performed by these commands.

Zakuro and the upstream Pocket Native work are credited in the source-backed
[matrix source inventory](docs/feature-parity.md#sources-and-counting). This repository retains the
[MIT license](LICENSE). Upstream desktop game reports remain in
[COMPATIBILITY.md](COMPATIBILITY.md); they are not Android device-test results.
