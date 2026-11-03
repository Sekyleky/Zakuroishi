# Changelog

## Unreleased

- **Menus.** One orange palette from the logo for dark and light mode, used by the
  library, settings and in-game menus. Every control shows focus (solid orange with
  a ring; rows get an orange outline). The pause menu is a 2x4 grid with Resume
  focused; Load state and Exit ask first with Cancel focused. The library opens
  with Play focused; L1/R1 page, Y opens Settings, X Saves, B no longer exits.
  Settings: Start = Done, "Discard changes?" on B, X clears a binding, defaults
  marked, Performance grouped with everyday options first.
- **Romfs mods on Android.** Game settings → **Import romfs mod…** takes a Luma3DS
  or Citra mod folder and copies its `romfs/` and `romfs_ext/` into the game's mods
  folder, which the core already lays over the game's files at launch. Mods with
  code changes (exefs, code.bin/ips/bps, exheader.bin) are refused, since the game
  runs its prebuilt native code; a folder named for another title id is refused.

## 0.2.0 — ABI-5 game code, upstream 0.2.31 renderer, save editors (2026-11-03)

- **Upstream Zakuro 0.2.31 renderer and CPU work.** Draws are shaded on other
  threads ahead of the walk that draws them (`shade_ahead` renderer option),
  decoded textures are read again only once memory was written over them,
  mipmapped textures sample their smaller sizes, and the CPU vertex JIT compiles
  quickly first and again in full once a program is hot (both through the two
  queued `shader jit` workers; warm-up compiles in full). Plus framebuffer flip,
  projected texture and stencil fixes.
- **Recompiled code runs svcs in place (3dsrecomp ABI 5).** The pinned generator
  moves to `73b8f9c` (ABI 5, generation 5); `build-title` now makes ABI-5 code.
  ABI-4 libraries still load and run; rebuild them for the faster code. Memory
  access is unchanged (page tables and callbacks, no fast memory). Pocket Native
  fast-memory libraries stay rejected.
- **Save editors can open saves directly.** Zakuroishi lists each game's in-game saves
  in Android's file picker (**Zakuroishi saves**), so editors such as PKForge open and
  write them in place without root or a copy. Writes are refused while the game runs
  and back the saves up to `user.previous` first; nothing can be created, renamed or
  deleted there.

## 0.1.9 — Zakuroishi, shader warm-up and hitch fixes (2026-11-01)

- Rename the app to **Zakuroishi** and add the supplied logo to the library
  header, launcher and recent-app identity. Preserve the original SVG alongside
  Android vector/adaptive-icon resources; both APK build paths compile them.
- Keep the Android package, signing identity, JNI interface, game/save storage,
  deep links and frontend reference identifiers compatible with existing installs.
- Restore vsync pacing at normal speed (**Performance → Pace to display refresh**,
  on by default). 0.1.8 used mailbox at every speed; on the Thor at 3× + HD that
  held 365 of 375 presents on 16.7 ms versus 374 of 376 with vsync. Fast-forward
  still uses mailbox, and the presenter switches live when it starts or stops.
- Guard GPU vertex shaders on the system driver (**Guard GPU vertex shaders**, on
  by default): the stock Qualcomm driver throws from pipeline creation and the
  process aborts, so the game starts on Auto with a message instead.
- Persist compiled GPU pipelines on Android. The per-title pipeline cache only
  resolved `XDG_CACHE_HOME`/`HOME`, which Android apps lack, so every session
  recompiled every pipeline. It now lives in `files/cache/zakuro/pipelines/
  <TITLE>.bin` (2.2 MB for Ultra Moon after a battle). **Settings → Clear shader
  cache** deletes it for one game.
- Compile CPU vertex programs on two queued worker threads instead of a thread
  per program. A battle start compiled ~10 programs (7–70 ms each) at once, which
  starved the emulation thread; the worst battle-start frame fell from 113 to 71 ms.
- The hitch log line reports GPU pipelines and CPU vertex programs compiled since
  the previous line; module loads log their code-check time.
- Add **Turnip: render in system memory** (off by default), which sets Mesa's
  `TU_DEBUG=sysmem` for custom drivers to test the corner-block artifact.
- Cut HD-texture upload cost at battle start. Profiling on the Thor showed battle-start
  frames were spent uploading HD pictures on the emulation thread: a driver memory
  allocation per picture plus a large staging copy, and a 2 ms budget that let one big
  picture through every frame. Four switches under **Performance**, all on by default:
  pooled upload memory, a per-frame upload size limit, background picture copies and
  block-wise ETC1 decoding with HD-pack size pre-checks. Same build on the Thor, one
  battle each: worst battle-start frame 73 ms with all four off, 55 ms with them on.
- **Fast texture decoding** now covers every 3DS texture format, not only ETC1: whole
  textures decode tile by tile with the format chosen once (RGBA8 4.7×, RGB565 2.3× on
  a desktop CPU, bit-identical). On the Thor the worst battle-start frame fell from a
  mean of 53.8 ms to 37.6 ms (3 alternating runs each, 46–64 ms vs 37–38 ms).
- Fix the in-game HUD: clock, battery and FPS were collected but never drawn, because
  NativeActivity takes the game window's surface and views added to it don't render. The
  HUD now has a window of its own (touches pass through). It also shows battery
  temperature and power draw, and with **Show native FPS** the emulation time per frame,
  the worst frame and slow frames (> 20 ms) of the last second, and shader compiles.
  The on-screen Pause button is now **Controls → Show pause button**, off by default
  (Back or the controller's menu button pauses). The touch controls (button bar, Circle
  Pad, C-stick) had the same problem and now get windows of their own too; when they
  auto-hide for a gamepad their windows also stop taking touches.
- **Warm up shaders at launch** from earlier sessions (two switches, on by default).
  Gameplay monitoring showed the worst spikes (38–67 ms) were CPU vertex processing on
  the interpreter while new vertex programs compiled. Each game's vertex program
  sources (`files/cache/zakuro/programs/<ID>.bin`, the game's shader words, never
  machine code) and the GPU pipelines it used (`pipelines/<ID>.keys`) are now saved and
  compiled in the background at the next launch, behind anything the game asks for.
  Files are validated on load and rewritten when bad; **Clear shader cache** removes
  them too. Thor, 3 alternating runs each after one recording session: worst
  battle-start frame 66.7 → 41.3 ms mean, biggest vertex spikes 24–43 → 12–18 ms.
- Look textures up in the HD pack on a background thread (**HD textures: look up
  pictures in the background**, on by default). The lookup decodes each new texture a
  second time, as Citra laid it out, to hash it; the first field frame after the title
  screen brings ~28 new textures at once. Thor, 3 alternating runs each: field-entry
  frame 58.9 ms → 46.8 ms mean. Battle start unchanged (45.9 vs 45.0 ms mean): its HD
  pictures now arrive a little later and upload during the battle start instead.
- A second `frame hitch parts` log line splits a hitch frame's emulation time into GPU
  command lists, texture decode, HD-pack lookup, vertex processing, texture upload,
  submit and flush.
- Add an adb-only settings hook (`.DebugSettings`, protected by the `DUMP` permission)
  and `android/scripts/device-test.sh`, which lists games, reads and changes settings,
  launches a game and runs a scripted grass-to-battle test with A/B comparison.

## 0.1.8 — controller mapping, frontend launching and pacing

- Add a dedicated per-game Button mapping menu with visible custom assignments,
  reset/clear actions and a held fast-forward binding. The hotkey defaults to
  150% speed and restores the configured emulation limit on release. ADPF work
  targets follow the active speed; no physical button is reassigned by default.
- Export a scan-ready frontend folder containing small `.zkr` game references,
  a Cocoon platform definition and an iiSU emulator-entry example. Public
  ACTION_VIEW launches resolve only games already installed in Zakuro, sharing
  the library's native-code validation, settings and saves. Original decrypted
  ROM URIs can also identify installed games. Repeated launches reuse the active
  game; switching titles asks before discarding unsaved progress. See
  [frontend setup](frontends.md) for custom-player requirements.
- Add an iiSU configuration assistant: choose the existing emulator catalog and
  save an edited copy that retains other consoles/players, adds Zakuro and allows
  `.zkr` files. It does not overwrite the original or silently import the copy
  into iiSU. Oversized, missing-3DS and ambiguous catalogs are rejected.

- Use the default preferred mailbox presenter at normal speed too. Previously
  100% speed plus a 60 FPS cap forced FIFO, while 150% used mailbox. This removes
  that presentation-path difference without accelerating the 100% game clock.
  A short Thor check at Mount Hokulani retained full normal speed and 3x HD
  rendering; isolated missed refreshes remain. See the verification record.
- Add a per-game emulation speed limit: normal (100%), 1.5×, 2×, 3×, 4× or
  unlimited. Presentation remains independently capped; fast-forward does not
  wait for the normal-speed timer gate. Performance hints follow the speed
  target, aiming at 4× work intervals when unlimited is selected.
- Measure emulation FPS and percentage of real-time speed from guest cycles;
  report presentation submissions separately in the overlay and benchmark log.
- Preserve up to one refresh of scheduling debt so a slow guest tick does not
  delay the following cheap tick by another full interval. Lifecycle transitions
  still reset the deadline; the frame-rate ceiling remains.
- Reuse unchanged CPU screen images in each Android presenter instead of
  uploading them again. GPU transitions, size changes and failed presents force
  a fresh upload.
- Avoid copying the final 2 KiB of output registers into the vertex batch's
  divergence list. Shader arithmetic and divergent-path ordering are unchanged.
- Apply the HD texture upload allowance per emulated refresh rather than per GPU
  submission, including incoming picture bytes in the remaining allowance.
  One oversized picture may upload into an empty allowance so it can progress.
- Log frames longer than 25 ms with separate emulation/presentation times, at
  most once per second, to distinguish remaining stalls without flooding logcat.
- Keep full resolution after an app switch: the presenter keeps its Vulkan device
  and only replaces the window surface, so screens stay shared with the renderer
  instead of falling back to 1× copies read from guest memory.

## 0.1.7 — frame pacing, performance and fixes (2026-10-31)

Performance (measured on AYN Thor, Ultra Moon; see [testing](testing.md)):

- Stop rebuilding both swapchains every frame. The 0.1.4 identity pre-transform
  made Android report every present as `SUBOPTIMAL`, and each was answered with
  `device_wait_idle` plus a rebuild (~65 per second). An intentional transform
  mismatch is now ignored; resizes still rebuild through window events.
- Pace the main screen to vsync: request a fixed 60 Hz source rate per window
  (`ANativeWindow_setFrameRate`, linked from `libnativewindow`) and present in FIFO
  at the default 60 FPS cap, with the timer kept only as a 61 Hz ceiling.
- Report each frame's emulation work to Android's performance-hint API (ADPF, API
  31+) against a 16.7 ms target so the governor holds CPU clocks. The vsync wait is
  excluded from the report so it does not over-boost.
- Stop the JIT flood: `cranelift_jit` logged every shader's IR at info level (35k
  lines in 40 s). Only Zakuro crates log at info; others at warning.
- Cap rayon at 3 workers (8 idle-spun in the kernel on the emulation thread's
  cores), skip the spin-wait under FIFO, reuse clipping buffers per draw, and reuse
  a CPU-drawn screen's upscaled picture while it is unchanged.
- The benchmark log line now reports worst frame, frames over budget, CPU screen
  fallbacks and GPU waits per caller. The app is `profileable` from the shell.

Fixes:

- Pokémon Ultra Sun/Moon: the Ultra Wormhole showed a grey top screen. HID
  `GetGyroscopeLowCalibrateParam` returned zeros; the game divides by the unit
  range, so its camera matrices became NaN. It now returns the console's ±6700.
- Turning HD textures off now holds: pause-menu Restart relaunches from saved
  settings, and the library reads settings across processes.
- Save restore accepts ZIPs re-made by Windows PowerShell/.NET (backslash paths),
  with one wrapper folder, macOS metadata or a CRLF manifest; all safety checks run
  on the normalized names, and errors say what is wrong.
- Save lock: a second in-process lock attempt no longer drops the held POSIX lock.
- Export opens the new document in plain write mode for cloud providers.

Known issue: on the stock Qualcomm driver, Performance → Vertex shader processing →
GPU crashes the game (the driver throws inside pipeline creation). Keep it on Auto.

## 0.1.6 — save folder hotfix (2026-10-31)

- Fix "Cannot open saves. Unsafe directory" on devices, which blocked game launch
  and the Saves button: resolve Android's symlinked files directory before checking
  Zakuro's own save folders.

## 0.1.5 — accessible save backups (2026-10-31)

- Add a per-game Saves button and Settings shortcut to export in-game saves as
  ZIPs to Android's file picker and restore a matching backup with confirmation.
- Keep the previous save on restore and recover an interrupted directory switch
  before the next game starts. Validate backups before replacing live files.
- Lock save access across the game and library processes so transfers cannot race
  a paused or running game. Quick save states remain separate from in-game saves.

## 0.1.4 — display rotation fix (2026-10-31)

- Use identity presentation transform when supported: the renderer draws in
  window coordinates and Android handles the panel rotation. Previously the
  swapchain claimed pre-rotated content without rotating it, leaving game screens
  sideways on rotated panels. Applies to primary and external Vulkan presenters.
- Keep logical window dimensions and touch coordinates unchanged. Android may
  incur an extra composition pass on devices without hardware rotation support.
- No game-code rebuild or reimport needed. Physical Thor verification is pending.

## 0.1.3 — signed test build (2026-10-31)

- Reject incompatible ABI-5/fast-memory game libraries before copying a ROM.
  Preserve the real loader error for compatible-but-unloadable native code.
- Add pinned Android ABI-4 game-code builder and code-only repair, preserving
  existing ROMs and in-game saves. Keep the previous native library for recovery.
- Make texture packs explicitly optional. Skip pack inspection/copy when excluded;
  attach or replace only a pack later without reimporting the game.
- Add grouped per-game display, performance, motion, controls, audio and system
  settings, plus pause actions, native FPS/clock and optional touch controls.
- Wire actual sensor calibration, axis/sensitivity controls, stick motion,
  controller mappings, layout/stretch/filter/stereo, volume, game enhancements,
  save/load and restart to the runtime. Unsupported backend features are tracked
  in the feature matrix rather than represented by ineffective controls.
- Add second-display routing, custom Vulkan driver import/loading, battery HUD,
  orientation and automatic gamepad touch-overlay hiding.
- Add real Vulkan shader/debug controls and a version-gated Ultra Moon v1.0
  outline toggle, preserving the existing frame-rate warning.
- Add source-backed compatibility, architecture, feature and verification docs.

Exact build and verification results are in [Testing](testing.md). A release
number records packaging; it does not certify device or game compatibility.

## 0.1.2

Validate ROM/native/selected asset headers and sizes before destination staging;
check capacity, copy selected files only, and activate an immutable import version
through a synced/read-back AtomicFile pointer. Preserve stable in-game save links.

## 0.1.1

Native Android library UI with light/dark palette, accessible focus and font
scaling, responsive game rows, title extraction, search, progress and safe dialogs.

## 0.1.0

Separate ARM64 Android Vulkan frontend based on Zakuro v0.2.30, NativeActivity
lifecycle, Android audio and physical input, raw motion bridge and signed APK
builds. Original Pocket Native and game folders remain separate.
