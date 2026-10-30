# Android test APK

The frontend update is version 0.1.1 (version code 2). Its current artifact,
interface changes and frontend test scope are recorded in [frontend.md](frontend.md).
The earlier 0.1.0 validation record below remains the native runtime baseline.

The standalone app is `io.github.zakuro.android`, launcher `.Launcher`, with
NativeActivity `io.github.zakuro.android.Game`. The native crate is
`crates/zakuro-android`, Cargo package `zakuro-android`, cdylib
`libzakuro_android.so`. Only `arm64-v8a` is packaged; rendering uses Vulkan.
The source baseline is upstream Zakuro v0.2.30 (`e174910`), imported locally as
`41c3f0dddd65906d3601f6bfca653ce1cb73706f`, with the standalone Android additions.
The app manifest declares minSdk 30, targetSdk 35, version code 1 and version
name 0.1.0; the native crate version is 0.2.30. Java loads `zakuro_android` through
NativeActivity metadata; Game runs in the private `:game` process. Android 11 /
API 30 is the minimum ABI/toolchain target.
The upstream presenter requests Vulkan 1.3 (`backend/vulkan.rs`). Hardware
rendering additionally checks `VK_KHR_push_descriptor`, depth clamp, an attachment
depth format, and a graphics+compute queue (`raster/hardware.rs`). Device creation
enables Vulkan 1.3 dynamic rendering, synchronization2 and pipeline cache control.
API 30 is the native ABI floor, not a promise of Vulkan 1.3 on Android 11: query
the actual device/driver. Building the APK does not establish renderer or game
compatibility. No Vulkan loader is bundled: ash dynamically loads Android's
`libvulkan.so`, and ash-window uses Android surface extensions.

The manifest declares Vulkan 1.3 as required:

```xml
<uses-feature android:name="android.hardware.vulkan.version"
    android:version="0x00403000" android:required="true" />
```

This declares Vulkan 1.3 for filtering; it does not check the additional renderer
features above. Runtime startup errors report the Vulkan 1.3 requirement. See
[Android Vulkan engine guidance](https://developer.android.com/games/develop/vulkan/native-engine-support).

## Build from PowerShell

Prerequisites: Docker Desktop using Linux containers and PowerShell. Run from
the repository root (paths containing spaces are supported):

```powershell
./android/scripts/build.ps1
# Optional output location:
./android/scripts/build.ps1 -OutputDirectory 'C:/build output/zakuro'
```

These commands download toolchains/dependencies and build only when you run them.
No Gradle or host Android SDK is needed. The wrapper builds a Linux amd64 image,
mounts the source read-only, and invokes Bash inside Docker. Each run produces
`build/android/zakuro-android-test-<timestamp>-<id>.apk`, a SHA-256 sidecar, and
`build-<timestamp>-<id>.log`. Docker stdout/stderr are logged, and a failed Docker
build or run exits PowerShell with Docker's actual exit code. Bash uses
`set -euo pipefail`; Cargo output is never piped through `tail`.

### Windows fallback when Docker's engine is unavailable

`android/scripts/build-windows.ps1` performs the full locked release build and
APK packaging/signing on Windows. It defaults to installed SDK build-tools
36.0.0, platform `android-37.0`, and Android Studio's JBR (then JAVA_HOME).
These differ from the Docker toolchain: record which path produced a benchmark
APK rather than treating the outputs as identical.

The wrapper ensures the Android standard library is installed for the exact
`1.95.0` toolchain with `rustup target add --toolchain 1.95.0 aarch64-linux-android`
before Cargo. A target installed only for `stable` does not satisfy this named
toolchain, even if both report the same Rust version. Extract Google's Windows
NDK r27c under `build/toolchain/android-ndk-r27c`. The official
archive is 781511249 bytes; published SHA1:
`ac5f7762764b1f15341094e148ad4f847d050c38`
([official NDK release table](https://github.com/android/ndk/wiki/Home/24fe2d7ee3591346e0e8ae615977a15c0a4fba40)).
The workspace download's SHA256 is
`27e49f11e0cee5800983d8af8f4acd5bf09987aa6f790d4439dda9f3643d2494`.
Verify the archive before extraction; the wrapper does not download the NDK or
SDK. Rustup may download the pinned Rust toolchain and its Android standard library.

```powershell
./android/scripts/build-windows.ps1
# Explicit overrides when needed:
./android/scripts/build-windows.ps1 -Sdk 'C:/Android/Sdk' `
  -JavaHome 'C:/Program Files/Android/Android Studio/jbr' `
  -Ndk 'C:/toolchains/android-ndk-r27c'
```

Output goes to `build/android-windows`: unique APK, checksum, log and per-run
packaging work directory. Cargo's persistent target cache is
`[IO.Path]::GetTempPath()/zakuro-android-target`, which must contain no whitespace:
the Windows GNU host's dlltool fails with workspace target paths containing
spaces. The source, NDK and APK outputs may still have spaces.
An exclusive `.build.lock` in that cache covers compilation, stripping and
signing; a concurrent wrapper fails immediately with a clear lock error. The
lock is released in `finally`, including on build/package failure. Environment
changes apply only to the current process and are restored afterward. The
fallback uses the same API 30 linker target, Vulkan feature and 16 KB alignment
as Docker, and verifies signature, alignment, package, launcher, library and
absence of assets.

The Windows test key stays in an ACL-restricted `private-signing/test.p12` under
that output directory. Keep it private and retain it for Windows-built updates.
It is a separate signer from the Docker volume key, so APKs from the two paths
cannot update each other without transferring the original signing identity or
uninstalling the old app (which can delete app data). Windows signing writes to
a fresh APK filename; it never rewrites an existing output APK.

The Containerfile adapts local Pocket Native's `scripts/android/Containerfile`
and its direct aapt2/javac/d8/zipalign/apksigner packaging approach. Versions:
Ubuntu 22.04, NDK r27c, API 30, SDK platform 35, build tools 35.0.0,
command-line tools 11076708, Rust 1.95.0, and Java 17 compiling Java 11 bytecode.
Cargo uses `--locked`, release, `aarch64-linux-android`, and explicitly enables
`zakuro-gpu/vulkan`. The Android crate is a workspace member and its dependencies
are resolved in Cargo.lock. No Cargo files are modified by these scripts.
Missing crate, stale lockfile, compile, package, alignment, or
signature failures stop the run.

### Lockfile maintenance

The integrated lockfile is ready for `--locked` builds. After future dependency
edits, resolve on the host before using the container's read-only source mount:

```powershell
# From the repository root, only after changing dependencies.
cargo +1.95.0 metadata --format-version 1 --filter-platform aarch64-linux-android > $null
if ($LASTEXITCODE -ne 0) { throw 'Dependency resolution failed' }
git diff -- Cargo.lock
cargo +1.95.0 fetch --locked --target aarch64-linux-android
if ($LASTEXITCODE -ne 0) { throw 'Locked dependency fetch failed' }
```

Metadata resolves the workspace and updates a missing/stale lockfile without
compiling, preserving existing locked versions where possible. Review and retain
the resulting Cargo.lock with the integrated manifests. Host fetch populates the
host cache, not Docker's separate Cargo volume. Explicit `cargo +1.95.0
generate-lockfile` is an alternative for deliberate full regeneration, but it
re-resolves every package to the latest allowed versions; review that broader
change ([Cargo documentation](https://doc.rust-lang.org/cargo/commands/cargo-generate-lockfile.html)).
Do not remove `--locked` from `inside.sh` to work around integration failures:
the read-only mount intentionally cannot repair Cargo.lock. The build scripts
never generate it.

### Native library alignment

SDK/build-tools 35 match the current targetSdk 35 manifest and support
`zipalign -P 16`. NDK r27 is retained, with the Android Rust linker explicitly
given `-Wl,-z,max-page-size=16384`; ZIP alignment is also set and verified at 16 KB.
This addresses ELF segment/ZIP alignment, not all runtime page-size assumptions.
Verify every imported `title.so` independently and test on a 16 KB device before
claiming compatibility; imported code is not rebuilt by this APK workflow. See
[Android's page-size guide](https://developer.android.com/guide/practices/page-sizes).

The packager accepts `android/app/src/main/AndroidManifest.xml` with Java under
`src/main/java`, or `android/app/AndroidManifest.xml` with Java under `java`.
It expects standalone Java sources, no Gradle dependencies or generated `R`
class, and a manifest without unresolved resource references. App resources
would require a resource compilation step before integration. No assets directory
is supplied, and the packaged APK is checked for unexpected assets entries.
Original game assets, ROMs, keys, firmware and saves are never bundled.

## Signing and repeatability

Docker volume `zakuro-android-work` stores Cargo downloads, targets and temporary
signed output. A lock serializes builds using that volume. The separate
`zakuro-android-test-key` volume holds a private, locally generated PKCS12 test
key, alias `zakuro-android-test`, password `android`. It is never exported to
the repository or output directory. This is a development identity, not a release
credential. Retain the volume to install updates with the same signer; losing it
requires a new identity and may require uninstalling the existing app, losing its
private data. Do not delete the key volume casually.

Signing occurs entirely in Docker storage. The verified signed APK is copied to
a new unique filename on the host bind mount; no existing host APK is overwritten.
The packager verifies the signature, alignment, package, launcher and library.

This is a repeatable procedure, not a claim of byte-identical APKs: base image and
apt repositories are mutable, upstream tool archives are not checksum pinned,
ZIP timestamps vary, and first-use test keys differ. For a comparison, retain
the exact toolchain image by digest and record source revision plus dirty diff,
Cargo.lock hash, image ID/digest, APK SHA-256 and signing certificate digest.
Reuse that image with `-Image <retained-image-id-or-digest>`. This skips the image
build. Record `docker image inspect <image>` alongside the build log.

## Install and launch

With Android platform-tools on PATH and USB debugging authorized:

```powershell
adb -s DEVICE_SERIAL install -r 'C:/build output/zakuro/EXACT_OUTPUT.apk'
adb -s DEVICE_SERIAL shell am start -n io.github.zakuro.android/.Launcher
```

Supply legally obtained game files through the launcher's supported import/open
flow. See [benchmark.md](benchmark.md) for a controlled comparison.

## Validation status

Core full suite: 141 passed before the additional serde skip test; subsequent
targeted core checks: 5 passed. GPU: 124 passed, 2 ignored. Android host tests:
6 passed. An actual `aarch64-linux-android` Cargo check passed on the installed
stable toolchain.

The Windows Rust 1.95.0 ARM64 release build and complete APK packaging succeeded
on 2026-10-30, using NDK r27c, SDK build-tools 36.0.0/platform 37 and Studio JBR
25.0.3. Verified artifact:
`build/android-windows/zakuro-android-test-20261030-135509-e93b601a.apk`
(7,311,848 bytes), SHA256:
`eada63c2a889c194ab6cf098d714da47cdcc8f5b77362858b3a450a375e1df1b`.
The matching build log and `.apk.sha256` are beside it.

Direct checks passed: APK signature v3, 16 KB ZIP alignment, package/launcher/ABI,
native library present and no bundled assets. The native ELF is AArch64, exports
`ANativeActivity_onCreate`, has four LOAD segments aligned to `0x4000`, and has no
RPATH/RUNPATH. All 205 required global imports resolve in the six linked Android
API 30 NDK stubs: libdl, libandroid, libaaudio, liblog, libm and libc. The current
`hid.rs` serde skip change is included, as is the final `platform.rs` logger
filter for the exact normal android-activity sensor callback diagnostic. This
artifact supersedes the initial APK built before the logger filter.
The Windows test signing certificate SHA256 is
`5821ce1e0d2c1aa009bc1d269ae3865d50ce1448854d067203ce406dea967228`.

No physical-device launch, game compatibility check, or benchmark has been run.
Docker's engine was unavailable during packaging work; the Windows path is used
for the release APK validation.
