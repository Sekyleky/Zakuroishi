use crate::{
    audio::Audio,
    bridge::{self, Command},
    geometry,
    input::Input,
    platform,
    sensors::Sensors,
    settings::{Advanced, RightStick},
};
use android_activity::{
    input::{Axis, InputEvent, KeyAction, MotionAction, Source},
    AndroidApp, InputStatus, MainEvent, PollEvent,
};
use ndk::native_window::NativeWindow;
use raw_window_handle::{
    AndroidDisplayHandle, DisplayHandle, HandleError, HasDisplayHandle, HasWindowHandle,
    WindowHandle,
};
use std::time::{Duration, Instant};
use zakuro_common::Screen;
use zakuro_core::{Config, FrameOutcome, System};
use zakuro_gpu::{
    backend::vulkan::VulkanPresenter, GpuScreen, Overlay, OverlayMesh, OverlayTexture,
    OverlayVertex, PresentError, Presenter, ScreenImage,
};

// Keep a NativeWindow reference until its Vulkan surface has been destroyed.
struct Window(NativeWindow);
/// A fixed 60 Hz source lets a 120 Hz panel hold an even 2-vsync cadence.
fn request_frame_rate(window: &Window) {
    // Exported by libnativewindow, not libandroid; ndk-sys alone leaves it unresolved at dlopen.
    #[link(name = "nativewindow")]
    extern "C" {
        fn ANativeWindow_setFrameRate(window: *mut ndk_sys::ANativeWindow, rate: f32, compatibility: i8) -> i32;
    }
    let rate = unsafe { ANativeWindow_setFrameRate(window.0.ptr().as_ptr(), 60.0, 1) };
    log::info!("ANativeWindow_setFrameRate(60, fixed_source) = {rate}");
}
impl HasWindowHandle for Window {
    fn window_handle(&self) -> Result<WindowHandle<'_>, HandleError> {
        self.0.window_handle()
    }
}
impl HasDisplayHandle for Window {
    fn display_handle(&self) -> Result<DisplayHandle<'_>, HandleError> {
        Ok(unsafe { DisplayHandle::borrow_raw(AndroidDisplayHandle::new().into()) })
    }
}
struct Surface {
    presenter: VulkanPresenter,
    window: Window,
    shared: bool,
    settings: Advanced,
    cursor_texture: bool,
}
impl Surface {
    fn new(window: NativeWindow, settings: &Advanced, entry: ash::Entry) -> Result<Self, String> {
        let window = Window(window);
        request_frame_rate(&window);
        let size = (
            window.0.width().max(1) as u32,
            window.0.height().max(1) as u32,
        );
        let presenter = VulkanPresenter::with_entry(&window, size, entry).map_err(|e| {
            format!("Could not start Vulkan. Zakuro needs a Vulkan 1.3 capable driver. {e}")
        })?;
        let mut surface = Self {
            presenter,
            window,
            shared: false,
            settings: settings.clone(),
            cursor_texture: false,
        };
        surface.configure(settings);
        Ok(surface)
    }
    /// Presents to Android's next window on the same Vulkan device, so the renderer's
    /// images stay shared at full resolution after an app switch.
    fn reattach(&mut self, window: NativeWindow) -> Result<(), String> {
        let window = Window(window);
        request_frame_rate(&window);
        let size = (window.0.width().max(1) as u32, window.0.height().max(1) as u32);
        self.presenter.attach_surface(&window, size).map_err(|e| e.to_string())?;
        self.window = window;
        Ok(())
    }
    fn configure(&mut self, settings: &Advanced) {
        self.settings = settings.clone();
        self.presenter.set_layout(geometry::layout(settings));
        self.presenter.set_top_width(settings.top_width);
        self.presenter
            .set_scaling(geometry::filter(settings), settings.integer_scale);
        self.presenter.set_stereo(geometry::stereo(settings));
        self.presenter
            .set_geometry(settings.stretch, settings.swap_screens);
    }
    fn resize(&mut self) {
        self.presenter.resize(
            self.window.0.width().max(1) as u32,
            self.window.0.height().max(1) as u32,
        );
    }
    fn touch(&self, x: f32, y: f32) -> Option<(u16, u16)> {
        geometry::touch(
            &self.settings,
            self.window.0.width().max(1) as u32,
            self.window.0.height().max(1) as u32,
            x,
            y,
        )
    }
    fn present(
        &mut self,
        system: &mut System,
        cursor: Option<[f32; 2]>,
    ) -> Result<(), PresentError> {
        let shown = geometry::places(
            &self.settings,
            self.window.0.width().max(1) as u32,
            self.window.0.height().max(1) as u32,
            system.draws_in_3d(),
        );
        let empty = (std::sync::Arc::new(Vec::new()), 0, 0);
        let (top, top_gpu) = if shown
            .iter()
            .any(|(_, s)| matches!(s, zakuro_gpu::Shown::Top | zakuro_gpu::Shown::Both))
        {
            picture(system, Screen::Top, self.shared)
        } else {
            (empty.clone(), None)
        };
        let (bottom, bottom_gpu) = if shown.iter().any(|(_, s)| *s == zakuro_gpu::Shown::Bottom) {
            picture(system, Screen::Bottom, self.shared)
        } else {
            (empty.clone(), None)
        };
        let right = if shown
            .iter()
            .any(|(_, s)| matches!(s, zakuro_gpu::Shown::Right | zakuro_gpu::Shown::Both))
        {
            right_picture(system, self.shared)
        } else {
            None
        };
        let (right, right_gpu) = right
            .as_ref()
            .map(|(image, gpu)| (image, *gpu))
            .unwrap_or((&empty, None));
        let mut overlay = Overlay::default();
        if let Some(cursor) = cursor {
            self.cursor(cursor, &mut overlay);
        }
        self.presenter.present(
            ScreenImage {
                pixels: &top.0,
                width: top.1,
                height: top.2,
                gpu: top_gpu,
            },
            ScreenImage {
                pixels: &bottom.0,
                width: bottom.1,
                height: bottom.2,
                gpu: bottom_gpu,
            },
            ScreenImage {
                pixels: &right.0,
                width: right.1,
                height: right.2,
                gpu: right_gpu,
            },
            &overlay,
        )
    }
    fn cursor(&mut self, cursor: [f32; 2], overlay: &mut Overlay) {
        let width = self.window.0.width().max(1) as u32;
        let height = self.window.0.height().max(1) as u32;
        let id = u64::MAX;
        if !self.cursor_texture {
            overlay.textures.push(OverlayTexture {
                id,
                offset: None,
                size: [1, 1],
                pixels: vec![255; 4],
                linear: false,
            });
            self.cursor_texture = true;
        }
        for (v, shown) in geometry::places(&self.settings, width, height, true) {
            if shown != zakuro_gpu::Shown::Bottom {
                continue;
            }
            let x = v.x + cursor[0] / 320.0 * v.width;
            let y = v.y + cursor[1] / 240.0 * v.height;
            let mut mesh = OverlayMesh {
                texture: id,
                clip: [
                    v.x.max(0.0) as u32,
                    v.y.max(0.0) as u32,
                    (v.x + v.width).min(width as f32) as u32,
                    (v.y + v.height).min(height as f32) as u32,
                ],
                vertices: Vec::new(),
                indices: Vec::new(),
            };
            for [left, top, right, bottom] in [
                [x - 7.0, y - 1.5, x + 7.0, y + 1.5],
                [x - 1.5, y - 7.0, x + 1.5, y + 7.0],
            ] {
                let base = mesh.vertices.len() as u32;
                for position in [[left, top], [right, top], [right, bottom], [left, bottom]] {
                    mesh.vertices.push(OverlayVertex {
                        position,
                        uv: [0.5; 2],
                        color: [255, 220, 64, 255],
                    });
                }
                mesh.indices
                    .extend([base, base + 1, base + 2, base, base + 2, base + 3]);
            }
            overlay.meshes.push(mesh);
        }
    }
}
fn right_picture(
    system: &mut System,
    shared: bool,
) -> Option<(zakuro_core::Screen, Option<GpuScreen>)> {
    if shared {
        if let Some(gpu) = system.gpu_right_eye() {
            let scale = system.gpu.scale();
            return Some((
                (std::sync::Arc::new(Vec::new()), 400 * scale, 240 * scale),
                Some(gpu),
            ));
        }
    }
    system.read_right_eye_scaled().map(|screen| (screen, None))
}
fn picture(
    system: &mut System,
    screen: Screen,
    shared: bool,
) -> (zakuro_core::Screen, Option<GpuScreen>) {
    if shared {
        if let Some(gpu) = system.gpu_screen(screen) {
            let scale = system.gpu.scale();
            return (
                (
                    std::sync::Arc::new(Vec::new()),
                    screen.width() * scale,
                    screen.height() * scale,
                ),
                Some(gpu),
            );
        }
    }
    // Counted for the benchmark line: each fallback copies the screen on the CPU at render scale.
    CPU_SCREENS[screen as usize].fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    (system.read_screen_scaled(screen), None)
}
static CPU_SCREENS: [std::sync::atomic::AtomicU32; 2] = [std::sync::atomic::AtomicU32::new(0), std::sync::atomic::AtomicU32::new(0)];

impl Input {
    fn read(&mut self, app: &AndroidApp, surface: Option<&Surface>, settings: &Advanced) {
        let Ok(mut events) = app.input_events_iter() else {
            return;
        };
        let mut menu = false;
        while events.next(|event| {
            match event {
                InputEvent::KeyEvent(key) => {
                    let code = u32::from(key.key_code()) as i32;
                    if code == 4 {
                        menu |= key.action() == KeyAction::Up;
                        return InputStatus::Handled;
                    }
                    if settings.binding(code).is_none()
                        && settings.tilt_button.keycode() != Some(code)
                    {
                        return InputStatus::Unhandled;
                    }
                    match key.action() {
                        KeyAction::Down => {
                            self.keys.insert(code);
                        }
                        KeyAction::Up => {
                            self.keys.remove(&code);
                        }
                        _ => return InputStatus::Unhandled,
                    }
                }
                InputEvent::MotionEvent(motion) => {
                    let source: u32 = motion.source().into();
                    if source & u32::from(Source::Joystick) == u32::from(Source::Joystick) {
                        if motion.pointer_count() == 0 {
                            return InputStatus::Unhandled;
                        }
                        let p = motion.pointer_at_index(0);
                        self.state.circle_x = p.axis_value(Axis::X);
                        self.state.circle_y = -p.axis_value(Axis::Y);
                        self.state.c_stick_x = p.axis_value(Axis::Z);
                        self.state.c_stick_y = -p.axis_value(Axis::Rz);
                        self.axes.clear();
                        for (pressed, bit) in [
                            (p.axis_value(Axis::HatX) < -0.5, 21),
                            (p.axis_value(Axis::HatX) > 0.5, 22),
                            (p.axis_value(Axis::HatY) < -0.5, 19),
                            (p.axis_value(Axis::HatY) > 0.5, 20),
                            (
                                p.axis_value(Axis::Ltrigger).max(p.axis_value(Axis::Brake)) > 0.5,
                                104,
                            ),
                            (
                                p.axis_value(Axis::Rtrigger).max(p.axis_value(Axis::Gas)) > 0.5,
                                105,
                            ),
                        ] {
                            if pressed {
                                self.axes.insert(bit);
                            }
                        }
                    } else if source & u32::from(Source::Touchscreen)
                        == u32::from(Source::Touchscreen)
                    {
                        match motion.action() {
                            MotionAction::Cancel | MotionAction::Up => {
                                self.finger = None;
                                self.state.touch = None;
                            }
                            MotionAction::PointerUp => {
                                if motion.pointer_index() < motion.pointer_count()
                                    && Some(
                                        motion
                                            .pointer_at_index(motion.pointer_index())
                                            .pointer_id(),
                                    ) == self.finger
                                {
                                    self.finger = None;
                                    self.state.touch = None;
                                }
                            }
                            MotionAction::Down | MotionAction::PointerDown | MotionAction::Move => {
                                for p in motion.pointers() {
                                    let position = surface.and_then(|s| s.touch(p.x(), p.y()));
                                    if self.finger == Some(p.pointer_id())
                                        || (self.finger.is_none() && position.is_some())
                                    {
                                        self.finger = Some(p.pointer_id());
                                        self.state.touch = position;
                                        break;
                                    }
                                }
                            }
                            _ => return InputStatus::Unhandled,
                        }
                    } else {
                        return InputStatus::Unhandled;
                    }
                }
                _ => return InputStatus::Unhandled,
            }
            InputStatus::Handled
        }) {}
        if menu {
            platform::pause_menu(app);
        }
    }
}

pub fn run(app: &AndroidApp) -> Result<(), String> {
    // Rayon defaults to one worker per core (8 on Snapdragon 8 Gen 2); idle workers spin in the
    // kernel on the cores the emulation thread needs. Three cover the big cores beside it.
    if std::env::var_os("RAYON_NUM_THREADS").is_none() {
        std::env::set_var("RAYON_NUM_THREADS", "3");
    }
    let launch = platform::launch(app)?;
    let _session = bridge::Session::start(app);
    let mut settings = launch.advanced.clone();
    zakuro_gpu::backend::set_renderer_options(settings.renderer_options());
    // Declared before surfaces/core so custom libraries outlive every Vulkan object.
    let driver = crate::driver::Driver::load(launch.driver.as_ref())?;
    let mut paused = false;
    for axis in [
        Axis::Z,
        Axis::Rz,
        Axis::HatX,
        Axis::HatY,
        Axis::Ltrigger,
        Axis::Rtrigger,
        Axis::Brake,
        Axis::Gas,
    ] {
        app.enable_motion_axis(axis);
    }
    let mut surface: Option<Surface> = None;
    let mut second: Option<Surface> = None;
    // The main presenter while Android has taken its window away (app switch).
    let mut parked: Option<Surface> = None;
    let mut system: Option<System> = None;
    let mut audio: Option<Audio> = None;
    let mut sensors = Sensors::new();
    let mut input = Input::default();
    let (mut resumed, mut focused, mut destroy) = (false, false, false);
    let mut rotation = platform::rotation(app).unwrap_or(0);
    let mut failure = None;
    let frame_time = Duration::from_secs_f64(zakuro_core::CYCLES_PER_FRAME as f64 / 268_111_856.0);
    let present_interval = settings.presentation_interval();
    let mut next_present = Instant::now();
    let mut stats_start = Instant::now();
    let mut stats_frames = 0u32;
    let mut next_frame = Instant::now();
    let mut retry_audio = Instant::now();
    let mut benchmark_start = Instant::now();
    let mut benchmark_frames = 0u32;
    let mut core_time = Duration::ZERO;
    // Averages hide hitches: track the worst frame and frames over budget.
    let (mut worst_core, mut worst_frame, mut slow_frames) = (Duration::ZERO, Duration::ZERO, 0u32);
    let mut present_time = Duration::ZERO;
    platform::start_performance_hints(app);
    loop {
        let active = resumed && focused && !paused && surface.is_some();
        let timeout = if active {
            // ms-granular wakeup: stop 1 ms short, the tail is spun below.
            next_frame
                .saturating_duration_since(Instant::now())
                .saturating_sub(Duration::from_millis(1))
        } else {
            Duration::from_millis(250)
        };
        app.poll_events(Some(timeout), |event| {
            let PollEvent::Main(event) = event else {
                return;
            };
            match event {
                MainEvent::InitWindow { .. } => {
                    // Back from an app switch: same device, so GPU images stay shared at full resolution.
                    let reattached = match (parked.take(), app.native_window()) {
                        (Some(mut s), Some(window)) => match s.reattach(window) {
                            Ok(()) => {
                                surface = Some(s);
                                next_frame = Instant::now();
                                true
                            }
                            Err(e) => {
                                log::warn!("could not reattach the window ({e}), starting a new presenter");
                                false
                            }
                        },
                        _ => false,
                    };
                    if let (false, Some(window)) = (reattached, app.native_window()) {
                        match Surface::new(window, &geometry::display(&settings, false, second.is_some()), driver.entry.clone()) {
                            Ok(mut s) => {
                                // The panel is asked for 60 Hz above; at the default cap let its vsync pace frames.
                                if settings.frame_cap == 60 {
                                    s.presenter.pace_to_display();
                                }
                                if system.is_some() {
                                    // Only when reattaching failed: the renderer keeps its device, so screens are read back.
                                    log::warn!("window recreated: presentation now uses GPU readback on the new Vulkan device");
                                }
                                surface = Some(s);
                                next_frame = Instant::now();
                            }
                            Err(e) => failure = Some(e),
                        }
                    }
                }
                MainEvent::TerminateWindow { .. } => {
                    // Keep the presenter's device while a game runs; only the surface goes with the window.
                    if let (Some(mut s), true) = (surface.take(), system.is_some()) {
                        s.presenter.detach_surface();
                        parked = Some(s);
                    }
                    audio = None;
                    input = Input::default();
                    bridge::clear_input();
                }
                MainEvent::WindowResized { .. } | MainEvent::ContentRectChanged { .. } => {
                    if let Some(s) = &mut surface {
                        s.resize();
                    }
                    input.state.touch = None;
                    input.finger = None;
                }
                MainEvent::ConfigChanged { .. } => {
                    rotation = platform::rotation(app).unwrap_or(rotation);
                    if let Some(s) = &mut surface {
                        s.resize();
                    }
                }
                MainEvent::Resume { .. } => {
                    resumed = true;
                    next_frame = Instant::now();
                }
                MainEvent::GainedFocus => {
                    focused = true;
                    next_frame = Instant::now();
                }
                MainEvent::LostFocus => {
                    focused = false;
                    audio = None;
                    input = Input::default();
                    bridge::clear_input();
                }
                MainEvent::Pause | MainEvent::Stop => {
                    resumed = false;
                    audio = None;
                    input = Input::default();
                    bridge::clear_input();
                }
                MainEvent::Destroy => {
                    destroy = true;
                    surface = None;
                    parked = None;
                    audio = None;
                }
                MainEvent::InputAvailable => input.read(app, surface.as_ref(), &settings),
                MainEvent::RedrawNeeded { .. } => {
                    if let (Some(s), Some(system)) = (&mut surface, &mut system) {
                        if let Err(e) = s.present(system, (settings.right_stick == RightStick::Cursor).then_some(input.cursor)) {
                            if !matches!(e, PresentError::OutOfDate) {
                                failure = Some(e.to_string());
                            }
                        }
                    }
                }
                _ => {}
            }
            if let Some(sensors) = &mut sensors {
                sensors.set_active(resumed && focused && !paused && settings.motion_enabled && surface.is_some());
            }
            if !(resumed && focused && !paused && surface.is_some()) {
                benchmark_frames = 0;
                core_time = Duration::ZERO;
                present_time = Duration::ZERO;
            }
        });
        for command in bridge::pending() {
            match command {
                Command::Pause => {
                    paused = true;
                    audio = None;
                    input = Input::default();
                    bridge::clear_input();
                }
                Command::Resume => {
                    paused = false;
                    next_frame = Instant::now();
                }
                Command::Exit => destroy = true,
                Command::Restart => {
                    system = None;
                    audio = None;
                    input = Input::default();
                    bridge::clear_input();
                    next_frame = Instant::now();
                    platform::message(app, "Restarting game");
                }
                Command::Recalibrate => {
                    if let Some(sensors) = &mut sensors {
                        sensors.recalibrate();
                    }
                    platform::message(app, "Hold the device still briefly to recalibrate motion");
                }
                Command::Swap => {
                    settings.swap_screens = !settings.swap_screens;
                    if let Some(surface) = &mut surface {
                        surface.configure(&geometry::display(&settings, false, second.is_some()));
                    }
                    if let Some(external) = &mut second {
                        external.configure(&geometry::display(&settings, true, true));
                    }
                    input.state.touch = None;
                    input.finger = None;
                    bridge::clear_input();
                }
                Command::Save | Command::Load => {
                    if let Some(system) = &mut system {
                        let id = system
                            .title
                            .as_ref()
                            .map(|title| title.program_id())
                            .unwrap_or(0);
                        let path = launch
                            .data
                            .join("states")
                            .join(format!("{id:016X}"))
                            .join("0.state");
                        let result = if matches!(command, Command::Save) {
                            system
                                .save_state()
                                .and_then(|bytes| crate::storage::save(&path, &bytes))
                        } else {
                            crate::storage::load(&path).and_then(|bytes| system.load_state(&bytes))
                        };
                        match result {
                            Ok(()) => platform::message(
                                app,
                                if matches!(command, Command::Save) {
                                    "Saved slot 0"
                                } else {
                                    "Loaded slot 0"
                                },
                            ),
                            Err(error) => platform::message(app, &error),
                        }
                        audio = None;
                        input = Input::default();
                        bridge::clear_input();
                        next_frame = Instant::now();
                    } else {
                        platform::message(app, "Game has not started yet");
                    }
                }
            }
        }
        if let Some(window) = bridge::second_surface() {
            second = None;
            if settings.dual_display {
                if let Some(window) = window {
                    match Surface::new(
                        window,
                        &geometry::display(&settings, true, true),
                        driver.entry.clone(),
                    ) {
                        Ok(external) => second = Some(external),
                        Err(error) => {
                            platform::message(app, &format!("Second display unavailable: {error}"));
                            platform::close_second_display(app);
                        }
                    }
                }
            }
            if let Some(main) = &mut surface {
                main.configure(&geometry::display(&settings, false, second.is_some()));
            }
            input.state.touch = None;
            input.finger = None;
            bridge::clear_input();
        }
        if let Some(sensors) = &mut sensors {
            sensors.set_active(
                resumed && focused && !paused && settings.motion_enabled && surface.is_some(),
            );
        }
        if let Some(error) = failure.take() {
            return Err(error);
        }
        if destroy {
            break;
        }
        if !(resumed && focused && !paused && surface.is_some()) {
            continue;
        }
        let s = surface.as_mut().unwrap();
        if system.is_none() {
            let device = s.presenter.shared_device();
            if settings.custom_driver && settings.hardware_renderer && device.is_none() {
                return Err("The selected custom driver cannot supply the Vulkan device required by this renderer. Choose Software 3D rendering or System driver.".into());
            }
            s.shared = device.is_some() && !settings.gpu_readback;
            let config = Config {
                recompiled: settings.aot.then(|| launch.code.clone()),
                require_recompiled: settings.aot,
                new3ds: settings.new_3ds,
                language: settings.language as u8,
                slider_3d: settings.slider_3d,
                skip_idle_loops: settings.skip_idle_loops,
                interpolate: settings.interpolate_on_main(second.is_some()) && s.shared,
                data_dir: Some(launch.data.clone()),
                hardware_renderer: settings.hardware_renderer,
                texture_packs: launch.texture_packs,
                resolution: launch.resolution,
                device,
                ..Config::default()
            };
            let mut loaded =
                zakuro_core::loader::load(&launch.game, config).map_err(|e| e.to_string())?;
            if settings.region >= 0 {
                loaded.config.region = settings.region as u8;
            }
            // The loader selected only enhancements matching this title/build.
            for cheat in loaded.cheats.iter_mut().filter(|cheat| cheat.builtin) {
                cheat.enabled = settings.enhancements.contains(&cheat.name);
            }
            for name in &settings.enhancements {
                if !loaded
                    .cheats
                    .iter()
                    .any(|cheat| cheat.builtin && &cheat.name == name)
                {
                    platform::message(
                        app,
                        &format!("Enhancement unavailable for this title/build: {name}"),
                    );
                }
            }
            let available: Vec<_> = loaded
                .cheats
                .iter()
                .filter(|cheat| cheat.builtin)
                .take(32)
                .map(
                    |cheat| serde_json::json!({"name": cheat.name, "note": cheat.notes.join("\n")}),
                )
                .collect();
            let metadata = serde_json::to_vec(&available).map_err(|e| e.to_string())?;
            if metadata.len() <= 64 * 1024 {
                if let Err(error) = crate::storage::save(
                    &launch.data.join("available-enhancements.json"),
                    &metadata,
                ) {
                    platform::message(
                        app,
                        &format!("Could not publish available enhancements: {error}"),
                    );
                }
            }
            if settings.interpolate
                && (!settings.interpolate_on_main(second.is_some())
                    || !s.shared
                    || !loaded.gpu.interpolates())
            {
                platform::message(app, "Frame interpolation requires shared GPU presentation");
            }
            if settings.hardware_renderer && !loaded.gpu.draws_on_gpu() {
                return Err("Could not start the Vulkan hardware renderer. Select Software 3D rendering to troubleshoot, or use a compatible Vulkan 1.3 driver.".into());
            }
            if !loaded.gpu.shows_directly() {
                log::warn!("Vulkan renderer is not sharing the presentation device");
            }
            system = Some(loaded);
            next_frame = Instant::now();
        }
        if Instant::now() + Duration::from_millis(2) < next_frame {
            continue;
        }
        // FIFO already waits for vsync; spinning here would only burn the main core.
        if !(s.presenter.waits_for_display() && settings.frame_cap == 60) {
            while Instant::now() < next_frame {
                std::thread::yield_now();
            }
        }
        input.read(app, Some(s), &settings);
        let motion = if settings.motion_enabled {
            sensors.as_mut().and_then(|s| s.sample(rotation))
        } else {
            None
        };
        let system = system.as_mut().unwrap();
        let interpolate = settings.interpolate_on_main(second.is_some())
            && s.shared
            && system.gpu.shows_directly();
        if system.gpu.interpolates() != interpolate {
            system.set_interpolation(interpolate);
        }
        let mut configured = input.configured(&settings, &bridge::input(), motion);
        if let (Some(external), Some((x, y, width, height))) = (&second, bridge::second_touch()) {
            configured.touch =
                geometry::touch(&external.settings, width, height, x, y).or(configured.touch);
        }
        system.set_input(configured);
        if benchmark_frames == 0 {
            benchmark_start = Instant::now();
        }
        let core_start = Instant::now();
        let outcome = system.run_frame();
        let work = core_start.elapsed();
        core_time += work;
        // Emulation work only: under FIFO the present below includes the vsync wait,
        // which would read as a full 16.7 ms frame and over-boost the clocks.
        platform::report_frame(app, work.as_nanos() as i64);
        worst_core = worst_core.max(core_start.elapsed());
        let sound = system.take_audio();
        if audio.as_ref().is_some_and(Audio::disconnected) {
            audio = None;
        }
        if audio.is_none() && Instant::now() >= retry_audio {
            audio = Audio::open()
                .inspect_err(|e| log::warn!("AAudio: {e}"))
                .ok();
            retry_audio = Instant::now() + Duration::from_secs(2);
        }
        if let Some(audio) = &audio {
            audio.push(&sound, if settings.mute { 0.0 } else { settings.volume });
        }
        match outcome {
            FrameOutcome::Completed => {}
            FrameOutcome::Exited => break,
            FrameOutcome::Faulted => return Err(format!("game faulted: {}", system.debug_output)),
        }
        let present_start = Instant::now();
        // Under FIFO the display is the cap; a timer gate would drop frames that arrive a hair early.
        let vsync = s.presenter.waits_for_display() && settings.frame_cap == 60;
        if vsync || present_start >= next_present {
            match s.present(
                system,
                (settings.right_stick == RightStick::Cursor).then_some(input.cursor),
            ) {
                Ok(()) => stats_frames += 1,
                Err(PresentError::OutOfDate) => {}
                Err(e) => return Err(e.to_string()),
            }
            if let Some(external) = &mut second {
                // Separate Vulkan devices cannot consume the main renderer's image views.
                if let Err(error) = external.present(
                    system,
                    (settings.right_stick == RightStick::Cursor).then_some(input.cursor),
                ) {
                    if !matches!(error, PresentError::OutOfDate) {
                        platform::message(app, &format!("Second display disconnected: {error}"));
                        second = None;
                        s.configure(&geometry::display(&settings, false, false));
                        bridge::clear_input();
                        platform::close_second_display(app);
                    }
                }
            }
            next_present = (next_present + present_interval).max(present_start);
        }
        present_time += present_start.elapsed();
        let frame = core_start.elapsed();
        worst_frame = worst_frame.max(frame);
        slow_frames += u32::from(frame > Duration::from_micros(16_000));
        benchmark_frames += 1;
        if stats_start.elapsed() >= Duration::from_secs(1) {
            if settings.show_fps {
                platform::stats(
                    app,
                    &format!(
                        "{:.1} FPS",
                        f64::from(stats_frames) / stats_start.elapsed().as_secs_f64()
                    ),
                );
            }
            stats_frames = 0;
            stats_start = Instant::now();
        }
        if benchmark_frames == 300 {
            let waits = zakuro_gpu::take_wait_stats();
            let by = zakuro_gpu::take_caller_stats();
            let ms = |i: usize| format!("{}/{:.1}ms", by[i].0, by[i].1.as_secs_f64() * 1000.0);
            log::info!("benchmark frames=300 fps={:.2} core_ms={:.3} present_ms={:.3} worst_core_ms={:.1} worst_frame_ms={:.1} slow={} vsync={} cpu_top={} cpu_bottom={} resolution={} textures={} shared={} gpu_waits={:.2} gpu_wait_ms={:.3} readbacks={:.2} readback_ms={:.3} wb[sync={} fill={} rows={} read={} flush={} pic={} room={}]",
                300.0 / benchmark_start.elapsed().as_secs_f64(), core_time.as_secs_f64() * 1000.0 / 300.0,
                present_time.as_secs_f64() * 1000.0 / 300.0, worst_core.as_secs_f64() * 1000.0, worst_frame.as_secs_f64() * 1000.0,
                slow_frames, vsync, CPU_SCREENS[0].swap(0, std::sync::atomic::Ordering::Relaxed), CPU_SCREENS[1].swap(0, std::sync::atomic::Ordering::Relaxed), launch.resolution, launch.texture_packs, s.shared,
                waits.0 as f64 / 300.0, waits.1.as_secs_f64() * 1000.0 / 300.0, waits.2 as f64 / 300.0, waits.3.as_secs_f64() * 1000.0 / 300.0,
                ms(0), ms(1), ms(2), ms(3), ms(4), ms(5), ms(6));
            (worst_core, worst_frame, slow_frames) = (Duration::ZERO, Duration::ZERO, 0);
            benchmark_frames = 0;
            core_time = Duration::ZERO;
            present_time = Duration::ZERO;
        }
        // No catch-up burst after a slow frame or lifecycle transition.
        // With vsync pacing the timer is only a ceiling (61 Hz) in case the panel stays at 120 Hz.
        let step = if vsync { Duration::from_secs_f64(1.0 / 61.0) } else { frame_time };
        next_frame = (next_frame + step).max(Instant::now());
    }
    Ok(())
}
