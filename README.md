# Zakuroishi

**[Download the latest Android release](https://github.com/Sekyleky/Zakuroishi/releases/latest)** ·
[Setup guide](docs/android.md) · [Report a problem](https://github.com/Sekyleky/Zakuroishi/issues/new/choose)

An unofficial community fork, not an official release from the Zakuro developers.
Previously named Zakuro Android. Existing installs update in place; the Android
package, imported games and save locations are unchanged.
Requires **64-bit ARM Android 11+ and Vulkan 1.3**. Games need a decrypted ROM and
matching Android ABI-5 native code built on a computer (ABI-4 builds still run); the APK does not compile
games on your handheld. **HD texture packs are optional.**

Experimental Android frontend for [Zakuro](https://github.com/fearkov/zakuro), based on
upstream v0.2.30 (`e174910`) plus ported 0.2.31 changes
([listed](docs/upstream-desktop.md#ported-upstream-changes)). It runs legally obtained
decrypted 3DS games with separately built native game code. It includes no games,
firmware, keys or texture packs.

**This is a developer preview.** Each game needs a native build made on a PC, with Rust and
the Android NDK installed; the APK does not make one.
Azahar is simpler to set up. Everything here was tested on one device (an AYN Thor),
mostly with one game (Pokémon Ultra Moon), and has not been compared with Azahar.
See [known issues](docs/known-issues.md) and the [security notes](SECURITY.md): native
game code and drivers you import run with the app's access.

**Start here:** [Install, import and play](docs/android.md). For an existing import
that reports an incompatible native library, use the
[native-code compatibility and repair guide](docs/native-library.md). An ARM64
file from Pocket Native is not automatically compatible: this host uses upstream
3dsrecomp's ABI 5 (and still runs ABI 4), while the inspected Pocket Native
fastmem generator's own ABI 5 needs a fast-memory context this host lacks.

## Documentation

- [Controller mapping, fast-forward and frontend setup](docs/frontends.md)

| Need | Canonical page |
| --- | --- |
| Requirements, game folder, optional textures, controls and saves | [Android user guide](docs/android.md) |
| Build a matching Android game library; repair without recopying a ROM | [Native library contract](docs/native-library.md) |
| Every audited Pocket Native setting and upstream Zakuro feature | [Feature parity matrix](docs/feature-parity.md) |
| Architecture, APK toolchains, developer tests | [Development](docs/development.md) |
| What actually ran, on which source and architecture | [Verification record](docs/testing.md) |
| Versioned changes, limitations and integration status | [Changelog](docs/changelog.md) |
| What is not tested or not working | [Known issues](docs/known-issues.md) |
| What you trust, what the app checks, how to report | [Security](SECURITY.md) |
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

Version 0.1.7 presented 60 refreshes per second in Pokémon Ultra Moon at 3× with HD
textures on an AYN Thor, while that game draws 30 pictures per second; it also fixed the
Ultra Wormhole grey screen and made save restore accept ZIPs re-made on any computer.
The 0.1.3 foundation repairs the ABI mismatch through a pinned Android game-code builder
and code-only replacement. Texture packs are optional at import and can be
attached later without copying a ROM again. In-game saves stay at a stable
per-title path. Settings are saved only on confirmation.

The [feature matrix](docs/feature-parity.md) maps every audited source setting to
its actual consumer or an explicit limitation. Pocket Native's GLES-specific
speed hacks are not automatically equivalent to this Vulkan backend; unsupported
heuristics are not presented as working controls. This is an experimental test
build, with [recorded checks and hardware limits](docs/testing.md). A performance
win over Azahar remains a benchmark goal rather than a measured result.

## Verify the signing certificate

Published APKs are signed with a **development certificate**, `CN=Zakuro Android Windows Test`,
not a dedicated release key. Its SHA-256 fingerprint is:

```text
5821ce1e0d2c1aa009bc1d269ae3865d50ce1448854d067203ce406dea967228
```

Check it with `apksigner verify --print-certs APK`. Updates only install over an APK with
the same certificate. A release key is planned; the move is meant to keep updates working
and has not been done (see [Development](docs/development.md#planned-move-to-a-release-key)).

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
on a physical device is performed by these commands. A self-built APK is signed with a
different key than the published ones and does not update over them.

Zakuro and the upstream Pocket Native work are credited in the source-backed
[matrix source inventory](docs/feature-parity.md#sources-and-counting). This repository retains the
[MIT license](LICENSE). Upstream desktop game reports remain in
[COMPATIBILITY.md](COMPATIBILITY.md); they are not Android device-test results.
