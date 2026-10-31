use android_activity::AndroidApp;
use jni::{
    objects::{JObject, JString, JValue},
    JNIEnv, JavaVM,
};
use std::path::PathBuf;

struct AndroidLog;
static LOG: AndroidLog = AndroidLog;
impl log::Log for AndroidLog {
    fn enabled(&self, metadata: &log::Metadata<'_>) -> bool {
        // cranelift_jit logs each shader's whole IR at info; logcat writes stall frames.
        metadata.level() <= log::Level::Warn
            || (metadata.level() <= log::Level::Info && metadata.target().starts_with("zakuro"))
    }
    fn log(&self, record: &log::Record<'_>) {
        if !self.enabled(record.metadata()) {
            return;
        }
        // android-activity 0.6 treats normal sensor looper callbacks as errors.
        if record.target().starts_with("android_activity::")
            && record.args().as_str()
                == Some("Spurious ALOOPER_POLL_CALLBACK from ALooper_pollOnce() (ignored)")
        {
            return;
        }
        let priority = match record.level() {
            log::Level::Error => 6,
            log::Level::Warn => 5,
            _ => 4,
        };
        if let Ok(message) =
            std::ffi::CString::new(format!("{}: {}", record.target(), record.args()))
        {
            unsafe {
                ndk_sys::__android_log_write(priority, c"Zakuro".as_ptr(), message.as_ptr());
            }
        }
    }
    fn flush(&self) {}
}
pub fn init_log() {
    let _ = log::set_logger(&LOG);
    log::set_max_level(log::LevelFilter::Info);
    std::panic::set_hook(Box::new(|info| log::error!("{info}")));
}

fn with_activity<T>(
    app: &AndroidApp,
    f: impl FnOnce(&mut JNIEnv<'_>, &JObject<'_>) -> jni::errors::Result<T>,
) -> Result<T, String> {
    // android-activity owns the VM and global Activity reference throughout android_main.
    let vm = unsafe { JavaVM::from_raw(app.vm_as_ptr().cast()) }.map_err(|e| e.to_string())?;
    let mut env = vm.attach_current_thread().map_err(|e| e.to_string())?;
    let activity = unsafe { JObject::from_raw(app.activity_as_ptr().cast()) };
    let result = env.with_local_frame(32, |env| f(env, &activity));
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_describe();
        let _ = env.exception_clear();
    }
    result.map_err(|e| e.to_string())
}

pub struct Launch {
    pub game: PathBuf,
    pub code: PathBuf,
    pub data: PathBuf,
    pub texture_packs: bool,
    pub resolution: u32,
    pub advanced: crate::settings::Advanced,
    pub driver: Option<crate::driver::DriverSpec>,
}

pub fn launch(app: &AndroidApp) -> Result<Launch, String> {
    let save_error = with_activity(app, |env, activity| {
        let value = env.call_method(activity, "prepareSaves", "()Ljava/lang/String;", &[])?.l()?;
        if value.is_null() { Ok(None) } else {
            let message: String = env.get_string(&JString::from(value))?.into();
            Ok(Some(message))
        }
    })?;
    if let Some(error) = save_error { return Err(error); }
    let (
        game,
        code,
        data,
        texture_packs,
        resolution,
        advanced_json,
        driver_dir,
        driver_name,
        hooks,
    ) = with_activity(app, |env, activity| {
        let intent = env
            .call_method(activity, "getIntent", "()Landroid/content/Intent;", &[])?
            .l()?;
        let mut extra = |name: &str| -> jni::errors::Result<PathBuf> {
            let key = env.new_string(name)?;
            let value = env
                .call_method(
                    &intent,
                    "getStringExtra",
                    "(Ljava/lang/String;)Ljava/lang/String;",
                    &[JValue::Object(&key)],
                )?
                .l()?;
            if value.is_null() {
                return Ok(PathBuf::new());
            }
            let value = JString::from(value);
            let value: String = env.get_string(&value)?.into();
            Ok(PathBuf::from(value))
        };
        let paths = (extra("game")?, extra("code")?, extra("data")?);
        let driver_dir = extra("driver_dir")?;
        let driver_name = extra("driver_name")?;
        let info = env
            .call_method(
                activity,
                "getApplicationInfo",
                "()Landroid/content/pm/ApplicationInfo;",
                &[],
            )?
            .l()?;
        let hooks = env
            .get_field(info, "nativeLibraryDir", "Ljava/lang/String;")?
            .l()?;
        let hooks: String = env.get_string(&JString::from(hooks))?.into();
        let key = env.new_string("texture_packs")?;
        let texture_packs = env
            .call_method(
                &intent,
                "getBooleanExtra",
                "(Ljava/lang/String;Z)Z",
                &[JValue::Object(&key), JValue::Bool(1)],
            )?
            .z()?;
        let key = env.new_string("resolution")?;
        let resolution = env
            .call_method(
                &intent,
                "getIntExtra",
                "(Ljava/lang/String;I)I",
                &[JValue::Object(&key), JValue::Int(1)],
            )?
            .i()?
            .clamp(1, 3) as u32;
        let key = env.new_string("advanced")?;
        let value = env
            .call_method(
                &intent,
                "getStringExtra",
                "(Ljava/lang/String;)Ljava/lang/String;",
                &[JValue::Object(&key)],
            )?
            .l()?;
        let advanced_json: String = if value.is_null() {
            "{}".into()
        } else {
            env.get_string(&JString::from(value))?.into()
        };
        Ok((
            paths.0,
            paths.1,
            paths.2,
            texture_packs,
            resolution,
            advanced_json,
            driver_dir,
            driver_name,
            PathBuf::from(hooks),
        ))
    })?;
    let advanced = crate::settings::Advanced::parse(&advanced_json)?;
    let internal = app.internal_data_path().ok_or("no app internal storage")?;
    let driver = if advanced.custom_driver {
        Some(crate::driver::DriverSpec::validate(
            &driver_dir,
            &driver_name.to_string_lossy(),
            &internal,
            hooks,
        )?)
    } else {
        None
    };
    let mut roots = vec![internal.clone()];
    if let Some(external) = app.external_data_path() {
        roots.push(external);
    }
    let game = crate::transform::owned_path(&game, &roots)?;
    let data = crate::transform::owned_path(&data, &roots)?;
    // Executable imports must stay in private internal storage.
    let code = if advanced.aot {
        crate::transform::owned_path(&code, &[internal])?
    } else {
        code
    };
    if !game.is_dir()
        || !data.is_dir()
        || (advanced.aot && (!code.is_file() || code.extension().is_none_or(|s| s != "so")))
    {
        return Err("game/data must be directories and code must be a .so file".into());
    }
    let rom = crate::transform::owned_path(&game.join("game.3ds"), &roots)?;
    if !rom.starts_with(&game) || !rom.is_file() {
        return Err("game/game.3ds must be a regular ROM file".into());
    }
    Ok(Launch {
        game: rom,
        code,
        data,
        texture_packs,
        resolution,
        advanced,
        driver,
    })
}

pub fn stats(app: &AndroidApp, text: &str) {
    java_text(app, "updateNativeStats", text);
}
/// ADPF hint session for the calling thread (must be the emulation thread).
pub fn start_performance_hints(app: &AndroidApp) {
    let result = with_activity(app, |env, activity| {
        let tid = env
            .call_static_method("android/os/Process", "myTid", "()I", &[])?
            .i()?;
        let tids = env.new_int_array(1)?;
        env.set_int_array_region(&tids, 0, &[tid])?;
        env.call_method(
            activity,
            "startPerformanceHints",
            "([I)V",
            &[JValue::Object(&tids)],
        )
        .map(|_| ())
    });
    if let Err(error) = result {
        log::warn!("performance hints: {error}");
    }
}
pub fn report_frame(app: &AndroidApp, nanos: i64) {
    let _ = with_activity(app, |env, activity| {
        env.call_method(activity, "reportFrame", "(J)V", &[JValue::Long(nanos)])
            .map(|_| ())
    });
}
pub fn pause_menu(app: &AndroidApp) {
    let _ = with_activity(app, |env, activity| {
        env.call_method(activity, "showPauseMenu", "()V", &[])
            .map(|_| ())
    });
}
pub fn close_second_display(app: &AndroidApp) {
    let _ = with_activity(app, |env, activity| {
        env.call_method(activity, "closeSecondDisplay", "()V", &[])
            .map(|_| ())
    });
}
pub fn message(app: &AndroidApp, text: &str) {
    log::info!("{text}");
    java_text(app, "showNativeMessage", text);
}
fn java_text(app: &AndroidApp, method: &str, text: &str) {
    let _ = with_activity(app, |env, activity| {
        let text = env.new_string(text)?;
        env.call_method(
            activity,
            method,
            "(Ljava/lang/String;)V",
            &[JValue::Object(&text)],
        )
        .map(|_| ())
    });
}

/// Game may show a dialog on the UI thread and finish after dismissal.
pub fn report_error(app: &AndroidApp, error: &str) -> bool {
    log::error!("{error}");
    with_activity(app, |env, activity| {
        let message = env.new_string(error)?;
        env.call_method(
            activity,
            "showNativeError",
            "(Ljava/lang/String;)V",
            &[JValue::Object(&message)],
        )
        .map(|_| ())
    })
    .is_ok()
}

pub fn rotation(app: &AndroidApp) -> Result<i32, String> {
    with_activity(app, |env, activity| {
        let manager = env
            .call_method(
                activity,
                "getWindowManager",
                "()Landroid/view/WindowManager;",
                &[],
            )?
            .l()?;
        let display = env
            .call_method(
                manager,
                "getDefaultDisplay",
                "()Landroid/view/Display;",
                &[],
            )?
            .l()?;
        env.call_method(display, "getRotation", "()I", &[])?.i()
    })
}

pub fn finish(app: &AndroidApp) {
    if let Err(error) = with_activity(app, |env, activity| {
        env.call_method(activity, "finish", "()V", &[]).map(|_| ())
    }) {
        log::error!("could not finish Game activity: {error}");
    }
}

pub fn wait_for_destroy(app: &AndroidApp) {
    // android-activity finishes the Activity when android_main returns, so keep
    // servicing lifecycle while Java's error dialog waits for dismissal.
    let mut destroyed = false;
    while !destroyed {
        app.poll_events(None, |event| match event {
            android_activity::PollEvent::Main(android_activity::MainEvent::Destroy) => {
                destroyed = true
            }
            android_activity::PollEvent::Main(android_activity::MainEvent::InputAvailable) => {
                if let Ok(mut input) = app.input_events_iter() {
                    while input.next(|_| android_activity::InputStatus::Unhandled) {}
                }
            }
            _ => {}
        });
    }
}
