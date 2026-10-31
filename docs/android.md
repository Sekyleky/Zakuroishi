# Install, import and play

Zakuro Android is an experimental ARM64 frontend for upstream Zakuro v0.2.30
(`e174910`). It requires Android 11 or newer and a Vulkan 1.3 driver with the
renderer features described in [Development](development.md). The app includes
no ROMs, native game code, firmware, keys, or texture packs.

## Install or update

Download `Zakuro-Android-0.1.4.apk` from the
[GitHub prerelease](https://github.com/Sekyleky/zakuro-android/releases/tag/android-v0.1.4)
and open it on the handheld. Allow installation from that browser or file manager
when Android asks. `SHA256SUMS.txt` is provided to verify the download. GitHub's
automatic source archives are for developers, not installable apps.
See the [verification record](testing.md) for test details.
Update the existing app with the same signer; do not uninstall to repair a game,
as uninstalling can delete saves. This app has its own package
`io.github.zakuro.android` and does not replace Pocket Native.

With authorized USB debugging:

```powershell
adb -s DEVICE_SERIAL install -r 'PATH_TO_CURRENT_APK'
adb -s DEVICE_SERIAL shell am start -n io.github.zakuro.android/.Launcher
```

## Add a game

1. Prepare one folder containing your original decrypted `.3ds`, `.cci` or `.cxi`
   ROM and its matching Android ARM64 **ABI 4** `title.so`.
2. Choose **Add game** and select that folder. Leave **Include texture pack** off
   for the original graphics. Textures are optional and can be attached later.
3. The app inspects the ROM, native interface, selected asset headers and sizes,
   checks available space, then copies only the validated selection. Invalid
   inputs are rejected before staging. Copy progress shows actual bytes/files.
4. Choose **Play**. Settings are saved per game; resolution starts at 1x.

The importer accepts a nested build folder but rejects multiple games or multiple
compatible native libraries. Keep desktop, Linux and old Pocket Native builds out
of the selected folder. It does not compile a ROM on the handheld. Native code
executes inside the app: use only builds you trust.

Optional `textures/` PNGs and `pack.json` are imported only when selected;
`sysdata/shared_font.bin` and `sysdata/CFL_Res.dat` are recognized when supplied.
Other build outputs, source files and unrelated files are not copied.

## Fix an existing native-library error

Open the affected game's **Settings**, choose **Replace native game code**, and
select a matching Android `title.so`. This copies only the replacement library,
keeps a backup of the previous library, and preserves the ROM, pack and saves.
Restart the game afterward.

The inspected Pocket Native fast-memory library uses ABI 5 and cannot run on this
ABI 4 host. Renaming it or changing its ABI number is not a fix. Use the pinned
[build-title tool and compatibility guide](native-library.md) to rebuild it.
Errors now include the native loader's reason instead of requiring logcat for
the basic diagnosis. `adb logcat` remains useful for detailed renderer failures.

## HD textures and game enhancements

Extract a Citra-format pack first. In game Settings choose the texture-pack
operation and select the folder containing its PNGs. Only pack files are copied;
the ROM is not rebaked or recopied. Enable the pack switch to use it, or disable
it to use the original textures. Packs consume host GPU memory; start at 1x/2x
resolution and compare gameplay before increasing it. Updating a pack retains
the previous pack directory for recovery.

Build-specific enhancements appear after the native runtime identifies the game.
Only enhancements supported for that title/build are applied. A presentation cap
of 120 is not a 120 FPS game patch: guest timing is unchanged. Games require
compatible enhancement codes to change their own frame rate or remove outlines.

## Settings and controls

Settings group display layout, filtering, shape/stretch, stereo, resolution,
compiled/interpreter execution, idle-loop skipping, presentation cap,
interpolation, motion, controller mapping, audio and console settings. Save
applies changes; Cancel discards them. Runtime-affecting changes need a restart.
The [feature matrix](feature-parity.md) identifies supported backend behavior
and settings still requiring a port.

A selects and B cancels in the library. In play, open the pause menu for resume,
save/load slot 0, restart, screen swap, gyro recalibration and settings. Optional
on-screen controls provide buttons and sticks. Controller settings include
Nintendo/Xbox face layout, remapping, stick dead zone, C-stick/touch cursor and
stick-generated motion. Bottom-screen touch remains available on the game image.

Device motion uses the accelerometer and gyroscope when available. Hold the
handheld still briefly after resume, then use recalibration if aiming drifts.
Sensitivity and axis inversion are per game. The Thor's physical sensor axes,
latency and calibration still require hardware verification.

## Two displays and custom drivers

Enable the second-display option to route the top screen to the main display and
the touch screen to an available secondary display. Swap exchanges them;
external stretch is independent. Disconnecting the second display falls back to
the normal main-screen layout. The external presenter currently uses GPU
readback; actual Thor panel routing and performance are not hardware-verified.

In Performance settings, import a trusted ARM64 Android Vulkan driver ZIP and
select it for the game. Imports are bounded and private, with metadata/API and
ELF checks; the system driver remains the default. Driver load failures are
reported rather than silently selecting a different driver. Recover by turning
off the custom-driver option and restarting. Driver/GPU compatibility still
requires testing on the handheld. No driver pack is included with the APK.

## Saves and recovery

The app stores files privately beneath Android's app files directory:

| Path relative to `files/` | Purpose |
| --- | --- |
| `savedata/TITLE_ID/user/` | Stable in-game saves, linked into each import |
| `imports/TITLE_ID.active` | Atomically activated version UUID |
| `imports/UUID/game/game.3ds` | Imported original ROM |
| `imports/UUID/code/title.so` | Read-only native game library |
| `imports/UUID/data/mods/TITLE_ID/textures/` | Optional replacement pack |
| `imports/UUID/data/states/TITLE_ID/0.state` | Quick-save slot for this import |

Reimport keeps in-game saves; quick states are version-specific and are not a
substitute for in-game saving. Component replacement keeps the current import's
state files. Old imports and component backups are retained deliberately; this
version does not automatically delete them. Large imports need space for both
old and new copies. App-private data is not ordinarily browsable in Android's
Files app. Back up saves before uninstalling or clearing app data.

## Verification limits

See [Testing](testing.md) for exact artifacts and checks. APK packaging, host
tests and emulator UI/import tests do not establish ARM64 gameplay, Thor dual
screen/sensor behavior, 16 KB device execution, or a performance win over Azahar.
Use the [benchmark protocol](benchmark.md) for that comparison.
