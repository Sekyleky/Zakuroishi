# Install, import and play

From 0.1.9 the app is named **Zakuroishi**, with a new launcher logo. It updates
the existing Zakuro Android installation in place; do not uninstall to rename it.
The package, game imports and save paths are unchanged.

Zakuro Android is an experimental ARM64 frontend merged with upstream Zakuro v0.2.32
(`c856685`). It is a developer preview: each
game needs a native build made on a PC. It requires Android 11 or newer and a Vulkan 1.3 driver with the
renderer features described in [Development](development.md). The app includes
no ROMs, native game code, firmware, keys, or texture packs.

Version 0.1.8 includes **Settings → Button mapping** for controller actions and
held fast-forward, plus **Frontends → Export library** for Cocoon, iiSU and other
launchers. See [mapping and frontend setup](frontends.md) for the export folder,
custom-player configuration and direct-launch contract.

## Install or update

Download the latest `Zakuro-Android-*.apk` from the
[GitHub releases page](https://github.com/Sekyleky/Zakuroishi/releases/latest)
and open it on the handheld. Allow installation from that browser or file manager
when Android asks. `SHA256SUMS.txt` is provided to verify the download. GitHub's
automatic source archives are for developers, not installable apps.
See the [verification record](testing.md) for test details.
Update the existing app with the same signer (all published APKs share one certificate,
see the [README](../README.md#verify-the-signing-certificate); an APK you build yourself is
signed differently and will not update over them); do not uninstall to repair a game,
as uninstalling can delete saves. This app has its own package
`io.github.zakuro.android` and does not replace Pocket Native.

With authorized USB debugging:

```powershell
adb -s DEVICE_SERIAL install -r 'PATH_TO_CURRENT_APK'
adb -s DEVICE_SERIAL shell am start -n io.github.zakuro.android/.Launcher
```

## Add a game

1. Prepare one folder containing your original decrypted `.3ds`, `.cci` or `.cxi`
   ROM and its matching Android ARM64 **ABI 5** `title.so` (ABI 4 builds still run).
2. Choose **Add game** and select that folder. Leave **Include texture pack** off
   for the original graphics. Textures are optional and can be attached later.
3. The app inspects the ROM, native interface, selected asset headers and sizes,
   checks available space, then copies only the validated selection. Invalid
   inputs are rejected before staging. Copy progress shows actual bytes/files.
4. Choose **Play**. Settings are saved per game; resolution starts at 1x.

The importer accepts a nested build folder but rejects multiple games or multiple
compatible native libraries. Keep desktop, Linux and old Pocket Native builds out
of the selected folder. It does not compile a ROM on the handheld. Native code
executes inside the app: use only builds you trust (see [Security](../SECURITY.md)).

Optional `textures/` PNGs and `pack.json` are imported only when selected;
`sysdata/shared_font.bin` and `sysdata/CFL_Res.dat` are recognized when supplied.
Other build outputs, source files and unrelated files are not copied.

## Mods

Open a game's **Settings** and choose **Import mod…**, then select the mod's
folder as Luma3DS (`luma/titles/TITLE_ID/`) or Citra/Azahar (`load/mods/TITLE_ID/`)
lay it out: its `romfs/`, `romfs_ext/` and code patches are copied to the game's mods
folder and laid over the game the next time it starts. Importing again replaces
the previous mod; adding the game again removes it. A folder named for a different
title id is refused.

Mods that change game code (`code.bin`, `code.ips`, `code.bps`, in the folder or its
`exefs/`, and `exheader.bin`) run best with a `title.so` built with them: pass a data
folder holding `mods/TITLE_ID/` as `zakuro-build-title`'s fourth argument and import
the result with **Replace native game code**. With a `title.so` built without them,
the functions they change run in the interpreter, and an ABI 4 library is refused.

## Fix an existing native-library error

Open the affected game's **Settings**, choose **Replace native game code**, and
select a matching Android `title.so`. This copies only the replacement library,
keeps a backup of the previous library, and preserves the ROM, pack and saves.
Restart the game afterward.

The inspected Pocket Native fast-memory library is numbered ABI 5 but is not
upstream 3dsrecomp's ABI 5: it needs a fast-memory context this host does not
provide. Renaming it or changing its ABI number is not a fix. Use the pinned
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

Settings are grouped as Display (resolution, HD pack, screen layout and second
display, scaling, on-screen info, 3D), Performance (speed and fast-forward, frame
cap and vsync, shader warm-up, custom driver), Controls, Button mapping, Motion,
Audio, System, and Advanced (renderer, shader, HD-texture and driver
troubleshooting switches). Save
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

## Performance

Start with **2× resolution** and the **system Vulkan driver**; on an AYN Thor
Pokémon Ultra Moon has reached about 60 emulation refreshes/s at 2× and 3× with
HD textures in the tested scenes; occasional hitches remain. Keep
Advanced → Vertex shader processing on **Auto**: on the stock Qualcomm driver
**GPU** crashes the game, and on Turnip it was slower. Leave the presentation cap
at 60. At 100% speed frames are paced to the display refresh (FIFO); fast-forward
uses mailbox, with the guest timer controlling game speed. The app asks Android for
a 60 Hz panel mode and steady CPU clocks. Expect more battery use and heat at
3×. If a game stutters, report the logcat `benchmark` line (see the
[benchmark protocol](benchmark.md#reading-the-benchmark-line)).

For fast-forward, choose **Performance → Game speed limit (%) → Unlimited**
(or a 2×/3×/4× limit), then save and restart. Normal is 100%; a higher limit is a
ceiling, not a promise that the device can reach it. Keep **Presentation frame
cap** at 60 to display up to 60 pictures/s while the game advances faster.
Changing that presentation cap alone does not unlock the emulation clock.
The FPS overlay shows emulation FPS, percent of normal game-clock speed and
submitted presentation FPS separately. These are not counts of unique game
images. The HUD's `game` number counts game pictures per second; `distinct` counts new top-screen pictures per second, not repeats. Audio is not time-stretched for fast-forward; the bounded audio queue
can drop samples when emulation runs faster than playback.

Switches for A/B testing (all apply at the next launch). They are under
**Advanced**, except vsync, warm-up and the custom driver (Performance) and
sound/tap latching (Audio/Controls):

- **Pace to display refresh (vsync)**, on: even frame times at normal speed. Off
  restores 0.1.8's mailbox presentation (more 33 ms hitches on the Thor).
- **Guard GPU vertex shaders on the system driver**, on: starts on Auto instead
  of crashing the stock Qualcomm driver. Turn off only to test GPU shaders.
- **Turnip: render in system memory**, off: custom Turnip drivers only
  (`TU_DEBUG=sysmem`); try it if a black block appears in a screen corner.
- **Frame generation** (`frame_generation`, Performance, off by default, experimental): whole
  pictures between the game's own from the MIT lsfg-vk 1.0 code and the shaders of your own
  `Lossless.dll`. You need a copy of Lossless Scaling, a paid app; nothing from it comes with
  Zakuroishi, which is not affiliated with it or its developer. Use a Turnip driver: the stock
  driver has not been measured. It adds input delay (about 25 ms by design plus queue, not
  measured end to end), and text and menus can smear. Top screen only. Screens that draw
  at 60, such as the title and intro, reach about 80% speed at 4×. Measured on one device
  (AYN Thor) and one game.
  - **Import once:** copy `Lossless.dll` from your Lossless Scaling folder to the device, then
    Game settings → Performance → **Import your Lossless.dll…** and choose it. It is checked,
    translated in the app's private storage and the app's copy deleted; you can delete yours from
    the device too. A file that isn't a usable DLL is refused and shaders already imported stay.
    Lossless.dll 3.2.2.0 is the version tested; another may not fit, and frame generation then
    says so and stays off. No file is ever downloaded.
  - **On and off, per game:** the **Frame generation** switch, applied at the next launch.
    Turning it on turns off Frame interpolation in the same page, and the other way around.
    If it is on with nothing imported, the game starts normally without it and says so.
  - **Pictures per game picture** (`frame_generation_multiplier`): 4× runs the panel at 120 Hz,
    2× stays at 60 Hz. If Android holds the screen at 60 Hz, 4× switches itself to 2× and says so.
    On a 60 Hz panel on the Thor (Ultra Moon, 3× + HD, Turnip) the switch ran: about
    60 presents/s, game 30 fps, 100.4% speed, no audio underruns. Without it that
    setup ran at 15 game fps and 50.1% speed.
    **Motion search size**
    (`frame_generation_flow`): smaller costs the GPU less and follows motion less closely.
- **Frame interpolation** (`interpolate`, Performance, off by default): for games that
  draw every other refresh, such as Ultra Moon, it draws a picture in between from the
  game's own draws with positions halfway, so game speed does not change. Use it with a
  Turnip driver: on the Thor, Ultra Moon held 58-60 distinct pictures per second on
  Turnip but fell to 65-77% speed on the stock Qualcomm driver. The newest picture waits
  about one refresh (16.7 ms). Camera cuts are refused. It needs hardware rendering, and
  it is disabled during GPU readback or when the top screen goes to the second display.
- **Fast texture decoding**, on: decodes every texture format tile by tile; the
  largest battle-start win (worst frame 53.8 → 37.6 ms on the Thor).
- **HD textures: look up pictures in the background**, on: field-entry frame
  58.9 → 46.8 ms; an HD picture may appear a frame later.
- **HD textures: pooled upload memory / limit upload size per frame / copy pictures
  in the background**, on: remove HD picture allocations and copies from the game
  thread; no single one measurably changes the worst frame.

- **Warm up vertex programs / GPU pipelines at launch**, on: compiles what earlier
  sessions used in the background at start; battle-start worst frame 66.7 → 41.3 ms.
  The first session of a game still compiles as it goes.
- **Finish shader warm-up before the game starts** (`warm_before_start`), off: holds
  the first emulated frame until both warm-ups above are done (vertex programs and
  GPU pipelines, 30 s at most; "Preparing shaders..." shows meanwhile). Not measured
  on a device yet.
- **Shade draws on other threads**, on: the shade-ahead for the CPU vertex path,
  ported from upstream 0.2.31; off only to compare.
- **Keep sound in step with the picture** (`audio_queue_managed`), on: the desktop
  queue (~125 ms, drift trim, time stretch, fades); off is 0.2.0's, ~230 ms behind.
- **Low-latency sound output** (`low_latency_audio`), off: asks AAudio for a FAST track
  (allocated session, Game usage); on the Thor a global software equalizer denies FAST.
- **Never miss a quick tap** (`latch_short_presses`), on: a button or touch pressed and
  released between two frames counts as pressed for one frame (on-screen buttons excepted).
- **Vsync: fewer frames waiting** (`short_vsync_queue`), off: 3 swapchain images instead
  of 4 under FIFO; Turnip shows frames 57 → 40 ms after they are ready, the stock driver
  is unchanged (16 ms) with a few more late frames. Worth turning on with Turnip.

Scripted A/B on a device: `android/scripts/device-test.sh` (see its header).

Driver results depend on the scene and whether emulation is capped. Earlier
normal-speed tests on an AYN Thor (Ultra Moon, 3× + HD, 0.1.7) found:

| Driver | Result |
|---|---|
| System Qualcomm | 60 emulation refreshes/s, ~4.0 ms emulation per frame, no artifacts |
| Balemuni Apex Ultimate, Mesa 26.3.0-devel (Turnip) | 60 FPS, ~4.4 ms; a black block in the top screen's bottom-left corner |

A later uncapped comparison of the same saved Route 2 grass scene, 3× + HD,
measured **111.4 emulation FPS (1.86× speed) on system Qualcomm** and **170.9
(2.86×) on the imported Turnip build**. Each used 30 seconds warmup and a
60-second capture. Both submitted about 60 pictures/s; the presentation counter
alone hid the difference. The stock run showed more GPU queue waits.

Use the tested Turnip build when fast-forward headroom matters in that workload.
System Qualcomm remains the option for the observed Turnip corner artifact:
if a small black rectangle appears, disable the custom driver and restart.
These measurements do not rank every driver release, game or device; see
[the verification record](testing.md).

## Two displays and custom drivers

Enable the second-display option to route the top screen to the main display and
the touch screen to an available secondary display. Swap exchanges them;
external stretch is independent. Disconnecting the second display falls back to
the normal main-screen layout. The external presenter currently uses GPU
readback. Main/top and secondary/bottom routing have been checked on the Thor;
other display configurations still need device testing.

In Performance settings, import a trusted ARM64 Android Vulkan driver ZIP and
select it for the game. Imports are bounded and private, with metadata/API and
ELF checks; the system driver remains the default. Driver load failures are
reported rather than silently selecting a different driver. Recover by turning
off the custom-driver option and restarting. Driver/GPU compatibility still
requires testing on the handheld. No driver pack is included with the APK.

## Saves and recovery

Each game in the library has a **Saves** button (also available under **Settings →
Manage saves**). You do not need root access or an app-data file manager.

- **Export ZIP…**: first save inside the game and exit to the library. Choose a
  destination in Android's file picker: Downloads, SD card, or an installed cloud
  provider. The ZIP contains ordinary files you can extract on a computer.
- **Restore ZIP…**: choose a Zakuro save backup for the same game and confirm
  replacement. The archive is checked before replacing the current saves. The
  previous saves are retained locally as a recovery copy.

**Save editors (file picker).** Zakuroishi appears as **Zakuroishi saves** in
Android's file picker (Open from → scroll the side drawer), with one folder per game
holding its in-game saves, e.g. `savedata/<TITLE_ID>/main` for the Pokémon games. An
editor that opens files through the picker (PKForge, PKHeX ports) can open and save
them in place; no extra permission is needed and other apps only get the file you
pick. Writes are refused while that game is running, and files can't be created,
renamed or deleted from the picker. Quick save states are not listed. Before a write,
0.3.1 and earlier copy the saves to `user.previous`. 0.4.0 and later instead keep
the 3 newest backups of the in-game saves as `user.backup-<ms>`, skipping a copy when
nothing changed; browse them read-only under `:backups` in the game's folder in the picker.

Save transfers are blocked while the game's runtime holds its saves open, including
when it is paused in the background. Exit the game rather than just pressing Home.
After restoring, launch normally; loading an older quick save state can overwrite
the restored progress. These backups cover **in-game saves**, not quick save states,
ROMs, native game code or texture packs. Keep exported ZIPs somewhere outside the
app before uninstalling or clearing its data. Restore accepts this app's backup
format, not arbitrary ZIPs from other emulators.

The app stores files privately beneath Android's app files directory:

| Path relative to `files/` | Purpose |
| --- | --- |
| `savedata/TITLE_ID/user/` | Stable in-game saves, linked into each import |
| `savedata/TITLE_ID/user.previous/` | Recovery copy retained by Restore ZIP (0.3.1 and earlier: also the save-editor backup) |
| `savedata/TITLE_ID/user.backup-<ms>/` | Save-editor backups, 3 newest kept (0.4.0 and later) |
| `imports/TITLE_ID.active` | Atomically activated version UUID |
| `imports/UUID/game/game.3ds` | Imported original ROM |
| `imports/UUID/code/title.so` | Read-only native game library |
| `imports/UUID/data/mods/TITLE_ID/textures/` | Optional replacement pack |
| `imports/UUID/data/mods/TITLE_ID/romfs/`, `romfs_ext/` | Optional mod (also `code.ips`, `code.bps`, `code.bin`, `exheader.bin`) |
| `imports/UUID/data/states/TITLE_ID/0.state` | Quick-save slot for this import |

Reimport keeps in-game saves; quick states are version-specific and are not a
substitute for in-game saving. Component replacement keeps the current import's
state files. Old imports and component backups are retained deliberately and are never
deleted automatically; **Tools → Storage** lists them and deletes one at a
time after a confirm. Large imports need space for both old and new copies. App-private data is not ordinarily browsable in Android's
Files app. Back up saves before uninstalling or clearing app data.

## Tools menu

The library header has a **Tools** menu:

- **Storage** lists old imports, previous native code, texture and mod backups and
  unfinished imports with their sizes, and deletes one at a time after a confirm. It never
  lists an active import or anything in `savedata`. Deleting an old import also deletes
  its quick states. It refuses while a game or an import runs.
- **Export diagnostics** writes app, device and log details to a text file. Picker
  addresses are removed; file paths remain, so read it before sharing.
- **Export settings** / **Import settings** use a JSON file of per-game settings,
  controller mappings and enhancements. No drivers, saves or ROMs. Import refuses the
  whole file if any value is invalid.

## Verification limits

See [Testing](testing.md) for exact artifacts and checks. APK packaging, host
tests and emulator UI/import tests do not establish ARM64 gameplay, Thor dual
screen/sensor behavior, 16 KB device execution, or a performance win over Azahar.
Use the [benchmark protocol](benchmark.md) for that comparison.
