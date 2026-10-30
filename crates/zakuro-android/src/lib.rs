//! NativeActivity frontend. Java owns import and launch; this crate owns the runtime.
#[cfg(target_os = "android")]
mod audio;
#[cfg(target_os = "android")]
mod platform;
#[cfg(target_os = "android")]
mod runtime;
#[cfg(target_os = "android")]
mod sensors;
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
        Err(_) => {
            if platform::report_error(
                &app,
                "The native runtime stopped unexpectedly. See Zakuro logcat for details.",
            ) {
                platform::wait_for_destroy(&app);
                return;
            }
        }
    }
    platform::finish(&app);
}
