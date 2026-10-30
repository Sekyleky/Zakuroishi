use crate::transform::GyroCalibration;
use ndk_sys as ffi;
use zakuro_core::services::hid::MotionSample;

#[derive(Default)]
struct Samples {
    queue: *mut ffi::ASensorEventQueue,
    acceleration: Option<[f32; 3]>,
    acceleration_at: i64,
    gyro: Option<[f32; 3]>,
    calibration: GyroCalibration,
}
impl Samples {
    fn drain(&mut self) {
        unsafe {
            let mut event: ffi::ASensorEvent = std::mem::zeroed();
            while ffi::ASensorEventQueue_getEvents(self.queue, &mut event, 1) > 0 {
                let data = event.__bindgen_anon_1.__bindgen_anon_1.data;
                let value = [data[0], data[1], data[2]];
                if !value.iter().all(|v| v.is_finite()) {
                    continue;
                }
                match event.type_ {
                    1 => {
                        self.acceleration = Some(value);
                        self.acceleration_at = event.timestamp;
                    }
                    4 => {
                        let rate = value.map(f32::to_degrees);
                        self.gyro = Some(rate);
                        if let Some(accel) = self.acceleration.filter(|_| {
                            (event.timestamp - self.acceleration_at).abs() <= 100_000_000
                        }) {
                            if let Some(bias) = self.calibration.observe(
                                accel.map(|v| v / 9.80665),
                                rate,
                                event.timestamp,
                            ) {
                                log::info!("gyro calibrated stationary bias_dps={bias:?}");
                            }
                        }
                    }
                    _ => {}
                }
            }
        }
    }
}
unsafe extern "C" fn sensor_ready(_: i32, _: i32, data: *mut std::ffi::c_void) -> i32 {
    // The boxed state lives until the queue is destroyed; callbacks run on android_main only.
    (&mut *data.cast::<Samples>()).drain();
    1
}

pub struct Sensors {
    manager: *mut ffi::ASensorManager,
    queue: *mut ffi::ASensorEventQueue,
    sensors: [*const ffi::ASensor; 2],
    active: bool,
    samples: Box<Samples>,
}

impl Sensors {
    pub fn new() -> Option<Self> {
        // Use a callback so android-activity never sees an unknown looper ident.
        unsafe {
            let manager =
                ffi::ASensorManager_getInstanceForPackage(c"io.github.zakuro.android".as_ptr());
            let looper = ffi::ALooper_forThread();
            if manager.is_null() || looper.is_null() {
                return None;
            }
            let mut samples = Box::<Samples>::default();
            let queue = ffi::ASensorManager_createEventQueue(
                manager,
                looper,
                -2,
                Some(sensor_ready),
                (&mut *samples as *mut Samples).cast(),
            );
            if queue.is_null() {
                return None;
            }
            samples.queue = queue;
            Some(Self {
                manager,
                queue,
                sensors: [
                    ffi::ASensorManager_getDefaultSensor(manager, 1),
                    ffi::ASensorManager_getDefaultSensor(manager, 4),
                ],
                active: false,
                samples,
            })
        }
    }

    pub fn set_active(&mut self, active: bool) {
        if active == self.active {
            return;
        }
        self.active = active;
        *self.samples = Samples {
            queue: self.queue,
            ..Samples::default()
        };
        if active {
            log::info!(
                "motion sensors enabled: hold the handheld still briefly for gyro calibration"
            );
        }
        for sensor in self.sensors.into_iter().filter(|p| !p.is_null()) {
            unsafe {
                if active {
                    if ffi::ASensorEventQueue_enableSensor(self.queue, sensor) < 0 {
                        log::warn!("could not enable motion sensor");
                    } else {
                        let minimum = ffi::ASensor_getMinDelay(sensor);
                        let _ = ffi::ASensorEventQueue_setEventRate(
                            self.queue,
                            sensor,
                            minimum.max(10_000),
                        );
                    }
                } else {
                    let _ = ffi::ASensorEventQueue_disableSensor(self.queue, sensor);
                }
            }
        }
        // Discard samples queued before pause/resume.
        unsafe {
            let mut event = std::mem::zeroed();
            while ffi::ASensorEventQueue_getEvents(self.queue, &mut event, 1) > 0 {}
        }
    }

    pub fn sample(&mut self, rotation: i32) -> Option<MotionSample> {
        if !self.active {
            return None;
        }
        self.samples.drain();
        // Android measures support acceleration; HID expects gravity (upright: -Y).
        let acceleration = crate::transform::display_vector(self.samples.acceleration?, rotation)
            .map(|v| -v / 9.80665);
        let angular_velocity = crate::transform::display_vector(
            self.samples
                .calibration
                .correct(self.samples.gyro.unwrap_or([0.0; 3])),
            rotation,
        );
        Some(MotionSample {
            acceleration,
            angular_velocity,
        })
    }
}
impl Drop for Sensors {
    fn drop(&mut self) {
        self.set_active(false);
        unsafe {
            ffi::ASensorManager_destroyEventQueue(self.manager, self.queue);
        }
    }
}
