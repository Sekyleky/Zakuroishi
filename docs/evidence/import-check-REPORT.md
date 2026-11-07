# Real Android SAF import regression checks — PASS

_Copied unchanged from the maintainer's `build/import-check/REPORT.md`. The APK, logs and JSON it names are not in this repository._

Final run: 2026-10-31 Europe/Prague, **27 passed / 0 failed**.
All three host suites also passed: NativeLibraryCheck, ImportPlanCheck,
and GameImportCheck, compiled with Studio JDK and SDK 37 android.jar.

Production importer sources were compiled unchanged into the disposable,
debuggable/testOnly APK on emulator-5582, private adb server 5039,
AOSP Android 15/API 35 x86_64. No native game library was loaded/executed;
no user games were accessed. The test APK contains no native libraries.

## Exact final evidence

- APK: apk-20261031-103507/import-check.apk
- APK SHA256: 3E1431973E9EF03A51A32B5FCCF12CCF6C5BB0B38C8D0D6A1DCC9F341D78B73D
- Android suite: /data/user/0/io.github.zakuro.importcheck/files/suite-1793439318613
- results.json: final 27-case results with provider opens/queries per case.
- test.log: Android ImportAndroidCheck logcat output (may include earlier runs;
  final suite/results.json identify this run).
- importer-source-hashes.json: every APK Java source still matches its build hash.
- APK directory source-hashes.json and build.log retain build evidence.
- host-regression-source-hashes.json and host-*.log retain host evidence.
- backup-sha256.txt: old source and retained native backup both have SHA256
  d74fe3787f6038642d1be870f627a79c3a3723b249d16c242f36f7bcf53393e6.

## Regression coverage

The exact 512-byte NCCH repro has only the header, nonzero title ID,
content size 512, decrypted flag, and no ExeFS/exheader. It is rejected before
code/assets open or destination creation. Valid ROM fixtures now have a declared
0x400-byte exheader, ExeFS at 0x600 and an eight-byte .code entry at 0x800.
The host sparse 4 GiB fixture retains only a 0xA00-byte prefix; title validation
reads bounded metadata, not the ROM body. Invalid Android large-ROM fixtures
are sparse; no multi-gigabyte payload was copied.

The exact 33-byte signature/IHDR PNG repro is rejected on initial import and
texture replacement. Host tests cover absent IDAT/IEND, truncated IEND,
IDAT/IEND CRC failures, chunk overflow, trailing bytes and a valid 140000-byte
ancillary chunk scanned through reads no larger than 65536 bytes. PNG fixtures
are generated with valid compressed pixels and chunk CRCs; no invalid base64
fixture is used. Byte-total overflow uses sparse sysdata, not fictitious PNGs.

Hidden/internal required ELF exports are rejected. Host tests check each required
symbol independently and accept protected visibility; real SAF verifies hidden
initial/replacement rejection and protected-export import. The shared ELF fixture
is metadata only, not executable native code.

Real SAF also mutates IDAT bytes with an unchanged IHDR on the second PNG open.
Post-copy validation rejects the payload, removes the failed stage and preserves
the existing active pointer/save. The prior same-size IHDR mutation test remains.
Invalid component replacements compare all prior file hashes, file sets,
save/link/pointer and staging entries. Code-backup EACCES injection remains real.

## Cases

| Case | Result |
|---|---|
| invalid-rom | PASS |
| missing-so | PASS |
| multiple-rom | PASS |
| wrong-elf | PASS |
| truncated-png | PASS |
| abi5-no-stage | PASS |
| header-only-rom | PASS |
| ihdr-only-png | PASS |
| hidden-exports | PASS |
| no-pack | PASS |
| empty-optional-pack | PASS |
| manifest-only-pack | PASS |
| skip-pack | PASS |
| protected-exports | PASS |
| valid-exact-plan | PASS |
| reimport-save-pointer | PASS |
| same-size-png-change | PASS |
| same-header-png-body-change | PASS |
| replace-code-only | PASS |
| replace-textures-only | PASS |
| invalid-code-preserves-old | PASS |
| invalid-pack-preserves-old | PASS |
| empty-pack-preserves-old | PASS |
| backup-failure-preserves-old | PASS |
| ihdr-only-replacement-preserves-old | PASS |
| hidden-code-replacement-preserves-old | PASS |
| long-path | PASS |

## Reproduce

Use start-emulator.ps1 only if the isolated emulator is stopped; wait for boot=1.
Run build-check.ps1, then the installed SDK adb, always -P5039 -s emulator-5582:

    adb -P 5039 -s emulator-5582 install -t -r <latest-apk.txt path>
    adb -P 5039 -s emulator-5582 shell am force-stop io.github.zakuro.importcheck
    adb -P 5039 -s emulator-5582 shell am start -W -n io.github.zakuro.importcheck/io.github.zakuro.android.ImportAndroidCheck
    adb -P 5039 -s emulator-5582 shell run-as io.github.zakuro.importcheck cat files/results.json

Compile production Java plus android/app/tests/*.java against SDK 37 android.jar
with Studio javac --release 11, then run the three named host main classes using
the compiled classes and android.jar on the classpath. The shared test NativeFixture
path is android/app/tests/NativeFixture.java; build-check.ps1 includes it explicitly.

## Limits and ownership

No system picker UI, cloud/nonseekable provider, SD card, native loading/gameplay,
physical device, performance, process death, power loss, disk-full mid-copy,
active-pointer race injection or uncertain texture rollback was tested.
Core filesystem panic handling and core test reruns are native-agent/parent-owned.
These passing Java/SAF tests do not claim verification of that Rust fix.

This test task changed only the four named android/app/tests Java files and
ignored build/import-check harness/evidence. Production fixes were parent-owned.
The prior 20-case report is retained in REPORT-20-case-history.md; the final
27-case run above supersedes its source hashes and run-specific evidence.
