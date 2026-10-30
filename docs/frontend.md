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
Android's folder picker. Imports report copied MB and completed files, rather than
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

## Frontend test APK 0.1.1

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
