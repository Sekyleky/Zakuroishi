# Android benchmark protocol

This is a measurement plan, with no performance or compatibility claims. Compare
Zakuro and Azahar on the same physical device, game, save and repeatable scene.
If either cannot render/play that scene correctly, report that limitation before
comparing speed. Never redistribute game files with scripts or result bundles.

## Record and control

Record device model, SoC/GPU, RAM, Android build, Vulkan driver/version, refresh
rate, power/performance mode, brightness, battery/charging state, ambient
temperature and thermal state. Keep them fixed, close background workloads,
disable recording/overlays unless needed for both, and cool to the same starting
thermal state between runs. Use the same controller input route.

Record both app versions, source revisions and APK hashes; for Zakuro also retain
the build log, Cargo.lock hash and toolchain image digest. For Azahar record its
exact settings/export or screenshots, including renderer (Vulkan), resolution
scale, shader compilation/cache policy, CPU clock override, speed limit,
VSync/frame pacing, audio, texture/filter settings, cheats/mods and hacks. Match
every equivalent Zakuro setting and document absent equivalents. A mismatch
prevents a claim of an equivalent-settings comparison.

Hash the exact ROM and record title ID, region, game version/update/DLC and
firmware requirements. Match an in-game save and location, camera, time/weather,
input sequence and route. Prefer portable in-game saves over emulator-specific
save states; document any conversion. Keep these files private. Record setup and
verification outside the timed window.

## Reading the benchmark line

Every 300 frames the runtime logs (tag `Zakuro`, `adb logcat -s Zakuro:*`):

```
benchmark frames=300 fps=60.10 core_ms=5.6 present_ms=10.7 worst_core_ms=20.4 worst_frame_ms=21.2
  slow=204 vsync=true cpu_top=0 cpu_bottom=300 resolution=3 textures=true shared=true
  gpu_waits=1.00 gpu_wait_ms=0.003 readbacks=1.02 readback_ms=0.30 wb[sync=305/91.2ms …]
```

- `core_ms`: emulation work per frame (game code, GPU command processing, CPU
  vertex shading). Over ~16.7 ms means emulation-bound.
- `present_ms`: presenting, including the wait for vsync when `vsync=true`. A
  high value with low `core_ms` is idle time, not a problem. `slow` counts frames
  over 16 ms *including* that wait, so it is only meaningful when `vsync=false`.
- `cpu_top`/`cpu_bottom`: frames whose screen the CPU copied because the GPU did
  not draw it. `gpu_*`, `readbacks` and `wb[…]`: renderer waits on the GPU, by
  caller, totals over the window.
- Real smoothness is the present interval, not `fps`:
  `adb shell dumpsys SurfaceFlinger --latency '<Game layer>'` (layer name from
  `dumpsys SurfaceFlinger --list`). All intervals at 16.7 ms is smooth; 33/50 ms
  entries are visible hitches.

To profile, the APK is `profileable`: push the NDK's
`simpleperf/bin/android/arm64/simpleperf` to `/data/local/tmp`, run
`simpleperf record --app io.github.zakuro.android -g --duration 10`, and report on
the host with `--symfs` pointing at the unstripped `libzakuro_android.so` from the
Cargo target directory. Off-CPU waits do not appear in these samples.

## Fixed-duration runs

1. Install both exact APKs and prepare equivalent settings and saves. Verify the
   scene visually. Select an explicit cache policy: warmed caches for steady
   state, or a separately reported cold-cache experiment. Do not mix the two.
2. Navigate to the agreed starting scene, then run a fixed 30-second warmup and
   60-second capture using the script below. For an active route, coordinate its
   start with the end of warmup; the script does not automate game input.
3. Repeat at least three runs per app, alternating order (A/B, B/A, A/B), cooling
   between runs. Keep durations fixed and retain failed runs/crashes, reasons and
   repeats. Log any thermal throttling or scene deviation.
4. Report per-run results and spread, plus the precise data source and metric
   definition. Use emulator-provided guest frame times/speed counters if present,
   otherwise mark guest speed unavailable. Distinguish guest frames, Android
   presentation frames and UI frames. State the game's intended frame rate.

```powershell
# Scene already running; the helper does not launch, reset or import games.
./android/scripts/capture-adb.ps1 -Serial DEVICE_SERIAL `
  -Package io.github.zakuro.android -WarmupSeconds 30 -Seconds 60 `
  -OutputDirectory './benchmark-captures/zakuro-run1'
# Substitute the exact installed Azahar application ID, including any suffix.
./android/scripts/capture-adb.ps1 -Serial DEVICE_SERIAL `
  -Package AZAHAR_APPLICATION_ID -WarmupSeconds 30 -Seconds 60 `
  -OutputDirectory './benchmark-captures/azahar-run1'
```

The script creates a unique directory with device/package metadata, start/end
PIDs, battery and thermal snapshots, memory, `gfxinfo framestats`, logcat and UTC
window timestamps plus actual host timer duration. An adb failure stops the
capture; partial output is preserved for diagnosis. A missing/dead app fails the
PID check. Zakuro's manifest runs Game in `io.github.zakuro.android:game`, so the
helper checks that process and measures its memory rather than the launcher.
For other apps the default process is the package; use `-ProcessName` to override
it when the tested app uses another process. Review start/end PIDs and logs for
restarts. The warmup and timed wait
have fixed requested lengths; adb snapshot overhead falls outside the timed wait.
`gfxinfo reset` precedes the timer and the final dump follows it, so its counters
can include boundary overhead. Logcat is a device-wide ring-buffer snapshot,
may contain earlier messages and may have dropped messages; filter by timestamps
and process identity. Review logs before sharing for personal data or paths.

NativeActivity/Vulkan presentation may bypass Android UI frame statistics;
`gfxinfo` is diagnostic and must not be used as guest FPS. Memory/thermal samples
are endpoints, not continuous traces. The script neither measures power nor
computes FPS, percentiles or speedup. If guest timing is unavailable, collect
equivalent Perfetto/SurfaceFlinger traces as a separately documented experiment
on both apps; presentation timing still does not prove guest emulation speed.
No benchmark has been run yet.
