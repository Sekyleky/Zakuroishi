# Android library

The launcher uses Android framework widgets, system fonts, and a restrained green
accent. Light and dark colors follow the device setting. No web view, UI framework,
remote artwork service, or additional runtime dependency is involved.

The library puts game names and Play first. Search filters names; Settings keeps
rename, resolution, and the texture-pack switch out of the main list. Wide screens
use compact horizontal game rows. Small screens and large text stack the controls.
The whole page scrolls so the library stays reachable with large text in landscape.

New imports prefer the game's embedded English title, then the original ROM
filename or selected folder. Existing imports without stored names also try the
embedded title, without rewriting their files. Names can be edited in Settings
and never become filesystem paths. The existing game preferences,
import pointers and save locations are preserved.

Setup help explains the required folder and trusted native code before opening
Android's folder picker. Imports start with a read-only checking phase before any
staging files exist. A validated, selective file plan and free-space check must pass
before copying. Copy progress reports MB and completed files, rather than
an invented percentage. Progress updates do not rebuild the library or flood the
screen reader. Import activation and reads share a short lock; copying remains
off the UI thread. Errors explain how to retry and retain prior games and saves.

Buttons have at least 48 dp targets, visible focus, descriptive accessibility labels,
and Android font scaling. D-pad navigation uses native focus behavior. Controller A
selects; B closes the keyboard first, then cancels a dialog or clears search before
leaving the library. Settings changes are applied only with Save.

The body, secondary text and primary-button text contrast ratios are 13.83, 6.04
and 7.07 in light mode; 15.85, 7.54 and 10.85 in dark mode. These are calculated
ratios for the explicit palette, not a complete accessibility certification.

Frontend validation uses a separate Java-only debug package on a workspace-local
Android emulator, with fictional fixture entries. It cannot run games and is not
the ARM64 release APK. This separates launcher/layout checks from renderer and
game compatibility. Physical Thor ergonomics, TalkBack and SAF import behavior
still need hardware testing.

## Validate-first import fix 0.1.2

Current signed ARM64 APK (version code 3):
`build/android-windows/zakuro-android-test-20261030-180612-eb156189.apk`.
SHA256: `67cb01d2a4d1da88dcfa40e93edbcd0e8d4a10375980401d897181e27d7d7e18`.
Signature, package/launcher identity, ARM64 library and 16 KB alignment checks passed.

Source inventory, ROM/native/PNG headers, source sizes and readability, destination
path lengths and free space are checked before creating staging files. Only the
frozen selection is copied. Unrelated archives and files are not copied. The UI
explicitly starts with “Checking game folder… No files copied yet.”

Host regression checks passed for malformed inputs, ordering, bounded reads of a
sparse 4 GiB ROM, selective files, size overflow, path limits and changed PNG headers.
Nine real Android SAF tests passed against the final source: invalid ROM, missing
library, multiple ROMs, wrong ELF, truncated PNG and overlong paths reject before
creating a destination; valid import copies the exact plan; reimport preserves saves;
a same-size PNG header change removes failed staging without activating it.
Evidence is retained in `build/import-check/REPORT.md` and `results.json`.

A read-only preflight of the local Ultra Moon HD folder also passed: 3,542 selected
files, 7,411,482,645 planned bytes, with no destination created or payload copied.
Physical-device/cloud-provider behavior and AtomicFile failure injection remain
untested. No native runtime or game-performance behavior changed.

## Earlier frontend test APK 0.1.1

Built and signed with the existing test identity:
`build/android-windows/zakuro-android-test-20261030-171706-b8f9e07c.apk`.
SHA256: `38053438d68ea9475fd9d7731e5636017140cffa94af3248460320b65c683d62`.
Package/launcher, ARM64 library, signature and 16 KB ZIP alignment checks passed.
Version code 2 updates version code 1 without requiring an uninstall.

Java compilation and host boundary tests passed, including metadata parsing,
malformed/truncated ROM headers, old imports and throttled progress. Embedded
names were also read successfully from the local Ultra Moon and Alpha Sapphire
ROMs without changing those files.

API 35 emulator checks passed for light/dark, empty/full libraries, portrait and
landscape, D-pad focus and A/B selection, search and no results, keyboard dismissal,
Save/Cancel persistence, and settings at 2× font scale. The layout source hash in
`build/frontend-preview/REPORT.md` matches the final launcher. Screenshots and
hierarchy assertions are retained in `build/frontend-preview/captures/review-*`.
Only the isolated test emulator and its private adb server were stopped afterward.
