# Native game-code compatibility and repair

The APK's `libzakuro_android.so` is the emulator. A game's `title.so` is separate
ahead-of-time recompiled game code. A successful APK build does not produce it.
Use only trusted code built from the game/version you own; it executes inside
the app's private process and can access the app's files and saves.

## Compatibility contract

All of these must agree; filename and ELF machine alone are insufficient:

| Layer | Required for this Android host | Evidence |
| --- | --- | --- |
| OS/CPU | Android ELF64 little-endian AArch64 ET_DYN, Android API 30-compatible imports | `android/scripts/Containerfile`, `ImportPlan.java`, NDK readelf |
| Recompiler interface | Exported `recomp_abi` **value 4**, not merely a symbol with that name | Cargo.lock pins `recomp-abi` to `cec1270db38411f9c02d3f356fc3ed6c3f007424`; matching abi/src/lib.rs:14 |
| Generator contract | Compatible `recomp.h`, entry/module tables and generation/origin layout from that same revision | 3dsrecomp abi/src/lib.rs:311 and docs/interface.md |
| Memory access | Callback/page-table ABI 4; no ABI-5 fastmem context requirement | Android host `crates/zakuro-core/src/recompiled.rs`; local PN recompiler/abi/src/lib.rs:14 is ABI 5 |
| Guest code | Same title ID, game region/version, update and code-changing mods as the input used to recompile | Loader code/origin checks; title ROM header |
| Native dependencies | All required dynamic symbols available in Android's public system libraries; no desktop glibc or host DLL imports | `llvm-readelf --dynamic --dyn-syms --version-info` |
| Page sizes | 16 KB-aligned LOAD segments for modern devices; runtime code must also avoid fixed-page assumptions | NDK linker `-Wl,-z,max-page-size=16384`; readelf program headers |

The inspected local Pocket Native branch is `thor-textures`, HEAD `c16f4e3`,
with additional dirty Vulkan/driver changes. Its recompiler ABI is **5** and its
Linux kit invokes `RECOMP_CFLAGS=-DRECOMP_FASTMEM` with a Linux AArch64 compiler
(`scripts/container/inside.sh:35`). That library is not an ABI-4 Android library.
The reported game failure is consistent with this mismatch. Never change an
exported ABI number, remove a loader check, or rename Linux code as an Android
compatibility fix: these actions do not change structure layouts or memory access.

Import preflight is a rejection boundary, not a guarantee that arbitrary native
code is safe. The ELF/header/ABI checks can reject known incompatibilities before
copying gigabytes; only the actual Android loader and a controlled execution test
can establish that a particular library loads and runs on that architecture.

## Build matching code safely

Use the exact generator revision pinned by this APK, not the current default
branch of Pocket Native or 3dsrecomp. The reference is
[3dsrecomp at cec1270](https://github.com/fearkov/3dsrecomp/tree/cec1270db38411f9c02d3f356fc3ed6c3f007424)
and its [interface contract](https://github.com/fearkov/3dsrecomp/blob/cec1270db38411f9c02d3f356fc3ed6c3f007424/docs/interface.md).
If Cargo.lock changes, re-audit this instruction before generating more libraries.

The workspace tool uses that exact generator dependency and applies the Android
compiler flags directly, without a shell wrapper:

```powershell
cargo +1.95.0 build --locked --release -p zakuro-build-title
./target/release/zakuro-build-title.exe 'PATH_TO_DECRYPTED_ROM' 'NEW_OUTPUT_FOLDER' 'build/toolchain/android-ndk-r27c'
```

On Windows GNU, use an existing writable Cargo target path **without spaces**
(e.g. `$env:CARGO_TARGET_DIR='C:/build/zakuro-target'`) if dlltool rejects the
workspace path. Then run the executable from that target directory's `release/`.
The output directory must not already exist. The tool leaves the source ROM,
previous builds, textures and saves untouched and writes `title.so` plus generated
sources/objects in the new directory. It can take several minutes and substantial
disk space. No output directory is reused across ABI revisions.

The compiler target is `aarch64-linux-android30`; required generator flags include
`-O2 -ffp-contract=off -fno-math-errno`, PIC, hidden visibility, no undefined
symbols and 16 KB LOAD alignment. No `RECOMP_FASTMEM` is defined. A Windows-hosted
generator may name its intermediate output `.dll`; the emitted format is Android
ELF because the compiler is the Android NDK. The tool copies the result to
`title.so`. Renaming an actual PE DLL or Linux/glibc library remains invalid.

### Verified local Ultra Moon rebuild

The original Ultra Moon dump was rebuilt successfully into
`build/ultra-moon-android-abi4/title.so` (286,066,048 bytes), using NDK r27c and the
pinned generator. SHA256:

```text
3f688826dbdc187c19d8d0cc9d25eac67de0f06e5a0b4ea4c6b9b961211cc475
```

Static inspection confirms ARM64 ELF, exported ABI **4**, no fastmem export,
Android `libm.so` / `libdl.so` / `libc.so` dependencies, and 0x4000 LOAD alignment.
The original supplied Pocket Native library was independently inspected as ABI
**5** with fastmem. This confirms a real mismatch and a separately built matching
replacement. It does **not** establish that the rebuilt game has executed on an
ARM64 Android device. Keep that distinction in support and release notes.

Useful static inspection with the workspace NDK on Windows:

```powershell
$ndkBin = './build/toolchain/android-ndk-r27c/toolchains/llvm/prebuilt/windows-x86_64/bin'
& "$ndkBin/llvm-readelf.exe" --file-header --program-headers './title.so'
& "$ndkBin/llvm-readelf.exe" --dynamic --dyn-syms --version-info './title.so'
Get-FileHash './title.so' -Algorithm SHA256
```

Expect AArch64, Android system dependencies, LOAD alignment at least `0x4000`,
the ABI-4 interface exports and no host RPATH/RUNPATH. `readelf --dyn-syms` shows
that a data symbol exists, not the integer stored in it: use the tested ELF
inspection helper or a controlled loader probe to read `recomp_abi` and any
`recomp_fastmem` value. Do not execute an untrusted library merely to inspect it.

## Repair an existing import

Use version 0.1.3's **Replace native game code** action in the affected game's
Settings. Select the replacement Android `title.so`. Preflight runs before
staging and checks capacity for the new library plus a backup of the old one.
Only code is replaced: the ROM, optional pack and stable in-game saves remain.
The old library backup is synced before a single atomic replacement of title.so.
Restart the game after replacement.

Do not uninstall to work around an ABI error: uninstall can erase saves. Updating
the APK with the same signing identity is separate from replacing game code.

An actionable error report includes the APK version/hash, source/ABI revision,
title ID/version, library SHA256, detected ABI/fastmem fields and Android device
details. A user-visible error and repair path should come first; logcat is
additional diagnostic evidence, not the only recovery instruction.
