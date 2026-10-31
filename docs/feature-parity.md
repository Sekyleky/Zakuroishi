# Feature parity audit

Source audit: 2026-10-30, refreshed 2026-10-31. This is an inventory of the local Pocket Native frontend,
upstream Zakuro desktop frontend, and this Android port. A setting is supported
only when its value reaches a real consumer. Shared engine code, a saved key, or
a visible checkbox alone is not an Android feature claim.

## Sources and counting

* Pocket Native checkout HEAD: `c16f4e3eaf8bf8a439deff0fe666fc7d787881f5`,
  origin `whovian64/pocket-native`, with local modifications. The modified local
  sources, not the remote commit alone, define the Pocket Native column.
* Upstream Zakuro checkout: [e174910de5e7285581b153cbce05543efb135cd5](https://github.com/fearkov/zakuro/tree/e174910de5e7285581b153cbce05543efb135cd5).
  This port HEAD was `fd6896223a1863cbc19960c0f5299c1c2b2b1df6` when inspected;
  Android implementation below includes the current uncommitted changes.
* [audit-sources.json](audit-sources.json) records the local reference hashes.
  Verified SHA256: PN settings
  `FCD9FD0E9D2AEEF47C65C2324C137E3A1A765C8C68E2E8CC68BDEA66F2B65A81`;
  PN menu `9F2CE62D8051733B358306C27D4EB9D5904A42DD340485747FC00F6F6FDC03C3`;
  desktop settings `95DF886B4FEC28860A460D912D8DDB0B49003AC31E14DB9612DB2C9B46DE08EB`.
  These identify source content, not test results or shipped APKs.

Count named struct fields once per declaration, including container fields;
do not count enum choices, defaults, or generated title options as additional keys.

| Reference | Exact inventory |
|---|---|
| PN [settings.rs](../../pocket-native-src/frontend/src/settings.rs) | `Settings`: **31**; `GameSettings`: **4**; `Advanced`: **32**. Total **67 field declarations**, including the `advanced` container. |
| PN [menu.rs](../../pocket-native-src/frontend/src/menu.rs) | **42** `Field` variants, **31** boolean Advanced toggles plus numeric `FrameSkip`; **7** `Action` variants. `Option(usize)` is dynamic, not a fixed option count. |
| PN Java `Launcher.showDevice` | **22** controls: **20** choice rows (including five motion-inversion rows generated in a loop) and **2** import buttons. The `orientation` preference and `oneScreen()` marker are additional device state outside Rust `Settings`. |
| Zakuro [settings.rs](../crates/zakuro/src/settings.rs) | `Settings`: **31**; nested `Keys`: **22**, `PadButtons`: **14**. **65 leaf fields** when the two binding containers are expanded. |
| Zakuro [menus.rs](../crates/zakuro/src/menus.rs) | **28** `Action` variants, inventoried below. |
| Android [settings.rs](../crates/zakuro-android/src/settings.rs) | `Advanced`: **50** typed JSON fields at this source refresh (initial audit: 34). |
| Android [GameSettings.java](../android/app/src/main/java/io/github/zakuro/android/GameSettings.java) | **53** `ALL` entries at this source refresh, plus `resolution`, `textures`, display `name`, selected `driver`, controller bindings and enhancements. Java-only settings have real Java consumers. |

## CodeSupport and verification legend

* **Wired**: Android UI/launch input reaches the named implementation.
* **Partial**: a working subset exists; the limitation in the row is material.
* **Fixed**: Android uses a real fixed policy instead of the source's selector.
* **Unsupported**: no corresponding Android feature path; do not add inert flags.
* **Host passed**: actual host execution in this audit, identified at the end.
* **Source only**: inspected implementation; device behavior not verified here.
* **Tests present**: named tests exist; presence alone is not a passing run.
* **Android SAF passed/failed**: executed through the real DocumentsProvider on
  isolated API 35 emulator `emulator-5582`, adb server `5039`; no native game ran.

Unless a row says otherwise, runtime verification is **Source only**. None of
these rows claims an ARM64 gameplay, sensor, driver, or physical dual-display test.

Android source abbreviations used in the tables:

* **J**: [GameSettings](../android/app/src/main/java/io/github/zakuro/android/GameSettings.java)
  `from/save/json/apply`, [SettingsDialog](../android/app/src/main/java/io/github/zakuro/android/SettingsDialog.java).
  Preferences use `<titleId>.advanced.<key>`; `resolution` and `textures` have
  their own per-title preferences and Intent extras.
* **R**: [runtime.rs](../crates/zakuro-android/src/runtime.rs) `run`, `Surface::configure/present`.
* **S**: [settings.rs](../crates/zakuro-android/src/settings.rs) `Advanced::parse/validate/binding/axis/gyro/presentation_interval`.
* **G**: [geometry.rs](../crates/zakuro-android/src/geometry.rs) `layout/filter/stereo/places/touch`;
  [GPU geometry](../crates/zakuro-gpu/src/backend/mod.rs) `place_configured`.
* **I**: [input.rs](../crates/zakuro-android/src/input.rs) `Input::configured`.
* **M**: [sensors.rs](../crates/zakuro-android/src/sensors.rs)
  `Sensors::new/set_active/sample/recalibrate`; [transform.rs](../crates/zakuro-android/src/transform.rs)
  `GyroCalibration`, `display_vector`.
* **P**: [platform.rs](../crates/zakuro-android/src/platform.rs) `launch`, JNI Intent decoding and owned-path validation.
* **Game**: [Game.java](../android/app/src/main/java/io/github/zakuro/android/Game.java)
  `overlays`, `pauseMenu`, `openSettings`, `clock`, `showRuntimeError`.

## Critical differences

1. **Custom Vulkan driver code is implemented; packaging/hardware validation is
   separate.** Java `Launcher.importDriver/installDriver/extractDriver` and
   `GameSettings.driverLibrary/apply` feed `driver_dir/driver_name` plus
   `custom_driver` into P, [DriverSpec::validate/Driver::load](../crates/zakuro-android/src/driver.rs),
   adrenotools and `VulkanPresenter::with_entry`, then the shared core renderer.
   Private ARM64 ELF validation and visible errors are real; loading failure does
   not silently use the system driver. The final build includes hook-library vendoring and
   build/package verification. No compatible-GPU driver execution was tested here.
2. **Two physical displays are implemented, with readback limitations.** Game
   `updateSecondDisplay` creates a Presentation/SurfaceView; `nativeSecondSurface`
   and `nativeSecondTouch` reach the bridge and external Vulkan presenter. Main
   top/external bottom routing, swap, external touch, independent stretch and
   disconnect fallback are implemented. External frames use readback, not shared
   cross-device GPU images. Physical two-display hardware was not exercised.
3. **PN Advanced GPU hacks are not Android settings.** The PN engine and its
   environment-variable consumers differ from this upstream engine. Exporting
   those variable names here would not establish equivalent behavior.
4. **New3DS model and extended application-memory modes are implemented.**
   `new_3ds` reaches `Config.new3ds`; the loader sets `ConsoleModel::New3ds` and
   allocates 256 MiB FCRAM instead of 128 MiB. The corrected exheader decoder
   reads CPU/L2 from flag1 and the memory-mode low nibble from flag2.
   `ExHeader::application_memory(new3ds)` selects 124 MiB for modes 1/3 or
   178 MiB for mode 2 only when New3DS is enabled; other cases retain the old
   system-mode mapping. Raw `SystemMode` discriminants are explicit; loader config
   page reports mode 6 for 124 MiB or mode 7 for 178 MiB. Final verification passed **145 core
   and 24 filesystem tests**; source implementation was inspected here.
   This is not full New3DS/FIRM fidelity
   and does not give every game 256 MiB of application RAM. Reference:
   [3dbrew Exheader](https://www.3dbrew.org/wiki/Exheader), supplied by parent;
   direct retrieval returned HTTP 403, so the mapping above is verified against
   local source and the parent's implementation report, not a fetched quotation.
5. **ABI contracts differ.** PN ABI 5/fast-memory title libraries cannot be used
   by this ABI 4 runtime. Rebuild native game code; keep strict compatibility
   guards. Successful metadata inspection is not successful native execution.
6. **Code replacement's Android hard-link failure is fixed and retested.**
   An earlier SAF run found `Os.link` returning `EACCES`. The parent replaced
   that design with a synced copy of only the old library, then one atomic
   staged-code rename. Capacity includes replacement plus backup before staging.
   The final **27/27 Android SAF** run verifies successful replacement, exact old
   backup bytes and preservation when backup creation is denied. It does not
   simulate process death, disk-full mid-write or an uncertain texture rollback.

## Local additions versus pre-existing PN features

Checked `git diff HEAD` in the local PN checkout, not just similarly named
settings. The local diff adds **10** `Settings` fields; the other PN fields and
Advanced schema predate these changes. Missing inherited PN behavior is still
listed in the full matrix, but is not mislabeled as a newly added feature.

| Locally added PN feature/key | Current Android result | Actionable gap / owner |
|---|---|---|
| `vulkan` and Vulkan renderer/core bridge | Vulkan rendering is wired; selector is fixed to Vulkan. | Runtime/UI: OpenGL ES/Vulkan choice is missing. Port an actual alternate backend before adding a selector. |
| `custom_driver` plus new `DriverPack`, `Launcher.importDriver/pickDriver`, `Game.nativeLibraries`, bundled adrenotools integration | Implemented Java import/selection, private validation and native adrenotools/Vulkan entry path. | Parent must verify final packaged hook libraries; compatible-GPU hardware validation still required. Driver metadata/API and basename parity checks were reported to UI owner. |
| `device_motion` | Wired as `motion_enabled`. | No missing enable/disable mechanism found; physical sensor behavior remains untested here. |
| `gyro_sensitivity` | Wired with the same key. | No missing mechanism found; gain scales angular velocity, not gravity. |
| `reverse_gyro_x`, `reverse_gyro_y`, `reverse_gyro_z` | Wired as `gyro_invert_x/y/z`. | No missing inversion mechanism found. |
| `stick_motion` | Wired. | Defaults differ (Android off, PN on); do not confuse defaults with an absent feature. |
| `tilt_button` | Wired dedicated `always/l1/l2/r1/r2/l3/r3` selector plus bindable `motion_gate`. | Real `TiltButton::keycode/guest_bits` and I gate handling, including virtual controls; native agent reports host tests passed. Physical controller test remains. |
| `tilt_sensitivity` | Wired as `stick_motion_sensitivity`. | No missing gain mechanism found. |
| Later HD texture-folder import/replacement and optional texture selection | Wired through `texturePack`, `replaceTextures` and per-title toggle. | **Android SAF passed**: replacement changes only pack files; original ROM/code/save/link/pointer hashes preserved. |
| Device-page exposure of `reverse_tilt_x/y` | Wired independent stick-tilt inversion, separate from gyro inversion. | Implemented at final freeze; hardware/controller behavior not exercised by this audit. |
| Atomic multi-key PN settings writes and errors, strict provider-listing errors | Android uses synchronous preferences commit and explicit import errors, rather than PN TOML. | Preserve failed-save and unreadable-provider errors; do not port the storage format merely for parity. |

### Complete missing/partial PN backlog, grouped for implementation

This list includes inherited PN features as well as local additions. It is a
source-backed backlog, not authorization for this documentation task to edit
production code. Runtime/UI agents were notified of these gaps.

1. **Runtime + UI, device integrations:** OpenGL ES alternate renderer and direct
   panel rotation override remain absent. Drivers, dual-display routing,
   independent main/external stretch and auto/landscape/portrait preference are
   now implemented; final native packaging and physical-device validation remain.
2. **Runtime + UI, controls:** D-pad Circle Pad/touch modes; R3 right-stick mode toggle
   and PN layout/D-pad shortcut chords; move/resize/reset touch-layout editor,
   saved layouts per orientation. Independent tilt X/Y inversion, seven-choice
   gate and automatic gamepad hiding are now implemented. Existing analog
   sticks, basic touch overlay and gyro controls must not be reported missing.
3. **Runtime + UI, presentation/status:** exact BigBeside/Original layout geometry;
   quarter-step 1–3.5 resolution; explicit 30/60 interpolation target;
   hardware Saver/Balanced/Performance policy (needs a real platform mechanism).
   Battery HUD is now implemented in Java.
4. **Runtime + UI, Advanced:** no Android user path for `skip_bottom`,
   `skip_right_eye`, `frame_skip`, `shown_only`, `fast`, `quick_checks`,
   `half_precision`, `plain_math`, `tile_copies`, `gpu_clears`, `gpu_arrays`,
   `trust_meshes`, `trust_textures`, `small_cache`, `cull`,
   `gpu_transfers`, `gpu_texture_copies`,
   `in_place`, `past_drawing`, `etc1`, `generic_lighting`,
   `draw_ranges`, `gpu_thread`, `realtime_gpu`, `exact_events`, `prewarm`,
   `read_ahead`, `background_shaders`. These are **28** of the 32 PN Advanced
   fields; `recompiled` has the Android `aot` equivalent and `gpu_drawing` now
   maps to real `hardware_renderer` software/hardware selection. `gpu_vertices`
   and `generic_shaders` have upstream equivalents below. Port underlying
   consumers and tests before exposing settings, especially PN-only GPU hacks.
5. **Runtime + UI, title policy:** `profile.toml` advanced inheritance,
   `Advanced::compatible()` preset, `Profile.bottom_relearn`, profile
   `GameOption.drops_when_off/bottom_full_rate_when_on` rules. Build-matched
   Android enhancements are useful but are not that profile rule engine.
6. **Runtime, audio:** PN WSOLA time-stretch/pacing is absent; Android bounded
   AAudio queue and resampling are a different mechanism.
7. **UI + lifecycle, launcher:** pinned game shortcuts, running-title/task
   discovery and PN cross-game switch protocol, in-app release/APK update flow.

The separate upstream-desktop gaps (camera, guest keyboard, room/Download Play,
updates/DLC management, arbitrary cheat editor, on-device compilation and extra
state slots) are enumerated below; they are not falsely attributed to the local
PN diff. New3DS extended application-memory sizing has now been implemented by
the final suites (145 core / 24 filesystem tests passed), rather than remaining a missing feature;
it is not a local PN-added key or a claim of full FIRM fidelity.

## Every Pocket Native global and game key

Reference mechanisms: PN `Settings`, `GameSettings`, `menu::sections/change`,
[frontend lib](../../pocket-native-src/frontend/src/lib.rs),
[layout](../../pocket-native-src/frontend/src/layout.rs), and Android launcher
[Device settings](../../pocket-native-src/scripts/android/java/io/github/pocketnative/Launcher.java).

| PN key | Android key / method and CodeSupport |
|---|---|
| `layout` | **Partial**: `layout`, G/R; stacked, side-by-side, large-top, top-only, bottom-only. PN `BigBeside` and `Original` geometries do not have exact Android layout equivalents. |
| `swapped` | **Wired**: `swap_screens`, G/R; in-game `Command::Swap` updates geometry and clears touch. |
| `smooth` | **Wired**: `filter=smooth/sharp/pixels`, S/G and presenter scaling. |
| `stretched` | **Wired equivalent**: `stretch` fills main layout areas and `external_stretch` independently fills the physical external display. These are per-display booleans rather than PN's guest-top/bottom enum; screen swapping changes routing. |
| `aspect` | **Partial**: `top_width`, `integer_scale`, `stretch`; original and wide shapes. No PN six-value enum/preset equivalence. |
| `resolution` | **Partial**: J/P `resolution` Intent → R `Config.resolution`, integer 1–3. PN supports 1–3.5 in quarter steps. |
| `vulkan` | **Fixed presentation backend**: Vulkan presenter with hardware or software 3D selected by `hardware_renderer`; no Android OpenGL ES selector. R fails explicitly when selected hardware Vulkan cannot start. |
| `custom_driver` | **Wired code**: same key, J selected driver/Intent paths, `DriverSpec::validate`, `Driver::load`, adrenotools and `VulkanPresenter::with_entry`. Packaged hooks and compatible hardware require separate verification. |
| `interpolate_to` | **Partial**: `interpolate` → R `Config.interpolate`, `System::set_interpolation(true)`; upstream shared-GPU interpolation, no PN target-30/60 selector. Failure is surfaced. |
| `show_fps` | **Wired**: R statistics → P `stats` → Game `updateNativeStats`; counts presented frames, not a benchmark claim. |
| `show_clock` | **Wired**: Java `show_clock`, Game `clock`; local time. `show_battery` separately adds percentage/charging from battery broadcasts. |
| `rotation` | **Partial**: Java `orientation=auto/landscape/portrait` calls `setRequestedOrientation`, while P `rotation` → M `display_vector` remaps motion. No PN direct clockwise/counterclockwise/off panel override. |
| `frame_cap` | **Wired**: `frame_cap`, S `presentation_interval`, R presentation deadline; 0 unlimited, 1–240. Guest frame clock remains paced separately. |
| `power` | **Unsupported**: no Saver/Balanced/Performance hardware policy or PN `POCKET_NATIVE_POWER` helper. |
| `touch_speed` | **Wired equivalent**: `cursor_speed`, I right-stick cursor update; numeric gain instead of Slow/Normal/Fast. |
| `buttons` | **Wired**: `button_mapping=nintendo/xbox`, S `binding`. |
| `left_stick` | **Wired**: `left_stick=circle_pad/dpad`, I axis-to-guest mapping. |
| `dpad` | **Partial**: physical directions and per-key bindings; no Dpad/CirclePad/Touch mode selector or D-pad cursor path. |
| `right_stick` | **Wired**: `right_stick=c_stick/cursor`, I; cursor touch press uses ZR. PN's R3 runtime mode toggle is not equivalent. |
| `reverse_tilt_x` | **Wired**: same key, I independent stick-tilt horizontal inversion. |
| `reverse_tilt_y` | **Wired**: same key, I independent stick-tilt vertical inversion. |
| `device_motion` | **Wired**: `motion_enabled`, M lifecycle enable and I device-motion route. |
| `gyro_sensitivity` | **Wired**: same key, S `gyro`, I; scales angular velocity, not gravity. |
| `reverse_gyro_x` | **Wired**: `gyro_invert_x`, S `gyro`. |
| `reverse_gyro_y` | **Wired**: `gyro_invert_y`, S `gyro`. |
| `reverse_gyro_z` | **Wired**: `gyro_invert_z`, S `gyro`. |
| `stick_motion` | **Wired**: same key, I; gated stick tilt suppresses C-stick and replaces device motion for that sample. Default differs: Android off, PN on. |
| `tilt_button` | **Wired**: same seven-choice key plus `stick_motion_gate` and custom `motion_gate` binding. S `TiltButton::keycode/guest_bits` and I handle physical/virtual gates; legacy gate=false means always. |
| `tilt_sensitivity` | **Wired**: `stick_motion_sensitivity`, I tilt gain. |
| `touch_controls` | **Partial**: Java `touch_controls`, Game `overlays`, virtual guest button mask and two sticks through JNI. `touch_auto_hide` and InputDeviceListener now hide/release virtual controls for attached gamepads. Fixed positions/sizes; no PN edit/move/resize editor. |
| `texture_packs` | **Wired**: J `textures` → `texture_packs` Intent, P/R `Config.texture_packs`, imported title-specific directory. Optional import and malformed-pack handling: **Host passed**. |
| Game `language` | **Wired**: `language` 0–11 → R `Config.language`; language availability remains title dependent. |
| Game `region` | **Wired**: `region=-1` automatic or 0–6 override applied after core title-region selection. |
| Game `options` | **Partial**: `enhancements` selects core title/build-matched built-in cheats, with availability metadata exported for J. PN profile options such as draw-drop rules and bottom-full-rate rules are not imported. |
| Game `advanced` | **Partial**: Android typed `advanced` Intent JSON through J/P/S; not PN's optional profile override object or its complete Advanced schema. |

## Every Pocket Native Advanced key

PN [Advanced::export](../../pocket-native-src/frontend/src/settings.rs) establishes
the environment contract below; PN `lib::apply_options` and frame loop handle
the live options. **Unsupported** means no Android preference-to-mechanism route,
even if the upstream engine contains a related internal optimization.

| PN key | PN mechanism | Android CodeSupport |
|---|---|---|
| `skip_bottom` | `System::set_skip_bottom` | **Unsupported**; no key/live command. |
| `skip_right_eye` | `System::set_skip_right_eye` | **Unsupported** as a performance switch; Android `stereo=off` only selects presentation. |
| `frame_skip` | `most_skipped` in PN loop | **Unsupported**; Android presentation cap is not adaptive emulation frame skipping. |
| `shown_only` | PN `unshown` draw decision | **Unsupported**; limiting presentation does not establish skipped GPU drawing. |
| `fast` | `ZAKURO_FAST` | **Unsupported**. |
| `quick_checks` | `ZAKURO_FAST_OFF` token `spots` | **Unsupported**. |
| `half_precision` | `ZAKURO_FAST_OFF` token `half` | **Unsupported**. |
| `plain_math` | `ZAKURO_FAST_OFF` token `plain-math` | **Unsupported**. |
| `tile_copies` | `ZAKURO_FAST_OFF` token `tiles` | **Unsupported**. |
| `gpu_clears` | `ZAKURO_FAST_OFF` token `clears` | **Unsupported**. |
| `gpu_arrays` | `ZAKURO_FAST_OFF` token `arrays` | **Unsupported**. |
| `trust_meshes` | `ZAKURO_FAST_OFF` token `meshes` | **Unsupported**. |
| `trust_textures` | `ZAKURO_FAST_OFF` token `textures` | **Unsupported**. |
| `small_cache` | `ZAKURO_FAST_OFF` token `small-cache` | **Unsupported**. |
| `cull` | inverse `ZAKURO_NO_CULL` | **Unsupported**. |
| `gpu_drawing` | `Config.software_draws` | **Wired equivalent** `hardware_renderer`: false runs upstream software 3D, true hardware 3D. Vulkan still presents either result; software mode does not promise HD/scaling/interpolation. |
| `gpu_vertices` | inverse `ZAKURO_CPU_SHADERS` | **Wired equivalent** `shader_mode=auto/gpu/cpu` selects upstream vertex processing; identical PN implementation/performance is not claimed. |
| `gpu_transfers` | inverse `ZAKURO_CPU_TRANSFERS` | **Unsupported**. |
| `gpu_texture_copies` | inverse `ZAKURO_NO_GPU_TEXTURES` | **Unsupported**. |
| `in_place` | inverse `ZAKURO_NO_IN_PLACE` | **Unsupported**. |
| `past_drawing` | inverse `ZAKURO_NO_PAST_DRAWING` | **Unsupported**. |
| `etc1` | inverse `ZAKURO_NO_ETC1` | **Unsupported** as a user setting. |
| `generic_shaders` | `ZAKURO_GENERIC_SHADERS` | **Wired equivalent** same JSON key selects upstream generic GPU pipelines, separate from shader execution and CPU shader JIT. |
| `generic_lighting` | `ZAKURO_GENERIC_LIGHTING` | **Unsupported**. |
| `draw_ranges` | inverse `ZAKURO_NO_RANGE` | **Unsupported**. |
| `gpu_thread` | inverse `ZAKURO_SYNC_GPU` | **Unsupported** as an Android setting. |
| `realtime_gpu` | `ZAKURO_GPU_REALTIME` | **Unsupported**. |
| `recompiled` | selects `Config.recompiled` | **Wired** as Android `aot` (`recompiled` JSON alias); R also sets `require_recompiled` so failed AOT loading is not silently accepted. ABI gate: **Host passed**. |
| `exact_events` | `ZAKURO_EXACT_EVENTS` | **Unsupported**; `skip_idle_loops` is a different optimization. |
| `prewarm` | removes `ZAKURO_SHADER_MANIFEST` when off | **Unsupported** as an Android preference/imported PN shader-manifest workflow. |
| `read_ahead` | PN `ReadAhead::start/save` | **Unsupported**; no PN learned ROM-access replay. |
| `background_shaders` | inverse `ZAKURO_SYNC_SHADERS` | **Unsupported** as an Android preference. |

PN `Advanced::compatible()` is a preset that changes ten fields; it is
**unsupported** as an equivalent Android preset. Restoring Android defaults is
not that compatibility preset. `Advanced::of` prioritizes game override, then
profile override, then defaults; Android has per-title settings but no PN
`profile.toml` inheritance. PN `Profile.bottom_relearn` and `GameOption`
`drops_when_off`/`bottom_full_rate_when_on` are also unsupported.

## Every Zakuro desktop settings key

The 31 fields below refer to [desktop Settings](../crates/zakuro/src/settings.rs),
not to a claim that the desktop settings file is read on Android.

| Desktop key | Android key / method and CodeSupport |
|---|---|
| `games` | **Partial**: Launcher SAF game-folder import and private library; no watched desktop games directory. |
| `language` | **Wired**: `language`, J/R. |
| `renderer` | **Fixed** Vulkan; desktop Vulkan/OpenGL selection absent. |
| `hardware_rasterizer` | **Wired** `hardware_renderer`, R Config; real software 3D fallback selectable while Vulkan remains the presenter. |
| `resolution` | **Wired** 1–3 integer J/P/R resolution. |
| `texture_packs` | **Wired** J textures toggle, P/R and ImportPlan; optional original-texture game works. **Host passed** planner checks. |
| `frame_interpolation` | **Wired** `interpolate`, R; shared GPU prerequisite, not universal title compatibility. |
| `recompiled` | **Wired** `aot`, R strict required AOT; interpreter troubleshooting when false. |
| `scale` | **Unsupported** desktop initial window pixel scale; Android uses its window size. |
| `layout` | **Wired** `layout`, all five desktop layouts in G. |
| `filter` | **Wired** same key, G smooth/sharp/pixels. |
| `integer_scale` | **Wired** same key, G/presenter. |
| `top_shape` | **Wired equivalent** `top_width` numeric 320–800; picture stretching alone is not a widescreen game patch. |
| `stereo` | **Wired** same key, G/R right-eye presentation: off, anaglyph, side-by-side, half-side-by-side, interlaced. |
| `depth_3d` | **Wired equivalent** `slider_3d` 0–1 → guest `Config.slider_3d`. |
| `swap_eyes` | **Wired** `stereo_swap`, G. |
| `volume` | **Wired** same key, R → `Audio::push`; separate from Android system volume. |
| `mute` | **Wired** same key, R uses gain zero. |
| `show_fps` | **Wired** same key, native presentation count to Java HUD. |
| `background` | **Unsupported** library background image picker. |
| `background_opacity` | **Unsupported** image opacity control. |
| `keys` | **Partial**: `controller_bindings` uses Android keycodes and guest targets, not winit keycodes. Expanded 22 keys below. |
| `pad` | **Wired equivalent** `controller_bindings` explicit overrides plus Nintendo/Xbox preset; Android keycodes instead of gilrs buttons. Expanded 14 keys below. |
| `enhancements` | **Wired** per-title `enhancements`, R loader-matched built-ins and metadata export; unsupported names produce a message. No arbitrary "60 FPS" success flag. |
| `camera` | **Unsupported** webcam/picture/nothing selector and Android camera capture bridge. |
| `camera_picture` | **Unsupported** image import into guest cameras. |
| `nickname` | **Unsupported** Android editor/launch override. |
| `console_id` | **Unsupported** Android identity editor/launch override. |
| `room_address` | **Unsupported** Android network room client UI/integration. |
| `room_name` | **Unsupported** Android room-host configuration. |
| `room_port` | **Unsupported** Android room-host configuration. |

All binding field names are accounted for:

| Desktop binding fields | Android mapping / CodeSupport |
|---|---|
| Keys `a`, `b`, `x`, `y`, `l`, `r`, `start`, `select`, `up`, `down`, `left`, `right`, `zl`, `zr` (14) | **Wired equivalent**: matching lowercase targets in `controller_bindings`, S `binding`, I guest mask. |
| Keys `circle_up`, `circle_down`, `circle_left`, `circle_right` (4) | **Partial**: left analog stick works; these four keyboard-to-Circle-Pad binding targets are absent. |
| Keys `c_up`, `c_down`, `c_left`, `c_right` (4) | **Partial**: right analog C-stick works; these four keyboard-to-C-stick binding targets are absent. |
| PadButtons `a`, `b`, `x`, `y`, `l`, `r`, `start`, `select`, `up`, `down`, `left`, `right`, `zl`, `zr` (14) | **Wired equivalent**: same guest targets with Android keycodes; axis trigger/hat input handled by R. |

## Android-only additions and full JSON accounting

The native 50-field schema consists of these groups; every member has a consumer
in the referenced code rather than only a stored value:

| Android fields | Consumer / scope |
|---|---|
| `layout`, `filter`, `integer_scale`, `top_width`, `stretch`, `swap_screens`, `stereo`, `stereo_swap` (8) | G and R Surface/presenter geometry. |
| `dual_display`, `external_stretch` (2) | Game Presentation/JNI bridge, G `display`, R external presenter/routing/touch/fallback. |
| `custom_driver`, `hardware_renderer`, `gpu_readback` (3) | DriverSpec/Driver, Vulkan entry selection, R renderer configuration and forced image-readback diagnostic. |
| `shader_mode`, `shader_execution`, `cpu_shader_jit`, `shader_compilation`, `generic_shaders`, `depth_blits`, `float_depth`, `static_blend` (8) | J controls → S `renderer_options` → R `set_renderer_options` → upstream GPU backend options. Source inspected; no Android GPU execution or performance measurement claimed. |
| `motion_enabled`, `gyro_sensitivity`, `gyro_invert_x`, `gyro_invert_y`, `gyro_invert_z`, `stick_motion`, `stick_motion_gate`, `stick_motion_sensitivity` (8) | M/I/S; physical sensors and synthetic stick motion are separate paths. |
| `tilt_button`, `reverse_tilt_x`, `reverse_tilt_y` (3) | S typed gate, I independent synthetic tilt sign/gate handling. |
| `button_mapping`, `controller_bindings`, `left_stick`, `right_stick`, `cursor_speed`, `deadzone` (6) | S/I; typed guest bindings, deadzone and cursor click/release. |
| `volume`, `mute` (2) | R/Audio gain. |
| `show_fps`, `frame_cap` (2) | R statistics and S presentation interval. |
| `region`, `language`, `new_3ds`, `skip_idle_loops`, `aot`, `slider_3d`, `interpolate`, `enhancements` (8) | R core configuration, post-loader region override and matched enhancements. |

`gpu_readback` is a real upload/readback diagnostic, not a substitute name for
PN's individual GPU-copy/transfer hacks. `Advanced::interpolate_on_main` permits
interpolation only with hardware rendering, no forced readback and the top
screen on the shared main presenter. Routing the top screen externally disables
effective interpolation; reconnect/swap updates the policy. External presentation
always uses its separate Vulkan device/readback route.

Additional Java-owned keys `show_clock`, `touch_controls`, `orientation`,
`show_battery`, `touch_auto_hide` are consumed by Game.
J sends them in the JSON too; serde's ignored unknown fields are not the
mechanism for those features. `resolution` and `texture_packs` are separate
Intent extras decoded by P. New3DS details are in
[loader](../crates/zakuro-core/src/loader.rs),
[PhysicalMemory::new](../crates/zakuro-core/src/memory/physical.rs),
[fcram_size](../crates/zakuro-common/src/memory_map.rs), and
[ExHeader::application_memory](../crates/zakuro-fs/src/ncch.rs).

## Menus, device controls, storage and non-setting features

| Feature / source action | Android mechanism and CodeSupport | Verification |
|---|---|---|
| HD pack attach, detach by toggle, later replacement | **Wired** J textures, `ImportPlan.inspect(..., includeTextures)`, `texturePack`, `GameImport.replaceTextures`. Exclusion skips texture subtree metadata and payload. Empty/manifest-only optional initial packs ignored; explicit pack-only import requires PNG. | **Host passed**, **Android SAF passed** including valid replacement and invalid/empty pack preservation. |
| Native code replacement without recopying ROM | **Wired** `ImportPlan.nativeCode`, strict `NativeLibrary.inspect().requireCompatible()`, synced backup copy of old title.so and one atomic new-code rename in `GameImport.replaceCode`. ROM is not recopied. | **Host passed**, **Android SAF passed** valid replacement, exact backup, ABI5 rejection and backup-creation-failure preservation. No native execution. |
| Physical motion and recalibration | **Wired** M stationary gyro bias calibration, orientation remap, enable only while active; Game Recalibrate → bridge → M. | Transform tests present; real sensor calibration not run here. |
| Two emulated screens / bottom-screen touch | **Wired** G viewport-derived touch mapping, letterbox rejection, screen swap and five layouts. | Geometry tests present; no physical device run here. |
| Two physical displays / PN Screens setting and `SecondScreen.show` | **Wired code** Android Game `updateSecondDisplay`/Presentation/SurfaceView → `nativeSecondSurface/nativeSecondTouch` → bridge/R. Main top and external bottom, swap and disconnect fallback. External presentation uses readback. | Native host geometry/bridge tests reported passed; physical dual-display device not tested here. |
| PN `orientation` preference, panel rotation override | **Partial** Java auto/landscape/portrait selector now calls `setRequestedOrientation`; config changes and sensor vectors handled. Direct PN panel override absent. | Source only here. |
| PN driver ZIP installation / graphics-driver choice | **Wired code** Launcher bounded private ZIP stage/import, GameSettings metadata/selection/Intent paths, native DriverSpec/Driver/adrenotools path; errors are visible. | Native validation tests/cross-check reported passed; parent verifies packaged hooks, compatible GPU not exercised here. |
| PN EditControls / `Action::EditControls` | **Unsupported** move/resize editor; Android fixed touch buttons and sticks exist. | Source only. |
| PN `Resume`, `Reset`, `Quit`; desktop `Resume`, `Reset`, `Library`, `Quit` | **Wired equivalents** Game pause dialog → bridge `Resume/Restart/Exit`. Restart is a fresh core load; saved slot/in-game saves are separate. | Source only. |
| PN `Advanced`, `Toggle`, `FrameSkip`, `Language`, `Region`, `Restart`, `Compatible`, `Defaults`, `Back` | **Partial** Android category settings, language/region and restart. Unsupported PN toggles/preset remain listed above; Back/cancel discards draft. | UI settings tests owned by UI agent; see verification. |
| PN `Action::None/Redraw/Changed` and generic `Field::Option` | **Partial equivalents** Android UI redraw/draft/apply path; no PN action protocol or profile option engine. | Source only. |
| Desktop `SaveState`, `LoadState` | **Partial** Game menu → bridge `Save/Load` → `System::save_state/load_state` → `storage::save/load`, `data/states/<titleId>/0.state`. One slot, no desktop slots 1–9 selector. Input/audio reset after command. | Storage/core tests present; Android save/load round trip not run here. |
| In-game Settings and confirmation | **Wired** Game `openSettings`, SettingsDialog draft/save/cancel; changed launch settings require game restart. | Source only here. |
| Desktop `Play`, `ChooseFolder`, `Rescan` | **Partial equivalents** Launcher private imported library, SAF chooser, library refresh. No live desktop filesystem scan. | Import metadata/boundary **Host passed**. |
| Desktop `Recompile`, `DownloadCompiler`, `CancelRecompile` | **Unsupported on device**: compatible title.so is built separately and imported/replaced. | ABI metadata inspection **Host passed**, not compiler/gameplay validation. |
| Desktop `Mods`, `CheatsFolder` | **Partial/core only** core mods/cheats support exists, but Android importer selects ROM, native code, textures and known sysdata, not arbitrary mod/cheat folders. No Android folder-manager action. | Source only. |
| Desktop `Cheat`, `AddCheat`, `ReloadCheats` | **Partial** matched built-in enhancements selectable; no arbitrary cheat text editor/import/reload menu. Core loading cheat files is not an Android management UI. | Source only. |
| Desktop `ChooseBackground` | **Unsupported**. | No implementation/test claim. |
| Desktop `ChooseCameraPicture` | **Unsupported**. | No implementation/test claim. |
| Desktop `HostRoom`, `JoinRoom`, `LeaveRoom`, `DlpJoin`, `DlpLeave` | **Unsupported** Android room and Download Play orchestration, even though upstream services exist. | No implementation/test claim. |
| Desktop `Keyboard(String, usize)` | **Unsupported** guest software-keyboard dialog bridge. | No implementation/test claim. |
| Desktop `Fullscreen` | **Fixed equivalent** Game immersive fullscreen; no desktop window/fullscreen toggle. | Source only. |
| Desktop `CopyReport` | **Partial** Game runtime error/details/copy UI, logcat and benchmark logging; no equivalent complete desktop game-report action. | Source only. |
| Desktop `Settings` | **Wired equivalent** J per-title typed editor, persisted before handing off to separate `:game` process. | Tests present; UI owner verifies. |
| Updates and DLC discovery | **Unsupported Android management path**: desktop discovers siblings; Android R does not populate `Config.update/dlc` from imports. | Source only; no successful update/DLC claim. |
| Audio output | **Wired** AAudio negotiated stereo float, bounded queue, sample-rate conversion, gain/mute, disconnect retry. PN's WSOLA time-stretch pacer is not ported. | Source only; no audio quality/latency measurement here. |
| Lifecycle, pause, focus loss, errors | **Wired** R keeps event loop active, drops audio/clears input and gates sensors; Java menu/error UI and separate game process. | Source only; device recovery needs runtime testing. |
| PN launcher `pin/shortcut/playShortcut` | **Unsupported** home-screen game shortcut integration; Android library launch remains available. | Source only. |
| PN launcher `play/start/playAgain` held-game task orchestration | **Partial** Android separate `:game` activity/process and pause/resume exist; no equivalent PN running-title discovery and cross-game task selection protocol. | Source only. |
| PN launcher `update/download` | **Unsupported** in-app release lookup/APK updater. Android `Launcher.update()` updates import-task UI, not installed APKs. | Source only. |

PN Field variants not repeated literally in this last table map to the global
key rows: `Layout`, `Aspect`, `Resolution`, `Vulkan`, `Smooth`, `Stretch`,
`Interpolate`, `ShowFps`, `ShowClock`, `Rotation`, `FrameCap`, `Power`,
`TouchSpeed`, `Buttons`, `LeftStick`, `Dpad`, `RightStick`, `DeviceMotion`,
`GyroSensitivity`, `ReverseGyroX/Y/Z`, `StickMotion`, `TiltButton`,
`TiltSensitivity`, `ReverseTiltX/Y`, `TouchControls`. Thus menu visibility and
settings persistence are audited separately from engine support.

## Actual verification and limits

The canonical [verification record](testing.md) identifies the final APK,
checksums, source-freeze records and evidence. Current results:

- 145 core and 24 filesystem tests passed, including executable-bounds errors.
- 23 native frontend helper tests passed; GPU/Vulkan suite: 174 passed, 2 ignored.
- ARM64 NDK check/release link, all six Java host suites, and scoped Android UI
  checks passed. The eight renderer controls have real native consumers.
- Real API35 x86_64 SAF checks: **27/27 passed**, including header-only ROM,
  IHDR-only PNG and hidden-export repros. The report and source hashes are in
  `build/import-check/REPORT.md`; no native fixture code was executed.
- The production APK is `build/releases/0.1.3/Zakuro-Android-0.1.3.apk`, SHA256
  `40ec9de249f9e1a1095a445c1c6706096b9f62be6463fecb3a6ab49317f4a25a`.
  Its six ARM64 libraries have 16 KB LOAD alignment; ZIP alignment/signature
  passed. The only assets are three license notices.
- The rebuilt Ultra Moon library is ABI4 without fastmem; the real original ROM,
  rebuilt code and complete extracted HD PNG pack pass current read-only
  preflight. No original ROM or older build was replaced.

These checks do not establish ARM64 gameplay, physical gyro axes/calibration,
Thor dual-panel routing, custom-driver execution, sustained performance or a
win over Azahar. The secondary presenter uses readback. Surface recreation also
uses readback until the renderer can rebind a shared Vulkan presentation device;
interpolation requires the shared path. Device-specific capabilities require
hardware verification even when their Java/native contracts are wired.
