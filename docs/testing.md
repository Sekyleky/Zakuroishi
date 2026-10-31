# Verification record

All checks below refer to the local 0.1.3 source and artifacts on 2026-10-31.
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
