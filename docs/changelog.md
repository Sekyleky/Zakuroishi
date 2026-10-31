# Changelog

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
