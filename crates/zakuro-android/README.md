# Android native runtime

`libzakuro_android.so` is a NativeActivity cdylib. The manifest's
`android.app.lib_name` must be `zakuro_android`. `android-activity` exports the
NativeActivity glue; this crate exports Rust `android_main`. The activity class
is `io.github.zakuro.android.Game`; no separate JNI start method is needed.

Launch extras:

| Key | Type | Meaning |
| --- | --- | --- |
| `game` | String | Absolute app-owned import directory containing `game.3ds` |
| `code` | String | Absolute internal app-owned ARM64 recompiled `.so` |
| `data` | String | Absolute app-owned data directory |
| `texture_packs` | boolean | Enable upstream texture packs; default true |
| `resolution` | int | Internal resolution multiplier; default 1, clamped to 1–3 |

Paths are canonicalized and checked against NativeActivity's private internal
or external data directories before loading. Code must be in internal storage.
The importer owns `.so` validation and permissions. Native requires the supplied
library to load successfully and the Vulkan hardware renderer to start.
Existing upstream saves/mod paths apply, including
`data/mods/<TITLE_ID>/textures`. No texture-pack cache budgets are overridden.

Optional Java error hook: `public void showNativeError(String message)`. Post
the dialog to the UI thread and call `finish()` on dismissal, including cancel.
Native stops emulation, drops its resources, then polls lifecycle/input until
Destroy, keeping the dialog alive. Without the hook, it logs and finishes.

Both screens are stacked in one Vulkan NativeWindow, with touch mapped through
the presenter's exact bottom-screen viewport. This does not drive a separate
Thor display. Android gamepad face labels map directly to 3DS face labels;
D-pad, both sticks, shoulders, triggers, Start and Select are handled. Back is
left to Android. Losing focus clears held inputs. Initial presentation shares
the renderer's Vulkan device. Window recreation preserves game state and uses
GPU readback on a new presenter device, with a downgrade warning.

AAudio negotiates stereo float output at the device rate. A bounded console
sample queue is linearly resampled in the callback; underflow emits silence.
Audio is closed on pause/focus loss/window loss and retried after disconnect.
This minimal path has no time stretching or audio-clock drift controller.

Motion uses real Android accelerometer and calibrated gyroscope sensors, rotated
from natural device coordinates using `Display.getRotation()`. Console axes are
X right, Y up, Z out of screen. Android support acceleration is negated and
converted to g; angular velocity becomes degrees/second, retaining yaw.
Core must supply `InputState.motion: Option<MotionSample>` with fields
`acceleration` and `angular_velocity`, both `[f32; 3]`.

**Hold the handheld still briefly after resuming.** Gyro bias calibration resets
each time sensors are enabled. It requires at least 25 fresh samples over 0.5 s,
acceleration magnitude within 0.03 g of 1 g, acceleration-vector movement within
0.03 g, gyro magnitude at most 0.5 degrees/s, and per-axis gyro variance at most
0.01 (degrees/s)². Sample gaps over 100 ms restart the estimate. Once calibrated,
bias stays fixed until the next sensor enable; turns do not update it. Startup
guidance and successful bias estimates are logged. A sufficiently slow constant
turn during startup is indistinguishable from bias; hold still as instructed.
No hardware axis/sign, sensor noise, temperature-drift, or Thor calibration has
been verified. There is no game-specific neutral-pose/recenter control. Missing
accelerometer means no raw motion sample; missing gyro gives zero angular rate.

Every 300 completed frames logcat tag `Zakuro` emits:

```text
benchmark frames=300 fps=... core_ms=... present_ms=... resolution=... textures=... shared=...
```

FPS is elapsed wall time including frame pacing. `core_ms` measures only
`System::run_frame`; `present_ms` includes screen acquisition/readback and the
presenter call, including any driver/display wait. These are CPU wall timings,
not GPU timestamps. Partial batches are discarded across inactivity. No game
has been run to establish performance numbers.

Validation without launching a game:

```text
cargo check --locked --target aarch64-linux-android -p zakuro-android --target-dir crates/zakuro-android/target-check
cargo test --locked -p zakuro-android --lib
```

Android-target checking needs the Rust target; linking the APK library needs the
Android NDK. Dependency versions should remain in the parent-owned workspace
lockfile. This crate uses the existing Vulkan renderer/presenter and permissive
Rust Android bindings; it contains no copied GPL frontend code.
