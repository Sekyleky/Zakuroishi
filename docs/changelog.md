# Changelog

## Unreleased

- **Cycle layout button (host-checked, not run on device):** Button mapping can bind Cycle layout, which switches between the five screen layouts while playing (not with two displays); the saved layout is unchanged.
- **Faster HD-pack lookups (host-measured, not run on device):** a texture whose size and format no pack picture is named for skips both pack hashes (on a PC 45-820 µs to 0 per miss); matches are unchanged. Turning off Fast texture decoding also turns this filter off.
- **Easier to read on a handheld (host-checked, not run on device):** setting descriptions are 16sp instead of 14sp, and at large font the library's details line keeps two lines so the game name is not cut.
- **4× and 5× rendering resolution (host-checked, not run on device):** the Rendering resolution setting offers 4× and 5× besides 1× to 3×; they need much more GPU memory and can slow the game.
- **Picture effects (host-checked, not run on device):** Display → Picture effect applies Sharpen, Vivid colours, Scanlines or Smooth edges to the screens, with a strength setting; off by default, and off costs nothing extra.
- **Texture packs with extra bytes in their pictures import (host-checked, not run on device):** a PNG with bytes after its end marker, which some pack tools leave, was refused as "Incomplete PNG image"; such bytes are now ignored, while truncated pictures, a missing end and bad checksums are still refused. Found with a Majora's Mask 3D HD pack where 89 of 1,624 pictures had them.
- **Dump textures (host-checked, not run on device):** an Advanced setting saves each new texture a game uses as a PNG named for a texture pack, in the app's folder under `Android/data`, as a start for making HD packs.
- **Controller polish (host-checked, not run on device):** Add cheat opens with the name field selected; L1/R1 in library dialogs scroll without moving the selection off the button; an open Game files page shows a finished import (update or DLC, camera picture, cheats) without reopening it; button-hint chips grow with large font instead of clipping.
- **Updater and rooms hardening (host-checked):** the in-app updater talks only to the exact GitHub hosts a release download uses; a room closes connections beyond 64 that have not said hello yet.
- **Screen gap and small-screen size:** Display settings add space between the screens in Stacked and Side by side, and set the size of the small screen in Large top (gap seen on the AYN Thor; Large top sizes and touch not run on device).
- **Faster texture decoding:** textures are decoded tile by tile, 1.4 to 3.3 times faster on a PC test per format; on the AYN Thor Ultra Moon draws correctly at 60 fps. Device frame-time gains not measured yet.
- **Sturdier rooms (tests on Windows and Linux; on the AYN Thor a hosted room took a 10-emoji nickname and cut a 300-character game name to 64):** a room now turns away nicknames over 40 bytes and cuts game names to 64 bytes, drops a member that stops receiving instead of waiting on it, caps what a console keeps unread at 4 MiB, and a console refuses a malformed answer from a host instead of stopping.
- **Controller fixes (checked on the AYN Thor):** A on a text field (Add cheat, Game name, room settings) opens the on-screen keyboard; Start no longer confirms a destructive dialog such as Discard changes or Remove cheats; L1/R1 scroll long library dialogs such as Setup help; A acts on what had focus when it was pressed, not when it was released; the pause menu scrolls when it does not fit (large font not run on device).
- **Play time survives a crash (checked on the AYN Thor):** it is saved every minute while a game plays, so a game that is killed loses at most a minute instead of the whole session.
- **Camera picture (loading checked on the AYN Thor; a game scanning it not run on device):** Game files → Camera picture… chooses a picture, such as a QR code, that the game's cameras see instead of black. See [android](android.md#camera-picture).
- **Cheat log:** when cheats change a game's code, the log names the first cheat or enhancement and the address it wrote, and the enhancements a game's settings switch on are logged. On the AYN Thor this showed that Ultra Moon's "1 functions run in the interpreter" line came from its No Outlines enhancement patching code, as it should, not from a player's cheat.
- **Local wireless (nightly build only; on the AYN Thor with a PC test client, not yet with two consoles in a game):** a game can join or host a room in Settings → Advanced → Local wireless, to play with another Zakuroishi or the Zakuro PC build. A hosted room can be given a name, and the pause menu can leave the room or join it again. A room that cannot be opened or joined says what to change. See [android](android.md#local-wireless-nightly-build-only).
- **Cheats in a running game (checked on the AYN Thor):** pause menu → Settings… → Cheats… switches the imported cheats, and a switch applies at once instead of at the next start. See [android](android.md#cheats).
- **Add cheat… (checked on the AYN Thor):** type a cheat's name and codes in Game files or the Cheats… list; it is added switched on.
- **Stick directions on buttons (checked on the AYN Thor):** Button mapping can bind a button to a Circle Pad or C-stick direction.
- **Mods… in Game files (list checked on the AYN Thor; removing a part not run on device):** lists what a game's mods change with their size, and removes one part (for example only the code patch) or all of them.
- **Updates and DLC (import, refusal, removal and Storage checked on the AYN Thor with a test file; a real update or DLC not run on device):** Game files → Import update or DLC… adds a game's decrypted update, DLC or Download Play child; the game uses them from its next start. See [android](android.md#updates-and-dlc).
- **Fixed (host-checked, not run on device):** importing cheats or settings, reading a driver's header and exporting or patching frontend files used Java stream methods that exist only from Android 13; on Android 11 and 12 they would have stopped with an error. A host check now refuses those methods in the app's source.

## 0.6.1 — Faster intro with frame generation, upstream Zakuro merge, clearer save-state messages (2026-11-17)

Release notes: [0.6.1](releases/android-0.6.1.md). Tested on one device, an AYN Thor; see
[testing](testing.md). Make an in-game save before updating: save states from 0.6.0 and earlier no
longer load.

- **Updater (nightly build only):** more than one nightly a day is now offered (tag
  `nightly-YYYYMMDD.N`); builds from before this see only the next day's nightly.
- **Frame generation:** new switch **Prepare while the game loads**
  (`frame_generation_prebuild`, Advanced, on by default) builds the generation graph before the
  game's first picture instead of at it. On the Thor the build took 465 ms and stalled Ultra Moon's
  intro (window 1 at ~91% speed vs 98.5% with generation off); now it happens during loading. On
  the Thor, intro window 1 ran at 99.5% / 98.2% instead of 90.8% / 90.4%, and the worst frame fell
  from 526 ms to 46-122 ms; loading takes about 0.4-0.5 s longer.
- **Fixes:** Exit to library no longer crashes the game process (which also lost the play time), and "title.so" in the help text is no longer a link.
- **Updates from inside the app (nightly build only; on the Thor it found, downloaded and verified a newer nightly, install not tested):** Menu → Check for
  updates… finds a newer release on GitHub, downloads it, checks it against the release's
  `SHA256SUMS` and the app's own package name and signing key, and offers Install / Later.
  Check for updates when the app opens (on by default) does this at most once a day and stays
  silent on failure. Nothing older is offered. Install is refused while a game is running.
  See [android](android.md#updates-from-inside-the-app-nightly-build-only).
- **Nightly builds contact GitHub (nightly build only):** the nightly APK now has `INTERNET`,
  `REQUEST_INSTALL_PACKAGES` and `UPDATE_PACKAGES_WITHOUT_USER_ACTION`; the stable APK still
  has no permissions. `build-windows.ps1` adds them only when the version name contains
  `-nightly` and fails if a stable APK has `INTERNET`.
- **Build (affects nightly builds only):** nightly version names carry their tag date, `-VersionName 0.6.0-nightly.YYYYMMDD`
  for tag `nightly-YYYYMMDD`, so the updater can tell nightlies apart. A name containing
  `-nightly` (no longer only ending in it) turns on the nightly features.
- **Upstream Zakuro after 0.2.32 (run on the Thor, build `b08f214`):** merged `fearkov/zakuro` up to `3fe7b15`:
  recompiled code that reads the clock, takes and lets go of a mutex and skips clock-polling loops
  itself (`spin.rs`); GPU draws that set, copy and hash only what changed (vertex arrays kept across
  batches, strips drawn as strips, lighting tables found by hash, descriptors pushed only when they
  change); multiply hashes instead of SipHash; Cranelift stack probes; the 3DS microphone service
  (games hear silence; there is no Android microphone input). 3dsrecomp moves from `73b8f9c` to
  `730fbac` (ABI 8, generation 6). Existing ABI 4/5 `title.so` files still load and run: 3dsrecomp
  accepts ABI 4 to 8 and the mutex lending is used only for ABI 8 code; rebuilding with
  `build-title` gets the new speedups. Import and Replace native game code now accept ABI 4 to 8.
  Left out: the macOS Pro Controller reader (`4bae63f`), desktop only. On the Thor, the
  `kept_arrays` A/B showed no difference and an old state was refused.
- **Save states from earlier versions no longer load:** the microphone service changed what a state
  holds, so loading an older state shows "Quick state not loaded: it was made by another version
  of Zakuroishi" (or "Slot N …") and changes nothing; the file is kept. In-game saves are not
  affected. Save and load messages now name the quick state instead of calling it slot 0.
- **New switch, Advanced → Keep vertex arrays on the GPU** (`kept_arrays`, on): upstream's
  vertex-array heap; turn off to compare. See [android](android.md).

## 0.6.0 — Library tiles, simple settings, pause menu lists, hotkeys, cheats, Rest in 60 FPS scenes (2026-11-16)

The release APKs were published without a device run and smoke-tested on the Thor afterwards on
2026-11-16: install over an earlier test build (version code 15), the stable and nightly builds
over each other, the library, and Pokémon X on its first screen. Installing over the released 0.5.0,
play past the first screen, saves, the Pin action, a ROM-only import and the second screen were not
run ([testing](testing.md#smoke-test-of-the-release-apks-2026-11-16-after-publishing)). The
features were checked on one device, an AYN Thor, on 2026-11-15 and 2026-11-16 with earlier builds
of this branch, see
[testing](testing.md#library-settings-and-pause-menu-rework-2026-11-15) and
[testing](testing.md#rework-re-check-and-rest-in-60-fps-scenes-2026-11-16). Release notes:
[0.6.0](releases/android-0.6.0.md). Two features were not run on the device beyond that and are only in the
nightly build (version name `0.6.0-nightly`): importing a game without native code, and Pin to
home screen. 0.6.0 refuses a folder without native code and hides Pin to home screen.

- **Library:** games are tiles with their own icon, most recently played first, with play time.
  The focused game's details show under the header and, on a second screen, large with Play,
  Settings and Saves buttons. The header is Add game and Menu (Start); Setup help, Frontends,
  Storage and the exports are in Menu. In the nightly build only, a game can be pinned to the
  home screen.
- **Settings:** a game's settings open on a short page with a preset (Battery, Balanced, Quality),
  resolution, the texture pack and twelve common settings; everything else is under
  All settings. **Default settings** in Menu apply to every game until a game changes them, and
  changed rows are marked. The comparison switches moved to Advanced. Preset values are a first
  guess, not measured.
- **Pause menu:** Save state and Load state each open one list (quick slot, then slots 1-9);
  X saves and Y loads the quick slot (seen on the Thor). Cancel or B goes back to the pause menu instead of
  resuming. New tiles: HUD on/off (clock and battery, no restart) and Screenshot (top screen,
  saved to Pictures/Zakuroishi).
- **Games without native code (nightly build only):** a folder with only a decrypted ROM can be
  imported and runs in the interpreter; native code can be added later from the game's settings.
  Not run on a device. 0.6.0 refuses such a folder before copying anything and says the nightly
  build can import it. See [native library](native-library.md#importing-without-native-code).
- **Build:** `build-windows.ps1 -VersionName 0.6.0-nightly` builds the nightly from the same
  tree. A version name ending in `-nightly` turns on the features above that were not run on a
  device; the stable and nightly APKs share version code 16 and the signing key, so either
  installs over the other.
- **Hotkeys:** a controller key can be mapped to quick save, quick load (asks first), swap
  screens, screenshot or the pause menu. Swap screens, screenshot, quick load, the pause menu
  and quick save were seen on the Thor.
- **Save-state thumbnails:** each slot shows a small picture of where it was saved. Seen on the
  Thor for a new save and in the Load state list; an older state shows a blank tile.
- **Cheats:** Game files has Import cheats (one text file in the common cheat format), a switch
  per cheat and Remove cheats; the file is kept when the game is imported again. Seen on the
  Thor with a harmless two-cheat file: import through the file picker, the Cheats… list with a
  switch each, the core logging "2 cheats in <file>, 1 of them on" at the next start, and Remove
  cheats (no cheat line at the next start). Not run: keeping the file across a re-import of the game, a rejected (invalid) file, and a cheat that really changes game memory.
- **Frame generation:** new switch **Rest in 60 FPS scenes** (`frame_generation_rest`, on by
  default; it is on because it was the better side in every measured window and changed nothing in 30 FPS scenes; a game with the setting stored keeps its value). At 4× a title or intro the game draws at 60 ran at 75-80% speed; with the switch the
  game's own pictures are shown there. On the Thor with the panel at 120 Hz and 4×, Ultra Moon's intro held 100.1-100.5% with it on and
  stayed at 88.7-93.8% with it off (two launches per side, after the first two windows); the panel
  then shows about 60 distinct pictures a second there instead of about 107. At 2× on a 60 Hz
  panel the first window was 88.4% on and 52.9% off, one launch each. Other games, the stock
  driver and input delay are not measured.
- **Fixed:** confirmation dialogs inside game settings (Discard changes?, Remove cheats?, Clear
  shader cache?) opened with no button lit; they now select Cancel or Keep editing on opening. Seen on
  the Thor for Discard changes?.
- **Fixed:** importing your Lossless.dll from the in-game settings closed the game; the button
  is now only in the library.
- **Build:** the Windows APK build passes the classes to the dexer as one jar. The class list
  had outgrown the command line limit.
- **Save slots:** the slot list now opens with its first row selected. In 0.5.0 nothing was
  selected until the D-pad moved, so A did nothing at first. Checked on the Thor, see
  [testing](testing.md#050-check-on-the-thor-2026-11-15).
- 0.5.0 was checked on the Thor on 2026-11-15: launch, play, save slots and the
  `request_wait_idle` switch. The software keyboard dialog and mod stacking were not reached.

## 0.5.0 — Save slots, stacking mods, the software keyboard, Pocket Native core fixes (2026-11-09)

Released without a device test: host checks and CI only, see the [release notes](releases/android-0.5.0.md).

- **Software keyboard:** a game asking for a name or other text (Ocarina of Time 3D's file name)
  now gets an Android text dialog with the game's own buttons. Before, nothing appeared and the game
  waited forever.
- **Save states:** **Save to slot…** and **Load from slot…** in the game menu keep up to 9 states
  per game, listed with the time they were saved. **Save state** and **Load state** still use the
  quick slot, slot 0, as before.
- **Mods:** a mod can be added on top of a game's current mods or replace them; files from the
  newer mod win, and two mods that both change game code are refused. **Remove mods** deletes a
  game's mods and keeps its texture pack. A mod folder is found when you pick a folder above it.
  An `exefs/` banner, icon or logo is ignored. ZIPs still need extracting first.
- **Ported from Pocket Native:**
  - A request to a system service no longer waits when no other thread is ready to run, so an
    idle core doesn't wait for the GPU on every request. The old wait is the Performance switch
    `request_wait_idle`, off by default.
  - Closed timers leave the timer list, and a timer is never listed twice.
  - Country code lookups in the system settings service.
  - Writing a sound voice's filter settings no longer turns the filter on.
  - Geometry shaders in fixed primitive mode (Kid Icarus: Uprising).
- Quick states saved by 0.4.0 may fail to load, with a message, because a sound filter's state
  changed. Saves are not affected.

## 0.4.0 — Upstream 0.2.32, Tools menu, save-editor backups, release signing key (2026-11-07)

Checked on the Thor on 2026-11-07, see
[testing](testing.md#thor-re-test-of-the-audit-fixes-branch-2026-11-07-build-2fc37e4). Release notes:
[0.4.0](releases/android-0.4.0.md).

- **Merged upstream Zakuro 0.2.32** (`c856685`), so upstream's history is part of this branch instead
  of ported commits. The fork's shader, texture and upload switches stay. Checked against the build
  without it, see [testing](testing.md#upstream-0232-merge-on-the-thor-2026-11-07-build-3488a03).
- **Quick states** made before this are refused with a message naming both versions, because the
  emulator's version changed. Make new ones. In-game saves are not affected.
- **CI:** the container APK build works again. It had failed on Java source encoding and on reading
  the APK a root container wrote, and the checksum file now holds a bare file name.
- **Tools menu** in the library header.
  - **Storage** lists old imports, previous native code, texture and mod backups and unfinished
    imports with sizes, and deletes one at a time after a confirm. It never lists an active
    import or anything in `savedata`. Deleting an old import also deletes its quick states. It
    refuses while a game or import runs (that refusal has not been tried on a device).
  - **Export diagnostics** writes app, device and log details to a text file. Picker addresses
    are removed; file paths remain.
  - **Export settings** and **Import settings** use a JSON file of per-game settings, controller
    mappings and enhancements. No drivers, saves or ROMs. Import refuses the whole file on any
    invalid value.
- **Imports** keep the larger of 256 MiB and 5% free and report running out of space plainly.
  Replacing mod parts is all-or-nothing.
- New games, replaced native code and drivers show a SHA-256. Libraries from an older generator get
  a rebuild hint. Damaged table counts are rejected at import. A library built for Pocket Native is
  named as such.
- **Save editors:** the 3 newest backups of the in-game saves are kept before an editor writes,
  skipping unchanged ones, as `savedata/TITLE_ID/user.backup-<ms>`, browsable read-only under
  `:backups` in each game's folder in the file picker. `user.previous` is now only the recovery
  copy from Restore ZIP.
- **HUD:** battery watts appear only once the unit is certain (a reading of 20000 or more means
  microamps for that session); a device reporting milliamps never shows watts. One message appears
  if the HUD can't be shown.
- **Texture packs:** a failing reader no longer aborts or leaves an HD texture stuck; the original
  texture is used.
- **Frontends:** `file://` launch URIs are no longer accepted. `zakuro://game/ID` still launches.
- **Signing:** new builds use a release key, with a lineage from the old development key, so they
  install over published APKs and keep your data. Fingerprints are in the README.
- **Build:** CI compiles the Android code and builds the APK. Container downloads are verified. The
  3dsrecomp dependency is pinned.
- Tried and dropped: a switch sharing the second display's picture with the main renderer. It
  removed the CPU copy (`cpu_bottom` 300 to 0 per window) but changed no speed, present, latency or
  gap figure within noise, so it is not in the code (it stays on the `framegen-lsfg` branch).
- Docs: `SECURITY.md`, [known issues](known-issues.md), the signing fingerprint in the README, a list of
  [ported upstream changes](upstream-desktop.md#ported-upstream-changes), and corrections to the
  "60 FPS" wording in the 0.1.7 and 0.2.0 notes (display refreshes, not new game pictures).

## 0.3.1 — Optional frame generation with your own Lossless Scaling, code mods, 120 Hz pacing fix (2026-11-07)

- **Mods that change game code can be imported.** **Import mod…** now takes `code.ips`, `code.bps`,
  `code.bin` and `exheader.bin`, and `zakuro-build-title` takes a data folder to build a `title.so` with them.
  `svcOpenProcess` and `svcControlProcessMemory` exist now, which Magikoopa-style mod loaders call first;
  without them the Ocarina of Time 3D free camera mod stopped the game on its first frame.
- **Games no longer run fast on a panel that stays at 120 Hz.** With vsync pacing on such a
  panel, games ran at 102% with the sound lag sawing between 100 and 450 ms. The loop's 61 Hz
  ceiling set the pace; it now measures its pace and uses exactly 60 Hz once it runs faster
  than a 60 Hz panel could show. 0.3.0 was affected. A 60 Hz panel keeps the old ceiling.
- **Frame generation, experimental and off by default** (`frame_generation`,
  `frame_generation_multiplier`, `frame_generation_flow`). Shows pictures made between the
  game's own with the MIT lsfg-vk 1.0 code and the shaders of **your own Lossless.dll**, which is
  never included or downloaded. Performance settings → **Import your Lossless.dll…** translates
  it on the device and deletes the app's copy. Needs Turnip. Unaffiliated with Lossless Scaling
  and its developer. On the Thor, Ultra Moon: 119 presents/s from 30 game fps at 99.7% speed;
  screens that draw at 60, such as the title and intro, reach only about 95 fps at about 80%
  speed with x4. With Android holding the screen at 60 Hz, x4 ran the game at half speed, so it
  now switches itself to x2 and says so (that switch not yet run on a 60 Hz screen). Numbers in
  `testing.md`.
- With the switch on and no imported shaders the game starts normally without it and says so.
  Frame generation and frame interpolation turn each other off in settings.

## 0.3.0 — Orange controller menus, romfs mods, sound in step, frame interpolation measured (2026-11-04)

- **Frame generation measured on the Thor.** The existing Frame interpolation setting
  takes Ultra Moon from 30 to 58-60 distinct pictures per second on Turnip at unchanged
  game speed. It stays off by default: on the stock Qualcomm driver the game fell to
  65-77% speed. The HUD and the benchmark line show `distinct` pictures per second
  (`game_fps distinct_fps between rough off_beat`); `device-test.sh latency` and `shots`
  are added.

- Settings reorganised: everyday options first; troubleshooting switches moved to a new Advanced page.
- **Optional: finish shader warm-up before the game starts** (`warm_before_start`, off).
  The first frame waits, 30 s at most, for the remembered vertex programs and GPU
  pipelines; the log says how long it waited and how many were warmed.
- **Sound stays in step with the picture.** Under vsync the game runs 0.3% fast and
  0.2.0's sound queue filled to its 250 ms cap; Android now uses the desktop queue
  (time stretch, fade instead of hard silence, no silent buffer on a busy lock)
  with a learned rate trim: 100–125 ms behind instead of ~230 ms
  (`audio_queue_managed`). The benchmark line logs `audio_queue_ms`/`audio_underruns`.
- **Low-latency sound switch, off.** `low_latency_audio` asks AAudio for a FAST track
  without the RAW flag the Thor's output lacks; the Thor still gets a normal track,
  because a system-wide software Equalizer on the output mix denies FAST to every app.
- **Quick taps are no longer lost.** A button pressed and released between two frames
  used to never reach the game; it now counts as pressed for one frame
  (`latch_short_presses`, controller keys, hat, triggers and touch). The benchmark
  line counts such presses as `short_presses`.
- **Vsync queue switch, off.** `short_vsync_queue` asks for the surface's minimum
  swapchain images (3 instead of 4 on the Thor) under vsync pacing: on Turnip a frame
  reaches the screen 40 ms after it is ready instead of 57 ms, with no extra late frames;
  on the stock driver it changes nothing (16 ms) and adds a few late frames, so it is off.
  The image count is logged with the present mode.
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
