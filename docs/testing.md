# Verification record

## 0.1.7 frame pacing, performance and fixes (2026-10-31)

- Delivery: `build/releases/0.1.7/Zakuro-Android-0.1.7.apk` (9,262,166 bytes),
  SHA256 `f3edd0cefd4404772ac8ee60df41fd091532a33e48d006efc29ce853047b6cfc`,
  same certificate as 0.1.4–0.1.6. Source: `android-handheld` at the release tag.
- Device: AYN Thor (Snapdragon 8 Gen 2, Adreno 740, Android 13), stock Qualcomm
  Vulkan driver, Pokémon Ultra Moon, New 3DS mode, vertex shaders Auto (CPU).
- Method: the `benchmark` logcat line (300-frame windows), SurfaceFlinger
  `--latency` present intervals, and `simpleperf record --app` (the APK is
  profileable). Scenes are the same save and overworld spot, not a scripted route,
  so treat numbers as indicative.

| Ultra Moon | 0.1.6 | 0.1.7 |
|---|---|---|
| 2×, HD on | 54–57 FPS, core ~10 ms | 59.5–60.2 FPS, core 6.4–6.8 ms |
| 3×, HD on | 43–50 FPS, core ~10.5 ms | 59.9–60.4 FPS, core 6.1–6.6 ms |
| 3×, present intervals | ~1 in 3 at 33 ms | 125 of 126 at 16.7 ms |
| Swapchain rebuilds / 30 s | ~1950 | 3 (startup) |

- Remaining: occasional 21–30 ms frames (about 1 in 125) in heavy scenes. GPU
  write-backs measured at ~1 per frame, 0.3 ms (`wb[sync=…]`), so not the cause.
- Ultra Wormhole: confirmed fixed by the tester after the HID calibration change;
  the log shows one `hid: gyroscope calibration asked for`. Before the fix the
  shader NaN tracer showed uniforms c10–c16 uploaded as NaN and every triangle
  clipped, in both renderers.
- Driver comparison: Turnip with Auto shading was slower and less even than stock;
  GPU vertex shaders were GPU-bound on Turnip and crash the stock driver
  (`Rust cannot catch foreign exceptions` from pipeline creation).
- Automated: zakuro-core 145, zakuro-gpu 128 host tests; SaveArchiveCheck 92 on
  Linux (Docker, symlink cases included) and 87 on Windows; settings, bridge and
  driver host checks pass.
- Not verified: other games and devices, battery/thermal behaviour of the ADPF
  hint over long sessions, and a fair comparison with Azahar.

## 0.1.6 save folder hotfix (2026-10-31)

- Delivery: `build/releases/0.1.6/Zakuro-Android-0.1.6.apk`, SHA256
  `615f50fe11d6dd8c7a74c347caad1c76aed136eb772c901b62fbbcf9692bc48d`, same certificate.
- Cause: 0.1.5 rejected every symlinked ancestor of `filesDir`; Android's
  `/data/user/0` is one. Host tests had used an already-resolved temp path.
- 79 SaveArchive checks pass on Windows. A new regression check (lock through an
  aliased files directory) is in the symlink group, which Windows skips; it was not
  run on Linux for this hotfix. Device confirmation pending.

## 0.1.5 save export and restore (2026-10-31)

- Delivery: `build/releases/0.1.5/Zakuro-Android-0.1.5.apk` (9,241,686 bytes),
  SHA256 `29127b672313eead36a411510a14dfd4e688c188d2f14812dd812df9b0759d56`,
  signed with the same certificate as 0.1.4 (SHA-256 `5821ce1e…7228`).
  Build log: `build/android-windows/build-20261031-111818-aeaa4dd9.log`.
- `android/app/tests/run-save-archive-tests.ps1`: 79 checks passed — round trip,
  wrong title, path traversal, case/duplicate collisions, CRC corruption, Unix
  symlink entries, truncation, read failure, interrupted-switch recovery, blocked
  backup path and cross-process locking. Filesystem symlink cases were skipped on
  Windows (no privilege); they run on Linux/Android hosts.
- `android/app/settingshosttests/run.ps1` passed, including the new
  `prepareSaves` Java/native contract. ARM64 `cargo check` of `zakuro-android` passed.
- Not yet verified on a device: the Android file picker flow and the lock held
  by a paused game process.

## 0.1.4 rotation hotfix (2026-10-31)

- Delivery: `build/releases/0.1.4/Zakuro-Android-0.1.4.apk` (9,221,205 bytes),
  SHA256 `f65984bb6e678d5859490e7e73a570d935c1b042ffc063309ebfa1197cf3c75c`.
- Two targeted Vulkan transform tests passed: window-coordinate rendering requests
  identity for 0/90/180/270-degree panels; unsupported identity is not requested.
  Log: `build/rotation-tests-0.1.4.log`.
- ARM64 release build, v3 signing with the existing certificate, ZIP alignment,
  package and asset checks passed. Build log:
  `build/android-windows/build-20261031-105748-356da32c.log`.
- The same presenter serves both Android displays. This fixes the mismatch between
  window-coordinate rendering and claimed pre-rotation; physical orientation,
  touchscreen alignment and composition overhead on Thor still need verification.
  See [Khronos surface rotation guidance](https://docs.vulkan.org/samples/latest/samples/performance/surface_rotation/README.html).
- Update the installed app directly. No native game-code replacement or ROM
  reimport is needed. The previous complete regression results below are historical,
  not a claim that all suites were rerun for this focused presentation change.

## 0.1.3 baseline

The checks below refer to the local 0.1.3 source and artifacts on 2026-10-31.
Compilation, Android UI/storage execution and physical ARM64 gameplay are separate
levels of evidence. A visible control or matching symbol is not a device test.

## Deliverable

- APK: `build/releases/0.1.3/Zakuro-Android-0.1.3.apk` (**9,225,301 bytes**).
- SHA256: `40ec9de249f9e1a1095a445c1c6706096b9f62be6463fecb3a6ab49317f4a25a`.
- Package `io.github.zakuro.android`, version **0.1.3 / code 4**, ARM64 only.
- Built artifact: `build/android-windows/zakuro-android-test-20261031-103522-67d859d4.apk`;
  full matching log and checksum are beside it. The delivery copy's hash matches.
- Same Windows test signer as previous builds, certificate SHA256
  `5821ce1e0d2c1aa009bc1d269ae3865d50ce1448854d067203ce406dea967228`.

The Windows release build, v3 signature verification, 16 KB ZIP alignment,
package/version/launcher checks and license-only asset allowlist passed. All six
packaged native libraries are AArch64 ELF with LOAD segments aligned to 0x4000
and no RPATH/RUNPATH. The host exports ANativeActivity_onCreate and all six Game
JNI bridge methods; adrenotools exports its Vulkan-loading entry point. Static
reports are in `build/apk-verification-0.1.3/`. Only license notices are assets;
there are no bundled games, firmware, keys, saves or custom driver packs.

## Tests

| Boundary | Executed result | What it proves |
| --- | --- | --- |
| Core + filesystem final suites | **145 core + 24 FS passed** | Loader failures vs optional desktop fallback, extended-memory flags/allocation/config pages, executable bounds returning errors, state/FS regressions |
| Android native host helpers | **23 passed** | Typed settings, motion/calibration transforms, input release/gates, display geometry and storage helpers |
| GPU suite with Vulkan | **174 passed, 2 ignored** | Existing GPU regressions plus presentation/options helpers; not Android driver execution |
| Android ARM64 check and release link | **Passed** | NDK r27c/API30 cross-compilation and linking of the final runtime |
| Java host checks | **All six suites passed** | ABI/export visibility, ROM/PNG preflight, copy boundaries, settings drafts/persistence, JNI/options contract and driver ZIP checks |
| Real Android SAF suite | **27/27 passed** | Actual DocumentsProvider/PFD/filesystem import and replacement behavior on API35 x86_64 |
| Native Android frontend UI | **Passed scoped checks** | Six groups, font scaling, light/dark orientation layouts, Save/Cancel, driver trust Cancel, eight shader controls |
| Pinned build-title tool | **Build + usage/no-overwrite guards passed** | Generates against this runtime's pinned ABI and preserves existing output |

Final core/FS log: `build/final-core-fs-tests.log`. UI details and source freeze:
`android/app/settingshosttests/RESULTS.md` and `source-freeze.json`. Real SAF
report, provider traces and exact source hashes: `build/import-check/REPORT.md`,
`results.json`, `importer-source-hashes.json` and its APK directory. The final SAF
APK is `apk-20261031-103507/import-check.apk`, hash
`3e1431973e9ef03a51a32b5fccf12ccf6c5bb0b38c8d0d6a1dcc9f341d78b73d`.

The final review reproduced and fixed header-only ROM acceptance/core panic,
IHDR-only PNG replacement, and hidden ELF exports passing preflight. New tests
reject all three before activation. PNG chunks/CRCs are streamed and the copied
PNG is checked again. This establishes structure/checksum validity, not arbitrary
image decompression or native-code safety.

An earlier Android run exposed hard-link backup EACCES. Code repair now syncs
only an old-library backup, then atomically renames the replacement. Capacity is
checked before staging; backup failure leaves the old target in place. The final
SAF run covers this path, backup hash equality, original ROM/save/pointer
preservation, optional/excluded packs, invalid replacements and source mutation.

## Ultra Moon replacement and HD pack

`build/releases/0.1.3/Ultra Moon HD/title.so`: **286,066,048 bytes**, SHA256
`3f688826dbdc187c19d8d0cc9d25eac67de0f06e5a0b4ea4c6b9b961211cc475`.
The original output remains at `build/ultra-moon-android-abi4/title.so`.
The rebuild completed 573 compilation units using the pinned generator and NDK.
Read-only inspection confirms ABI4, no fastmem, Android libm/libdl/libc dependencies
and 16 KB LOAD alignment. The old supplied library independently reports ABI5
with fastmem, confirming the mismatch. No ABI check was bypassed.

Current production preflight passed with the real original Ultra Moon ROM and
this rebuilt library: title `00040000001B5100`, two files / 4,581,033,344 bytes.
Including the real extracted HD pack also passed all PNG structure/CRC checks:
3,541 files / 7,553,657,631 bytes. Both probes were read-only, without staging.
The existing Pocket Native HD kit's ROM and original dump have identical SHA256
`e08a903921bb87bdfb6e46c2a9c5e41a685e91cc371d38ffcbdcc4884bc84054`.
Only native code needs replacing in the existing Zakuro import.

## Limits

The disposable UI/SAF APKs are test-only Java packages on a workspace-local API35
x86_64 emulator. They do not contain or execute the production ARM64 game code.
No physical-device gameplay, Thor gyro axes/latency, dual-panel Vulkan output,
custom-driver execution, audio/video correctness or 16 KB device execution was
verified. Power-loss durability and real cloud/SD providers are not established
by the local SAF fixture suite. No Azahar performance comparison was measured.
Use [the benchmark protocol](benchmark.md) for that comparison.

The last older 0.1.2 artifact was
`zakuro-android-test-20261030-180612-eb156189.apk`, SHA256
`67cb01d2a4d1da88dcfa40e93edbcd0e8d4a10375980401d897181e27d7d7e18`.
It predates the repair/settings integration and is superseded by the APK above.
