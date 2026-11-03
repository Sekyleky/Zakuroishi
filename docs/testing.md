# Verification record

## Audio, input and display latency (unreleased, 2026-11-03)

AYN Thor, Turnip (Balemuni Apex Ultimate), Ultra Moon 3× + HD, standing on the
field after loading the save, vsync pacing on (speed 100.3%). Each switch A/B in
the same build; numbers from the benchmark line every 300 frames.

| Switch | Off | On | Notes |
|---|---|---|---|
| `audio_queue_managed` | queue 72 → 225 ms after 40 s, then 214–237 ms at the cap | 165 → 100–125 ms from 30 s on | 0 underruns after the save load either way; load: legacy 230 short callbacks, managed 18 dry spells; emulation 5.9 vs 5.8 ms/frame |

Host: the queue's tests run in both crates; `a_game_running_a_little_fast_stays_near_the_target`
fails without the trim (193 ms after 3 min) and holds 104–116 ms with it.
Not verified: how the sound sounds (stretch splices, fades, 0.5% drift pitch).

## 0.2.0 release check (2026-11-03)

AYN Thor, Turnip, ABI-5 `title.so` (3dsrecomp `73b8f9c`) for every game.

- Ultra Moon, 3× + HD: the save loads, the field and a map change hold 60 fps
  (emulation 4–6 ms), a wild battle starts and runs at 60 fps. Worst battle-start
  frame 44.3 ms (0.1.9: ~41 ms), mean 5.6 ms; no crash.
- Boot check, 40 s each, no crash, ~60 fps: Ultra Sun, Alpha Sapphire, X, Y,
  Rutile Ruby, Ocarina of Time 3D, Majora's Mask 3D, Super Mario 3D Land, New
  Super Mario Bros. 2, Super Monkey Ball 3D. Only boot was tested (title, menus
  or attract demo). Omega Ruby shares Rutile Ruby's title id and was not run.
- Host: zakuro-gpu 142, zakuro-core 147, settings 31, Java settings/contract checks.

## Shader compiling and battle hitches (0.1.9, 2026-11-01)

AYN Thor, Ultra Moon, 3× + HD, Balemuni Turnip; fresh launch from the title,
walk in tall grass (injected stick input) into a wild battle, `frame hitch` lines.

| Run | Worst battle-start frames (emulation) | Present spikes |
|---|---|---|
| Before: no disk cache, a JIT thread per program | 111, 85 ms | 65, 26 ms |
| Cache persisted, JIT per program | 113, 88 ms | 49 ms |
| Cache persisted, 2 JIT workers | **71, 46 ms** | none over 25 ms |
| Vertex shader processing = GPU, cold cache | 140, 68 ms | 28 over 25 ms, worst 360 ms |
| Vertex shader processing = GPU, second session | 146, 71 ms | 28 over 25 ms, worst 267 ms |

- Module code checks are not the cause: `Battle` 1.6 ms, others ≤1.5 ms.
- 69–73 CPU vertex programs compile per session (7–70 ms each, ~18 ms average with
  2 workers); the interpreter shades until each finishes.
- The pipeline cache file existed (2,174 KB) and **Clear shader cache** removed it.
- Open areas: frames of 24–38 ms recur while walking in grass with no compiles.
  Profile (15 s, grass + battle, cache just cleared): GPU command emulation 27% of
  the emulation thread (CPU vertex path), bottom-screen CPU copy 11%, game code
  27%; three pipeline compiler threads used 31% of all samples on a cold cache.
- GPU vertex shaders do not help here: the second session stalls as badly as the
  first, and present frames of 33 ms recur with no compiles (the GPU becomes the
  limit at 3×). Steady walking was 60 fps either way. Keep Auto on the Thor.
- Remaining: JIT programs are not persisted (machine code), so the first battle
  of a session still interprets new programs; heavy render frames in grass exceed
  16.7 ms on the CPU vertex path.

## HD upload fixes and what remains at battle start (0.1.9, 2026-11-01)

AYN Thor, Ultra Moon, 3× + HD, Balemuni Turnip, `android/scripts/device-test.sh`.
Two separate hitches show in every run: a 52–66 ms **field-entry** frame (first field
frame after the title screen, also with no battle) and the **battle-start** frames.
Earlier single-run numbers mixed them; quote them separately and repeat runs.

- With the four HD-upload switches on, `upload_picture` fell from 47 ms to ~1 ms per
  7 s profile and frames over 28 ms after the battle loads from 209 to 138–152 ms total.
- One battle per setting cannot resolve 10–15 ms: the same settings gave a worst
  battle frame of 28–71 ms. Use `RUNS=3 device-test.sh ab ...` (alternating runs, mean/median).
- Pooling ordinary 3DS textures too (tried, reverted): worst battle frame mean 44.2 ms
  pooled vs 43.4 ms not pooled, 3 runs each — no effect.
- `frame hitch parts` (per-frame timers) replaced the profile guesswork: allocations
  (~1 ms in the hitch frame) and pipeline creation (30 in 3 s, 1.5 ms total) are small.
  Battle-start frames were texture decode 18–20 ms and vertex processing 11–23 ms.
- Fast decode for all formats, 3 alternating runs each: worst battle frame 37.1/38.3/37.5 ms
  on vs 64.2/51.6/45.7 ms off; battle decode 18–20 → 11–12 ms (pack lookup ~3 ms of it).
  The field-entry frame did not improve (decode 20–31 ms, pack 7 ms, vertices 9–14 ms).
- Field entry (first field frame after the title): ~28 new textures, mostly ETC1
  128–512 px at ~0.25 ms each, no outlier; the pack lookup took 6–7 ms of the decode.
  Background pack lookup, 3 alternating runs each: field entry 45.8/45.0/49.5 ms on vs
  60.7/59.3/56.6 ms off. Battle worst 35.4/32.9/69.5 vs 49.6/53.4/32.0 ms (no change in
  mean); with it on, battle decode is ~2 ms but HD upload 12–21 ms in that frame.
- Gameplay monitoring (5 min free play): worst hitches 67/44/38 ms were vertex
  processing 50/34/26 ms while both `shader jit` threads compiled; no throttling, 60 fps
  held. Shader warm-up at launch (76–77 programs, 63 pipelines queued), 3 alternating
  runs each: battle worst 44.1/31.7/48.1 ms on vs 50.3/74.8/75.1 ms off; field entry
  unchanged (~42 ms on vs 31–48 ms off; it comes seconds after launch, before the
  warm-up finishes). Pipelines warm in ~0 s as the pipeline cache already holds them.
- Remaining in battle-start frames (profile, before the per-frame timers): Turnip/kgsl driver time (134 memory
  allocations in the first 2 s, source not identified — not ordinary textures),
  `process_vertices` waiting on the rayon pool while the JIT and pack threads compete
  for the CPU, and per-texel decode of non-ETC1 textures in `TextureCache::decoded`.

## Testing toggles (0.1.9, 2026-11-01)

AYN Thor, Ultra Moon, 3× + HD, Balemuni Turnip unless stated, same save; three
3-second SurfaceFlinger present-interval samples per run (16.7 ms is on time).

| Run | On time | 33 ms | 50 ms |
|---|---|---|---|
| Vsync pacing on (default) | 374 | 2 | 0 |
| Vsync pacing off (0.1.8 mailbox) | 365 | 9 | 1 |
| Vsync on + Turnip sysmem | 374 | 1 | 0 |

- Turnip sysmem applies (`custom driver: TU_DEBUG=sysmem`); the corner block did not
  appear at this save location in any run, so its effect on the artifact is untested.
- Guard: system driver + GPU vertex shaders now starts on Auto and logs
  `using Auto (safe_gpu_shaders)`; no native crash. That run was slower (82–87%
  speed) after many consecutive runs; not compared under controlled temperature.
- Host: zakuro-gpu 129, zakuro-android settings 8 (new defaults/parse test);
  ARM64 check; GameSettings, Java/native contract (new keys), driver and frontend
  checks pass.

## Zakuroishi branding (0.1.9, 2026-11-01)

- Built and signed the Windows APK with application label **Zakuroishi**,
  versionName **0.1.9**, versionCode **10**, and the existing package/signing key.
- Replaced the initial artwork with the corrected user-supplied SVG. The full
  Android vector retains its 22 colored paths, black canvas, CSS fill rules and
  30480×30480 view box. Adaptive launcher artwork uses the same colored paths
  over a matching black background. The original SVG is in `branding/`.
- Installed over the existing Thor app without uninstalling. Visually checked
  the corrected library logo and new name; both imported games and their
  resolution/texture preferences remained listed. Java settings, native-contract,
  driver-package and frontend-reference host checks passed during the branding
  change. APK signing/alignment and resource linking passed for the corrected art.
- Container resource compilation was updated to match the Windows build, but
  a container APK build and a fresh gameplay/save-load test were not repeated
  for this branding-only change.

## Controller hotkeys and frontend library (2026-11-01)

Checks below used the pre-release build, retaining the current package and
signing certificate. These changes ship in Android 0.1.8; the release build changes
the version metadata to 0.1.8/versionCode 9.

- Native Android host suite: **30 passed**. New coverage checks physical,
  virtual and axis-key sources for held fast-forward, release, and isolation
  from guest buttons and the motion gate. Default/clamp coverage includes the
  separate fast-forward speed.
- Java settings, native-contract, driver-package and frontend-reference host
  checks passed. Reference tests cover NCCH/NCSD identification, exact-length
  launch references, malformed IDs, trailing/truncated bytes and filename safety.
- `settingshosttests/frontend-devicecheck.ps1 -Serial b225f4aa -Port 5039`:
  reference and iiSU/Cocoon checks passed on the Thor's Android runtime. Catalog
  checks retain other consoles/players, add the extension once, replace the owned
  player without duplication, reject malformed/oversized/ambiguous input, and
  distinguish the two frontends' launch templates. An initial standalone test
  assumed `/tmp`; the harness now explicitly uses `/data/local/tmp` on Android.
- Thor: captured **BUTTON_THUMBR (R3)** for Ultra Moon, saved the mapping and
  observed native log transitions **150% → 100%** on press/release. Normal game
  speed remains 100%, 3x resolution and HD textures remain selected. No default
  hotkey is imposed on other titles.
- Thor: exported both installed titles to **Documents/Zakuro frontend/Games**;
  each reference is 33 bytes. A repeated export updated setup files and retained
  exactly two references. Opening Ultra Moon's reference in Android Files with
  **Open with Zakuro → Just once** launched the installed game through a real
  content-URI grant. A direct `zakuro://game/…` link also launched it. Repeating
  that link retained a single Game activity.
- Thor: the iiSU assistant read the installed catalog through SAF and wrote
  **Documents/emuladores-zakuro.json**. The copy contains the existing 3DS players
  plus `ZAKURO_ANDROID` and `.zkr`; SHA-256 comparison confirmed the original
  catalog remained byte-for-byte unchanged.

Remaining checks: Cocoon is not installed on this Thor, and the generated iiSU
catalog has not been imported into the user's live launcher. End-to-end scanning
and launching inside those frontends, switching between two live titles, and a
long held-speed gameplay session remain unverified. The content-URI launch and
generated catalog were tested independently; they are not a claim that every
frontend version supports the exported platform schema. Setup is documented in
[Frontend integration](frontends.md).

## Driver comparison and final normal-speed candidate (2026-11-01)

Hardware follow-up on November 1: the final APK was installed on the Thor and
tested at Mount Hokulani with Turnip, 3x resolution, HD textures and Normal
(100%) speed. The user's saved speed was 150%; only that preference was changed.
A 30-second warmup and 60-second capture retained 11 complete benchmark windows:
60.01 emulation FPS, 100.29% guest-clock speed, 59.97 submitted FPS and 5.23 ms
mean core work. Three non-overlapping SurfaceFlinger samples contained 5 intervals
over 25 ms out of 376, with a 33.21 ms maximum. Isolated hitches remain; this is
not evidence of a stutter-free 60 unique game frames/s.

Short walking input and Home/Recents/resume also ran without a process restart
(PID 10148). Rendering stayed at 3x with shared GPU images and `cpu_top=0`, the
second display continued updating, and mailbox mode remained active. The first
post-resume window contained a 416 ms transition stall; later windows returned
to roughly 60 emulation FPS / normal speed. Battery temperature read 26 C and
thermal status 0 at the sampled endpoint. No new combat or long-duration test
was performed for this last pacing change. Logs, screenshots and summaries are
under `build/final-pacing/device-check/`; this is a different scene from the
October 31 captures, not a matched before/after smoothness comparison.

The controlled driver captures below were collected on October 31. The final
pacing change was prepared November 1 after feedback that 150% speed felt
smoother than 100% at 3x resolution.

- Final test artifact: `build/final-pacing/Zakuro-Android-Pacing-Test.apk`
  (9,270,358 bytes), SHA256
  `118dc30a76e153c8d5fb3cce5e2e7f7856fa8e069c503dcfa70d07f039afb95b`.
  ARM64 release build, existing signing certificate and alignment checks passed;
  29 Android host tests and Java settings/native-contract checks passed.
  It remains an unreleased test using manifest version 0.1.7.
- Same APK (`d2362a2f...11f5db42`), in-game save, Route 2 grass location, 3x
  resolution, HD pack, Auto/CPU vertices, two screens, unlimited emulation and
  60 presentations/s cap. Each overworld capture used 30 seconds warmup plus
  60 seconds measurement; screenshots verify the same player location/camera.

| Driver | Overworld emulation FPS | Normal-speed multiplier |
|---|---:|---:|
| Imported Turnip | 170.9 | 2.86x |
| System Qualcomm | 111.4 | 1.86x |

- Stock showed more GPU-queue waits (`wb[room]`), around 1 ms per emulated
  refresh in sampled windows. A 15-second stock CPU profile retained 79,212
  samples without loss; it does not measure GPU execution time. Turnip's roughly
  53% headroom advantage here supersedes a blanket stock-driver performance
  recommendation. The previously observed Turnip corner artifact is a separate
  compatibility tradeoff.
- Stock combat (Salamence versus Alolan Rattata, waiting for input) measured
  156.6 emulation FPS / 2.62x. It is not paired with the earlier Cutiefly battle:
  different models/cameras prevent a matched combat speedup claim.
- Sustained stock session: PID 1691 persisted from the first timed window at
  16:23:05 to the final endpoint at 16:43:06 Prague time, with continuous
  benchmark records. The final 674-second timed capture averaged 153.7
  emulation FPS / 2.57x in combat. Battery temperature was 30 C at sampled
  endpoints, thermal status 0, and no process restart was observed. Endpoint
  thermal readings do not prove absence of transient chip throttling.
  The retained log contains 598 benchmark records in that 20-minute interval,
  with a maximum gap of 3.95 seconds between completed 300-refresh windows.
- Raw logs, snapshots, profiles and bounded-window summaries are local under
  `build/driver-20261031/`. The later CPU index-copy experiment was dropped:
  host microbenchmarks showed mixed results and no device gain was established.
- Final candidate removes the 100%-only forced FIFO selection. Normal speed
  keeps its own guest-clock interval and now requests the same default preferred
  mailbox path used at 150%; device fallback modes remain supported. Tests also
  cover the 150% clock interval independently of the presentation cap.
- The initial disconnect delayed installation; the November 1 follow-up above
  completed the short hardware check and left Turnip at Normal (100%) speed.
  Sustained performance and combat still need checking for this final change.
  No release was published.

## Unreleased uncapped game speed (2026-10-31)

- Installed test APK:
  `build/android-windows/zakuro-android-test-20261031-155203-e9ff75a4.apk`,
  SHA256 `d2362a2f5cff1fdc40b15554ef36e4b3fa7acd0db41aa96f7aec8a0e11f5db42`.
  Same certificate, package and existing imported game/save data. Not released;
  this local test still uses manifest version 0.1.7.
- Thor, Ultra Moon, **3x rendering resolution, HD textures on**, the same selected
  custom Turnip driver, Auto/CPU vertex processing and both displays. Game speed
  unlimited (`speed_limit=0`); presentation cap 60, main presenter mailbox.
  Speed is measured from guest cycles / 268,111,856 / wall seconds, not the
  presentation counter. Emulation FPS is cycles / 4,481,136 / wall seconds.

| Scene | Emulation FPS | Normal-speed multiplier | 300-refresh window range |
|---|---:|---:|---:|
| Outdoors at Route 2 Pokemon Center | 177.9 | 2.97x | 2.92-3.01x |
| Wild-battle scene, waiting for input | 212.8 | 3.56x | 2.84-4.47x |
| Battle actions and enemy turns | 207.7 | 3.47x | 2.83-4.30x |

- Outdoor and idle-combat captures each requested 5 seconds warmup + 30 seconds
  measurement. The active capture lasted 30.20 seconds and injected 23 held A
  presses: Fight/Dragon Claw has no effect on this Fairy opponent, allowing
  repeated turns without ending the encounter. Enemy attacks reduced HP, checked
  visually. This samples those actions, not every move, effect or battle type.
- Summaries use complete 300-refresh log windows inside each timed interval:
  16 outdoor windows and 20 in each combat sample. FPS is weighted by estimated
  window duration (300 / logged emulation FPS); boundary windows are omitted.
  Submissions remained approximately 60/s. These FPS values count emulated
  refreshes, **not unique rendered game images or physical display refreshes**.
- Local captures, PID checks, summary JSON, screenshots and logs are under
  `build/speed-20261031/`. The process stayed at PID 1691 throughout the reported
  captures. Battery temperature reached 30 C and thermal status read 0 at sampled
  endpoints; sustained thermal performance was not established.
- The speed preset picker was checked on-device (Unlimited, Normal, 1.5x, 2x,
  3x, 4x). After closing settings the live HUD showed separate emulation FPS,
  speed percentage and submitted FPS. Unlimited was left enabled for testing.
- Android host suite: 29 passed, including cycle-based 100/300/400% measurement
  and speed-limit defaults/clamping/intervals. Java settings, callback/JNI contract
  and driver checks passed. ARM64 release build, signing and alignment passed.
- The 3x-4x target is approached/reached in these samples; a steady 4x across
  gameplay is not established. No matched Azahar run, other games, long thermal
  run or audio time-stretch test. Faster clocks can drop audio queue samples.

## Unreleased gameplay follow-up (2026-10-31)

- Baseline source: `b089a3b`; candidate: the working-tree changes described in
  the unreleased changelog. Test APK:
  `build/android-windows/zakuro-android-test-20261031-153717-3cd24054.apk`,
  SHA256 `cad565b590444cebf31da1ff1c2e0db171874e59569b8c05315826fef66cc69a`.
  Installed over the existing app with the same signing certificate; not a
  published release. Manifest version remains 0.1.7 for this test build.
- AYN Thor, Android 13, Adreno 740, selected custom Turnip driver unchanged,
  CPU vertex processing (Auto), HD pack enabled, both physical displays at 60 Hz,
  shared main-screen GPU presentation and CPU bottom-screen presentation.
  Battery sensor reported 29 C and thermal status 0 at the sampled endpoints;
  these are not continuous temperature or throttling measurements.
- Loaded the same Ultra Moon save inside the Pokemon Center. Warmed, idle scene
  windows below exclude the title/menu and initial loading. Captures and screenshots
  are retained locally in `build/performance-20261031/` and are not release assets.

| Loaded scene | Baseline emulation ticks/s | Candidate emulation ticks/s |
|---|---|---|
| 2x, HD on | 58.04-58.08 | 59.99-60.02 |
| 3x, HD on | 57.33-57.68 | 60.01-60.04 |

- Main-screen SurfaceFlinger samples at 2x each had 1 interval above 25 ms out of
  125 before and after. At 3x, the baseline sample had 18/125; six non-overlapping
  candidate samples had 3/751. Intervals normally measured about 16.6 ms, with
  missed refreshes near 33.2 ms. These are exploratory samples with unequal
  capture lengths, not the repeated, alternating benchmark protocol.
- A 15-second baseline CPU profile recorded 62,021 samples with none lost.
  Symbolication against the matching native library attributed 27.31% to batch
  shader execution, 3.74% to batch dispatch, 2.18% to batch initialization and
  6.50% to memcpy. The candidate removes an output-register copy, not shader
  arithmetic. No individual change's speedup was isolated from the combined APK.
- Also observed outdoor gameplay at 3x with HD enabled, both displays updating,
  and roughly 60 ticks/s after loading. This route was not controlled and is
  excluded from the comparison table. Loading still produced core-work stalls
  above 100 ms; warmed gameplay still had occasional presentation stalls.
- Automated: GPU suite 179 passed / 2 ignored; Android host suite 27 passed;
  core suite 145 passed. New helper checks cover upload allowance/oversized
  progress, output equivalence, retained image identity and bounded frame debt.
  Final upload allowance checks separately passed after the refresh-boundary glue.
- Home/Recents/resume check kept `resolution=3`, `shared=true`, `cpu_top=0` and
  approximately 60 ticks/s; the second screen continued updating after resume.
- Limitations: emulation ticks and presented buffers are not unique rendered game
  frames; this does not establish native 60 FPS animation. No long-session thermal
  trial, battle/wormhole regression or matched Azahar comparison was performed.
  Azahar 2126.2-vanilla is installed, but equivalent settings were not established.
  One indivisible texture upload can still exceed the nominal 2 ms allowance.

## 0.1.7 frame pacing, performance and fixes (2026-10-31)

- Delivery: `build/releases/0.1.7/Zakuro-Android-0.1.7.apk` (9,262,166 bytes),
  SHA256 `f3edd0cefd4404772ac8ee60df41fd091532a33e48d006efc29ce853047b6cfc`,
  same certificate as 0.1.4–0.1.6. Source: `android-handheld` at the release tag.
- Device: AYN Thor (Snapdragon 8 Gen 2, Adreno 740, Android 13), stock Qualcomm
  Vulkan driver, Pokémon Ultra Moon, New 3DS mode, vertex shaders Auto (CPU).
- Method: the `benchmark` logcat line (300-frame windows), SurfaceFlinger
  `--latency` present intervals, and `simpleperf record --app` (the APK is
  profileable). Scenes are the same save and overworld spot, not a scripted route,
  so treat numbers as indicative.

| Ultra Moon | 0.1.6 | 0.1.7 |
|---|---|---|
| 2×, HD on | 54–57 FPS, core ~10 ms | 59.5–60.2 FPS, core 6.4–6.8 ms |
| 3×, HD on | 43–50 FPS, core ~10.5 ms | 59.9–60.4 FPS, core 6.1–6.6 ms |
| 3×, present intervals | ~1 in 3 at 33 ms | 125 of 126 at 16.7 ms |
| Swapchain rebuilds / 30 s | ~1950 | 3 (startup) |

- Remaining: occasional 21–30 ms frames (about 1 in 125) in heavy scenes. GPU
  write-backs measured at ~1 per frame, 0.3 ms (`wb[sync=…]`), so not the cause.
- Ultra Wormhole: confirmed fixed by the tester after the HID calibration change;
  the log shows one `hid: gyroscope calibration asked for`. Before the fix the
  shader NaN tracer showed uniforms c10–c16 uploaded as NaN and every triangle
  clipped, in both renderers.
- Driver comparison: Turnip with Auto shading was slower and less even than stock;
  GPU vertex shaders were GPU-bound on Turnip and crash the stock driver
  (`Rust cannot catch foreign exceptions` from pipeline creation).
- Automated: zakuro-core 145, zakuro-gpu 128 host tests; SaveArchiveCheck 92 on
  Linux (Docker, symlink cases included) and 87 on Windows; settings, bridge and
  driver host checks pass.
- Not verified: other games and devices, battery/thermal behaviour of the ADPF
  hint over long sessions, and a fair comparison with Azahar.

## 0.1.6 save folder hotfix (2026-10-31)

- Delivery: `build/releases/0.1.6/Zakuro-Android-0.1.6.apk`, SHA256
  `615f50fe11d6dd8c7a74c347caad1c76aed136eb772c901b62fbbcf9692bc48d`, same certificate.
- Cause: 0.1.5 rejected every symlinked ancestor of `filesDir`; Android's
  `/data/user/0` is one. Host tests had used an already-resolved temp path.
- 79 SaveArchive checks pass on Windows. A new regression check (lock through an
  aliased files directory) is in the symlink group, which Windows skips; it was not
  run on Linux for this hotfix. Device confirmation pending.

## 0.1.5 save export and restore (2026-10-31)

- Delivery: `build/releases/0.1.5/Zakuro-Android-0.1.5.apk` (9,241,686 bytes),
  SHA256 `29127b672313eead36a411510a14dfd4e688c188d2f14812dd812df9b0759d56`,
  signed with the same certificate as 0.1.4 (SHA-256 `5821ce1e…7228`).
  Build log: `build/android-windows/build-20261031-111818-aeaa4dd9.log`.
- `android/app/tests/run-save-archive-tests.ps1`: 79 checks passed — round trip,
  wrong title, path traversal, case/duplicate collisions, CRC corruption, Unix
  symlink entries, truncation, read failure, interrupted-switch recovery, blocked
  backup path and cross-process locking. Filesystem symlink cases were skipped on
  Windows (no privilege); they run on Linux/Android hosts.
- `android/app/settingshosttests/run.ps1` passed, including the new
  `prepareSaves` Java/native contract. ARM64 `cargo check` of `zakuro-android` passed.
- Not yet verified on a device: the Android file picker flow and the lock held
  by a paused game process.

## 0.1.4 rotation hotfix (2026-10-31)

- Delivery: `build/releases/0.1.4/Zakuro-Android-0.1.4.apk` (9,221,205 bytes),
  SHA256 `f65984bb6e678d5859490e7e73a570d935c1b042ffc063309ebfa1197cf3c75c`.
- Two targeted Vulkan transform tests passed: window-coordinate rendering requests
  identity for 0/90/180/270-degree panels; unsupported identity is not requested.
  Log: `build/rotation-tests-0.1.4.log`.
- ARM64 release build, v3 signing with the existing certificate, ZIP alignment,
  package and asset checks passed. Build log:
  `build/android-windows/build-20261031-105748-356da32c.log`.
- The same presenter serves both Android displays. This fixes the mismatch between
  window-coordinate rendering and claimed pre-rotation; physical orientation,
  touchscreen alignment and composition overhead on Thor still need verification.
  See [Khronos surface rotation guidance](https://docs.vulkan.org/samples/latest/samples/performance/surface_rotation/README.html).
- Update the installed app directly. No native game-code replacement or ROM
  reimport is needed. The previous complete regression results below are historical,
  not a claim that all suites were rerun for this focused presentation change.

## 0.1.3 baseline

The checks below refer to the local 0.1.3 source and artifacts on 2026-10-31.
Compilation, Android UI/storage execution and physical ARM64 gameplay are separate
levels of evidence. A visible control or matching symbol is not a device test.

## Deliverable

- APK: `build/releases/0.1.3/Zakuro-Android-0.1.3.apk` (**9,225,301 bytes**).
- SHA256: `40ec9de249f9e1a1095a445c1c6706096b9f62be6463fecb3a6ab49317f4a25a`.
- Package `io.github.zakuro.android`, version **0.1.3 / code 4**, ARM64 only.
- Built artifact: `build/android-windows/zakuro-android-test-20261031-103522-67d859d4.apk`;
  full matching log and checksum are beside it. The delivery copy's hash matches.
- Same Windows test signer as previous builds, certificate SHA256
  `5821ce1e0d2c1aa009bc1d269ae3865d50ce1448854d067203ce406dea967228`.

The Windows release build, v3 signature verification, 16 KB ZIP alignment,
package/version/launcher checks and license-only asset allowlist passed. All six
packaged native libraries are AArch64 ELF with LOAD segments aligned to 0x4000
and no RPATH/RUNPATH. The host exports ANativeActivity_onCreate and all six Game
JNI bridge methods; adrenotools exports its Vulkan-loading entry point. Static
reports are in `build/apk-verification-0.1.3/`. Only license notices are assets;
there are no bundled games, firmware, keys, saves or custom driver packs.

## Tests

| Boundary | Executed result | What it proves |
| --- | --- | --- |
| Core + filesystem final suites | **145 core + 24 FS passed** | Loader failures vs optional desktop fallback, extended-memory flags/allocation/config pages, executable bounds returning errors, state/FS regressions |
| Android native host helpers | **23 passed** | Typed settings, motion/calibration transforms, input release/gates, display geometry and storage helpers |
| GPU suite with Vulkan | **174 passed, 2 ignored** | Existing GPU regressions plus presentation/options helpers; not Android driver execution |
| Android ARM64 check and release link | **Passed** | NDK r27c/API30 cross-compilation and linking of the final runtime |
| Java host checks | **All six suites passed** | ABI/export visibility, ROM/PNG preflight, copy boundaries, settings drafts/persistence, JNI/options contract and driver ZIP checks |
| Real Android SAF suite | **27/27 passed** | Actual DocumentsProvider/PFD/filesystem import and replacement behavior on API35 x86_64 |
| Native Android frontend UI | **Passed scoped checks** | Six groups, font scaling, light/dark orientation layouts, Save/Cancel, driver trust Cancel, eight shader controls |
| Pinned build-title tool | **Build + usage/no-overwrite guards passed** | Generates against this runtime's pinned ABI and preserves existing output |

Final core/FS log: `build/final-core-fs-tests.log`. UI details and source freeze:
`android/app/settingshosttests/RESULTS.md` and `source-freeze.json`. Real SAF
report, provider traces and exact source hashes: `build/import-check/REPORT.md`,
`results.json`, `importer-source-hashes.json` and its APK directory. The final SAF
APK is `apk-20261031-103507/import-check.apk`, hash
`3e1431973e9ef03a51a32b5fccf12ccf6c5bb0b38c8d0d6a1dcc9f341d78b73d`.

The final review reproduced and fixed header-only ROM acceptance/core panic,
IHDR-only PNG replacement, and hidden ELF exports passing preflight. New tests
reject all three before activation. PNG chunks/CRCs are streamed and the copied
PNG is checked again. This establishes structure/checksum validity, not arbitrary
image decompression or native-code safety.

An earlier Android run exposed hard-link backup EACCES. Code repair now syncs
only an old-library backup, then atomically renames the replacement. Capacity is
checked before staging; backup failure leaves the old target in place. The final
SAF run covers this path, backup hash equality, original ROM/save/pointer
preservation, optional/excluded packs, invalid replacements and source mutation.

## Ultra Moon replacement and HD pack

`build/releases/0.1.3/Ultra Moon HD/title.so`: **286,066,048 bytes**, SHA256
`3f688826dbdc187c19d8d0cc9d25eac67de0f06e5a0b4ea4c6b9b961211cc475`.
The original output remains at `build/ultra-moon-android-abi4/title.so`.
The rebuild completed 573 compilation units using the pinned generator and NDK.
Read-only inspection confirms ABI4, no fastmem, Android libm/libdl/libc dependencies
and 16 KB LOAD alignment. The old supplied library independently reports ABI5
with fastmem, confirming the mismatch. No ABI check was bypassed.

Current production preflight passed with the real original Ultra Moon ROM and
this rebuilt library: title `00040000001B5100`, two files / 4,581,033,344 bytes.
Including the real extracted HD pack also passed all PNG structure/CRC checks:
3,541 files / 7,553,657,631 bytes. Both probes were read-only, without staging.
The existing Pocket Native HD kit's ROM and original dump have identical SHA256
`e08a903921bb87bdfb6e46c2a9c5e41a685e91cc371d38ffcbdcc4884bc84054`.
Only native code needs replacing in the existing Zakuro import.

## Limits

The disposable UI/SAF APKs are test-only Java packages on a workspace-local API35
x86_64 emulator. They do not contain or execute the production ARM64 game code.
No physical-device gameplay, Thor gyro axes/latency, dual-panel Vulkan output,
custom-driver execution, audio/video correctness or 16 KB device execution was
verified. Power-loss durability and real cloud/SD providers are not established
by the local SAF fixture suite. No Azahar performance comparison was measured.
Use [the benchmark protocol](benchmark.md) for that comparison.

The last older 0.1.2 artifact was
`zakuro-android-test-20261030-180612-eb156189.apk`, SHA256
`67cb01d2a4d1da88dcfa40e93edbcd0e8d4a10375980401d897181e27d7d7e18`.
It predates the repair/settings integration and is superseded by the APK above.
