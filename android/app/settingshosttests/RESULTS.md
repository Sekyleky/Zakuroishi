# Java frontend freeze — 2026-10-31

Owned production source: Launcher.java, Game.java, GameSettings.java, SettingsDialog.java. Parent-owned GameImport, ImportPlan, NativeLibrary and documentation were not edited.

Final compilation: Android SDK 37.0 android.jar, Android Studio JBR 25.0.3, --release 11. Disposable frontend APK build/frontend-preview/apk-20261031-005527/frontend-only.apk uses version 0.1.3/code 4 and has no native libraries. Its source-hashes.json was rechecked against all four final Java files; settingshosttests/source-freeze.json preserves their SHA-256 hashes outside the ignored build directory. This APK is only a frontend test fixture and must not ship. The previous 005117 freeze predates the shader controls and is superseded.

Passed host suites (run.ps1):
- GameSettingsCheck: legacy values, isolated drafts/cancel, synchronous save and failed commit, typed normalization, escaped JSON, bindings round-trip and optional pack detection. All eight shader controls retain native defaults, round-trip through saved preferences and JSON, reject invalid types/choices, and remain independent of native game AOT.
- SettingsBridgeCheck: actual eight Java/Rust command IDs, six JNI exports, five platform callbacks, all native-facing advanced keys. Also verifies runtime installation of renderer_options and each shader key's mapping to an actual GPU hardware or CPU shader JIT consumer. Java HUD, orientation and touch-overlay keys intentionally remain UI-only.
- DriverPackageCheck: archive traversal/entry count, safe extraction through read-only opened files and fsync, ARM64 ELF shared-library header, basename parity, minApi bounds and supported schemaVersion.

Passed emulator checks on workspace-local AOSP API35 x86_64, serial emulator-5580/private adb5038:
- Six advanced groups reachable.
- Group Cancel preserves resolution; root Cancel discards accepted group drafts; root Save commits resolution and dual-display setting.
- Portrait light and landscape dark settings captures.
- Font scale 2.0 Audio dialog is readable and its buttons remain reachable.
- Non-ARM64 Add game is disabled as intended. Optional pack checkbox interaction was SKIPPED on this emulator; it cannot enter the ARM64 import flow. Host optional-pack checks passed. Parent/coordinating task owns separate real SAF results.
- Final rebuilt APK driver trust confirmation opens, and Cancel preserves the settings dialog.

Continuation verification reran run.ps1, emulatorcheck.ps1 and shader-emulatorcheck.ps1 against the 005527 source freeze. Shader UI saves CPU vertex processing, interpreted GPU execution, disabled CPU shader JIT, wait-for-compilation, generic shaders enabled, depth blits disabled, floating-point depth enabled and static blend enabled. Group Cancel preserves the saved CPU processing mode. The general suite's six groups, nested cancel/save, dual-display persistence, portrait/light, landscape/dark and 2.0 font-scale checks were repeated. New shader selector/flag captures are in ignored build/frontend-preview/captures; they are test evidence, not source.

Shader native contract: shader_mode auto/gpu/cpu maps to gpu_shaders None/Some(true)/Some(false); shader_execution compiled/interpreted maps to translate_shaders; cpu_shader_jit selects the CPU shader compiler independently; shader_compilation async/wait maps to wait_for_shaders. generic_shaders, depth_blits, float_depth and static_blend map directly to hardware renderer options. runtime.rs installs these options before renderer creation. hardware.rs consumes all GPU options and shader/jit.rs consumes CPU JIT. Defaults are auto, compiled, true, async, false, true, false, false respectively. No new native functionality or GPU performance result is inferred from these frontend checks.

Driver contract: private files/drivers/UUID directory, root meta.json libraryName restricted to [A-Za-z0-9_.-]+\.so, minApi or fallback minApiVersion checked against SDK_INT, schemaVersion 1 if declared. All extracted files are read-only and synced before staging rename. Explicit trust prompt precedes ZIP selection. Intent driver_dir/driver_name supplied only when custom_driver is enabled.

Second-screen contract: nativeSecondSurface(Surface|null), nativeSecondTouch(boolean,float,float,int,int), closeSecondDisplay(). Presentation is nonfocusable, prefers presentation-category displays then another valid display excluding its own, detaches on destruction/disconnect and suppresses failed rendering until a topology event. Driver loading, actual dual-screen Vulkan output, native gameplay, live battery/gamepad behavior and physical rotation were NOT hardware-tested here.

Other implemented frontend behavior: sticky battery broadcast HUD, sensor orientation preference auto/landscape/portrait, InputManager-driven touch hiding with gamepad, reset touch settings to defaults, R3/L3/ZL/ZR virtual input. Native-facing controls include only fields implemented in current settings/runtime; inherited PN GPU heuristics are not claimed complete.

Final git diff --check for owned tracked Java files passed. Production compilation reports deprecated Android API usage; host fixtures additionally report test-only sun.misc.Unsafe warnings. No frameworks or dependencies were added.
