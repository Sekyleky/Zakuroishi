//! NativeActivity frontend. Java owns import and launch; this crate owns the runtime.
#[cfg(target_os = "android")]
mod audio;
#[cfg(target_os = "android")]
mod bridge;
#[cfg(any(target_os = "android", test))]
mod driver;
#[cfg(any(target_os = "android", test))]
mod geometry;
#[cfg(any(target_os = "android", test))]
mod input;
#[cfg(target_os = "android")]
mod platform;
#[cfg(any(target_os = "android", test))]
mod pacing;
#[cfg(any(target_os = "android", test))]
mod presentation;
#[cfg(target_os = "android")]
mod runtime;
#[cfg(target_os = "android")]
mod sensors;
pub mod settings;
#[cfg(any(target_os = "android", test))]
mod storage;
mod transform;

#[cfg(target_os = "android")]
#[no_mangle]
pub fn android_main(app: android_activity::AndroidApp) {
    platform::init_log();
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| runtime::run(&app)));
    match result {
        Ok(Ok(())) => {}
        Ok(Err(error)) => {
            if platform::report_error(&app, &error) {
                platform::wait_for_destroy(&app);
                return;
            }
        }
        Err(payload) => {
            let detail = payload
                .downcast_ref::<String>()
                .map(String::as_str)
                .or_else(|| payload.downcast_ref::<&str>().copied())
                .unwrap_or("unknown native panic");
            if platform::report_error(&app, &format!("The native runtime stopped: {detail}")) {
                platform::wait_for_destroy(&app);
                return;
            }
        }
    }
    platform::finish(&app);
}
